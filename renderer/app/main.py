from __future__ import annotations

import logging
import os
import shutil
from pathlib import Path
from typing import Any
from uuid import UUID

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

from .render_engine import RenderEngine, RenderFailure
from .character_routes import router as character_router

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

app = FastAPI(title="WhatsApp CRM Media Renderer", version="1.0.0")
app.include_router(character_router)


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
    durationSeconds: int = Field(default=30, ge=5, le=90)
    outputPath: str


class RenderResponse(BaseModel):
    status: str
    outputPath: str
    durationSeconds: float
    warnings: list[str] = Field(default_factory=list)


@app.get("/health")
def health() -> dict[str, Any]:
    from .character_routes import _get_pack, VIDEO_TEMPLATE_ROOT
    from character_pack.video_compositor import template_status, templates_ready
    pack = _get_pack()
    vt_status = template_status(VIDEO_TEMPLATE_ROOT)
    return {
        "status": "UP",
        "ffmpeg": shutil.which("ffmpeg") is not None,
        "renderRoot": str(RENDER_ROOT),
        "characterPackRoot": str(CHARACTER_PACK_ROOT),
        "videoTemplatesReady": templates_ready(VIDEO_TEMPLATE_ROOT),
        "videoTemplates": vt_status,
        "staticSpritePackReady": pack.is_ready() if pack else False,
        "templates": sorted(engine.template_codes()),
    }


@app.post("/v1/render", response_model=RenderResponse)
def render(request: RenderRequest) -> RenderResponse:
    try:
        result = engine.render(request.model_dump())
        return RenderResponse(
            status="COMPLETED",
            outputPath=str(result.output_path),
            durationSeconds=result.duration_seconds,
            warnings=result.warnings,
        )
    except RenderFailure as exc:
        logger.exception("Render failed. job=%s", request.jobId)
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    except Exception as exc:
        logger.exception("Unexpected render failure. job=%s", request.jobId)
        raise HTTPException(status_code=500, detail="Renderer failed") from exc
