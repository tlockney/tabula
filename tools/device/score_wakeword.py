#!/usr/bin/env python3
"""Score recorded wake-word clips with the reference microWakeWord pipeline.

Why this is a host tool: the spike build measures what continuous detection
costs on the device, with the app's own lightweight frontend. Detection
accuracy is a different question — a microphone that cannot hear the wake word
at 3 m and a frontend that is not bit-compatible look the same on the device —
so the clips are recorded on the tablet and scored here, with the reference
frontend (pymicro-features) and the shipped model.

Usage (the dependencies come from PyPI, never a release download):

    uv run --with ai-edge-litert --with pymicro-features \
        python tools/device/score_wakeword.py build/spike/clips \
        --model build/spike/okay_nabu.tflite

Prints one row per clip: seconds, inferences, the highest probability the model
gave, and how many times it crossed the cutoff. A clip is a hit when it crossed
the cutoff at all, at the same 200/255 the spike counts on the device; the
wake-word accuracy table in the packet's Result is those counts, per distance,
with and without playback.

Both libraries are Apache-2.0. The model is microWakeWord's, fetched by
tools/device/check_p70.sh at a pinned commit with a sha256 check; see the P7.0
ADR for the license finding.
"""

from __future__ import annotations

import argparse
import sys
import wave
from pathlib import Path

# The model's input quantisation: int8, scale 0.10196078, zero point -128.
# pymicro_features already returns the frontend's 0-26 log-mel values - the
# range the model was quantised over (255 x 0.10196 is about 26.0) - so the
# quantiser applies the scale once. P7.0 divided by 25.6 as well and scored
# every clip as silence; P7.1 corrected it and added the test beside this.
FEATURE_SCALE = 0.10196078
FEATURE_ZERO_POINT = -128

FEATURE_BINS = 40

# The spike counts a detection at DETECTION_CUTOFF = 200 in SpikeService.kt, in
# the model's own 0-255 scale. Scoring at any other level would call a clip a
# hit on the host that the device ignored, so this is the default here too.
DETECTION_CUTOFF = 200


def quantise(features: list[float]) -> "np.ndarray":
    """Maps frontend features (0-26) to the model's int8 input, one window."""
    import numpy as np

    return np.clip(
        np.round(np.asarray(features, dtype=np.float64) / FEATURE_SCALE) + FEATURE_ZERO_POINT,
        -128,
        127,
    ).astype(np.int8)


def read_clip(path: Path) -> bytes:
    """Returns 16-bit mono PCM bytes; refuses anything else rather than guessing."""
    with wave.open(str(path), "rb") as clip:
        if clip.getnchannels() != 1 or clip.getsampwidth() != 2:
            raise SystemExit(
                f"{path}: expected 16-bit mono, got {clip.getnchannels()} channels "
                f"of {clip.getsampwidth() * 8} bits"
            )
        if clip.getframerate() != 16000:
            raise SystemExit(f"{path}: expected 16000 Hz, got {clip.getframerate()}")
        return clip.readframes(clip.getnframes())


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("clips", type=Path, help="directory of 16 kHz mono WAV clips")
    parser.add_argument("--model", type=Path, required=True, help="the wake-word .tflite")
    parser.add_argument(
        "--cutoff",
        type=float,
        default=DETECTION_CUTOFF / 255,
        help=(
            "probability cutoff in 0-1; the default is the device's "
            f"{DETECTION_CUTOFF}/255, so a clip is a hit at the same level in both"
        ),
    )
    args = parser.parse_args()

    try:
        import numpy as np
        from ai_edge_litert.interpreter import Interpreter
        from pymicro_features import MicroFrontend
    except ImportError as error:  # pragma: no cover - the docstring has the command
        print(f"missing dependency: {error}", file=sys.stderr)
        print("run with: uv run --with ai-edge-litert --with pymicro-features ...", file=sys.stderr)
        return 2

    if not args.model.is_file():
        print(f"no model at {args.model}", file=sys.stderr)
        return 2
    clips = sorted(args.clips.glob("*.wav"))
    if not clips:
        print(f"no clips in {args.clips}", file=sys.stderr)
        return 2

    cutoff = round(args.cutoff * 255)
    print(f"model {args.model} cutoff={cutoff}/255 clips={len(clips)}")
    print(f"{'clip':<28} {'s':>6} {'inferences':>10} {'max_prob':>9} {'crossings':>9}  verdict")
    hits = 0
    for path in clips:
        pcm = read_clip(path)
        # A fresh interpreter per clip: the model's recursion lives in its
        # variable tensors, so sharing one would leak state between clips.
        interpreter = Interpreter(model_path=str(args.model))
        interpreter.allocate_tensors()
        frontend = MicroFrontend()
        frontend.reset()
        rows_per_inference = interpreter.get_input_details()[0]["shape"][1]
        pending: list[int] = []
        highest = 0
        crossings = 0
        inferences = 0
        # 10 ms of audio at a time: the frontend yields one 40-bin frame per hop.
        chunk = 160 * 2
        for offset in range(0, len(pcm) - chunk + 1, chunk):
            out = frontend.process_samples(pcm[offset : offset + chunk])
            for feature in out.features:
                pending.append(feature)
                if len(pending) < rows_per_inference * 40:
                    continue
                quantised = quantise(pending).reshape(1, rows_per_inference, FEATURE_BINS)
                interpreter.set_tensor(interpreter.get_input_details()[0]["index"], quantised)
                interpreter.invoke()
                probability = int(
                    interpreter.get_tensor(interpreter.get_output_details()[0]["index"])[0][0]
                )
                inferences += 1
                highest = max(highest, probability)
                if probability >= cutoff:
                    crossings += 1
                # consume the window: one inference per three rows (30 ms of
                # audio), which is microWakeWord's own default stride and the
                # only one this model fires on (verified by the desk harness:
                # overlapping windows never crossed the cutoff)
                pending = []
        seconds = len(pcm) / 2 / 16000
        verdict = "hit" if crossings else "miss"
        hits += 1 if crossings else 0
        print(
            f"{path.stem:<28} {seconds:6.1f} {inferences:10d} {highest:9d} "
            f"{crossings:9d}  {verdict}"
        )

    print(f"clips with a detection: {hits}/{len(clips)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
