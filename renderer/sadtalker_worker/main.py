"""
SadTalker worker service — portrait animation with zero API calls per video.

Setup (one-time per deployment):
  - Models download on first start into the mounted checkpoints volume (~4 GB).
  - Character photos uploaded once via POST /v1/photos/{character}.

Per-dialogue-turn usage (no internet, no API key):
  POST /v1/animate?character=bhaiya  (multipart: audio file)
  → returns animated talking-head MP4
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
ENHANCER = os.getenv("SADTALKER_ENHANCER", "")

app = FastAPI(title="SadTalker Worker", version="1.1.0")


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


@app.post("/v1/photos/{character}")
async def upload_photo(character: str, photo: UploadFile) -> dict:
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
    if len(content) > 20 * 1024 * 1024:
        raise HTTPException(status_code=413, detail="Photo exceeds 20 MB")

    for existing in PHOTOS_DIR.glob(f"{character}.*"):
        if existing != dest and existing.suffix.lower() in {".jpg", ".jpeg", ".png", ".webp"}:
            existing.unlink(missing_ok=True)
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


@app.get("/v1/photos/{character}")
def get_photo(character: str) -> FileResponse:
    """Return the stored reference photo for idle/listener composition."""
    _validate_character(character)
    photo = _find_photo(character)
    if photo is None:
        raise HTTPException(status_code=404, detail=f"No photo found for '{character}'")
    media_type = "image/jpeg" if photo.suffix.lower() in {".jpg", ".jpeg"} else "image/png"
    return FileResponse(photo, media_type=media_type, filename=photo.name)


@app.post("/v1/animate")
async def animate(character: str, audio: UploadFile) -> FileResponse:
    _validate_character(character)

    photo = _find_photo(character)
    if photo is None:
        raise HTTPException(
            status_code=422,
            detail=f"No photo found for '{character}'. Upload it first via POST /v1/photos/{character}.",
        )

    audio_bytes = await audio.read()
    if not audio_bytes:
        raise HTTPException(status_code=422, detail="audio file is empty")
    if len(audio_bytes) > 25 * 1024 * 1024:
        raise HTTPException(status_code=413, detail="Audio exceeds 25 MB")

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
            "--still",
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
            raise HTTPException(status_code=500, detail=f"SadTalker failed: {proc.stderr[-500:]}")

        mp4s = sorted(out_dir.rglob("*.mp4"))
        if not mp4s:
            raise HTTPException(status_code=500, detail="SadTalker produced no output MP4")

        shutil.copy(mp4s[-1], result_path)
        logger.info("SadTalker done: %s", result_path.name)
        return FileResponse(result_path, media_type="video/mp4", filename=f"{character}_animated.mp4")


def _validate_character(name: str) -> None:
    if name not in ("bhaiya", "chitti"):
        raise HTTPException(status_code=422, detail="character must be 'bhaiya' or 'chitti'")


def _find_photo(character: str) -> Path | None:
    for ext in (".jpg", ".jpeg", ".png", ".webp"):
        path = PHOTOS_DIR / f"{character}{ext}"
        if path.exists():
            return path
    return None
