"""Dynamic two-character dialogue compositor.

For each dialogue turn:
  1. Generate local Kokoro TTS for the active speaker.
  2. Measure the real WAV duration (no LLM timing guess).
  3. Animate the active speaker with SadTalker using that exact WAV.
  4. Keep the other character visible using its stored reference photo.
  5. Composite both characters, background and subtitles with FFmpeg.
"""
from __future__ import annotations

import logging
import os
import shutil
import subprocess
import tempfile
import threading
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from .sadtalker_client import SadTalkerClient

logger = logging.getLogger("hybrid-photo-compositor")

CANVAS_W, CANVAS_H = 1080, 1920
FONT_PATH = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
TURN_PADDING_SECONDS = float(os.getenv("DIALOGUE_TURN_PADDING_SECONDS", "0.30"))

_PLACEMENT: dict[str, dict[str, Any]] = {
    "bhaiya": {
        "scale_w": 560,
        "scale_h": 720,
        "overlay_x": 20,
        "overlay_y": 330,
        "label": "Bhaiya",
        "label_color": "FFA040",
    },
    "chitti": {
        "scale_w": 460,
        "scale_h": 620,
        "overlay_x": 590,
        "overlay_y": 430,
        "label": "Chitti",
        "label_color": "FF80C0",
    },
}

_SUBTITLE_Y = 1490
_SUBTITLE_BOX_H = 260
_LABEL_Y = 1780


@dataclass
class HybridDialogueTurn:
    speaker: str
    text: str
    emotion: str = "talking"
    duration_seconds: float = 4.0


@dataclass
class HybridPhotoCompositorRequest:
    turns: list[HybridDialogueTurn]
    output_path: Path
    background_path: Path | None = None
    fps: int = 30


class HybridPhotoDialogueCompositor:
    """Render a real lip-synced speaker while keeping the listener on screen."""

    def __init__(self, sadtalker_client: SadTalkerClient | None = None) -> None:
        self._st = sadtalker_client or SadTalkerClient()
        self._tts_lock = threading.Lock()
        self._tts_pipeline: Any | None = None

    def ready(self) -> bool:
        return self._st.is_available() and self._st.photos_ready()

    def render(self, req: HybridPhotoCompositorRequest) -> Path:
        if not req.turns:
            raise ValueError("At least one dialogue turn is required")
        if not self.ready():
            raise RuntimeError("SadTalker models and both character photos must be ready")

        req.output_path.parent.mkdir(parents=True, exist_ok=True)

        with tempfile.TemporaryDirectory(prefix="hybrid-dialogue-") as tmp:
            workdir = Path(tmp)
            photos = {
                "bhaiya": self._st.download_photo("bhaiya", workdir / "bhaiya.png"),
                "chitti": self._st.download_photo("chitti", workdir / "chitti.png"),
            }
            segments: list[Path] = []
            audio_map: list[tuple[Path, float]] = []
            cursor = 0.0

            for idx, turn in enumerate(req.turns):
                if turn.speaker not in _PLACEMENT:
                    raise ValueError(f"Unsupported speaker: {turn.speaker}")

                audio = workdir / f"turn-{idx:03d}.wav"
                self._synth(turn.text, turn.speaker, audio)
                speech_duration = self._audio_duration(audio)
                turn_duration = max(1.0, speech_duration + TURN_PADDING_SECONDS)

                speaker_clip = workdir / f"speaker-{idx:03d}.mp4"
                self._st.animate(turn.speaker, audio, speaker_clip)

                listener = "chitti" if turn.speaker == "bhaiya" else "bhaiya"
                segment = workdir / f"segment-{idx:03d}.mp4"
                self._compose_turn(
                    speaker=turn.speaker,
                    listener=listener,
                    speaker_clip=speaker_clip,
                    listener_photo=photos[listener],
                    background_path=req.background_path,
                    text=turn.text,
                    duration=turn_duration,
                    output=segment,
                )
                segments.append(segment)
                audio_map.append((audio, cursor))
                cursor += turn_duration

            joined = workdir / "joined.mp4"
            self._concat(segments, joined, workdir)
            mixed_audio = workdir / "dialogue.wav"
            self._mix_audio(audio_map, mixed_audio, cursor)
            self._mux(joined, mixed_audio, req.output_path, cursor)

        return req.output_path

    def _synth(self, text: str, speaker: str, output: Path) -> None:
        import numpy as np
        import soundfile as sf
        from kokoro import KPipeline

        voice_map = {
            "bhaiya": os.getenv("VOICE_BHAIYA", "am_liam"),
            "chitti": os.getenv("VOICE_CHITTI", "af_heart"),
        }
        lang = os.getenv("KOKORO_LANG_CODE", "a")
        with self._tts_lock:
            if self._tts_pipeline is None:
                self._tts_pipeline = KPipeline(lang_code=lang)
            pipeline = self._tts_pipeline
            chunks = [audio for _, _, audio in pipeline(text[:2000], voice=voice_map[speaker], speed=1.0)]

        if not chunks:
            raise RuntimeError(f"Kokoro produced no audio for {speaker}")
        sf.write(output, np.concatenate(chunks), 24000)

    @staticmethod
    def _audio_duration(path: Path) -> float:
        import soundfile as sf
        info = sf.info(path)
        return float(info.frames) / float(info.samplerate)

    def _compose_turn(
        self,
        speaker: str,
        listener: str,
        speaker_clip: Path,
        listener_photo: Path,
        background_path: Path | None,
        text: str,
        duration: float,
        output: Path,
    ) -> None:
        speaker_p = _PLACEMENT[speaker]
        listener_p = _PLACEMENT[listener]

        command = ["ffmpeg", "-y"]
        input_index = 0
        if background_path and background_path.exists():
            command += ["-loop", "1", "-i", str(background_path)]
            bg_filter = f"[{input_index}:v]scale={CANVAS_W}:{CANVAS_H},setsar=1[bg];"
            input_index += 1
        else:
            bg_filter = f"color=c=0x1a1a2e:size={CANVAS_W}x{CANVAS_H}:rate=30,setsar=1[bg];"

        speaker_idx = input_index
        command += ["-stream_loop", "-1", "-i", str(speaker_clip)]
        input_index += 1
        listener_idx = input_index
        command += ["-loop", "1", "-i", str(listener_photo)]

        lines = _wrap(text, 38)[:4]
        text_filters = [
            f"drawbox=x=40:y={_SUBTITLE_Y}:w={CANVAS_W - 80}:h={_SUBTITLE_BOX_H}:color=black@0.75:t=fill"
        ]
        y = _SUBTITLE_Y + 18
        for line in lines:
            text_filters.append(
                f"drawtext=fontfile={FONT_PATH}:text='{_esc(line)}':fontsize=48:fontcolor=white:"
                f"x=(w-tw)/2:y={y}:shadowcolor=black@0.9:shadowx=2:shadowy=2"
            )
            y += 60
        text_filters.append(
            f"drawtext=fontfile={FONT_PATH}:text='{speaker_p['label']}':fontsize=40:"
            f"fontcolor=0x{speaker_p['label_color']}:x=80:y={_LABEL_Y}:"
            f"shadowcolor=black@0.9:shadowx=2:shadowy=2"
        )

        filter_complex = (
            f"{bg_filter}"
            f"[{speaker_idx}:v]scale={speaker_p['scale_w']}:{speaker_p['scale_h']},setsar=1[speaker];"
            f"[{listener_idx}:v]scale={listener_p['scale_w']}:{listener_p['scale_h']},setsar=1[listener];"
            f"[bg][speaker]overlay={speaker_p['overlay_x']}:{speaker_p['overlay_y']}[withSpeaker];"
            f"[withSpeaker][listener]overlay={listener_p['overlay_x']}:{listener_p['overlay_y']}[scene];"
            f"[scene]{','.join(text_filters)}[out]"
        )

        command += [
            "-filter_complex", filter_complex,
            "-map", "[out]",
            "-t", f"{duration:.3f}",
            "-c:v", "libx264", "-preset", "veryfast", "-crf", "21",
            "-pix_fmt", "yuv420p", "-an", "-r", "30",
            str(output),
        ]
        _run(command, f"compose {speaker} turn")

    @staticmethod
    def _concat(segments: list[Path], output: Path, workdir: Path) -> None:
        listing = workdir / "concat.txt"
        listing.write_text("\n".join(f"file '{segment.as_posix()}'" for segment in segments), encoding="utf-8")
        _run([
            "ffmpeg", "-y", "-f", "concat", "-safe", "0", "-i", str(listing),
            "-c", "copy", str(output),
        ], "concatenate dialogue segments")

    @staticmethod
    def _mix_audio(audio_map: list[tuple[Path, float]], output: Path, total_seconds: float) -> None:
        inputs: list[str] = []
        delays: list[str] = []
        for index, (path, start) in enumerate(audio_map):
            inputs += ["-i", str(path)]
            delay_ms = int(start * 1000)
            delays.append(f"[{index}]adelay={delay_ms}|{delay_ms}[d{index}]")
        mix_inputs = "".join(f"[d{i}]" for i in range(len(audio_map)))
        filter_complex = ";".join(delays) + f";{mix_inputs}amix=inputs={len(audio_map)}:duration=longest[out]"
        _run([
            "ffmpeg", "-y", *inputs,
            "-filter_complex", filter_complex,
            "-map", "[out]", "-t", f"{total_seconds:.3f}", "-ar", "48000", str(output),
        ], "mix dialogue audio")

    @staticmethod
    def _mux(video: Path, audio: Path, output: Path, total_seconds: float) -> None:
        partial = output.with_suffix(".part.mp4")
        _run([
            "ffmpeg", "-y", "-i", str(video), "-i", str(audio),
            "-map", "0:v:0", "-map", "1:a:0", "-c:v", "copy", "-c:a", "aac",
            "-b:a", "192k", "-t", f"{total_seconds:.3f}", "-movflags", "+faststart", str(partial),
        ], "mux dialogue audio")
        os.replace(partial, output)


def _run(command: list[str], operation: str) -> None:
    process = subprocess.run(command, capture_output=True, text=True, timeout=600)
    if process.returncode != 0:
        raise RuntimeError(f"Could not {operation}: {(process.stderr or '')[-2500:]}")


def _wrap(text: str, max_chars: int) -> list[str]:
    words = text.split()
    lines: list[str] = []
    current: list[str] = []
    for word in words:
        candidate = " ".join(current + [word])
        if len(candidate) > max_chars and current:
            lines.append(" ".join(current))
            current = [word]
        else:
            current.append(word)
    if current:
        lines.append(" ".join(current))
    return lines


def _esc(text: str) -> str:
    return (
        text.replace("\\", "\\\\")
        .replace("'", "\\'")
        .replace(":", "\\:")
        .replace("[", "\\[")
        .replace("]", "\\]")
    )
