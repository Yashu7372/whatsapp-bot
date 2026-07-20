from __future__ import annotations

import base64
import ipaddress
import os
import shutil
import socket
import subprocess
import tempfile
import textwrap
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

import requests
from fastapi import FastAPI, HTTPException, Response
from PIL import Image, ImageDraw, ImageEnhance, ImageFilter, ImageFont, ImageOps
from pydantic import BaseModel, Field

WIDTH = 1080
HEIGHT = 1920
FPS = 30
MAX_REMOTE_BYTES = 30 * 1024 * 1024
FONT_BOLD_PATH = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
FONT_REGULAR_PATH = "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
KOKORO_BASE_URL = os.getenv("KOKORO_BASE_URL", "http://kokoro:8880").rstrip("/")
RENDER_TIMEOUT_SECONDS = int(os.getenv("RENDER_TIMEOUT_SECONDS", "240"))

app = FastAPI(title="Local Reel Renderer", version="1.0.0")


class RenderAsset(BaseModel):
    name: str = "asset"
    contentType: str = "application/octet-stream"
    dataBase64: str | None = None
    url: str | None = None


class RenderRequest(BaseModel):
    jobId: str
    templateCode: str = "DYNAMIC_BOLD"
    title: str = ""
    hook: str = ""
    script: str = ""
    caption: str = ""
    durationSeconds: int = Field(default=30, ge=5, le=90)
    shots: list[dict[str, Any]] = Field(default_factory=list)
    includeVoice: bool = False
    voice: str = "af_heart"
    assets: list[RenderAsset] = Field(default_factory=list)


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "UP", "ffmpeg": shutil.which("ffmpeg") or "missing"}


@app.post("/render")
def render(request: RenderRequest) -> Response:
    if not shutil.which("ffmpeg"):
        raise HTTPException(status_code=503, detail="ffmpeg is not installed")

    try:
        with tempfile.TemporaryDirectory(prefix=f"reel-{request.jobId}-") as temp_name:
            workdir = Path(temp_name)
            assets = prepare_assets(request.assets, workdir)
            scenes = normalize_scenes(request)

            voice_path: Path | None = None
            voice_duration = 0.0
            if request.includeVoice and request.script.strip():
                voice_path = generate_voice(request.script, request.voice, workdir)
                if voice_path:
                    voice_duration = probe_duration(voice_path)

            target_duration = max(float(request.durationSeconds), voice_duration + 0.4)
            scale_scene_durations(scenes, target_duration)

            segment_paths: list[Path] = []
            for index, scene in enumerate(scenes):
                image_path = workdir / f"scene-{index:02d}.png"
                segment_path = workdir / f"segment-{index:02d}.mp4"
                create_scene_image(
                    image_path,
                    request.templateCode,
                    request.title,
                    request.hook,
                    request.caption,
                    scene,
                    index,
                    len(scenes),
                    assets[index % len(assets)] if assets else None,
                )
                render_segment(image_path, segment_path, float(scene["duration"]))
                segment_paths.append(segment_path)

            silent_video = workdir / "silent.mp4"
            concatenate_segments(segment_paths, silent_video, workdir)

            output = workdir / "reel.mp4"
            if voice_path:
                mux_audio(silent_video, voice_path, output)
            else:
                shutil.copyfile(silent_video, output)

            payload = output.read_bytes()
            return Response(
                content=payload,
                media_type="video/mp4",
                headers={
                    "Content-Disposition": f'attachment; filename="reel-{request.jobId}.mp4"',
                    "X-Rendered-By": "local-ffmpeg-template-renderer",
                },
            )
    except HTTPException:
        raise
    except Exception as exc:  # noqa: BLE001 - converted to a safe API error
        raise HTTPException(status_code=500, detail=f"Render failed: {exc}") from exc


def normalize_scenes(request: RenderRequest) -> list[dict[str, Any]]:
    scenes: list[dict[str, Any]] = []
    for shot in request.shots[:8]:
        text = str(shot.get("audio") or shot.get("text") or shot.get("visual") or "").strip()
        visual = str(shot.get("visual") or "").strip()
        try:
            duration = float(shot.get("duration", 4))
        except (TypeError, ValueError):
            duration = 4.0
        scenes.append({"text": text or visual, "visual": visual, "duration": clamp(duration, 2.0, 9.0)})

    if not scenes:
        if request.hook.strip():
            scenes.append({"text": request.hook.strip(), "visual": "Hook", "duration": 3.5})
        chunks = chunk_words(request.script, 24)
        for chunk in chunks[:5]:
            scenes.append({"text": chunk, "visual": request.title, "duration": 5.0})
        if request.caption.strip():
            scenes.append({"text": request.caption.strip(), "visual": "Call to action", "duration": 3.5})

    if not scenes:
        scenes = [{"text": request.title or "Your reel is ready", "visual": "", "duration": 6.0}]
    return scenes


def scale_scene_durations(scenes: list[dict[str, Any]], target_duration: float) -> None:
    current = sum(float(scene["duration"]) for scene in scenes)
    if current <= 0:
        current = float(len(scenes))
    factor = target_duration / current
    for scene in scenes:
        scene["duration"] = clamp(float(scene["duration"]) * factor, 1.8, 14.0)


def prepare_assets(items: list[RenderAsset], workdir: Path) -> list[Path]:
    prepared: list[Path] = []
    for index, item in enumerate(items[:8]):
        suffix = extension_for(item.name, item.contentType)
        source = workdir / f"asset-{index:02d}{suffix}"
        try:
            if item.dataBase64:
                raw = base64.b64decode(item.dataBase64, validate=True)
                if len(raw) > MAX_REMOTE_BYTES:
                    continue
                source.write_bytes(raw)
            elif item.url:
                download_public_asset(item.url, source)
            else:
                continue

            if item.contentType.startswith("image/") or suffix.lower() in {".png", ".jpg", ".jpeg", ".webp"}:
                prepared.append(source)
            elif item.contentType.startswith("video/") or suffix.lower() in {".mp4", ".mov", ".webm", ".mkv"}:
                preview = workdir / f"asset-{index:02d}-frame.jpg"
                extract_video_frame(source, preview)
                if preview.exists():
                    prepared.append(preview)
        except Exception:
            # One broken optional asset must not fail the whole render.
            continue
    return prepared


def download_public_asset(url: str, target: Path) -> None:
    parsed = urlparse(url)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname:
        raise ValueError("Only public http/https asset URLs are supported")
    ensure_public_host(parsed.hostname)

    with requests.get(url, stream=True, timeout=(8, 30), allow_redirects=True) as response:
        response.raise_for_status()
        final_host = urlparse(response.url).hostname
        if final_host:
            ensure_public_host(final_host)
        size = 0
        with target.open("wb") as output:
            for chunk in response.iter_content(64 * 1024):
                if not chunk:
                    continue
                size += len(chunk)
                if size > MAX_REMOTE_BYTES:
                    raise ValueError("Remote asset exceeds size limit")
                output.write(chunk)


def ensure_public_host(hostname: str) -> None:
    addresses = socket.getaddrinfo(hostname, None)
    for address in addresses:
        ip = ipaddress.ip_address(address[4][0])
        if ip.is_private or ip.is_loopback or ip.is_link_local or ip.is_reserved or ip.is_multicast:
            raise ValueError("Private-network asset URLs are not allowed")


def extract_video_frame(source: Path, target: Path) -> None:
    run_command([
        "ffmpeg", "-y", "-ss", "0.5", "-i", str(source),
        "-vf", f"scale={WIDTH}:{HEIGHT}:force_original_aspect_ratio=increase,crop={WIDTH}:{HEIGHT}",
        "-frames:v", "1", str(target),
    ])


def create_scene_image(
    target: Path,
    template_code: str,
    title: str,
    hook: str,
    caption: str,
    scene: dict[str, Any],
    index: int,
    total: int,
    asset: Path | None,
) -> None:
    template = template_code.upper()
    image = background_image(template, asset)
    draw = ImageDraw.Draw(image, "RGBA")

    if template == "MINIMAL_PRODUCT":
        draw.rounded_rectangle((70, 90, WIDTH - 70, HEIGHT - 90), radius=40, fill=(255, 255, 255, 228))
        title_color = (24, 27, 39, 255)
        text_color = (42, 47, 62, 255)
        accent = (93, 70, 255, 255)
    elif template == "NEWS_CARD":
        draw.rectangle((0, 0, WIDTH, 170), fill=(210, 29, 49, 245))
        draw.rectangle((55, HEIGHT - 320, WIDTH - 55, HEIGHT - 80), fill=(5, 10, 22, 225))
        title_color = (255, 255, 255, 255)
        text_color = (255, 255, 255, 255)
        accent = (255, 215, 0, 255)
    else:
        draw.rounded_rectangle((55, 90, WIDTH - 55, HEIGHT - 90), radius=48, fill=(8, 11, 27, 178))
        title_color = (255, 255, 255, 255)
        text_color = (255, 255, 255, 255)
        accent = (255, 102, 84, 255)

    title_font = font(50, bold=True)
    hook_font = font(77, bold=True)
    body_font = font(59, bold=True)
    small_font = font(34, bold=False)

    progress = int((index + 1) / total * (WIDTH - 110))
    draw.rounded_rectangle((55, 42, WIDTH - 55, 58), radius=8, fill=(255, 255, 255, 70))
    draw.rounded_rectangle((55, 42, 55 + progress, 58), radius=8, fill=accent)

    draw_text_block(draw, clean_text(title)[:90], (85, 115, WIDTH - 85, 300), title_font, title_color, align="left")

    scene_text = clean_text(str(scene.get("text") or scene.get("visual") or ""))
    if index == 0 and hook.strip():
        scene_text = clean_text(hook)
    draw_text_block(draw, scene_text, (95, 420, WIDTH - 95, 1430), hook_font if index == 0 else body_font, text_color, align="center")

    visual = clean_text(str(scene.get("visual") or ""))
    if visual and visual.lower() not in scene_text.lower():
        draw_text_block(draw, visual, (120, 1450, WIDTH - 120, 1590), small_font, text_color, align="center")

    footer = clean_text(caption) if index == total - 1 else f"Scene {index + 1} / {total}"
    draw_text_block(draw, footer, (90, HEIGHT - 250, WIDTH - 90, HEIGHT - 105), small_font, accent, align="center")
    image.convert("RGB").save(target, quality=95)


def background_image(template: str, asset: Path | None) -> Image.Image:
    if asset:
        try:
            source = Image.open(asset).convert("RGB")
            source = ImageOps.fit(source, (WIDTH, HEIGHT), method=Image.Resampling.LANCZOS)
            source = ImageEnhance.Contrast(source).enhance(0.9)
            source = source.filter(ImageFilter.GaussianBlur(radius=1.2))
            overlay = Image.new("RGBA", source.size, (0, 0, 0, 88 if template != "MINIMAL_PRODUCT" else 30))
            return Image.alpha_composite(source.convert("RGBA"), overlay)
        except Exception:
            pass

    if template == "MINIMAL_PRODUCT":
        top, bottom = (238, 241, 255), (221, 251, 244)
    elif template == "NEWS_CARD":
        top, bottom = (5, 10, 22), (32, 39, 58)
    else:
        top, bottom = (45, 22, 93), (8, 12, 35)

    image = Image.new("RGBA", (WIDTH, HEIGHT), top + (255,))
    draw = ImageDraw.Draw(image)
    for y in range(HEIGHT):
        ratio = y / max(HEIGHT - 1, 1)
        color = tuple(int(top[i] * (1 - ratio) + bottom[i] * ratio) for i in range(3)) + (255,)
        draw.line((0, y, WIDTH, y), fill=color)
    draw.ellipse((-180, 180, 480, 840), fill=(255, 95, 109, 35))
    draw.ellipse((680, 1180, 1320, 1820), fill=(70, 180, 255, 32))
    return image


def draw_text_block(
    draw: ImageDraw.ImageDraw,
    text: str,
    box: tuple[int, int, int, int],
    selected_font: ImageFont.FreeTypeFont,
    fill: tuple[int, int, int, int],
    align: str,
) -> None:
    if not text:
        return
    left, top, right, bottom = box
    max_width = right - left
    lines = wrap_for_width(draw, text, selected_font, max_width)
    line_gap = max(10, int(selected_font.size * 0.22))
    heights = [draw.textbbox((0, 0), line, font=selected_font)[3] for line in lines]
    total_height = sum(heights) + line_gap * max(len(lines) - 1, 0)
    y = top + max((bottom - top - total_height) // 2, 0)

    for line, line_height in zip(lines, heights):
        width = draw.textbbox((0, 0), line, font=selected_font)[2]
        if align == "center":
            x = left + max((max_width - width) // 2, 0)
        else:
            x = left
        draw.text((x, y), line, font=selected_font, fill=fill, stroke_width=1, stroke_fill=(0, 0, 0, 45))
        y += line_height + line_gap
        if y > bottom:
            break


def wrap_for_width(draw: ImageDraw.ImageDraw, text: str, selected_font: ImageFont.FreeTypeFont, max_width: int) -> list[str]:
    paragraphs = text.splitlines() or [text]
    output: list[str] = []
    for paragraph in paragraphs:
        words = paragraph.split()
        current = ""
        for word in words:
            candidate = f"{current} {word}".strip()
            if draw.textbbox((0, 0), candidate, font=selected_font)[2] <= max_width:
                current = candidate
            else:
                if current:
                    output.append(current)
                current = word
        if current:
            output.append(current)
    return output[:12]


def render_segment(image: Path, output: Path, duration: float) -> None:
    run_command([
        "ffmpeg", "-y", "-loop", "1", "-i", str(image),
        "-t", f"{duration:.3f}", "-r", str(FPS),
        "-vf", f"scale={WIDTH}:{HEIGHT},format=yuv420p",
        "-c:v", "libx264", "-preset", "veryfast", "-crf", "22",
        "-movflags", "+faststart", str(output),
    ])


def concatenate_segments(segments: list[Path], output: Path, workdir: Path) -> None:
    concat_file = workdir / "concat.txt"
    concat_file.write_text("".join(f"file '{path.as_posix()}'\n" for path in segments), encoding="utf-8")
    run_command([
        "ffmpeg", "-y", "-f", "concat", "-safe", "0", "-i", str(concat_file),
        "-c", "copy", "-movflags", "+faststart", str(output),
    ])


def generate_voice(script: str, voice: str, workdir: Path) -> Path | None:
    try:
        response = requests.post(
            f"{KOKORO_BASE_URL}/v1/audio/speech",
            json={
                "model": "kokoro",
                "input": script[:5000],
                "voice": voice or "af_heart",
                "response_format": "mp3",
                "speed": 1.0,
            },
            timeout=(5, min(RENDER_TIMEOUT_SECONDS, 180)),
        )
        response.raise_for_status()
        if not response.content:
            return None
        target = workdir / "voice.mp3"
        target.write_bytes(response.content)
        return target
    except Exception:
        # Voice is optional. A missing voice service produces a silent reel.
        return None


def mux_audio(video: Path, audio: Path, output: Path) -> None:
    run_command([
        "ffmpeg", "-y", "-i", str(video), "-i", str(audio),
        "-map", "0:v:0", "-map", "1:a:0", "-c:v", "copy", "-c:a", "aac",
        "-b:a", "192k", "-shortest", "-movflags", "+faststart", str(output),
    ])


def probe_duration(path: Path) -> float:
    result = subprocess.run(
        [
            "ffprobe", "-v", "error", "-show_entries", "format=duration",
            "-of", "default=noprint_wrappers=1:nokey=1", str(path),
        ],
        capture_output=True,
        text=True,
        timeout=30,
        check=True,
    )
    try:
        return float(result.stdout.strip())
    except ValueError:
        return 0.0


def run_command(command: list[str]) -> None:
    result = subprocess.run(
        command,
        capture_output=True,
        text=True,
        timeout=RENDER_TIMEOUT_SECONDS,
        check=False,
    )
    if result.returncode != 0:
        error = (result.stderr or result.stdout or "unknown ffmpeg error")[-2000:]
        raise RuntimeError(error)


def font(size: int, bold: bool) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(FONT_BOLD_PATH if bold else FONT_REGULAR_PATH, size=size)


def chunk_words(value: str, words_per_chunk: int) -> list[str]:
    words = clean_text(value).split()
    return [" ".join(words[index:index + words_per_chunk]) for index in range(0, len(words), words_per_chunk)]


def clean_text(value: str) -> str:
    return " ".join(value.replace("\u0000", "").split())


def extension_for(name: str, content_type: str) -> str:
    suffix = Path(name).suffix
    if suffix:
        return suffix[:10]
    mapping = {
        "image/jpeg": ".jpg",
        "image/png": ".png",
        "image/webp": ".webp",
        "video/mp4": ".mp4",
        "video/webm": ".webm",
        "video/quicktime": ".mov",
    }
    return mapping.get(content_type.lower(), ".bin")


def clamp(value: float, minimum: float, maximum: float) -> float:
    return max(minimum, min(value, maximum))
