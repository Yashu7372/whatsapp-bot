"""
Character pack and dialogue render routes.

All heavy work runs in this renderer worker service.
Spring Boot only submits jobs and polls status — it never processes video itself.

── Video-template mode (preferred) ─────────────────────────────────────────
Download animated character clips once from the SadTalker website (or any
portrait-animation tool) and upload them here.  Every future render loops
those stored clips onto a background with subtitles — zero API calls, zero
re-generation.

Upload clips once:
  POST /v1/character-pack/video-templates/{character}/{emotion}
Check which are present:
  GET  /v1/character-pack/video-templates/status

── Static-sprite mode (fallback) ────────────────────────────────────────────
Generate expression PNG sprites via Gemini and use PIL frame compositing.

Generate sprites from a reference photo:
  POST /v1/character-pack/generate
Check status:
  GET  /v1/character-pack/status

── Dialogue rendering ────────────────────────────────────────────────────────
  POST /v1/dialogue/render       Submit turns → background render job
  GET  /v1/dialogue/render/{id}  Poll job status

Render priority: video-templates → static-sprites → error
"""

from __future__ import annotations

import logging
import os
import threading
import uuid
from enum import Enum
from pathlib import Path
from typing import Any

from fastapi import APIRouter, BackgroundTasks, HTTPException, UploadFile
from pydantic import BaseModel, Field

from character_pack.compositor import DialogueCompositor, DialogueTurn, CompositorRequest
from character_pack.generator import CharacterPackGenerator
from character_pack.models import CharacterPack
from character_pack.video_compositor import (
    VideoTemplateCompositor,
    VideoTemplateTurn,
    VideoCompositorRequest,
    template_status,
    templates_ready,
)

logger = logging.getLogger("character-routes")

router = APIRouter()

PACK_ROOT = Path(os.getenv("CHARACTER_PACK_ROOT", "/data/character_pack"))
RENDER_ROOT = Path(os.getenv("RENDER_ROOT", "/data/renders"))
GEMINI_API_KEY = os.getenv("GEMINI_API_KEY") or os.getenv("AI_GEMINI_API_KEY")

VIDEO_TEMPLATE_ROOT = PACK_ROOT / "video_templates"

VALID_EMOTIONS = {"idle", "talking", "curious", "surprised", "laughing", "thinking"}

_video_compositor = VideoTemplateCompositor()
_static_compositor = DialogueCompositor()


# ------------------------------------------------------------------
# In-memory job registry
# ------------------------------------------------------------------

class _JobStatus(str, Enum):
    PENDING = "PENDING"
    RUNNING = "RUNNING"
    DONE = "DONE"
    FAILED = "FAILED"


_pack_job: dict[str, Any] = {"id": None, "status": _JobStatus.PENDING, "error": None}
_pack_job_lock = threading.Lock()

_dialogue_jobs: dict[str, dict[str, Any]] = {}
_dialogue_lock = threading.Lock()


# ------------------------------------------------------------------
# Cached Gemini expression pack
# ------------------------------------------------------------------

_cached_pack: CharacterPack | None = None
_pack_cache_lock = threading.Lock()


def _get_pack() -> CharacterPack | None:
    global _cached_pack
    with _pack_cache_lock:
        if _cached_pack is None:
            _cached_pack = CharacterPackGenerator.load(PACK_ROOT)
        return _cached_pack


def _invalidate_pack() -> None:
    global _cached_pack
    with _pack_cache_lock:
        _cached_pack = None


# ------------------------------------------------------------------
# Video template upload & status
# ------------------------------------------------------------------

class VideoTemplateUploadResponse(BaseModel):
    character: str
    emotion: str
    bytes: int
    path: str
    message: str


class VideoTemplatesStatusResponse(BaseModel):
    ready: bool
    templates: dict[str, dict[str, str]]


@router.post(
    "/v1/character-pack/video-templates/{character}/{emotion}",
    response_model=VideoTemplateUploadResponse,
)
async def upload_video_template(
    character: str,
    emotion: str,
    clip: UploadFile,
) -> VideoTemplateUploadResponse:
    """
    Upload a pre-generated animated character clip.

    Download the clip once from the SadTalker website (or any portrait-animation
    tool using the real nephews' photos).  Store it here and every future video
    will reuse it — no API, no re-generation.

    character: bhaiya | chitti
    emotion:   idle | talking | curious | surprised | laughing | thinking
    """
    if character not in ("bhaiya", "chitti"):
        raise HTTPException(status_code=422, detail="character must be 'bhaiya' or 'chitti'")
    if emotion not in VALID_EMOTIONS:
        raise HTTPException(
            status_code=422,
            detail=f"emotion must be one of: {', '.join(sorted(VALID_EMOTIONS))}",
        )
    allowed_types = {"video/mp4", "video/quicktime", "application/octet-stream"}
    if clip.content_type not in allowed_types and not (clip.filename or "").endswith(".mp4"):
        raise HTTPException(status_code=422, detail="Only MP4 video files accepted")

    content = await clip.read()
    if not content:
        raise HTTPException(status_code=422, detail="Uploaded file is empty")

    dest_dir = VIDEO_TEMPLATE_ROOT / character
    dest_dir.mkdir(parents=True, exist_ok=True)
    dest = dest_dir / f"{emotion}.mp4"
    dest.write_bytes(content)
    logger.info(
        "Stored video template: %s/%s (%d bytes) → %s",
        character, emotion, len(content), dest,
    )

    return VideoTemplateUploadResponse(
        character=character,
        emotion=emotion,
        bytes=len(content),
        path=str(dest),
        message=(
            f"Template stored. All future '{emotion}' turns for {character} "
            "will use this clip — no re-generation needed."
        ),
    )


@router.get(
    "/v1/character-pack/video-templates/status",
    response_model=VideoTemplatesStatusResponse,
)
def video_templates_status() -> VideoTemplatesStatusResponse:
    """List which video templates are stored and ready for rendering."""
    st = template_status(VIDEO_TEMPLATE_ROOT)
    return VideoTemplatesStatusResponse(
        ready=templates_ready(VIDEO_TEMPLATE_ROOT),
        templates=st,
    )


# ------------------------------------------------------------------
# Gemini-based character pack generation (static PNG sprites)
# ------------------------------------------------------------------

class GeneratePackResponse(BaseModel):
    jobId: str
    status: str
    message: str


class PackStatusResponse(BaseModel):
    jobId: str | None
    status: str
    error: str | None = None
    pack: dict | None = None


@router.post("/v1/character-pack/generate", response_model=GeneratePackResponse)
async def generate_character_pack(
    reference_image: UploadFile,
    background_tasks: BackgroundTasks,
) -> GeneratePackResponse:
    allowed_types = {"image/jpeg", "image/png", "image/webp"}
    if reference_image.content_type not in allowed_types:
        raise HTTPException(status_code=422, detail="Only JPEG / PNG / WEBP images accepted")

    with _pack_job_lock:
        if _pack_job["status"] == _JobStatus.RUNNING:
            raise HTTPException(
                status_code=409,
                detail="A character pack generation job is already running",
            )
        job_id = str(uuid.uuid4())
        _pack_job["id"] = job_id
        _pack_job["status"] = _JobStatus.RUNNING
        _pack_job["error"] = None

    ref_path = PACK_ROOT / "upload_reference.png"
    PACK_ROOT.mkdir(parents=True, exist_ok=True)
    content = await reference_image.read()
    ref_path.write_bytes(content)

    background_tasks.add_task(_run_generation, ref_path)

    return GeneratePackResponse(
        jobId=job_id,
        status=_JobStatus.RUNNING,
        message="Character pack generation started in the background",
    )


def _run_generation(ref_path: Path) -> None:
    try:
        generator = CharacterPackGenerator(pack_root=PACK_ROOT, gemini_api_key=GEMINI_API_KEY)
        generator.generate(ref_path)
        _invalidate_pack()
        with _pack_job_lock:
            _pack_job["status"] = _JobStatus.DONE
        logger.info("Character pack generation complete")
    except Exception as exc:
        logger.exception("Character pack generation failed")
        with _pack_job_lock:
            _pack_job["status"] = _JobStatus.FAILED
            _pack_job["error"] = str(exc)


@router.get("/v1/character-pack/status", response_model=PackStatusResponse)
def character_pack_status() -> PackStatusResponse:
    with _pack_job_lock:
        job_id = _pack_job["id"]
        status = _pack_job["status"]
        error = _pack_job["error"]

    pack = _get_pack()
    pack_info = pack.status_dict() if pack else None

    if job_id is None and pack and pack.is_ready():
        status = _JobStatus.DONE

    return PackStatusResponse(jobId=job_id, status=status, error=error, pack=pack_info)


@router.get("/v1/character-pack/info")
def character_pack_info() -> dict:
    pack = _get_pack()
    if not pack:
        raise HTTPException(
            status_code=404,
            detail="No character pack found. Generate one via POST /v1/character-pack/generate",
        )
    return pack.status_dict()


# ------------------------------------------------------------------
# Dialogue rendering — uses video templates when present, else static sprites
# ------------------------------------------------------------------

class DialogueTurnRequest(BaseModel):
    speaker: str = Field(pattern="^(bhaiya|chitti)$")
    emotion: str = Field(default="talking")
    text: str = Field(min_length=1, max_length=500)
    duration_seconds: float = Field(default=4.0, ge=1.0, le=20.0)


class DialogueRenderRequest(BaseModel):
    jobId: str
    tenantId: str
    turns: list[DialogueTurnRequest] = Field(min_length=1, max_length=30)
    outputPath: str


class DialogueRenderResponse(BaseModel):
    jobId: str
    status: str
    renderMode: str   # "video-templates" | "static-sprites"


class DialogueStatusResponse(BaseModel):
    jobId: str
    status: str
    outputPath: str | None = None
    error: str | None = None


@router.post("/v1/dialogue/render", response_model=DialogueRenderResponse)
async def start_dialogue_render(
    request: DialogueRenderRequest,
    background_tasks: BackgroundTasks,
) -> DialogueRenderResponse:
    output_path = Path(request.outputPath).resolve()
    render_root = RENDER_ROOT.resolve()
    if not output_path.is_relative_to(render_root):
        raise HTTPException(status_code=422, detail="outputPath must be inside RENDER_ROOT")
    if output_path.suffix.lower() != ".mp4":
        raise HTTPException(status_code=422, detail="outputPath must end with .mp4")

    use_video = templates_ready(VIDEO_TEMPLATE_ROOT)

    if not use_video:
        pack = _get_pack()
        if not pack or not pack.is_ready():
            raise HTTPException(
                status_code=422,
                detail=(
                    "No character assets are available for rendering. "
                    "Upload video templates (preferred) via "
                    "POST /v1/character-pack/video-templates/{character}/{emotion}, "
                    "or generate static sprites via POST /v1/character-pack/generate."
                ),
            )

    with _dialogue_lock:
        _dialogue_jobs[request.jobId] = {
            "status": _JobStatus.RUNNING,
            "outputPath": None,
            "error": None,
        }

    bg_path = PACK_ROOT / "scene" / "background.png"
    background_path = bg_path if bg_path.exists() else None

    if use_video:
        vt_turns = [
            VideoTemplateTurn(
                speaker=t.speaker,
                emotion=t.emotion,
                text=t.text,
                duration_seconds=t.duration_seconds,
            )
            for t in request.turns
        ]
        vt_req = VideoCompositorRequest(
            turns=vt_turns,
            output_path=output_path,
            template_root=VIDEO_TEMPLATE_ROOT,
            background_path=background_path,
        )
        background_tasks.add_task(_run_video_render, request.jobId, vt_req)
        render_mode = "video-templates"
    else:
        pack = _get_pack()
        static_turns = [
            DialogueTurn(
                speaker=t.speaker,
                emotion=t.emotion,
                text=t.text,
                duration_seconds=t.duration_seconds,
            )
            for t in request.turns
        ]
        static_req = CompositorRequest(
            turns=static_turns,
            output_path=output_path,
            pack=pack,
        )
        background_tasks.add_task(_run_static_render, request.jobId, static_req)
        render_mode = "static-sprites"

    return DialogueRenderResponse(
        jobId=request.jobId,
        status=_JobStatus.RUNNING,
        renderMode=render_mode,
    )


def _run_video_render(job_id: str, req: VideoCompositorRequest) -> None:
    try:
        req.output_path.parent.mkdir(parents=True, exist_ok=True)
        _video_compositor.render(req)
        with _dialogue_lock:
            _dialogue_jobs[job_id]["status"] = _JobStatus.DONE
            _dialogue_jobs[job_id]["outputPath"] = str(req.output_path)
        logger.info("Video-template render complete. job=%s", job_id)
    except Exception as exc:
        logger.exception("Video-template render failed. job=%s", job_id)
        with _dialogue_lock:
            _dialogue_jobs[job_id]["status"] = _JobStatus.FAILED
            _dialogue_jobs[job_id]["error"] = str(exc)


def _run_static_render(job_id: str, req: CompositorRequest) -> None:
    try:
        req.output_path.parent.mkdir(parents=True, exist_ok=True)
        _static_compositor.render(req)
        with _dialogue_lock:
            _dialogue_jobs[job_id]["status"] = _JobStatus.DONE
            _dialogue_jobs[job_id]["outputPath"] = str(req.output_path)
        logger.info("Static-sprite render complete. job=%s", job_id)
    except Exception as exc:
        logger.exception("Static-sprite render failed. job=%s", job_id)
        with _dialogue_lock:
            _dialogue_jobs[job_id]["status"] = _JobStatus.FAILED
            _dialogue_jobs[job_id]["error"] = str(exc)


@router.get("/v1/dialogue/render/{job_id}", response_model=DialogueStatusResponse)
def dialogue_render_status(job_id: str) -> DialogueStatusResponse:
    with _dialogue_lock:
        job = _dialogue_jobs.get(job_id)
    if not job:
        raise HTTPException(status_code=404, detail=f"Dialogue render job not found: {job_id}")
    return DialogueStatusResponse(
        jobId=job_id,
        status=job["status"],
        outputPath=job.get("outputPath"),
        error=job.get("error"),
    )
