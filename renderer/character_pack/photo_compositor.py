"""
Photo-based dialogue compositor — real faces + SadTalker + FFmpeg.

For each dialogue turn:
  1. Kokoro TTS → audio.wav                    (local, no API)
  2. SadTalker service: photo + audio → head.mp4  (local, no API, real face)
  3. FFmpeg: background + head overlay + subtitle drawtext → turn segment
  4. Concatenate all segments → mux with mixed audio → final MP4

Canvas: 1080 × 1920 (9:16 portrait)
Characters are positioned on separate sides of the frame.
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

from .sadtalker_client import SadTalkerClient, SadTalkerUnavailable

logger = logging.getLogger("photo-compositor")

CANVAS_W, CANVAS_H = 1080, 1920
FONT_PATH = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"

# How each character's talking-head is placed on the 1080×1920 canvas.
# scale_w/h: resize the SadTalker clip to this size before overlay.
# overlay_x/y: top-left corner on the canvas.
_PLACEMENT: dict[str, dict[str, Any]] = {
    "bhaiya": {
        "scale_w": 700,
        "scale_h": 700,
        "overlay_x": 80,
        "overlay_y": 300,
        "label": "Bhaiya",
        "label_color": "FFA040",   # warm orange
    },
    "chitti": {
        "scale_w": 500,
        "scale_h": 500,
        "overlay_x": 560,
        "overlay_y": 500,
        "label": "Chitti",
        "label_color": "FF80C0",   # warm pink
    },
}

_SUBTITLE_Y = 1520
_SUBTITLE_BOX_H = 240
_LABEL_Y = 1795


@dataclass
class PhotoDialogueTurn:
    speaker: str
    text: str
    duration_seconds: float = 4.0
    audio_path: Path | None = None


@dataclass
class PhotoCompositorRequest:
    turns: list[PhotoDialogueTurn]
    output_path: Path
    background_path: Path | None
    fps: int = 30


class PhotoDialogueCompositor:
    """
    Assembles dialogue video using real photos (animated by SadTalker) and
    FFmpeg. The resulting video contains the actual faces from the stored photos.
    """

    def __init__(
        self,
        sadtalker_client: SadTalkerClient | None = None,
    ) -> None:
        self._st = sadtalker_client or SadTalkerClient()
        self._tts_lock = threading.Lock()
        self._tts_pipeline: Any | None = None

    def render(self, req: PhotoCompositorRequest) -> Path:
        with tempfile.TemporaryDirectory(prefix="photo-dial-") as tmp:
            workdir = Path(tmp)
            segments: list[Path] = []
            audio_map: list[tuple[Path, float]] = []
            cursor = 0.0

            for idx, turn in enumerate(req.turns):
                audio = self._ensure_audio(turn, workdir, idx)
                head_clip = workdir / f"head-{idx:03d}.mp4"

                if audio and audio.exists():
                    try:
                        self._st.animate(
                            character=turn.speaker,
                            audio_path=audio,
                            output_path=head_clip,
                        )
                    except SadTalkerUnavailable as exc:
                        logger.warning(
                            "SadTalker unavailable for turn %d (%s): %s — using static fallback",
                            idx, turn.speaker, exc,
                        )
                        self._make_still_clip(audio, turn.duration_seconds, head_clip)
                    except Exception as exc:
                        logger.warning(
                            "SadTalker failed for turn %d (%s): %s — using static fallback",
                            idx, turn.speaker, exc,
                        )
                        self._make_still_clip(audio, turn.duration_seconds, head_clip)
                else:
                    self._make_still_clip(None, turn.duration_seconds, head_clip)

                seg = workdir / f"seg-{idx:03d}.mp4"
                self._composite_turn(
                    head_clip=head_clip,
                    background_path=req.background_path,
                    turn=turn,
                    output=seg,
                )
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
        head_clip: Path,
        background_path: Path | None,
        turn: PhotoDialogueTurn,
        output: Path,
    ) -> None:
        p = _PLACEMENT[turn.speaker]
        sw, sh = p["scale_w"], p["scale_h"]
        ox, oy = p["overlay_x"], p["overlay_y"]
        label = p["label"]
        color = p["label_color"]
        dur = turn.duration_seconds

        # Build filter_complex step by step
        if background_path and background_path.exists():
            bg_inputs = ["-loop", "1", "-i", str(background_path)]
            bg_filter = f"[0:v]scale={CANVAS_W}:{CANVAS_H},setsar=1[bg];"
            head_idx = 1
        else:
            bg_inputs = []
            bg_filter = (
                f"color=c=0x1a1a2e:size={CANVAS_W}x{CANVAS_H}:rate=30,"
                f"setsar=1[bg];"
            )
            head_idx = 0

        # Wrap subtitle text in Python (FFmpeg drawtext doesn't word-wrap)
        lines = _wrap_text(turn.text, max_chars=38)[:4]

        # Subtitle box + lines
        sub_y0 = _SUBTITLE_Y
        box_filter = (
            f"drawbox=x=40:y={sub_y0}:w={CANVAS_W - 80}:h={_SUBTITLE_BOX_H}:"
            f"color=black@0.75:t=fill"
        )
        text_filters: list[str] = [box_filter]
        ty = sub_y0 + 18
        for line in lines:
            esc = _esc(line)
            text_filters.append(
                f"drawtext=fontfile={FONT_PATH}:text='{esc}':"
                f"fontsize=48:fontcolor=white:x=(w-tw)/2:y={ty}:"
                f"shadowcolor=black@0.9:shadowx=2:shadowy=2"
            )
            ty += 60

        # Speaker label
        text_filters.append(
            f"drawtext=fontfile={FONT_PATH}:text='{label}':"
            f"fontsize=40:fontcolor=0x{color}:x=80:y={_LABEL_Y}:"
            f"shadowcolor=black@0.9:shadowx=2:shadowy=2"
        )

        text_chain = ",".join(text_filters)

        filter_complex = (
            f"{bg_filter}"
            f"[{head_idx}:v]scale={sw}:{sh},setsar=1[head];"
            f"[bg][head]overlay={ox}:{oy}[scene];"
            f"[scene]{text_chain}[out]"
        )

        cmd = [
            "ffmpeg", "-y",
            *bg_inputs,
            "-i", str(head_clip),
            "-filter_complex", filter_complex,
            "-map", "[out]",
            "-t", f"{dur:.3f}",
            "-c:v", "libx264", "-preset", "veryfast", "-crf", "22",
            "-pix_fmt", "yuv420p", "-an", "-r", "30",
            str(output),
        ]
        _run(cmd, f"composite turn for {turn.speaker}")

    def _make_still_clip(
        self,
        audio_path: Path | None,
        duration: float,
        output: Path,
    ) -> None:
        """Black frame fallback when SadTalker or audio is unavailable."""
        color_filter = f"color=c=0x1a1a2e:size=256x256:rate=30,setsar=1"
        cmd = [
            "ffmpeg", "-y",
            "-f", "lavfi", "-i", color_filter,
            "-t", f"{duration:.3f}",
            "-c:v", "libx264", "-preset", "veryfast", "-crf", "22",
            "-pix_fmt", "yuv420p", "-an",
            str(output),
        ]
        _run(cmd, "make still clip")

    # ------------------------------------------------------------------
    # TTS
    # ------------------------------------------------------------------

    def _ensure_audio(
        self, turn: PhotoDialogueTurn, workdir: Path, idx: int
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

def _run(cmd: list[str], op: str) -> None:
    result = subprocess.run(cmd, capture_output=True, text=True, timeout=600)
    if result.returncode != 0:
        tail = (result.stderr or "")[-2000:]
        raise RuntimeError(f"Could not {op}: {tail}")


def _wrap_text(text: str, max_chars: int) -> list[str]:
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
    """Escape text for FFmpeg drawtext filter."""
    return (
        text
        .replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace(":", "\\:")
        .replace("[", "\\[")
        .replace("]", "\\]")
    )
