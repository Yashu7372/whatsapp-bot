from __future__ import annotations

import logging
import os
import shutil
import subprocess
from pathlib import Path
from typing import Any
from uuid import UUID

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

from .render_engine import RenderEngine, RenderFailure
from .character_routes import router as character_router
from .dialogue_v2_routes import router as dialogue_v2_router

logging.basicConfig(level=os.getenv("LOG_LEVEL", "INFO"))
logger = logging.getLogger("media-renderer")

RENDER_ROOT = Path(os.getenv("RENDER_ROOT", "/data/renders")).resolve()
STORAGE_ROOT = Path(os.getenv("STORAGE_ROOT", "/data/storage")).resolve()
CHARACTER_PACK_ROOT = Path(os.getenv("CHARACTER_PACK_ROOT", "/data/character_pack")).resolve()
MAX_RENDER_SECONDS = int(os.getenv("MAX_RENDER_SECONDS", "90"))

engine = RenderEngine(
    render_root=RENDER_ROOT,
    storage_root=STORAGE_ROOT,
    max_render_seconds=MAX_RENDER_SECONDS,
    lang_code=os.getenv("KOKORO_LANG_CODE", "a"),
    default_voice=os.getenv("KOKORO_DEFAULT_VOICE", "af_heart"),
)

app = FastAPI(title="WhatsApp CRM Media Renderer", version="1.3.0")
app.include_router(character_router)
app.include_router(dialogue_v2_router)


class RenderRequest(BaseModel):
    jobId: UUID
    tenantId: UUID
    templateCode: str
    title: str = ""
    hook: str = ""
    voiceoverText: str = ""
    shotList: list[dict[str, Any]] | dict[str, Any] | None = None
    assetPaths: list[str] = Field(default_factory=list, max_length=8)
    assetUrls: list[str] = Field(default_factory=list, max_length=8)
    voice: str = "af_heart"
    brandName: str = ""
    callToAction: str = ""
    narrationAudioPath: str = ""
    durationSeconds: int = Field(default=30, ge=5, le=90)
    outputPath: str


class RenderResponse(BaseModel):
    status: str
    outputPath: str
    durationSeconds: float
    warnings: list[str] = Field(default_factory=list)


class AudioRequest(BaseModel):
    jobId: UUID
    tenantId: UUID
    text: str = Field(min_length=1, max_length=8000)
    voice: str = "af_heart"
    targetDurationSeconds: int = Field(default=30, ge=5, le=90)
    outputPath: str


class AudioResponse(BaseModel):
    status: str
    outputPath: str
    durationSeconds: float
    provider: str


class VerifyRequest(BaseModel):
    jobId: UUID
    tenantId: UUID
    videoPath: str


class VerifyResponse(BaseModel):
    passed: bool
    durationSeconds: float
    width: int
    height: int
    sizeBytes: int
    message: str


@app.get("/health")
def health() -> dict[str, Any]:
    from .character_routes import _get_pack, VIDEO_TEMPLATE_ROOT, DRESS_ROOT
    from character_pack.video_compositor import template_status, templates_ready, dress_status
    from character_pack.hybrid_photo_compositor import HybridPhotoDialogueCompositor

    pack = _get_pack()
    vt_status = template_status(VIDEO_TEMPLATE_ROOT)
    dynamic = HybridPhotoDialogueCompositor()
    return {
        "status": "UP",
        "ffmpeg": shutil.which("ffmpeg") is not None,
        "renderRoot": str(RENDER_ROOT),
        "characterPackRoot": str(CHARACTER_PACK_ROOT),
        "dynamicDialogueReady": dynamic.ready(),
        "videoTemplatesReady": templates_ready(VIDEO_TEMPLATE_ROOT),
        "videoTemplates": vt_status,
        "dressOverlays": dress_status(DRESS_ROOT),
        "staticSpritePackReady": pack.is_ready() if pack else False,
        "templates": sorted(engine.template_codes()),
    }


@app.post("/v1/audio/generate", response_model=AudioResponse)
def generate_audio(request: AudioRequest) -> AudioResponse:
    output = _validated_render_path(request.outputPath, ".wav")
    output.parent.mkdir(parents=True, exist_ok=True)

    created = engine._create_voice(
        request.text.strip(),
        request.voice.strip() or "af_heart",
        request.targetDurationSeconds,
        output,
    )
    if not created:
        raise HTTPException(status_code=422, detail="Narration audio generation failed")

    duration = _probe_duration(output)
    if duration <= 0:
        raise HTTPException(status_code=422, detail="Generated narration has invalid duration")

    return AudioResponse(
        status="COMPLETED",
        outputPath=str(output),
        durationSeconds=duration,
        provider="kokoro",
    )


@app.post("/v1/render", response_model=RenderResponse)
def render(request: RenderRequest) -> RenderResponse:
    try:
        payload = request.model_dump()
        narration = None
        if request.narrationAudioPath.strip():
            narration = _validated_existing_render_path(request.narrationAudioPath, ".wav")
            # The canonical narration is already locked. Avoid generating a second TTS track.
            payload["voiceoverText"] = ""

        result = engine.render(payload)
        final_duration = result.duration_seconds
        if narration is not None:
            final_duration = _probe_duration(narration)
            if final_duration <= 0:
                raise RenderFailure("Locked narration has invalid duration")
            _replace_audio(result.output_path, narration, final_duration)

        return RenderResponse(
            status="COMPLETED",
            outputPath=str(result.output_path),
            durationSeconds=final_duration,
            warnings=result.warnings,
        )
    except RenderFailure as exc:
        logger.exception("Render failed. job=%s", request.jobId)
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    except HTTPException:
        raise
    except Exception as exc:
        logger.exception("Unexpected render failure. job=%s", request.jobId)
        raise HTTPException(status_code=500, detail="Renderer failed") from exc


@app.post("/v1/verify", response_model=VerifyResponse)
def verify(request: VerifyRequest) -> VerifyResponse:
    video = _validated_existing_render_path(request.videoPath, ".mp4")
    duration = _probe_duration(video)
    width, height = _probe_dimensions(video)
    size = video.stat().st_size
    decodes = _decode_check(video)
    passed = duration > 0 and width > 0 and height > 0 and size > 0 and decodes
    return VerifyResponse(
        passed=passed,
        durationSeconds=duration,
        width=width,
        height=height,
        sizeBytes=size,
        message="Video passed technical QA." if passed else "Video failed technical QA.",
    )


def _validated_render_path(raw_path: str, suffix: str) -> Path:
    path = Path(raw_path).resolve()
    if not path.is_relative_to(RENDER_ROOT):
        raise HTTPException(status_code=422, detail="path must be inside RENDER_ROOT")
    if path.suffix.lower() != suffix:
        raise HTTPException(status_code=422, detail=f"path must end with {suffix}")
    return path


def _validated_existing_render_path(raw_path: str, suffix: str) -> Path:
    path = _validated_render_path(raw_path, suffix)
    if not path.is_file():
        raise HTTPException(status_code=422, detail=f"media file does not exist: {path.name}")
    return path


def _replace_audio(video: Path, narration: Path, duration: float) -> None:
    partial = video.with_suffix(".audio-lock.mp4")
    process = subprocess.run(
        [
            "ffmpeg", "-y",
            "-i", str(video),
            "-i", str(narration),
            "-map", "0:v:0",
            "-map", "1:a:0",
            "-c:v", "copy",
            "-c:a", "aac",
            "-b:a", "160k",
            "-af", "apad",
            "-t", f"{duration:.3f}",
            "-movflags", "+faststart",
            str(partial),
        ],
        capture_output=True,
        text=True,
        timeout=600,
    )
    if process.returncode != 0:
        raise RenderFailure("Could not apply locked narration: " + (process.stderr or "")[-2000:])
    os.replace(partial, video)


def _probe_duration(path: Path) -> float:
    process = subprocess.run(
        [
            "ffprobe",
            "-v", "error",
            "-show_entries", "format=duration",
            "-of", "default=noprint_wrappers=1:nokey=1",
            str(path),
        ],
        capture_output=True,
        text=True,
        timeout=30,
    )
    if process.returncode != 0:
        logger.warning("Could not probe media duration: %s", (process.stderr or "")[-1000:])
        return 0.0
    try:
        return float((process.stdout or "0").strip())
    except ValueError:
        return 0.0


def _probe_dimensions(path: Path) -> tuple[int, int]:
    process = subprocess.run(
        [
            "ffprobe",
            "-v", "error",
            "-select_streams", "v:0",
            "-show_entries", "stream=width,height",
            "-of", "csv=s=x:p=0",
            str(path),
        ],
        capture_output=True,
        text=True,
        timeout=30,
    )
    if process.returncode != 0:
        return 0, 0
    try:
        width, height = (process.stdout or "0x0").strip().split("x", 1)
        return int(width), int(height)
    except (ValueError, TypeError):
        return 0, 0


def _decode_check(path: Path) -> bool:
    process = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", str(path), "-f", "null", "-"],
        capture_output=True,
        text=True,
        timeout=600,
    )
    if process.returncode != 0:
        logger.warning("Video decode QA failed: %s", (process.stderr or "")[-1500:])
        return False
    return True
