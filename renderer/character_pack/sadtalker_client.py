"""HTTP client for the local SadTalker worker service."""
from __future__ import annotations

import logging
import os
from pathlib import Path

import httpx

logger = logging.getLogger("sadtalker-client")

SADTALKER_URL = os.getenv("SADTALKER_URL", "http://sadtalker:8091")
_ANIMATE_TIMEOUT = httpx.Timeout(300.0, connect=10.0)
_STATUS_TIMEOUT = httpx.Timeout(5.0)


class SadTalkerUnavailable(RuntimeError):
    pass


class SadTalkerClient:
    """Thin HTTP client for the local SadTalker microservice."""

    def __init__(self, base_url: str = SADTALKER_URL) -> None:
        self._base_url = base_url.rstrip("/")

    def animate(self, character: str, audio_path: Path, output_path: Path) -> Path:
        """Animate the stored character photo using the supplied dialogue audio."""
        logger.info("SadTalker: animate %s (%s)", character, audio_path.name)
        with open(audio_path, "rb") as handle:
            try:
                response = httpx.post(
                    f"{self._base_url}/v1/animate",
                    params={"character": character},
                    files={"audio": ("audio.wav", handle, "audio/wav")},
                    timeout=_ANIMATE_TIMEOUT,
                )
            except httpx.ConnectError as exc:
                raise SadTalkerUnavailable(
                    f"Cannot reach SadTalker service at {self._base_url}: {exc}"
                ) from exc

        if response.status_code == 422:
            raise SadTalkerUnavailable(
                f"SadTalker rejected request for {character}: {response.text}"
            )
        response.raise_for_status()
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_bytes(response.content)
        logger.info("SadTalker: wrote %d bytes to %s", len(response.content), output_path)
        return output_path

    def download_photo(self, character: str, output_path: Path) -> Path:
        """Download the stored reference photo for listener/idle composition."""
        try:
            response = httpx.get(
                f"{self._base_url}/v1/photos/{character}",
                timeout=httpx.Timeout(30.0, connect=10.0),
            )
        except httpx.ConnectError as exc:
            raise SadTalkerUnavailable(
                f"Cannot reach SadTalker service at {self._base_url}: {exc}"
            ) from exc
        if response.status_code == 404:
            raise SadTalkerUnavailable(f"No stored photo for {character}")
        response.raise_for_status()
        output_path.parent.mkdir(parents=True, exist_ok=True)
        output_path.write_bytes(response.content)
        return output_path

    def is_available(self) -> bool:
        try:
            response = httpx.get(f"{self._base_url}/health", timeout=_STATUS_TIMEOUT)
            return response.status_code == 200 and response.json().get("modelsReady", False)
        except Exception:
            return False

    def photos_status(self) -> dict[str, bool]:
        try:
            response = httpx.get(f"{self._base_url}/v1/photos/status", timeout=_STATUS_TIMEOUT)
            data = response.json()
            return {
                "bhaiya": bool(data.get("bhaiya")),
                "chitti": bool(data.get("chitti")),
            }
        except Exception:
            return {"bhaiya": False, "chitti": False}

    def photos_ready(self) -> bool:
        status = self.photos_status()
        return status.get("bhaiya", False) and status.get("chitti", False)

    def upload_photo(self, character: str, photo_path: Path) -> dict:
        ext = photo_path.suffix.lower()
        content_type = "image/jpeg" if ext in (".jpg", ".jpeg") else "image/png"
        with open(photo_path, "rb") as handle:
            response = httpx.post(
                f"{self._base_url}/v1/photos/{character}",
                files={"photo": (photo_path.name, handle, content_type)},
                timeout=30.0,
            )
        response.raise_for_status()
        return response.json()
