"""
SadTalker worker service — portrait animation with zero API calls per video.

Setup (one-time per deployment):
  - Models download on first start into the mounted checkpoints volume (~4 GB).
  - Character photos uploaded once via POST /v1/photos/{character}.

Per-dialogue-turn usage (no internet, no API key):
  POST /v1/animate?character=bhaiya  (multipart: audio file)
  → returns animated talking-head MP4

The face in every output is the real photo that was stored.
"""
from __future__ import annotations

import logging
import os
import shutil
import subprocess
import tempfile
from pathlib import Path

from fastapi import FastAPI, HTTPException, UploadFile
from fastapi.responses import FileResponse

logging.basicConfig(level=os.getenv("LOG_LEVEL", "INFO"))
logger = logging.getLogger("sadtalker-worker")

SADTALKER_DIR = Path("/opt/SadTalker")
PHOTOS_DIR = Path(os.getenv("PHOTOS_DIR", "/data/photos"))
FACE_SIZE = int(os.getenv("SADTALKER_FACE_SIZE", "256"))
EXPRESSION_SCALE = float(os.getenv("SADTALKER_EXPRESSION_SCALE", "1.0"))
ENHANCER = os.getenv("SADTALKER_ENHANCER", "")  # set to "gfpgan" for sharper output

app = FastAPI(title="SadTalker Worker", version="1.0.0")


# ------------------------------------------------------------------
# Health
# ------------------------------------------------------------------

@app.get("/health")
def health() -> dict:
    ckpt_ok = (SADTALKER_DIR / "checkpoints" / "SadTalker_V0.0.2_256.safetensors").exists()
    return {
        "status": "UP",
        "modelsReady": ckpt_ok,
        "photosPresent": {
            "bhaiya": _find_photo("bhaiya") is not None,
            "chitti": _find_photo("chitti") is not None,
        },
    }


# ------------------------------------------------------------------
# Photo management (one-time upload per character)
# ------------------------------------------------------------------

@app.post("/v1/photos/{character}")
async def upload_photo(character: str, photo: UploadFile) -> dict:
    """
    Store the real character photo. Called once per character; reused for
    every video generation without any API calls.
    """
    _validate_character(character)
    allowed = {"image/jpeg", "image/png", "image/webp"}
    if photo.content_type not in allowed:
        raise HTTPException(status_code=422, detail="Only JPEG / PNG / WEBP images accepted")

    ext = ".jpg" if "jpeg" in (photo.content_type or "") else ".png"
    PHOTOS_DIR.mkdir(parents=True, exist_ok=True)
    dest = PHOTOS_DIR / f"{character}{ext}"

    content = await photo.read()
    if not content:
        raise HTTPException(status_code=422, detail="Uploaded file is empty")
    dest.write_bytes(content)
    logger.info("Stored photo for %s at %s (%d bytes)", character, dest, len(content))

    return {"character": character, "path": str(dest), "bytes": len(content)}


@app.get("/v1/photos/status")
def photos_status() -> dict:
    return {
        "bhaiya": _find_photo("bhaiya") is not None,
        "chitti": _find_photo("chitti") is not None,
        "photosDir": str(PHOTOS_DIR),
    }


# ------------------------------------------------------------------
# Animation — the core: photo + audio → talking-head MP4
# ------------------------------------------------------------------

@app.post("/v1/animate")
async def animate(character: str, audio: UploadFile) -> FileResponse:
    """
    Animate the stored photo for `character` with the uploaded audio clip.

    Returns the talking-head MP4 directly.  The face is always the real photo
    stored on disk — no Gemini, no external API, zero network I/O.
    """
    _validate_character(character)

    photo = _find_photo(character)
    if photo is None:
        raise HTTPException(
            status_code=422,
            detail=(
                f"No photo found for '{character}'. "
                "Upload it first via POST /v1/photos/{character}."
            ),
        )

    audio_bytes = await audio.read()
    if not audio_bytes:
        raise HTTPException(status_code=422, detail="audio file is empty")

    with tempfile.TemporaryDirectory(prefix="sadtalker-") as tmp:
        tmp_path = Path(tmp)
        audio_path = tmp_path / "audio.wav"
        out_dir = tmp_path / "out"
        out_dir.mkdir()
        result_path = tmp_path / f"{character}_animated.mp4"

        audio_path.write_bytes(audio_bytes)

        cmd = [
            "python", "inference.py",
            "--source_image", str(photo),
            "--driven_audio", str(audio_path),
            "--result_dir", str(out_dir),
            "--size", str(FACE_SIZE),
            "--expression_scale", str(EXPRESSION_SCALE),
            "--still",  # minimal head movement — character stays in position
        ]
        if ENHANCER:
            cmd += ["--enhancer", ENHANCER]

        logger.info("SadTalker: animating %s (size=%d)", character, FACE_SIZE)
        proc = subprocess.run(
            cmd,
            cwd=str(SADTALKER_DIR),
            capture_output=True,
            text=True,
            timeout=300,
        )
        if proc.returncode != 0:
            logger.error("SadTalker stderr: %s", proc.stderr[-3000:])
            raise HTTPException(
                status_code=500,
                detail=f"SadTalker failed: {proc.stderr[-500:]}",
            )

        mp4s = sorted(out_dir.rglob("*.mp4"))
        if not mp4s:
            raise HTTPException(status_code=500, detail="SadTalker produced no output MP4")

        shutil.copy(mp4s[-1], result_path)
        logger.info("SadTalker done: %s", result_path.name)

        return FileResponse(
            result_path,
            media_type="video/mp4",
            filename=f"{character}_animated.mp4",
        )


# ------------------------------------------------------------------
# Helpers
# ------------------------------------------------------------------

def _validate_character(name: str) -> None:
    if name not in ("bhaiya", "chitti"):
        raise HTTPException(status_code=422, detail="character must be 'bhaiya' or 'chitti'")


def _find_photo(character: str) -> Path | None:
    for ext in (".jpg", ".jpeg", ".png"):
        p = PHOTOS_DIR / f"{character}{ext}"
        if p.exists():
            return p
    return None
