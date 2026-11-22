#!/usr/bin/env python3
"""The wake-word desk harness: the shipped model, Mac-made clips, no device.

`make voice-desk` (or `uv run --with ... python tools/desk/wake_word.py`)
synthesises clips with `say` and converts them with ffmpeg, then scores them
exactly as the device does: the same `.tflite` the build fetches into the APK,
the same 0-26 frontend values from pymicro-features, the same int8
quantisation, the same non-overlapping three-row windows, the same 200/255
cutoff and the same four-second cooldown (ADR 0002, ADR 0030). It prints one
row per clip and a summary of detections on positives and false accepts on
negatives.

`say -o` writes a file and plays nothing; ffmpeg only reads and writes files.
Nothing here opens a microphone or an audio device, which is what the owner's
rules after the P7.0 feedback test require.

The clips are generated at run time and never committed. Positives are the wake
phrase in several system voices, each also mixed with noise at 10 dB SNR;
negatives are ordinary sentences in the same voices, which is where a false
accept would show up. Both libraries are Apache-2.0; the model's provenance is
in ADR 0030.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import subprocess
import sys
import tempfile
import urllib.request
import wave
from dataclasses import dataclass, field
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]

#: The pinned model, the same one the build fetches into the APK's assets
#: (ADR 0030 item 8). The harness uses the built copy when there is one, then
#: a copy here, and only downloads when neither exists - always checking the
#: hash, so it can never score a different model from the one that ships.
MODEL_URL = (
    "https://raw.githubusercontent.com/esphome/micro-wake-word-models/"
    "40ff33f57f8fc6ad71a75ef085abbf742495b225/models/v2/okay_nabu.tflite"
)
GENERATED_MODEL = (
    REPO
    / "android"
    / "app"
    / "build"
    / "generated"
    / "wakeword"
    / "assets"
    / "wakeword"
    / "okay_nabu.tflite"
)
CACHED_MODEL = REPO / "build" / "wakeword" / "okay_nabu.tflite"

#: sha256 of that file, also recorded in ADR 0030 and asserted by the build.
MODEL_SHA256 = "0689abe1912a95a3318a0d8cb2e67bad0cbcfe3e24dd6e050c75debddfb6f891"

#: The phrase the model was trained on (ADR 0002), as `say` should pronounce it.
PHRASE = "okay nabu"

#: One voice per family macOS ships, so a detection is not one voice's luck.
VOICES = ("Samantha", "Daniel", "Tessa", "Alex")

#: What people actually say to the tablet, and what it must not react to.
NEGATIVES = (
    "turn off the lights",
    "what time is it",
    "play some music please",
    "open the blinds",
)

#: The device constants, mirrored here so the harness reports the app's verdict.
CUTOFF = 200
COOLDOWN_WINDOWS = 133
ROWS = 3
BINS = 40

#: Padding so the frontend's first window and the phrase's tail are whole.
LEAD_MS = 300
TAIL_MS = 300


@dataclass
class Clip:
    path: Path
    kind: str  # "positive" | "noisy" | "negative"
    label: str
    seconds: float = 0.0
    inferences: int = 0
    peak: int = 0
    detections: list[int] = field(default_factory=list)

    @property
    def hit(self) -> bool:
        return bool(self.detections)


def missing_tools() -> list[str]:
    """What this machine lacks; the harness runs only on macOS with ffmpeg."""
    missing = []
    if sys.platform != "darwin":
        missing.append("macOS (say)")
    elif shutil.which("say") is None:
        missing.append("say")
    if shutil.which("ffmpeg") is None:
        missing.append("ffmpeg")
    return missing


def sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def resolve_model() -> Path:
    """The pinned model: the built copy, else a cached one, else a download."""
    for candidate in (GENERATED_MODEL, CACHED_MODEL):
        if candidate.is_file() and sha256(candidate) == MODEL_SHA256:
            return candidate
    CACHED_MODEL.parent.mkdir(parents=True, exist_ok=True)
    print(f"fetching the wake-word model from {MODEL_URL}", file=sys.stderr)
    with urllib.request.urlopen(MODEL_URL) as response:  # pinned https URL, hash checked
        CACHED_MODEL.write_bytes(response.read())
    digest = sha256(CACHED_MODEL)
    if digest != MODEL_SHA256:
        CACHED_MODEL.unlink()
        raise SystemExit(f"model sha256 is {digest}, expected {MODEL_SHA256} (ADR 0030)")
    return CACHED_MODEL


def run(command: list[str]) -> None:
    result = subprocess.run(command, capture_output=True, text=True)
    if result.returncode != 0:
        raise RuntimeError(f"{' '.join(command)} failed: {result.stderr.strip()}")


def say(text: str, voice: str, out: Path) -> None:
    """`say` writes a file (`-o`) and plays nothing, with no audio device touched."""
    run(["say", "-v", voice, "-r", "180", "-o", str(out), text])


def to_pcm(source: Path, out: Path, *, noise: bool = False) -> None:
    """16 kHz mono 16-bit, padded at both ends; optionally mixed with noise."""
    pad = f"adelay={LEAD_MS},apad=pad_dur={TAIL_MS / 1000:.2f}"
    command = ["ffmpeg", "-y", "-loglevel", "error", "-i", str(source)]
    if noise:
        # white noise under the phrase, at roughly a quiet room rather than a
        # silent booth; it is mixed, never played
        command += ["-f", "lavfi", "-i", "anoisesrc=color=white:amplitude=0.15:sample_rate=16000"]
        # duration=first: the noise source is endless, so the voice sets the length
        graph = (
            f"[0:a]{pad}[voice];[1:a]volume=0.5[room];"
            "[voice][room]amix=inputs=2:duration=first[out]"
        )
        command += ["-filter_complex", graph, "-map", "[out]"]
    else:
        command += ["-af", pad]
    command += ["-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le", str(out)]
    run(command)


def make_clips(directory: Path, voices: tuple[str, ...], negatives: tuple[str, ...]) -> list[Clip]:
    clips: list[Clip] = []
    work = directory / "raw"
    work.mkdir(parents=True, exist_ok=True)
    for voice in voices:
        raw = work / f"{voice}-phrase.aiff"
        say(PHRASE, voice, raw)
        clean = directory / f"positive-{voice}.wav"
        to_pcm(raw, clean)
        clips.append(Clip(clean, "positive", f"{voice}: {PHRASE}"))
        noisy = directory / f"noisy-{voice}.wav"
        to_pcm(raw, noisy, noise=True)
        clips.append(Clip(noisy, "noisy", f"{voice}: {PHRASE} + noise"))
    for index, sentence in enumerate(negatives):
        voice = voices[index % len(voices)]
        raw = work / f"negative-{index}-{voice}.aiff"
        say(sentence, voice, raw)
        path = directory / f"negative-{index}-{voice}.wav"
        to_pcm(raw, path)
        clips.append(Clip(path, "negative", f"{voice}: {sentence}"))
    shutil.rmtree(work, ignore_errors=True)
    for clip in clips:
        clip.seconds = clip_seconds(clip.path)
    return clips


def clip_seconds(path: Path) -> float:
    with wave.open(str(path), "rb") as clip:
        return clip.getnframes() / clip.getframerate()


def score_clips(clips: list[Clip], model: Path) -> None:
    """Feeds each clip through the shipped model at the device's cadence."""
    from ai_edge_litert.interpreter import Interpreter
    from pymicro_features import MicroFrontend

    from tools.device.score_wakeword import quantise

    for clip in clips:
        with wave.open(str(clip.path), "rb") as source:
            pcm = source.readframes(source.getnframes())
        interpreter = Interpreter(model_path=str(model))
        interpreter.allocate_tensors()
        frontend = MicroFrontend()
        frontend.reset()
        pending: list[float] = []
        windows = 0
        last_detection: int | None = None
        offset = 0
        chunk = 160 * 2  # 10 ms
        while offset + chunk <= len(pcm):
            result = frontend.process_samples(pcm[offset : offset + chunk])
            offset += chunk
            for feature in result.features:
                pending.append(feature)
                if len(pending) < ROWS * BINS:
                    continue
                window = quantise(pending).reshape(1, ROWS, BINS)
                interpreter.set_tensor(interpreter.get_input_details()[0]["index"], window)
                interpreter.invoke()
                probability = int(
                    interpreter.get_tensor(interpreter.get_output_details()[0]["index"])[0][0]
                )
                windows += 1
                pending = []
                clip.peak = max(clip.peak, probability)
                if probability < CUTOFF:
                    continue
                if last_detection is not None and windows - last_detection <= COOLDOWN_WINDOWS:
                    continue
                last_detection = windows
                clip.detections.append(probability)
        clip.inferences = windows


@dataclass
class Report:
    clips: list[Clip]

    @property
    def positives(self) -> list[Clip]:
        return [c for c in self.clips if c.kind == "positive"]

    @property
    def noisy(self) -> list[Clip]:
        return [c for c in self.clips if c.kind == "noisy"]

    @property
    def negatives(self) -> list[Clip]:
        return [c for c in self.clips if c.kind == "negative"]

    @property
    def false_accepts(self) -> list[Clip]:
        return [c for c in self.negatives if c.hit]

    def lines(self) -> list[str]:
        rows = [f"{'clip':<34} {'kind':<9} {'s':>5} {'win':>4} {'peak':>5} {'hits':>4}  verdict"]
        for clip in self.clips:
            verdict = "DETECTED" if clip.hit else "silent"
            rows.append(
                f"{clip.path.stem:<34} {clip.kind:<9} {clip.seconds:5.1f} "
                f"{clip.inferences:4d} {clip.peak:5d} {len(clip.detections):4d}  {verdict}"
            )
        rows.append(
            f"positives detected: {sum(c.hit for c in self.positives)}/{len(self.positives)}"
            f"   noisy positives detected: {sum(c.hit for c in self.noisy)}/{len(self.noisy)}"
            f"   false accepts: {len(self.false_accepts)}/{len(self.negatives)}"
        )
        return rows

    def as_json(self) -> str:
        return json.dumps(
            {
                "positives": [
                    {
                        "clip": c.path.stem,
                        "label": c.label,
                        "peak": c.peak,
                        "hits": len(c.detections),
                    }
                    for c in self.positives
                ],
                "noisy_positives": [
                    {
                        "clip": c.path.stem,
                        "label": c.label,
                        "peak": c.peak,
                        "hits": len(c.detections),
                    }
                    for c in self.noisy
                ],
                "negatives": [
                    {
                        "clip": c.path.stem,
                        "label": c.label,
                        "peak": c.peak,
                        "hits": len(c.detections),
                    }
                    for c in self.negatives
                ],
            },
            indent=2,
        )


def run_harness(
    voices: tuple[str, ...] = VOICES,
    negatives: tuple[str, ...] = NEGATIVES,
    *,
    keep: Path | None = None,
) -> Report:
    """Generates the clips, scores them and returns the report."""
    missing = missing_tools()
    if missing:
        raise SystemExit(f"cannot run the desk harness: missing {', '.join(missing)}")
    model = resolve_model()
    directory = keep if keep is not None else Path(tempfile.mkdtemp(prefix="tabula-wake-"))
    directory.mkdir(parents=True, exist_ok=True)
    clips = make_clips(directory, voices, negatives)
    score_clips(clips, model)
    return Report(clips)


def main() -> int:
    parser = argparse.ArgumentParser(description=(__doc__ or "").splitlines()[0])
    parser.add_argument("--keep", type=Path, help="write the clips here instead of a temp dir")
    parser.add_argument("--json", action="store_true", help="print the per-clip JSON")
    args = parser.parse_args()
    report = run_harness(keep=args.keep)
    print("\n".join(report.lines()))
    if args.json:
        print(report.as_json())
    # the harness is a report, not a gate: the packet quotes its numbers and the
    # owner's microphone run is what decides (ADR 0030)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
