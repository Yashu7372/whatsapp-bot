"""V2 dialogue renderer with AUTO strategy selection and persistent job status."""
from __future__ import annotations

import json
import logging
import os
from pathlib import Path
from typing import Any

from fastapi import APIRouter, BackgroundTasks, HTTPException
from pydantic import BaseModel, Field

from character_pack.compositor import CompositorRequest, DialogueCompositor, DialogueTurn
from character_pack.hybrid_photo_compositor import (
    HybridDialogueTurn,
    HybridPhotoCompositorRequest,
    HybridPhotoDialogueCompositor,
)
from character_pack.video_compositor import (
    VideoCompositorRequest,
    VideoTemplateCompositor,
    VideoTemplateTurn,
    load_dress_overlays,
    templates_ready,
)
from .character_routes import _get_pack, DRESS_ROOT, PACK_ROOT, VIDEO_TEMPLATE_ROOT

logger = logging.getLogger("dialogue-v2-routes")
router = APIRouter()

RENDER_ROOT = Path(os.getenv("RENDER_ROOT", "/data/renders")).resolve()
STATUS_ROOT = RENDER_ROOT / ".dialogue-status"
RENDER_MODE = os.getenv("RENDER_MODE", "auto").strip().lower()

_dynamic = HybridPhotoDialogueCompositor()
_video = VideoTemplateCompositor()
_static = DialogueCompositor()


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
    renderMode: str | None = None
    outputPath: str | None = None
    error: str | None = None


@router.post("/v2/dialogue/render", response_model=DialogueRenderResponse)
async def start_dialogue_render(
    request: DialogueRenderRequest,
    background_tasks: BackgroundTasks,
) -> DialogueRenderResponse:
    output_path = Path(request.outputPath).resolve()
    if not output_path.is_relative_to(RENDER_ROOT):
        raise HTTPException(status_code=422, detail="outputPath must be inside RENDER_ROOT")
    if output_path.suffix.lower() != ".mp4":
        raise HTTPException(status_code=422, detail="outputPath must end with .mp4")

    mode = _select_mode()
    _write_status(request.jobId, {
        "jobId": request.jobId,
        "status": "RUNNING",
        "renderMode": mode,
        "outputPath": None,
        "error": None,
    })

    background = PACK_ROOT / "scene" / "background.png"
    background_path = background if background.exists() else None

    if mode == "dynamic":
        render_request = HybridPhotoCompositorRequest(
            turns=[
                HybridDialogueTurn(
                    speaker=turn.speaker,
                    emotion=turn.emotion,
                    text=turn.text,
                    duration_seconds=turn.duration_seconds,
                )
                for turn in request.turns
            ],
            output_path=output_path,
            background_path=background_path,
        )
        background_tasks.add_task(_run_dynamic, request.jobId, render_request)
    elif mode == "video-templates":
        render_request = VideoCompositorRequest(
            turns=[
                VideoTemplateTurn(
                    speaker=turn.speaker,
                    emotion=turn.emotion,
                    text=turn.text,
                    duration_seconds=turn.duration_seconds,
                )
                for turn in request.turns
            ],
            output_path=output_path,
            template_root=VIDEO_TEMPLATE_ROOT,
            background_path=background_path,
            dress_overlays=load_dress_overlays(DRESS_ROOT),
        )
        background_tasks.add_task(_run_video, request.jobId, render_request)
    else:
        pack = _get_pack()
        if not pack or not pack.is_ready():
            raise HTTPException(status_code=422, detail="Static character pack is not ready")
        render_request = CompositorRequest(
            turns=[
                DialogueTurn(
                    speaker=turn.speaker,
                    emotion=turn.emotion,
                    text=turn.text,
                    duration_seconds=turn.duration_seconds,
                )
                for turn in request.turns
            ],
            output_path=output_path,
            pack=pack,
        )
        background_tasks.add_task(_run_static, request.jobId, render_request)

    return DialogueRenderResponse(jobId=request.jobId, status="RUNNING", renderMode=mode)


@router.get("/v2/dialogue/render/{job_id}", response_model=DialogueStatusResponse)
def dialogue_render_status(job_id: str) -> DialogueStatusResponse:
    status = _read_status(job_id)
    if not status:
        raise HTTPException(status_code=404, detail=f"Dialogue render job not found: {job_id}")
    return DialogueStatusResponse(**status)


@router.get("/v2/dialogue/capabilities")
def dialogue_capabilities() -> dict[str, Any]:
    pack = _get_pack()
    return {
        "configuredMode": RENDER_MODE,
        "selectedMode": _select_mode(raise_if_missing=False),
        "dynamic": {
            "ready": _dynamic.ready(),
            "description": "Kokoro audio + SadTalker lip-sync + listener photo",
        },
        "videoTemplates": {"ready": templates_ready(VIDEO_TEMPLATE_ROOT)},
        "staticSprites": {"ready": bool(pack and pack.is_ready())},
    }


def _select_mode(raise_if_missing: bool = True) -> str | None:
    requested = RENDER_MODE
    if requested in {"dynamic", "sadtalker", "photo"}:
        if _dynamic.ready():
            return "dynamic"
        if raise_if_missing:
            raise HTTPException(status_code=422, detail="RENDER_MODE requests SadTalker but models/photos are not ready")
        return None

    if requested in {"template", "video-template", "video-templates"}:
        if templates_ready(VIDEO_TEMPLATE_ROOT):
            return "video-templates"
        if raise_if_missing:
            raise HTTPException(status_code=422, detail="RENDER_MODE requests video templates but they are not ready")
        return None

    if requested in {"static", "sprites"}:
        pack = _get_pack()
        if pack and pack.is_ready():
            return "static-sprites"
        if raise_if_missing:
            raise HTTPException(status_code=422, detail="RENDER_MODE requests static sprites but pack is not ready")
        return None

    if requested != "auto":
        if raise_if_missing:
            raise HTTPException(status_code=422, detail=f"Unsupported RENDER_MODE: {requested}")
        return None

    if _dynamic.ready():
        return "dynamic"
    if templates_ready(VIDEO_TEMPLATE_ROOT):
        return "video-templates"
    pack = _get_pack()
    if pack and pack.is_ready():
        return "static-sprites"
    if raise_if_missing:
        raise HTTPException(
            status_code=422,
            detail=(
                "No dialogue rendering mode is ready. Start SadTalker and upload both photos, "
                "or upload video templates, or generate a static character pack."
            ),
        )
    return None


def _run_dynamic(job_id: str, request: HybridPhotoCompositorRequest) -> None:
    _run_job(job_id, "dynamic", request.output_path, lambda: _dynamic.render(request))


def _run_video(job_id: str, request: VideoCompositorRequest) -> None:
    _run_job(job_id, "video-templates", request.output_path, lambda: _video.render(request))


def _run_static(job_id: str, request: CompositorRequest) -> None:
    _run_job(job_id, "static-sprites", request.output_path, lambda: _static.render(request))


def _run_job(job_id: str, mode: str, output_path: Path, action) -> None:
    try:
        output_path.parent.mkdir(parents=True, exist_ok=True)
        action()
        _write_status(job_id, {
            "jobId": job_id,
            "status": "DONE",
            "renderMode": mode,
            "outputPath": str(output_path),
            "error": None,
        })
        logger.info("Dialogue render complete. job=%s mode=%s", job_id, mode)
    except Exception as exc:
        logger.exception("Dialogue render failed. job=%s mode=%s", job_id, mode)
        _write_status(job_id, {
            "jobId": job_id,
            "status": "FAILED",
            "renderMode": mode,
            "outputPath": None,
            "error": str(exc)[:3000],
        })


def _status_path(job_id: str) -> Path:
    safe = "".join(ch for ch in job_id if ch.isalnum() or ch in {"-", "_"})
    if not safe or safe != job_id:
        raise HTTPException(status_code=422, detail="Invalid jobId")
    STATUS_ROOT.mkdir(parents=True, exist_ok=True)
    return STATUS_ROOT / f"{safe}.json"


def _write_status(job_id: str, payload: dict[str, Any]) -> None:
    path = _status_path(job_id)
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(payload), encoding="utf-8")
    os.replace(tmp, path)


def _read_status(job_id: str) -> dict[str, Any] | None:
    path = _status_path(job_id)
    if not path.exists():
        return None
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except Exception as exc:
        logger.warning("Could not read dialogue status %s: %s", job_id, exc)
        return None
