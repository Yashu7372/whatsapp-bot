"""
Video-template dialogue compositor.

Character animation clips are downloaded once (e.g. from the SadTalker website
using real photos) and stored locally under:
  <PACK_ROOT>/video_templates/{character}/{emotion}.mp4

Every render just loops the stored clips onto a background and burns in
subtitles — zero API calls, zero re-generation.

Per-turn FFmpeg pipeline:
  1. Loop the stored character clip to the required duration (-stream_loop)
  2. Overlay on a background image (or solid colour if none)
  3. drawbox (subtitle background) + drawtext (text + speaker label)
  4. Output 1080×1920 @ 30fps H.264 segment
"""
from __future__ import annotations

import logging
import os
import shutil
import subprocess
import tempfile
import threading
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

logger = logging.getLogger("video-compositor")

CANVAS_W, CANVAS_H = 1080, 1920
FONT_PATH = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"

# Emotions tried in order when the exact emotion clip isn't available
_FALLBACK_ORDER = ["talking", "idle"]

# Where each character is placed on the 1080×1920 canvas.
# scale_w/h: resize the clip to this size before overlay.
# overlay_x/y: top-left corner on the canvas.
_PLACEMENT: dict[str, dict[str, Any]] = {
    "bhaiya": {
        "scale_w": 700,
        "scale_h": 900,
        "overlay_x": 30,
        "overlay_y": 180,
        "label": "Bhaiya",
        "label_color": "FFA040",
    },
    "chitti": {
        "scale_w": 500,
        "scale_h": 680,
        "overlay_x": 560,
        "overlay_y": 460,
        "label": "Chitti",
        "label_color": "FF80C0",
    },
}

_SUBTITLE_Y = 1520
_SUBTITLE_BOX_H = 240
_LABEL_Y = 1790


@dataclass
class VideoTemplateTurn:
    speaker: str         # "bhaiya" or "chitti"
    emotion: str         # "talking", "idle", "curious", etc.
    text: str            # subtitle text
    duration_seconds: float = 4.0
    audio_path: Path | None = None


@dataclass
class VideoCompositorRequest:
    turns: list[VideoTemplateTurn]
    output_path: Path
    template_root: Path       # e.g. /data/character_pack/video_templates
    background_path: Path | None = None
    # RGBA PNG per character overlaid on top of the character clip (dress/costume layer).
    # Keys: "bhaiya" | "chitti".  Missing keys mean no dress for that character.
    dress_overlays: dict[str, Path] = field(default_factory=dict)
    fps: int = 30


class VideoTemplateCompositor:
    """
    Composites pre-generated video clips onto a background with subtitles.
    All heavy lifting is FFmpeg — no model inference, no API calls per render.
    """

    def __init__(self, tts_pipeline_factory=None) -> None:
        self._tts_lock = threading.Lock()
        self._tts_pipeline: Any | None = None

    def render(self, req: VideoCompositorRequest) -> Path:
        with tempfile.TemporaryDirectory(prefix="vt-dial-") as tmp:
            workdir = Path(tmp)
            segments: list[Path] = []
            audio_map: list[tuple[Path, float]] = []
            cursor = 0.0

            for idx, turn in enumerate(req.turns):
                audio = self._ensure_audio(turn, workdir, idx)
                clip = _find_clip(req.template_root, turn.speaker, turn.emotion)
                dress = req.dress_overlays.get(turn.speaker)

                seg = workdir / f"seg-{idx:03d}.mp4"
                self._composite_turn(clip, req.background_path, dress, turn, seg)
                segments.append(seg)

                if audio and audio.exists():
                    audio_map.append((audio, cursor))
                cursor += turn.duration_seconds

            joined = workdir / "joined.mp4"
            self._concat(segments, joined, workdir)

            if audio_map:
                mixed = workdir / "mixed.wav"
                total = sum(t.duration_seconds for t in req.turns)
                self._mix_audio(audio_map, mixed, total)
                self._mux(joined, mixed, req.output_path)
            else:
                shutil.copy(joined, req.output_path)

        return req.output_path

    # ------------------------------------------------------------------
    # FFmpeg scene assembly
    # ------------------------------------------------------------------

    def _composite_turn(
        self,
        clip: Path | None,
        background_path: Path | None,
        dress_path: Path | None,
        turn: VideoTemplateTurn,
        output: Path,
    ) -> None:
        p = _PLACEMENT[turn.speaker]
        sw, sh = p["scale_w"], p["scale_h"]
        ox, oy = p["overlay_x"], p["overlay_y"]
        label = p["label"]
        color = p["label_color"]
        dur = turn.duration_seconds

        has_bg = background_path and background_path.exists()
        has_clip = clip and clip.exists()
        has_dress = dress_path and dress_path.exists()

        # Background input
        if has_bg:
            bg_in = ["-loop", "1", "-i", str(background_path)]
            bg_filter = f"[0:v]scale={CANVAS_W}:{CANVAS_H},setsar=1[bg];"
            next_idx = 1
        else:
            bg_in = []
            bg_filter = (
                f"color=c=0x1a1a2e:size={CANVAS_W}x{CANVAS_H}:rate=30,setsar=1[bg];"
            )
            next_idx = 0

        # Character clip input (looped)
        if has_clip:
            char_in = ["-stream_loop", "-1", "-i", str(clip)]
            char_filter = f"[{next_idx}:v]scale={sw}:{sh},setsar=1[char];"
            overlay = f"[bg][char]overlay={ox}:{oy}[scene0];"
        else:
            char_in = []
            char_filter = ""
            overlay = "[bg]copy[scene0];"

        # Dress overlay — RGBA PNG composited on top of the character clip.
        # Input index is always bg_count + char_count.
        dress_idx = (1 if has_bg else 0) + (1 if has_clip else 0)
        if has_dress:
            dress_in = ["-loop", "1", "-i", str(dress_path)]
            dress_filter = (
                f"[{dress_idx}:v]scale={sw}:{sh},format=rgba[dresslayer];"
                f"[scene0][dresslayer]overlay={ox}:{oy}[scene];"
            )
        else:
            dress_in = []
            dress_filter = "[scene0]copy[scene];"

        # Subtitle box + text lines
        lines = _wrap(turn.text, max_chars=38)[:4]
        ty = _SUBTITLE_Y + 18
        text_parts = [
            f"drawbox=x=40:y={_SUBTITLE_Y}:w={CANVAS_W - 80}:h={_SUBTITLE_BOX_H}:"
            f"color=black@0.75:t=fill"
        ]
        for line in lines:
            text_parts.append(
                f"drawtext=fontfile={FONT_PATH}:text='{_esc(line)}':"
                f"fontsize=48:fontcolor=white:x=(w-tw)/2:y={ty}:"
                f"shadowcolor=black@0.9:shadowx=2:shadowy=2"
            )
            ty += 62

        text_parts.append(
            f"drawtext=fontfile={FONT_PATH}:text='{label}':"
            f"fontsize=40:fontcolor=0x{color}:x=80:y={_LABEL_Y}:"
            f"shadowcolor=black@0.9:shadowx=2:shadowy=2"
        )

        fc = (
            f"{bg_filter}"
            f"{char_filter}"
            f"{overlay}"
            f"{dress_filter}"
            f"[scene]{','.join(text_parts)}[out]"
        )

        _run([
            "ffmpeg", "-y",
            *bg_in,
            *char_in,
            *dress_in,
            "-filter_complex", fc,
            "-map", "[out]",
            "-t", f"{dur:.3f}",
            "-c:v", "libx264", "-preset", "veryfast", "-crf", "22",
            "-pix_fmt", "yuv420p", "-an", "-r", "30",
            str(output),
        ], f"composite {turn.speaker}/{turn.emotion}")

    # ------------------------------------------------------------------
    # TTS
    # ------------------------------------------------------------------

    def _ensure_audio(
        self, turn: VideoTemplateTurn, workdir: Path, idx: int
    ) -> Path | None:
        if turn.audio_path and turn.audio_path.exists():
            return turn.audio_path
        if not turn.text.strip():
            return None
        out = workdir / f"audio-{idx:03d}.wav"
        try:
            self._synth(turn.text, turn.speaker, out)
            return out
        except Exception as exc:
            logger.warning("TTS failed for turn %d: %s", idx, exc)
            return None

    def _synth(self, text: str, speaker: str, out: Path) -> None:
        voice_map = {
            "bhaiya": os.getenv("VOICE_BHAIYA", "am_liam"),
            "chitti": os.getenv("VOICE_CHITTI", "af_heart"),
        }
        voice = voice_map.get(speaker, "af_heart")

        import numpy as np
        import soundfile as sf
        from kokoro import KPipeline

        with self._tts_lock:
            if self._tts_pipeline is None:
                lang = os.getenv("KOKORO_LANG_CODE", "a")
                self._tts_pipeline = KPipeline(lang_code=lang)
            pipeline = self._tts_pipeline

        chunks = [a for _, _, a in pipeline(text[:2000], voice=voice, speed=1.0)]
        if chunks:
            sf.write(out, np.concatenate(chunks), 24000)

    # ------------------------------------------------------------------
    # FFmpeg helpers
    # ------------------------------------------------------------------

    def _mix_audio(
        self,
        audio_map: list[tuple[Path, float]],
        output: Path,
        total_seconds: float,
    ) -> None:
        inputs: list[str] = []
        delays: list[str] = []
        for i, (path, start) in enumerate(audio_map):
            inputs += ["-i", str(path)]
            ms = int(start * 1000)
            delays.append(f"[{i}]adelay={ms}|{ms}[d{i}]")
        mix_in = "".join(f"[d{i}]" for i in range(len(audio_map)))
        fc = (
            ";".join(delays)
            + f";{mix_in}amix=inputs={len(audio_map)}:duration=longest[out]"
        )
        _run([
            "ffmpeg", "-y", *inputs,
            "-filter_complex", fc,
            "-map", "[out]",
            "-t", f"{total_seconds:.3f}",
            "-ar", "48000",
            str(output),
        ], "mix audio")

    def _concat(self, segments: list[Path], output: Path, workdir: Path) -> None:
        lst = workdir / "concat.txt"
        lst.write_text("\n".join(f"file '{s}'" for s in segments))
        _run([
            "ffmpeg", "-y", "-f", "concat", "-safe", "0",
            "-i", str(lst), "-c", "copy", str(output),
        ], "concatenate segments")

    def _mux(self, video: Path, audio: Path, output: Path) -> None:
        part = output.with_suffix(".part.mp4")
        _run([
            "ffmpeg", "-y",
            "-i", str(video), "-i", str(audio),
            "-map", "0:v:0", "-map", "1:a:0",
            "-c:v", "copy", "-c:a", "aac", "-b:a", "192k",
            "-movflags", "+faststart", str(part),
        ], "mux audio")
        import os as _os
        _os.replace(part, output)


# ------------------------------------------------------------------
# Module-level helpers
# ------------------------------------------------------------------

def dress_status(dress_root: Path) -> dict[str, str | None]:
    """Return the stored dress PNG path per character, or None if not uploaded yet."""
    result: dict[str, str | None] = {}
    for speaker in ("bhaiya", "chitti"):
        p = dress_root / f"{speaker}.png"
        result[speaker] = str(p) if p.exists() else None
    return result


def load_dress_overlays(dress_root: Path) -> dict[str, Path]:
    """Return a dict mapping character name → dress PNG Path for use in VideoCompositorRequest."""
    overlays: dict[str, Path] = {}
    for speaker in ("bhaiya", "chitti"):
        p = dress_root / f"{speaker}.png"
        if p.exists():
            overlays[speaker] = p
    return overlays


def template_status(template_root: Path) -> dict[str, dict[str, str]]:
    """Return which video templates are present on disk."""
    result: dict[str, dict[str, str]] = {}
    for speaker in ("bhaiya", "chitti"):
        char_dir = template_root / speaker
        if char_dir.exists():
            result[speaker] = {
                mp4.stem: str(mp4)
                for mp4 in sorted(char_dir.glob("*.mp4"))
            }
        else:
            result[speaker] = {}
    return result


def templates_ready(template_root: Path) -> bool:
    """True when at least one clip is present for each character."""
    st = template_status(template_root)
    return bool(st.get("bhaiya")) and bool(st.get("chitti"))


def _find_clip(template_root: Path, speaker: str, emotion: str) -> Path | None:
    candidates = [emotion] + [e for e in _FALLBACK_ORDER if e != emotion]
    for candidate in candidates:
        p = template_root / speaker / f"{candidate}.mp4"
        if p.exists():
            return p
    # Any .mp4 in the character's folder
    char_dir = template_root / speaker
    if char_dir.exists():
        mp4s = sorted(char_dir.glob("*.mp4"))
        if mp4s:
            return mp4s[0]
    return None


def _run(cmd: list[str], op: str) -> None:
    result = subprocess.run(cmd, capture_output=True, text=True, timeout=600)
    if result.returncode != 0:
        tail = (result.stderr or "")[-2000:]
        raise RuntimeError(f"Could not {op}: {tail}")


def _wrap(text: str, max_chars: int) -> list[str]:
    words = text.split()
    lines: list[str] = []
    current: list[str] = []
    for word in words:
        test = " ".join(current + [word])
        if len(test) > max_chars and current:
            lines.append(" ".join(current))
            current = [word]
        else:
            current.append(word)
    if current:
        lines.append(" ".join(current))
    return lines


def _esc(text: str) -> str:
    return (
        text
        .replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace(":", "\\:")
        .replace("[", "\\[")
        .replace("]", "\\]")
    )
