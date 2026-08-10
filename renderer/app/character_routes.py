"""
Character pack and dialogue render routes — all heavy work runs here in the
renderer worker service. Spring Boot submits jobs and polls; it never processes
images or renders video itself.

Routes:
  POST /v1/character-pack/generate         Upload reference image → Gemini expression pack
  GET  /v1/character-pack/status           Poll generation job status
  GET  /v1/character-pack/info             Describe the ready pack
  POST /v1/character-pack/photos/{char}    Upload real photo for bhaiya / chitti (SadTalker mode)
  GET  /v1/character-pack/photos/status    Check whether both real photos are present
  POST /v1/dialogue/render                 Submit dialogue turns → background render
  GET  /v1/dialogue/render/{job_id}        Poll dialogue render status

RENDER_MODE env var:
  "auto"       (default) Use SadTalker when service is up and photos present, else static
  "sadtalker"  Always use SadTalker (fail clearly if unavailable or photos missing)
  "static"     Always use PIL-based static compositor (Gemini expression PNGs)
"""

from __future__ import annotations

import logging
import os
import threading
import uuid
from enum import Enum
from pathlib import Path
from typing import Any

import httpx
from fastapi import APIRouter, BackgroundTasks, HTTPException, UploadFile
from pydantic import BaseModel, Field

from ..character_pack.compositor import DialogueCompositor, DialogueTurn, CompositorRequest
from ..character_pack.generator import CharacterPackGenerator
from ..character_pack.models import CharacterPack
from ..character_pack.photo_compositor import PhotoDialogueCompositor, PhotoDialogueTurn, PhotoCompositorRequest
from ..character_pack.sadtalker_client import SadTalkerClient, SadTalkerUnavailable

logger = logging.getLogger("character-routes")

router = APIRouter()

PACK_ROOT = Path(os.getenv("CHARACTER_PACK_ROOT", "/data/character_pack"))
RENDER_ROOT = Path(os.getenv("RENDER_ROOT", "/data/renders"))
GEMINI_API_KEY = os.getenv("GEMINI_API_KEY") or os.getenv("AI_GEMINI_API_KEY")
RENDER_MODE = os.getenv("RENDER_MODE", "auto").lower()   # auto | sadtalker | static
SADTALKER_URL = os.getenv("SADTALKER_URL", "http://sadtalker:8091")

_static_compositor = DialogueCompositor()
_photo_compositor: PhotoDialogueCompositor | None = None
_photo_compositor_lock = threading.Lock()

_sadtalker_client = SadTalkerClient(base_url=SADTALKER_URL)


def _get_photo_compositor() -> PhotoDialogueCompositor:
    global _photo_compositor
    with _photo_compositor_lock:
        if _photo_compositor is None:
            _photo_compositor = PhotoDialogueCompositor(sadtalker_client=_sadtalker_client)
        return _photo_compositor


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
# Render mode selection
# ------------------------------------------------------------------

def _should_use_sadtalker() -> bool:
    """Decide whether to use the SadTalker photo compositor for this render."""
    if RENDER_MODE == "static":
        return False
    if RENDER_MODE == "sadtalker":
        return True
    # "auto": use SadTalker only when the service is reachable and photos are present
    try:
        return _sadtalker_client.is_available() and _sadtalker_client.photos_ready()
    except Exception:
        return False


# ------------------------------------------------------------------
# Gemini-based character pack generation (expression sprites)
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
# Real-photo management (SadTalker mode — stored once, reused forever)
# ------------------------------------------------------------------

class PhotoUploadResponse(BaseModel):
    character: str
    bytes: int
    message: str


class PhotosStatusResponse(BaseModel):
    bhaiya: bool
    chitti: bool
    sadtalkerAvailable: bool
    renderMode: str


@router.post("/v1/character-pack/photos/{character}", response_model=PhotoUploadResponse)
async def upload_character_photo(character: str, photo: UploadFile) -> PhotoUploadResponse:
    """
    Upload the real photo for bhaiya or chitti.  Stored once in the SadTalker
    service, reused for every video without any API calls.
    """
    if character not in ("bhaiya", "chitti"):
        raise HTTPException(status_code=422, detail="character must be 'bhaiya' or 'chitti'")
    allowed = {"image/jpeg", "image/png", "image/webp"}
    if photo.content_type not in allowed:
        raise HTTPException(status_code=422, detail="Only JPEG / PNG / WEBP images accepted")

    content = await photo.read()
    if not content:
        raise HTTPException(status_code=422, detail="Uploaded file is empty")

    # Save temporarily so we can forward to SadTalker service
    tmp_path = PACK_ROOT / f"_upload_{character}_{photo.filename}"
    PACK_ROOT.mkdir(parents=True, exist_ok=True)
    tmp_path.write_bytes(content)
    try:
        result = _sadtalker_client.upload_photo(character, tmp_path)
    except httpx.ConnectError:
        raise HTTPException(
            status_code=503,
            detail=(
                "SadTalker service is not reachable. "
                "Start the sadtalker service and try again."
            ),
        )
    except Exception as exc:
        raise HTTPException(status_code=500, detail=f"Photo upload failed: {exc}")
    finally:
        tmp_path.unlink(missing_ok=True)

    return PhotoUploadResponse(
        character=character,
        bytes=result.get("bytes", len(content)),
        message=f"Photo stored. Every video generated for {character} will use this real face.",
    )


@router.get("/v1/character-pack/photos/status", response_model=PhotosStatusResponse)
def photos_status() -> PhotosStatusResponse:
    st_available = _sadtalker_client.is_available()
    photos = _sadtalker_client.photos_status() if st_available else {"bhaiya": False, "chitti": False}
    return PhotosStatusResponse(
        bhaiya=photos.get("bhaiya", False),
        chitti=photos.get("chitti", False),
        sadtalkerAvailable=st_available,
        renderMode=RENDER_MODE,
    )


# ------------------------------------------------------------------
# Dialogue rendering
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
    renderMode: str


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

    use_sadtalker = _should_use_sadtalker()

    if use_sadtalker:
        active_mode = "sadtalker"
    else:
        # Static mode requires the Gemini expression pack
        pack = _get_pack()
        if not pack or not pack.is_ready():
            raise HTTPException(
                status_code=422,
                detail=(
                    "Neither SadTalker photos nor a Gemini expression pack are available. "
                    "Upload real photos via POST /v1/character-pack/photos/{character} "
                    "or generate the expression pack via POST /v1/character-pack/generate."
                ),
            )
        active_mode = "static"

    with _dialogue_lock:
        _dialogue_jobs[request.jobId] = {
            "status": _JobStatus.RUNNING,
            "outputPath": None,
            "error": None,
        }

    if use_sadtalker:
        photo_turns = [
            PhotoDialogueTurn(
                speaker=t.speaker,
                text=t.text,
                duration_seconds=t.duration_seconds,
            )
            for t in request.turns
        ]
        bg_path = (PACK_ROOT / "scene" / "background.png") if (PACK_ROOT / "scene" / "background.png").exists() else None
        photo_req = PhotoCompositorRequest(
            turns=photo_turns,
            output_path=output_path,
            background_path=bg_path,
        )
        background_tasks.add_task(
            _run_photo_render, request.jobId, photo_req
        )
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
        static_req = CompositorRequest(turns=static_turns, output_path=output_path, pack=pack)
        background_tasks.add_task(
            _run_static_render, request.jobId, static_req
        )

    return DialogueRenderResponse(
        jobId=request.jobId,
        status=_JobStatus.RUNNING,
        renderMode=active_mode,
    )


def _run_photo_render(job_id: str, req: PhotoCompositorRequest) -> None:
    try:
        req.output_path.parent.mkdir(parents=True, exist_ok=True)
        _get_photo_compositor().render(req)
        with _dialogue_lock:
            _dialogue_jobs[job_id]["status"] = _JobStatus.DONE
            _dialogue_jobs[job_id]["outputPath"] = str(req.output_path)
        logger.info("Photo dialogue render complete. job=%s", job_id)
    except Exception as exc:
        logger.exception("Photo dialogue render failed. job=%s", job_id)
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
        logger.info("Static dialogue render complete. job=%s", job_id)
    except Exception as exc:
        logger.exception("Static dialogue render failed. job=%s", job_id)
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
