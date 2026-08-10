"""
Dialogue Scene Compositor — runs inside the renderer worker service.

Takes a list of dialogue turns (speaker, emotion, text, audio) and composites
them into a single portrait MP4 using PIL for frame building and FFmpeg for
encoding. The main Spring Boot service never touches this code directly.

Portrait canvas: 1080 × 1920 (9:16)

Frame layout (single active speaker):
  ┌─────────────────────────┐  0
  │   warm-room background  │
  │                         │
  │   ┌───────────────┐     │  300
  │   │   character   │     │
  │   │   portrait    │     │  (PNG with alpha)
  │   └───────────────┘     │  1450
  │   ┌─────────────────┐   │  1480
  │   │  subtitle text  │   │
  │   └─────────────────┘   │  1760
  │   speaker label         │  1800
  └─────────────────────────┘  1920
"""

from __future__ import annotations

import logging
import subprocess
import tempfile
import threading
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from PIL import Image, ImageDraw, ImageFont

from .models import CharacterPack

logger = logging.getLogger("character-pack.compositor")

CANVAS_W, CANVAS_H = 1080, 1920
FONT_BOLD = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
FONT_REGULAR = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"

# Character image placement (x, y, w, h) when shown as the active solo speaker
_SOLO_REGION: dict[str, tuple[int, int, int, int]] = {
    "bhaiya": (90, 220, 900, 1240),
    "chitti": (180, 380, 720, 1070),
}
# Same characters when shown together (two-shot)
_DUO_REGION: dict[str, tuple[int, int, int, int]] = {
    "bhaiya": (0, 300, 580, 1000),
    "chitti": (520, 480, 500, 820),
}

_SUBTITLE_BOX = (55, 1480, 970, 280)   # x, y, w, h
_SPEAKER_LABEL_Y = 1800
_SPEAKER_NAMES = {"bhaiya": "👦 Bhaiya", "chitti": "👶 Chitti"}


@dataclass
class DialogueTurn:
    speaker: str            # "bhaiya" or "chitti"
    emotion: str            # expression name
    text: str               # spoken text (used for subtitle)
    duration_seconds: float = 4.0
    audio_path: Path | None = None   # pre-generated WAV for this turn


@dataclass
class CompositorRequest:
    turns: list[DialogueTurn]
    output_path: Path
    pack: CharacterPack
    fps: int = 30


class DialogueCompositor:
    def __init__(self, tts_pipeline_factory=None) -> None:
        self._tts_factory = tts_pipeline_factory
        self._tts_lock = threading.Lock()
        self._tts_pipeline: Any | None = None

    # ------------------------------------------------------------------
    # Public entry point
    # ------------------------------------------------------------------

    def render(self, req: CompositorRequest) -> Path:
        background = self._load_bg(req.pack.background_path)

        with tempfile.TemporaryDirectory(prefix="dialogue-") as tmp:
            workdir = Path(tmp)
            segments: list[Path] = []
            audio_map: list[tuple[Path, float]] = []   # (audio, start_seconds)
            cursor = 0.0

            for idx, turn in enumerate(req.turns):
                seg = workdir / f"seg-{idx:03d}.mp4"
                audio = self._ensure_audio(turn, workdir, idx)
                dur = turn.duration_seconds

                self._render_turn(
                    turn=turn,
                    background=background,
                    output=seg,
                    fps=req.fps,
                    workdir=workdir,
                    idx=idx,
                    pack=req.pack,
                )
                segments.append(seg)
                if audio and audio.exists():
                    audio_map.append((audio, cursor))
                cursor += dur

            joined = workdir / "joined.mp4"
            self._concat(segments, joined, workdir)

            if audio_map:
                mixed = workdir / "mixed.wav"
                total = sum(t.duration_seconds for t in req.turns)
                self._mix_audio(audio_map, mixed, total)
                self._mux(joined, mixed, req.output_path)
            else:
                import shutil
                shutil.copy(joined, req.output_path)

        return req.output_path

    # ------------------------------------------------------------------
    # Turn rendering
    # ------------------------------------------------------------------

    def _render_turn(
        self,
        turn: DialogueTurn,
        background: Image.Image,
        output: Path,
        fps: int,
        workdir: Path,
        idx: int,
        pack: CharacterPack,
    ) -> None:
        dur = turn.duration_seconds
        n_frames = max(1, int(dur * fps))

        frames_dir = workdir / f"frames-{idx:03d}"
        frames_dir.mkdir()

        # Alternate between idle and talking expressions to simulate mouth movement
        switch_every = max(2, fps // 4)

        for f in range(n_frames):
            cycle = (f // switch_every) % 2
            expr = "talking" if cycle == 0 else "idle"
            active_turn = DialogueTurn(
                speaker=turn.speaker,
                emotion=expr,
                text=turn.text,
                duration_seconds=dur,
            )
            frame = self._build_frame(active_turn, background, pack)
            frame.save(frames_dir / f"f{f:06d}.png")

        self._run([
            "ffmpeg", "-y",
            "-framerate", str(fps),
            "-i", str(frames_dir / "f%06d.png"),
            "-vf", f"scale={CANVAS_W}:{CANVAS_H},setsar=1",
            "-c:v", "libx264", "-preset", "veryfast", "-crf", "22",
            "-pix_fmt", "yuv420p", "-an",
            str(output),
        ], "encode turn frames")

    def _build_frame(
        self, turn: DialogueTurn, background: Image.Image, pack: CharacterPack
    ) -> Image.Image:
        frame = background.copy()

        char = pack.get_character(turn.speaker)
        if char:
            char_img = self._load_char_image(char, turn.emotion)
            if char_img:
                x, y, w, h = _SOLO_REGION.get(turn.speaker, (90, 220, 900, 1240))
                char_img = char_img.resize((w, h), Image.LANCZOS)
                frame.alpha_composite(char_img, (x, y))

        draw = ImageDraw.Draw(frame, "RGBA")
        self._draw_subtitle(draw, turn.text, turn.speaker)
        return frame

    # ------------------------------------------------------------------
    # Subtitle / label rendering
    # ------------------------------------------------------------------

    def _draw_subtitle(self, draw: ImageDraw.ImageDraw, text: str, speaker: str) -> None:
        try:
            font = ImageFont.truetype(FONT_BOLD, 52)
            label_font = ImageFont.truetype(FONT_BOLD, 38)
        except Exception:
            font = label_font = ImageFont.load_default()

        bx, by, bw, bh = _SUBTITLE_BOX
        draw.rounded_rectangle(
            [bx, by, bx + bw, by + bh],
            radius=28,
            fill=(0, 0, 0, 185),
        )

        lines = self._wrap(draw, text, font, bw - 48)
        ty = by + 22
        for line in lines[:4]:
            bbox = draw.textbbox((0, 0), line, font=font)
            lw = bbox[2] - bbox[0]
            lh = bbox[3] - bbox[1]
            tx = bx + (bw - lw) // 2
            draw.text((tx + 2, ty + 2), line, font=font, fill=(0, 0, 0, 150))
            draw.text((tx, ty), line, font=font, fill=(255, 255, 255, 255))
            ty += lh + 14

        label = _SPEAKER_NAMES.get(speaker, speaker)
        draw.text((82, _SPEAKER_LABEL_Y), label, font=label_font, fill=(255, 220, 90, 230))

    @staticmethod
    def _wrap(draw: ImageDraw.ImageDraw, text: str, font, max_px: int) -> list[str]:
        words = text.split()
        lines: list[str] = []
        current: list[str] = []
        for word in words:
            test = " ".join(current + [word])
            w = draw.textbbox((0, 0), test, font=font)[2]
            if w > max_px and current:
                lines.append(" ".join(current))
                current = [word]
            else:
                current.append(word)
        if current:
            lines.append(" ".join(current))
        return lines

    # ------------------------------------------------------------------
    # Image helpers
    # ------------------------------------------------------------------

    @staticmethod
    def _load_bg(path: Path | None) -> Image.Image:
        if path and path.exists():
            return Image.open(path).convert("RGBA").resize((CANVAS_W, CANVAS_H), Image.LANCZOS)
        img = Image.new("RGBA", (CANVAS_W, CANVAS_H), (25, 20, 30, 255))
        return img

    @staticmethod
    def _load_char_image(character, emotion: str) -> Image.Image | None:
        p = character.get_expression(emotion)
        if not p:
            return None
        return Image.open(p).convert("RGBA")

    # ------------------------------------------------------------------
    # TTS
    # ------------------------------------------------------------------

    def _ensure_audio(
        self, turn: DialogueTurn, workdir: Path, idx: int
    ) -> Path | None:
        if turn.audio_path and turn.audio_path.exists():
            return turn.audio_path

        if not turn.text.strip():
            return None

        out = workdir / f"audio-{idx:03d}.wav"
        try:
            self._synth(turn.text, turn.speaker, out)
            return out
        except Exception as exc:
            logger.warning("TTS failed for turn %d: %s", idx, exc)
            return None

    def _synth(self, text: str, speaker: str, out: Path) -> None:
        voice_map = {
            "bhaiya": os.getenv("VOICE_BHAIYA", "am_liam"),
            "chitti": os.getenv("VOICE_CHITTI", "af_heart"),
        }
        voice = voice_map.get(speaker, "af_heart")

        import numpy as np
        import soundfile as sf
        from kokoro import KPipeline

        with self._tts_lock:
            if self._tts_pipeline is None:
                lang = os.getenv("KOKORO_LANG_CODE", "a")
                self._tts_pipeline = KPipeline(lang_code=lang)
            pipeline = self._tts_pipeline

        chunks = [audio for _, _, audio in pipeline(text[:2000], voice=voice, speed=1.0)]
        if chunks:
            sf.write(out, np.concatenate(chunks), 24000)

    # ------------------------------------------------------------------
    # FFmpeg helpers
    # ------------------------------------------------------------------

    def _mix_audio(
        self,
        audio_map: list[tuple[Path, float]],
        output: Path,
        total_seconds: float,
    ) -> None:
        # Build a filter_complex that places each audio at its start offset
        inputs = []
        delays = []
        for i, (path, start) in enumerate(audio_map):
            inputs += ["-i", str(path)]
            delay_ms = int(start * 1000)
            delays.append(f"[{i}]adelay={delay_ms}|{delay_ms}[d{i}]")

        if not inputs:
            return

        mix_inputs = "".join(f"[d{i}]" for i in range(len(audio_map)))
        filter_complex = (
            ";".join(delays)
            + f";{mix_inputs}amix=inputs={len(audio_map)}:duration=longest[out]"
        )
        self._run([
            "ffmpeg", "-y",
            *inputs,
            "-filter_complex", filter_complex,
            "-map", "[out]",
            "-t", f"{total_seconds:.3f}",
            "-ar", "48000",
            str(output),
        ], "mix audio tracks")

    def _concat(self, segments: list[Path], output: Path, workdir: Path) -> None:
        lst = workdir / "concat.txt"
        lst.write_text("\n".join(f"file '{s}'" for s in segments))
        self._run([
            "ffmpeg", "-y", "-f", "concat", "-safe", "0",
            "-i", str(lst), "-c", "copy", str(output),
        ], "concatenate segments")

    def _mux(self, video: Path, audio: Path, output: Path) -> None:
        part = output.with_suffix(".part.mp4")
        self._run([
            "ffmpeg", "-y",
            "-i", str(video), "-i", str(audio),
            "-map", "0:v:0", "-map", "1:a:0",
            "-c:v", "copy", "-c:a", "aac", "-b:a", "192k",
            "-movflags", "+faststart", str(part),
        ], "mux audio")
        import os as _os
        _os.replace(part, output)

    @staticmethod
    def _run(cmd: list[str], op: str) -> None:
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=600)
        if result.returncode != 0:
            tail = (result.stderr or "")[-2000:]
            raise RuntimeError(f"Could not {op}: {tail}")


import os  # noqa: E402 — needed by _synth, placed here to avoid circular at top
