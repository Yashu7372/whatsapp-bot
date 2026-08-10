"""
Character pack and dialogue render routes — all heavy work runs here in the
renderer worker service. Spring Boot submits jobs and polls; it never processes
images or renders video itself.

Routes:
  POST /v1/character-pack/generate   Upload reference image → start generation job
  GET  /v1/character-pack/status     Poll generation job status
  GET  /v1/character-pack/info       Describe the ready pack (expressions per character)
  POST /v1/dialogue/render           Render a dialogue script → MP4
  GET  /v1/dialogue/render/{job_id}  Poll dialogue render status
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

from ..character_pack.compositor import DialogueCompositor, DialogueTurn, CompositorRequest
from ..character_pack.generator import CharacterPackGenerator
from ..character_pack.models import CharacterPack

logger = logging.getLogger("character-routes")

router = APIRouter()

PACK_ROOT = Path(os.getenv("CHARACTER_PACK_ROOT", "/data/character_pack"))
RENDER_ROOT = Path(os.getenv("RENDER_ROOT", "/data/renders"))
GEMINI_API_KEY = os.getenv("GEMINI_API_KEY") or os.getenv("AI_GEMINI_API_KEY")

_compositor = DialogueCompositor()


# ------------------------------------------------------------------
# In-memory job registry (survives across requests within one process)
# ------------------------------------------------------------------

class _JobStatus(str, Enum):
    PENDING = "PENDING"
    RUNNING = "RUNNING"
    DONE = "DONE"
    FAILED = "FAILED"


_pack_job: dict[str, Any] = {
    "id": None,
    "status": _JobStatus.PENDING,
    "error": None,
}
_pack_job_lock = threading.Lock()

_dialogue_jobs: dict[str, dict[str, Any]] = {}
_dialogue_lock = threading.Lock()


# ------------------------------------------------------------------
# Cached pack (loaded once, reloaded on generate)
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
# Character pack generation
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

    # Save uploaded reference image to disk
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

    # If no job has been started but a pack exists on disk, report DONE
    if job_id is None and pack and pack.is_ready():
        status = _JobStatus.DONE

    return PackStatusResponse(
        jobId=job_id,
        status=status,
        error=error,
        pack=pack_info,
    )


@router.get("/v1/character-pack/info")
def character_pack_info() -> dict:
    pack = _get_pack()
    if not pack:
        raise HTTPException(status_code=404, detail="No character pack found. Generate one first.")
    return pack.status_dict()


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
    pack = _get_pack()
    if not pack or not pack.is_ready():
        raise HTTPException(
            status_code=422,
            detail="Character pack is not ready. Generate it first via POST /v1/character-pack/generate",
        )

    output_path = Path(request.outputPath).resolve()
    render_root = RENDER_ROOT.resolve()
    if not output_path.is_relative_to(render_root):
        raise HTTPException(status_code=422, detail="outputPath must be inside RENDER_ROOT")
    if output_path.suffix.lower() != ".mp4":
        raise HTTPException(status_code=422, detail="outputPath must end with .mp4")

    with _dialogue_lock:
        _dialogue_jobs[request.jobId] = {
            "status": _JobStatus.RUNNING,
            "outputPath": None,
            "error": None,
        }

    turns = [
        DialogueTurn(
            speaker=t.speaker,
            emotion=t.emotion,
            text=t.text,
            duration_seconds=t.duration_seconds,
        )
        for t in request.turns
    ]
    background_tasks.add_task(
        _run_dialogue_render, request.jobId, turns, output_path, pack
    )
    return DialogueRenderResponse(jobId=request.jobId, status=_JobStatus.RUNNING)


def _run_dialogue_render(
    job_id: str,
    turns: list[DialogueTurn],
    output_path: Path,
    pack: CharacterPack,
) -> None:
    try:
        output_path.parent.mkdir(parents=True, exist_ok=True)
        req = CompositorRequest(turns=turns, output_path=output_path, pack=pack)
        _compositor.render(req)
        with _dialogue_lock:
            _dialogue_jobs[job_id]["status"] = _JobStatus.DONE
            _dialogue_jobs[job_id]["outputPath"] = str(output_path)
        logger.info("Dialogue render complete. job=%s output=%s", job_id, output_path)
    except Exception as exc:
        logger.exception("Dialogue render failed. job=%s", job_id)
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
