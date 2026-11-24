"""PCM for the mock's voice turns, generated in memory (P7.2, PROTOCOL.md 4.10).

No audio device is opened anywhere here: frames come from arithmetic (a tone),
zeros (silence), or a WAV file read into memory. That keeps turn tests runnable
in CI and on a laptop with no microphone.
"""

from __future__ import annotations

import math
import struct
import wave
from collections.abc import Callable
from pathlib import Path
from typing import Protocol

from mockdevice.constants import VOICE_CHUNK_BYTES

SAMPLE_RATE = 16_000  # Hz, HA's assist_pipeline format
SAMPLE_WIDTH = 2  # bytes, 16-bit signed little-endian
CHANNELS = 1

_SAMPLES_PER_CHUNK = VOICE_CHUNK_BYTES // SAMPLE_WIDTH


class Pacer:
    """One frame per `interval` of wall time, kept on absolute deadlines.

    Sleeping `interval` *after* each send makes the cadence `send + interval`,
    so any send cost leaves the stream behind the audio clock and the shortfall
    compounds over a turn: at 1 ms per send a 300 s turn delivers 270 s of audio
    (PROTOCOL.md 4.10, `VOICE_FRAME_INTERVAL` in ADR 0002). Deadlines are read
    from the caller's clock, so a test can drive a whole turn without waiting.
    """

    def __init__(self, interval: float, clock: Callable[[], float]) -> None:
        if interval <= 0:
            raise ValueError("interval must be positive")
        self.interval = interval
        self._clock = clock
        self._next_at: float | None = None

    def start(self) -> None:
        """Put frame 0 at now; later frames land on `start + k * interval`."""
        self._next_at = self._clock()

    def wait(self) -> float:
        """Advance one slot and return the sleep before the next send.

        A send that overran its slot returns 0 and the next frame goes out at
        once. Nothing is skipped, so the stream catches its audio clock back
        instead of losing audio.
        """
        if self._next_at is None:
            raise RuntimeError("start() before wait()")
        self._next_at += self.interval
        return max(0.0, self._next_at - self._clock())


class PcmSource(Protocol):
    """Anything that can hand out consecutive PCM chunks, looping at its end."""

    def read(self, nbytes: int) -> bytes:
        """Return `nbytes` of PCM from the current position, wrapping at the end."""
        ...


class Silence:
    """Digital silence, for tests that only care about framing."""

    def read(self, nbytes: int) -> bytes:
        return bytes(nbytes)


class Tone:
    """A quiet sine wave, so frames are distinguishable from silence and from each other.

    The default is A4 at about -24 dBFS: loud enough that a test can see
    non-zero samples, quiet enough that reading it as PCM adds nothing.
    """

    def __init__(self, frequency: float = 440.0, amplitude: int = 2000) -> None:
        self.frequency = frequency
        self.amplitude = amplitude
        self._index = 0

    def read(self, nbytes: int) -> bytes:
        count = nbytes // SAMPLE_WIDTH
        step = 2 * math.pi * self.frequency / SAMPLE_RATE
        samples = [int(self.amplitude * math.sin(step * (self._index + i))) for i in range(count)]
        self._index += count
        return struct.pack(f"<{count}h", *samples)


class WavFile:
    """A 16 kHz, 16-bit mono WAV file held in memory.

    Other formats are refused rather than converted: a resampler here would
    hide a device that streams the wrong format, which is what the channel's
    tests exist to catch.
    """

    def __init__(self, path: str | Path) -> None:
        with wave.open(str(path), "rb") as wav:
            problems = []
            if wav.getframerate() != SAMPLE_RATE:
                problems.append(f"{wav.getframerate()} Hz")
            if wav.getsampwidth() != SAMPLE_WIDTH:
                problems.append(f"{8 * wav.getsampwidth()}-bit")
            if wav.getnchannels() != CHANNELS:
                problems.append(f"{wav.getnchannels()} channels")
            if problems:
                raise ValueError(f"{path}: needs 16 kHz, 16-bit mono; got {', '.join(problems)}")
            self._pcm = wav.readframes(wav.getnframes())
        self._offset = 0

    def read(self, nbytes: int) -> bytes:
        if not self._pcm:  # a zero-length file still streams silence
            return bytes(nbytes)
        out = bytearray()
        while len(out) < nbytes:
            take = min(nbytes - len(out), len(self._pcm) - self._offset)
            out += self._pcm[self._offset : self._offset + take]
            self._offset = (self._offset + take) % len(self._pcm)
        return bytes(out)


def encode_frame(handler_id: int, pcm: bytes) -> bytes:
    """The binary frame: the handler byte, then one chunk of PCM (PROTOCOL.md 4.10)."""
    if not 1 <= handler_id <= 255:
        raise ValueError(f"handler_id {handler_id} is not a byte in 1..255")
    if len(pcm) != VOICE_CHUNK_BYTES:
        raise ValueError(f"PCM chunk is {len(pcm)} bytes, expected {VOICE_CHUNK_BYTES}")
    return bytes([handler_id]) + pcm


def decode_frame(payload: bytes) -> tuple[int, bytes]:
    """Split a binary frame into (handler_id, PCM); the sender's rules, enforced."""
    if not payload:
        raise ValueError("empty binary frame")
    handler_id, pcm = payload[0], payload[1:]
    if handler_id == 0:
        raise ValueError("handler_id 0 is reserved")
    if len(pcm) != VOICE_CHUNK_BYTES:
        raise ValueError(f"PCM chunk is {len(pcm)} bytes, expected {VOICE_CHUNK_BYTES}")
    return handler_id, pcm
