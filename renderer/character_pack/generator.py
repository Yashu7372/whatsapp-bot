"""
Character Pack Generator — runs entirely inside the renderer worker service.

Takes a reference image of the two characters and generates PNG expression
variants for each, stored under PACK_ROOT. All Gemini API calls and image
processing happen here so the Spring Boot main service never blocks on them.

Output layout:
    <pack_root>/
    ├── manifest.json
    ├── reference.png
    ├── scene/
    │   └── background.png
    ├── bhaiya/          (older Indian boy with glasses)
    │   ├── idle.png
    │   ├── talking.png
    │   ├── curious.png
    │   ├── surprised.png
    │   ├── laughing.png
    │   └── thinking.png
    └── chitti/          (Indian toddler girl in red pavadai)
        ├── idle.png
        ├── talking.png
        ├── curious.png
        ├── surprised.png
        ├── laughing.png
        └── thinking.png
"""

from __future__ import annotations

import base64
import json
import logging
import os
import time
from io import BytesIO
from pathlib import Path

from PIL import Image

from .models import Character, CharacterPack, EXPRESSIONS, CHARACTERS

logger = logging.getLogger("character-pack.generator")

_CHARACTER_META: dict[str, dict] = {
    "bhaiya": {
        "display_name": "Bhaiya (Explainer)",
        "description": (
            "the older Indian boy on the LEFT side of the reference image: "
            "approximately 9-10 years old, round brown-framed glasses, "
            "striped white and dark grey long-sleeve shirt, short black hair, "
            "warm medium-dark brown skin tone, seated cross-legged"
        ),
        "framing": "portrait from waist up, relaxed seated posture",
        "crop_box_ratio": (0.0, 0.0, 0.62, 1.0),
    },
    "chitti": {
        "display_name": "Chitti (Learner)",
        "description": (
            "the baby girl on the RIGHT side of the reference image: "
            "approximately 1-2 years old, traditional South Indian red pavadai "
            "with gold border and multicolour stripes, small black bindi on forehead, "
            "gold bangle on wrist, short black hair, round face, "
            "warm medium-dark brown skin tone, seated upright"
        ),
        "framing": "full body portrait, sitting upright on a pouf",
        "crop_box_ratio": (0.45, 0.0, 1.0, 1.0),
    },
}

_EXPRESSION_DESCRIPTIONS: dict[str, str] = {
    "idle":      "calm neutral expression, mouth closed, soft relaxed eyes, looking gently forward",
    "talking":   "actively speaking mid-sentence, mouth open, bright engaged eyes, animated face",
    "curious":   "head tilted slightly, eyebrows raised, eyes wide, slight open-mouthed wonder",
    "surprised": "eyes wide open, eyebrows raised high, mouth slightly open in surprise",
    "laughing":  "genuine big smile showing teeth, eyes crinkled with joy, head slightly back",
    "thinking":  "thoughtful gaze upward, mouth closed, finger near chin, pensive expression",
}


class GenerationError(RuntimeError):
    pass


class CharacterPackGenerator:
    def __init__(self, pack_root: Path, gemini_api_key: str | None = None) -> None:
        self.pack_root = pack_root
        self._api_key = (
            gemini_api_key
            or os.getenv("GEMINI_API_KEY")
            or os.getenv("AI_GEMINI_API_KEY")
        )
        self._client = None

    # ------------------------------------------------------------------
    # Public API
    # ------------------------------------------------------------------

    def generate(self, reference_image_path: Path) -> CharacterPack:
        """Generate the full character pack. Idempotent — skips existing files."""
        logger.info("Starting character pack generation from %s", reference_image_path)
        self.pack_root.mkdir(parents=True, exist_ok=True)

        ref_dest = self.pack_root / "reference.png"
        Image.open(reference_image_path).convert("RGBA").save(ref_dest)

        pack = CharacterPack(pack_root=self.pack_root)
        pack.background_path = self._generate_background(reference_image_path)

        for char_name in CHARACTERS:
            pack.characters[char_name] = self._generate_character(
                char_name, reference_image_path
            )

        self._save_manifest(pack, reference_image_path)
        logger.info("Character pack complete at %s", self.pack_root)
        return pack

    @classmethod
    def load(cls, pack_root: Path) -> CharacterPack | None:
        manifest_path = pack_root / "manifest.json"
        if not manifest_path.exists():
            return None
        try:
            data = json.loads(manifest_path.read_text())
            bg = data.get("background")
            pack = CharacterPack(
                pack_root=pack_root,
                background_path=Path(bg) if bg else None,
            )
            for name, cd in data.get("characters", {}).items():
                pack.characters[name] = Character(
                    name=name,
                    display_name=cd.get("display_name", name),
                    expressions={
                        expr: Path(p)
                        for expr, p in cd.get("expressions", {}).items()
                        if Path(p).exists()
                    },
                )
            return pack
        except Exception as exc:
            logger.warning("Failed to load character pack manifest: %s", exc)
            return None

    # ------------------------------------------------------------------
    # Internal generation methods
    # ------------------------------------------------------------------

    def _generate_character(
        self, char_name: str, reference_path: Path
    ) -> Character:
        meta = _CHARACTER_META[char_name]
        char_root = self.pack_root / char_name
        char_root.mkdir(exist_ok=True)

        character = Character(
            name=char_name, display_name=meta["display_name"]
        )
        for expression in EXPRESSIONS:
            dest = char_root / f"{expression}.png"
            if dest.exists():
                logger.info("  %s/%s already exists, skipping", char_name, expression)
                character.expressions[expression] = dest
                continue

            logger.info("  Generating %s/%s ...", char_name, expression)
            try:
                img = self._call_gemini(
                    reference_path=reference_path,
                    character_description=meta["description"],
                    framing=meta["framing"],
                    expression_description=_EXPRESSION_DESCRIPTIONS[expression],
                )
                img.save(dest, "PNG")
                character.expressions[expression] = dest
                time.sleep(1.2)  # stay within Gemini rate limits
            except Exception as exc:
                logger.warning(
                    "  Gemini failed for %s/%s (%s); using crop fallback",
                    char_name, expression, exc,
                )
                fallback = self._crop_fallback(
                    reference_path, meta["crop_box_ratio"]
                )
                if fallback:
                    fallback.save(dest, "PNG")
                    character.expressions[expression] = dest

        return character

    def _generate_background(self, reference_path: Path) -> Path:
        bg_path = self.pack_root / "scene" / "background.png"
        bg_path.parent.mkdir(exist_ok=True)
        if bg_path.exists():
            return bg_path

        logger.info("Generating background scene ...")
        prompt = (
            "Generate ONLY the background room — the warm cozy South Indian living room / "
            "playroom from this reference image with NO people or characters present. "
            "Keep all room details: cream walls, string fairy lights, glowing star decoration, "
            "potted plant left side, floor lamp right side, white bookshelves with colourful "
            "children's books and small toys, cream carpet. "
            "Same warm golden lighting. Empty room ready for animated characters. "
            "Portrait 9:16 orientation, photorealistic."
        )
        try:
            img = self._call_gemini_bg(reference_path, prompt)
            if img:
                img.save(bg_path, "PNG")
                return bg_path
        except Exception as exc:
            logger.warning("Background generation failed (%s); using resized reference", exc)

        # Fallback: blur + darken the reference so characters stand out
        src = Image.open(reference_path).convert("RGB")
        src = src.resize((1080, 1920), Image.LANCZOS)
        src.save(bg_path, "PNG")
        return bg_path

    # ------------------------------------------------------------------
    # Gemini API helpers
    # ------------------------------------------------------------------

    def _get_client(self):
        if self._client is None:
            if not self._api_key:
                raise GenerationError(
                    "No Gemini API key configured. "
                    "Set GEMINI_API_KEY or AI_GEMINI_API_KEY env var."
                )
            from google import genai
            self._client = genai.Client(api_key=self._api_key)
        return self._client

    def _call_gemini(
        self,
        reference_path: Path,
        character_description: str,
        framing: str,
        expression_description: str,
    ) -> Image.Image:
        from google.genai import types

        prompt = (
            f"Using this reference image as your STRICT character guide, generate an "
            f"isolated portrait of ONLY {character_description}. "
            f"Framing: {framing}. "
            f"Expression: {expression_description}. "
            f"Lighting: same warm golden indoor lighting as the reference. "
            f"Background: transparent or plain white — isolate only the character. "
            f"CRITICAL: The character's face, skin tone, hair, clothing, glasses, and "
            f"accessories must be IDENTICAL to the reference image. "
            f"Do not change any detail. Output a high-quality portrait PNG, 512x768 px."
        )
        image_blob = self._encode_image(reference_path)
        client = self._get_client()

        response = client.models.generate_content(
            model="gemini-2.0-flash-preview-image-generation",
            contents=[
                types.Content(parts=[
                    types.Part.from_text(prompt),
                    types.Part(inline_data=image_blob),
                ])
            ],
            config=types.GenerateContentConfig(
                response_modalities=["IMAGE", "TEXT"],
            ),
        )
        return self._extract_image(response)

    def _call_gemini_bg(self, reference_path: Path, prompt: str) -> Image.Image | None:
        from google.genai import types

        image_blob = self._encode_image(reference_path)
        client = self._get_client()
        response = client.models.generate_content(
            model="gemini-2.0-flash-preview-image-generation",
            contents=[
                types.Content(parts=[
                    types.Part.from_text(prompt),
                    types.Part(inline_data=image_blob),
                ])
            ],
            config=types.GenerateContentConfig(
                response_modalities=["IMAGE", "TEXT"],
            ),
        )
        try:
            return self._extract_image(response)
        except Exception:
            return None

    @staticmethod
    def _encode_image(path: Path):
        from google.genai import types

        data = path.read_bytes()
        suffix = path.suffix.lower()
        mime = "image/png" if suffix == ".png" else "image/jpeg"
        return types.Blob(mime_type=mime, data=base64.b64encode(data).decode())

    @staticmethod
    def _extract_image(response) -> Image.Image:
        for part in response.candidates[0].content.parts:
            if getattr(part, "inline_data", None):
                raw = base64.b64decode(part.inline_data.data)
                return Image.open(BytesIO(raw)).convert("RGBA")
        raise GenerationError("Gemini returned no image data in response")

    # ------------------------------------------------------------------
    # Fallback: simple crop from reference
    # ------------------------------------------------------------------

    @staticmethod
    def _crop_fallback(
        reference_path: Path, box_ratio: tuple[float, float, float, float]
    ) -> Image.Image | None:
        try:
            img = Image.open(reference_path).convert("RGBA")
            w, h = img.size
            x0, y0, x1, y1 = box_ratio
            crop = img.crop((int(w * x0), int(h * y0), int(w * x1), int(h * y1)))
            return crop.resize((512, 768), Image.LANCZOS)
        except Exception as exc:
            logger.warning("Crop fallback failed: %s", exc)
            return None

    # ------------------------------------------------------------------
    # Manifest
    # ------------------------------------------------------------------

    def _save_manifest(self, pack: CharacterPack, reference_path: Path) -> None:
        data: dict = {
            "version": 1,
            "reference_image": str(reference_path),
            "background": str(pack.background_path) if pack.background_path else None,
            "characters": {
                name: {
                    "display_name": ch.display_name,
                    "expressions": {
                        expr: str(p) for expr, p in ch.expressions.items()
                    },
                }
                for name, ch in pack.characters.items()
            },
        }
        (self.pack_root / "manifest.json").write_text(json.dumps(data, indent=2))
