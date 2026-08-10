from __future__ import annotations

import json
import logging
import os
import shutil
import subprocess
import tempfile
import textwrap
import threading
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

import httpx

logger = logging.getLogger("media-renderer.engine")

VIDEO_EXTENSIONS = {".mp4", ".mov", ".m4v", ".webm", ".mkv"}
IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp"}
ALLOWED_REMOTE_HOSTS = {"videos.pexels.com", "images.pexels.com", "cdn.pixabay.com"}
FONT_REGULAR = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
FONT_BOLD = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"


class RenderFailure(RuntimeError):
    pass


@dataclass(frozen=True)
class FixedTemplate:
    code: str
    background: str
    box_y: int
    box_height: int
    font_size: int
    brand_y: int
    cta_y: int


@dataclass(frozen=True)
class RenderResult:
    output_path: Path
    duration_seconds: float
    warnings: list[str]


TEMPLATES: dict[str, FixedTemplate] = {
    "PRODUCT_HOOK_V1": FixedTemplate(
        code="PRODUCT_HOOK_V1",
        background="0x111827",
        box_y=1320,
        box_height=460,
        font_size=64,
        brand_y=90,
        cta_y=1730,
    ),
    "LISTICLE_V1": FixedTemplate(
        code="LISTICLE_V1",
        background="0x172554",
        box_y=1270,
        box_height=500,
        font_size=60,
        brand_y=90,
        cta_y=1740,
    ),
    "MINIMAL_QUOTE_V1": FixedTemplate(
        code="MINIMAL_QUOTE_V1",
        background="0x18181b",
        box_y=650,
        box_height=620,
        font_size=58,
        brand_y=100,
        cta_y=1710,
    ),
}


class RenderEngine:
    def __init__(
        self,
        render_root: Path,
        storage_root: Path,
        max_render_seconds: int,
        lang_code: str,
        default_voice: str,
    ) -> None:
        self.render_root = render_root
        self.storage_root = storage_root
        self.max_render_seconds = max_render_seconds
        self.lang_code = lang_code
        self.default_voice = default_voice
        self.render_root.mkdir(parents=True, exist_ok=True)
        self._tts_pipeline: Any | None = None
        self._tts_lock = threading.Lock()

    def template_codes(self) -> set[str]:
        return set(TEMPLATES)

    def render(self, payload: dict[str, Any]) -> RenderResult:
        template_code = str(payload.get("templateCode", ""))
        template = TEMPLATES.get(template_code)
        if template is None:
            raise RenderFailure(f"Unknown fixed template: {template_code}")

        target_duration = max(5, min(int(payload.get("durationSeconds", 30)), self.max_render_seconds))
        output_path = self._validated_output_path(str(payload.get("outputPath", "")))
        output_path.parent.mkdir(parents=True, exist_ok=True)
        warnings: list[str] = []

        with tempfile.TemporaryDirectory(prefix="reel-render-") as temporary:
            workdir = Path(temporary)
            assets = self._resolve_assets(payload, workdir, warnings)
            scenes = self._build_scenes(payload, target_duration)
            segment_paths: list[Path] = []

            for index, scene in enumerate(scenes):
                asset = assets[index % len(assets)] if assets else None
                segment = workdir / f"scene-{index:02d}.mp4"
                self._create_segment(
                    template=template,
                    asset=asset,
                    duration=float(scene["duration"]),
                    text=str(scene["text"]),
                    brand_name=str(payload.get("brandName", "")),
                    call_to_action=str(payload.get("callToAction", "")),
                    scene_number=index + 1,
                    segment_path=segment,
                    workdir=workdir,
                )
                segment_paths.append(segment)

            joined_video = workdir / "joined.mp4"
            self._concat_segments(segment_paths, joined_video, workdir)

            voice_path = workdir / "voice.wav"
            voice_text = str(payload.get("voiceoverText", "")).strip()
            voice = str(payload.get("voice", "")).strip() or self.default_voice
            if not self._create_voice(voice_text, voice, target_duration, voice_path):
                warnings.append("Kokoro voice generation failed; the reel contains silent audio.")
                self._create_silence(target_duration, voice_path)

            self._mux_audio(joined_video, voice_path, target_duration, output_path)

        return RenderResult(output_path=output_path, duration_seconds=float(target_duration), warnings=warnings)

    def _validated_output_path(self, raw_path: str) -> Path:
        if not raw_path:
            raise RenderFailure("outputPath is required")
        path = Path(raw_path).resolve()
        if not path.is_relative_to(self.render_root):
            raise RenderFailure("outputPath is outside RENDER_ROOT")
        if path.suffix.lower() != ".mp4":
            raise RenderFailure("outputPath must end with .mp4")
        return path

    def _resolve_assets(self, payload: dict[str, Any], workdir: Path, warnings: list[str]) -> list[Path]:
        assets: list[Path] = []
        for raw in payload.get("assetPaths") or []:
            path = Path(str(raw)).resolve()
            if not path.is_relative_to(self.storage_root):
                warnings.append(f"Ignored asset outside storage root: {path.name}")
                continue
            if path.is_file() and path.suffix.lower() in VIDEO_EXTENSIONS | IMAGE_EXTENSIONS:
                assets.append(path)

        for index, raw_url in enumerate(payload.get("assetUrls") or []):
            try:
                assets.append(self._download_remote_asset(str(raw_url), workdir, index))
            except Exception as exc:
                logger.warning("Could not download stock asset: %s", exc)
                warnings.append(f"Could not download stock asset {index + 1}.")
        return assets[:8]

    def _download_remote_asset(self, url: str, workdir: Path, index: int) -> Path:
        parsed = urlparse(url)
        if parsed.scheme != "https" or parsed.hostname not in ALLOWED_REMOTE_HOSTS:
            raise RenderFailure("Remote media host is not allowed")
        suffix = Path(parsed.path).suffix.lower()
        if suffix not in VIDEO_EXTENSIONS | IMAGE_EXTENSIONS:
            suffix = ".mp4"
        target = workdir / f"remote-{index:02d}{suffix}"
        with httpx.stream("GET", url, follow_redirects=False, timeout=45.0) as response:
            response.raise_for_status()
            content_length = int(response.headers.get("content-length", "0") or 0)
            if content_length > 100 * 1024 * 1024:
                raise RenderFailure("Remote asset exceeds 100 MB")
            with target.open("wb") as handle:
                downloaded = 0
                for chunk in response.iter_bytes():
                    downloaded += len(chunk)
                    if downloaded > 100 * 1024 * 1024:
                        raise RenderFailure("Remote asset exceeds 100 MB")
                    handle.write(chunk)
        return target

    def _build_scenes(self, payload: dict[str, Any], target_duration: int) -> list[dict[str, Any]]:
        raw_shots = payload.get("shotList")
        if isinstance(raw_shots, dict):
            raw_shots = raw_shots.get("shots", [])
        scenes: list[dict[str, Any]] = []
        if isinstance(raw_shots, list):
            for shot in raw_shots[:8]:
                if not isinstance(shot, dict):
                    continue
                text = str(shot.get("audio") or shot.get("caption") or shot.get("visual") or "").strip()
                duration = max(2.0, min(float(shot.get("duration", 4)), 12.0))
                if text:
                    scenes.append({"text": text, "duration": duration})

        if not scenes:
            hook = str(payload.get("hook", "")).strip()
            title = str(payload.get("title", "")).strip()
            cta = str(payload.get("callToAction", "")).strip()
            base_texts = [value for value in [hook, title, cta] if value]
            if not base_texts:
                base_texts = ["Your reel is ready"]
            duration = target_duration / len(base_texts)
            scenes = [{"text": value, "duration": duration} for value in base_texts]

        total = sum(float(scene["duration"]) for scene in scenes)
        scale = target_duration / total if total else 1.0
        for scene in scenes:
            scene["duration"] = max(1.0, float(scene["duration"]) * scale)
        return scenes

    def _create_segment(
        self,
        template: FixedTemplate,
        asset: Path | None,
        duration: float,
        text: str,
        brand_name: str,
        call_to_action: str,
        scene_number: int,
        segment_path: Path,
        workdir: Path,
    ) -> None:
        text_file = workdir / f"text-{scene_number:02d}.txt"
        wrapped = "\n".join(textwrap.wrap(text[:220], width=28))
        text_file.write_text(wrapped, encoding="utf-8")
        brand_file = workdir / f"brand-{scene_number:02d}.txt"
        brand_file.write_text(brand_name[:60], encoding="utf-8")
        cta_file = workdir / f"cta-{scene_number:02d}.txt"
        cta_file.write_text(call_to_action[:80], encoding="utf-8")

        command = ["ffmpeg", "-y"]
        if asset and asset.suffix.lower() in VIDEO_EXTENSIONS:
            command += ["-stream_loop", "-1", "-i", str(asset), "-t", f"{duration:.3f}"]
        elif asset and asset.suffix.lower() in IMAGE_EXTENSIONS:
            command += ["-loop", "1", "-framerate", "30", "-i", str(asset), "-t", f"{duration:.3f}"]
        else:
            command += [
                "-f", "lavfi", "-i",
                f"color=c={template.background}:s=1080x1920:r=30:d={duration:.3f}",
            ]

        filters = [
            "scale=1080:1920:force_original_aspect_ratio=increase",
            "crop=1080:1920",
            "setsar=1",
            "fps=30",
            f"drawbox=x=55:y={template.box_y}:w=iw-110:h={template.box_height}:color=black@0.58:t=fill",
            (
                f"drawtext=fontfile={FONT_BOLD}:textfile={text_file}:reload=0:"
                f"fontcolor=white:fontsize={template.font_size}:line_spacing=18:"
                f"x=(w-text_w)/2:y={template.box_y + 70}"
            ),
        ]
        if brand_name:
            filters.append(
                f"drawtext=fontfile={FONT_BOLD}:textfile={brand_file}:fontcolor=white@0.92:"
                f"fontsize=38:x=70:y={template.brand_y}"
            )
        if call_to_action:
            filters += [
                f"drawbox=x=80:y={template.cta_y}:w=iw-160:h=105:color=white@0.92:t=fill",
                f"drawtext=fontfile={FONT_BOLD}:textfile={cta_file}:fontcolor=black:fontsize=36:"
                f"x=(w-text_w)/2:y={template.cta_y + 30}",
            ]

        command += [
            "-vf", ",".join(filters),
            "-an",
            "-c:v", "libx264",
            "-preset", "veryfast",
            "-crf", "21",
            "-pix_fmt", "yuv420p",
            "-movflags", "+faststart",
            str(segment_path),
        ]
        self._run(command, "create scene")

    def _concat_segments(self, segments: list[Path], output: Path, workdir: Path) -> None:
        if not segments:
            raise RenderFailure("No render scenes were created")
        concat_file = workdir / "concat.txt"
        concat_file.write_text(
            "\n".join(f"file '{segment.as_posix()}'" for segment in segments),
            encoding="utf-8",
        )
        self._run([
            "ffmpeg", "-y", "-f", "concat", "-safe", "0", "-i", str(concat_file),
            "-c", "copy", str(output),
        ], "concatenate scenes")

    def _create_voice(self, text: str, voice: str, duration: int, output: Path) -> bool:
        if not text:
            self._create_silence(duration, output)
            return True
        try:
            import numpy as np
            import soundfile as sf
            from kokoro import KPipeline

            with self._tts_lock:
                if self._tts_pipeline is None:
                    self._tts_pipeline = KPipeline(lang_code=self.lang_code)
                generated = self._tts_pipeline(text[:4000], voice=voice, speed=1.0, split_pattern=r"\n+")
            chunks = [audio for _, _, audio in generated]
            if not chunks:
                return False
            sf.write(output, np.concatenate(chunks), 24000)
            return True
        except Exception as exc:
            logger.warning("Kokoro TTS failed: %s", exc)
            return False

    def _create_silence(self, duration: int, output: Path) -> None:
        self._run([
            "ffmpeg", "-y", "-f", "lavfi", "-i", "anullsrc=r=48000:cl=stereo",
            "-t", str(duration), "-c:a", "pcm_s16le", str(output),
        ], "create silent audio")

    def _mux_audio(self, video: Path, audio: Path, duration: int, output: Path) -> None:
        partial = output.with_suffix(".partial.mp4")
        self._run([
            "ffmpeg", "-y", "-i", str(video), "-i", str(audio),
            "-map", "0:v:0", "-map", "1:a:0",
            "-c:v", "copy", "-c:a", "aac", "-b:a", "160k",
            "-af", "apad", "-t", str(duration),
            "-movflags", "+faststart", str(partial),
        ], "mux voice audio")
        os.replace(partial, output)

    def _run(self, command: list[str], operation: str) -> None:
        logger.debug("ffmpeg operation=%s command=%s", operation, json.dumps(command))
        process = subprocess.run(command, capture_output=True, text=True, timeout=600)
        if process.returncode != 0:
            tail = (process.stderr or "")[-3000:]
            raise RenderFailure(f"Could not {operation}: {tail}")
