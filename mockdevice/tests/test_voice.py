"""Voice turns on the mock: framing, the turn state machine, the stream on one socket (P7.2)."""

from __future__ import annotations

import asyncio
import json
import struct
import wave

import pytest
from mockdevice.client import TLPClient
from mockdevice.codec import REPO_ROOT
from mockdevice.constants import VOICE_CHUNK_BYTES, VOICE_FRAME_INTERVAL
from mockdevice.device import CommandError, MockDevice
from mockdevice.voice import (
    SAMPLE_RATE,
    Pacer,
    Silence,
    Tone,
    WavFile,
    decode_frame,
    encode_frame,
)

TURN = "01JCVTURN00000000000000AA"
FIXTURES = REPO_ROOT / "schema" / "fixtures" / "tlp" / "v1"


async def wait_for(predicate, timeout: float = 5.0) -> None:
    async def poll():
        while not predicate():
            await asyncio.sleep(0.01)

    await asyncio.wait_for(poll(), timeout)


# --- framing (PROTOCOL.md 4.10) ---------------------------------------------


def test_encode_frame_prefixes_the_handler_byte():
    pcm = bytes(range(2)) * (VOICE_CHUNK_BYTES // 2)
    frame = encode_frame(7, pcm)
    assert frame[0] == 7
    assert frame[1:] == pcm
    assert len(frame) == VOICE_CHUNK_BYTES + 1


def test_decode_frame_round_trips_and_enforces_the_sender_rules():
    pcm = bytes(VOICE_CHUNK_BYTES)
    assert decode_frame(encode_frame(255, pcm)) == (255, pcm)
    with pytest.raises(ValueError, match="empty"):
        decode_frame(b"")
    with pytest.raises(ValueError, match="handler_id 0"):
        decode_frame(b"\x00" + pcm)
    with pytest.raises(ValueError, match="expected"):
        decode_frame(b"\x07" + pcm[:-2])
    for bad in (0, 256, -1):
        with pytest.raises(ValueError, match=r"1\.\.255"):
            encode_frame(bad, pcm)


def test_sources_produce_whole_chunks():
    assert Silence().read(VOICE_CHUNK_BYTES) == bytes(VOICE_CHUNK_BYTES)
    tone = Tone()
    chunk = tone.read(VOICE_CHUNK_BYTES)
    assert len(chunk) == VOICE_CHUNK_BYTES
    assert any(struct.unpack(f"<{VOICE_CHUNK_BYTES // 2}h", chunk))
    assert tone.read(VOICE_CHUNK_BYTES) != chunk  # it keeps its place in the wave


def _write_wav(path, frames: bytes, rate: int = SAMPLE_RATE, width: int = 2, channels: int = 1):
    with wave.open(str(path), "wb") as wav:
        wav.setnchannels(channels)
        wav.setsampwidth(width)
        wav.setframerate(rate)
        wav.writeframes(frames)
    return path


def test_wav_file_loops_and_refuses_other_formats(tmp_path):
    samples = struct.pack("<4h", 1, 2, 3, 4)
    source = WavFile(_write_wav(tmp_path / "a.wav", samples))
    first = source.read(8)
    second = source.read(8)
    assert first == samples and second == samples  # wrapped, never a short frame

    with pytest.raises(ValueError, match="8000 Hz"):
        WavFile(_write_wav(tmp_path / "b.wav", samples, rate=8000))
    with pytest.raises(ValueError, match="2 channels"):
        WavFile(_write_wav(tmp_path / "c.wav", samples, channels=2))


# --- the turn state machine (device only) -----------------------------------


def _device(events: list, deltas: list) -> MockDevice:
    device = MockDevice()
    device.on_event = lambda event, data: events.append((event, data))
    device.on_change = deltas.append
    return device


def test_turn_sets_state_and_events():
    events: list = []
    deltas: list = []
    device = _device(events, deltas)
    assert device.state["voice_turn"] == "idle"

    turn = device.begin_voice_turn(TURN, 3, "stt", "okay_nabu")
    assert turn.turn_id == TURN and turn.handler_id == 3
    assert device.state["voice_turn"] == "listening"
    assert events == [("voice_turn_started", {"turn_id": TURN, "phrase": "okay_nabu"})]

    assert device.end_voice_turn(TURN, "command") is True
    assert device.state["voice_turn"] == "idle"
    assert events[-1] == ("voice_turn_ended", {"turn_id": TURN, "reason": "command"})
    assert device.voice_turn is None


def test_turn_rules():
    device = _device([], [])
    device.begin_voice_turn(TURN, 3, "wake_word")
    with pytest.raises(CommandError) as busy:
        device.begin_voice_turn("01JCVTURN00000000000000AB", 4, "stt")
    assert busy.value.code == "busy"
    # A stop for a turn that is not open changes nothing and is not an error.
    assert device.end_voice_turn("01JCVTURN00000000000000AB", "command") is False
    assert device.state["voice_turn"] == "listening"
    with pytest.raises(CommandError) as handler:
        device.begin_voice_turn("01JCVTURN00000000000000AC", 300, "stt")
    assert handler.value.code == "invalid_args"

    assert device.end_voice_turn(TURN, "command") is True
    with pytest.raises(CommandError) as phrase:
        device.begin_voice_turn("01JCVTURN00000000000000AD", 5, "wake_word", "okay_nabu")
    assert phrase.value.code == "invalid_args"


def test_voice_capability_gates_the_turn():
    device = MockDevice(capabilities=["screen"])
    with pytest.raises(CommandError) as err:
        device.begin_voice_turn(TURN, 1, "stt")
    assert err.value.code == "unsupported_command"


# --- on a real socket -------------------------------------------------------


async def test_voice_start_streams_until_voice_stop(server, store, start):
    device = MockDevice(base_url=server.url)
    running = await start(store, device, voice_frame_interval=0.005)
    record = await server.wait_registered(store.device_id)
    session = record.current
    assert session is not None
    handler_id, pcm = server.register_binary_handler(session)

    result = await server.send_command(
        record.device_id,
        "voice_start",
        {
            "turn_id": TURN,
            "handler_id": handler_id,
            "start_stage": "stt",
            "wake_word_phrase": "okay_nabu",
        },
    )
    assert result["ok"], result
    assert record.state["voice_turn"] == "listening"
    await wait_for(lambda: len(pcm) >= 3)
    assert all(len(chunk) == VOICE_CHUNK_BYTES for chunk in pcm)
    assert running.client.voice_bytes_sent >= len(pcm) * VOICE_CHUNK_BYTES
    assert ("voice_turn_started", TURN) in [
        (e["event"], e["data"]["turn_id"]) for e in record.events
    ]
    assert record.report["voice"]["frames"] >= len(pcm)

    # A stop closes the turn, the stream stops, and the event says why.
    result = await server.send_command(
        record.device_id, "voice_stop", {"turn_id": TURN, "reason": "ended"}
    )
    assert result["ok"], result
    assert device.voice_turn is None
    await wait_for(lambda: record.state["voice_turn"] == "idle")
    stopped_at = len(pcm)
    await asyncio.sleep(0.05)
    assert len(pcm) <= stopped_at + 1  # at most the frame already in flight
    ended = [e for e in record.events if e["event"] == "voice_turn_ended"]
    assert ended and ended[-1]["data"] == {"turn_id": TURN, "reason": "command"}

    # Idempotent: a second stop for the closed turn is still ok (PROTOCOL.md 4.10).
    again = await server.send_command(
        record.device_id, "voice_stop", {"turn_id": TURN, "reason": "cancelled"}
    )
    assert again["ok"], again
    assert len([e for e in record.events if e["event"] == "voice_turn_ended"]) == 1


async def test_json_and_binary_interleave_on_one_connection(server, store, start):
    device = MockDevice(base_url=server.url)
    await start(store, device, voice_frame_interval=0.005)
    record = await server.wait_registered(store.device_id)
    session = record.current
    assert session is not None
    handler_id, pcm = server.register_binary_handler(session)
    await server.send_command(
        record.device_id,
        "voice_start",
        {"turn_id": TURN, "handler_id": handler_id, "start_stage": "wake_word"},
    )
    await wait_for(lambda: len(pcm) >= 2)
    before = len(pcm)

    # A command mid-turn: its result and an event land while frames keep coming.
    result = await server.send_command(record.device_id, "notify", {"message": "hi"})
    assert result["ok"], result
    await wait_for(lambda: any(e["event"] == "notification_shown" for e in record.events))
    await wait_for(lambda: len(pcm) >= before + 2)
    assert device.state["voice_turn"] == "listening"
    await server.send_command(record.device_id, "voice_stop", {"turn_id": TURN, "reason": "ended"})


async def test_turn_limit_ends_the_turn_without_voice_stop(server, store, start):
    device = MockDevice(base_url=server.url)
    await start(store, device, voice_frame_interval=0.005, voice_turn_max_s=0.08)
    record = await server.wait_registered(store.device_id)
    session = record.current
    assert session is not None
    handler_id, _ = server.register_binary_handler(session)
    await server.send_command(
        record.device_id,
        "voice_start",
        {"turn_id": TURN, "handler_id": handler_id, "start_stage": "stt"},
    )
    await wait_for(lambda: device.voice_turn is None)
    await wait_for(lambda: record.state["voice_turn"] == "idle")
    ended = [e for e in record.events if e["event"] == "voice_turn_ended"]
    assert ended and ended[-1]["data"] == {"turn_id": TURN, "reason": "timeout"}


async def test_the_turn_limit_follows_the_injected_clock(server, store, start):
    """The loop reads the client's clock, so a test need not wait 300 s (4.10)."""
    now = [1000.0]

    def clock() -> float:
        now[0] += 5.0
        return now[0]

    device = MockDevice(base_url=server.url)
    running = await start(store, device, clock=clock, voice_turn_max_s=60.0)
    record = await server.wait_registered(store.device_id)
    session = record.current
    assert session is not None
    handler_id, _ = server.register_binary_handler(session)
    await server.send_command(
        record.device_id,
        "voice_start",
        {"turn_id": TURN, "handler_id": handler_id, "start_stage": "stt"},
    )
    await wait_for(lambda: device.voice_turn is None)
    ended = [e for e in record.events if e["event"] == "voice_turn_ended"]
    assert ended and ended[-1]["data"] == {"turn_id": TURN, "reason": "timeout"}
    # It streamed while the fake clock ran out the limit, and stopped on it.
    assert running.client.voice_frames_sent > 0


def test_the_pacer_keeps_the_interval_when_a_send_costs_time():
    """Frames land on an absolute schedule, not `send + interval` (4.10)."""
    now = [0.0]
    pacer = Pacer(VOICE_FRAME_INTERVAL, lambda: now[0])
    pacer.start()
    sent_at = []
    for _ in range(200):
        sent_at.append(now[0])  # the send goes out now
        now[0] += 0.001  # and costs 1 ms, which must not shift the next slot
        now[0] += pacer.wait()
    assert sent_at == pytest.approx([k * VOICE_FRAME_INTERVAL for k in range(200)], abs=1e-9)


def test_the_pacer_sends_back_to_back_after_an_overrun_and_keeps_every_frame():
    now = [0.0]
    pacer = Pacer(0.01, lambda: now[0])
    pacer.start()
    sent_at = []
    for cost in [0.035, 0.001, 0.001, 0.001, 0.001]:
        sent_at.append(now[0])
        now[0] += cost
        now[0] += pacer.wait()
    assert len(sent_at) == 5  # nothing skipped to catch up
    assert sent_at[1] == pytest.approx(0.035)  # the next frame went out at once
    assert sent_at[4] == pytest.approx(0.04)  # and the grid is back two frames later


async def test_disconnect_ends_the_turn_without_an_event(server, store, start):
    device = MockDevice(base_url=server.url)
    await start(store, device, voice_frame_interval=0.005)
    record = await server.wait_registered(store.device_id)
    session = record.current
    assert session is not None
    handler_id, _ = server.register_binary_handler(session)
    await server.send_command(
        record.device_id,
        "voice_start",
        {"turn_id": TURN, "handler_id": handler_id, "start_stage": "stt"},
    )
    assert device.voice_turn is not None

    await session.ws.close()
    await wait_for(lambda: device.voice_turn is None)
    # The state goes back to idle for the next connection, but there is nobody
    # left to tell, so no event is queued (PROTOCOL.md 4.10).
    assert device.state["voice_turn"] == "idle"
    assert not [e for e in record.events if e["event"] == "voice_turn_ended"]

    await server.wait_registered(store.device_id, connections=2)
    assert record.state["voice_turn"] == "idle"


async def test_a_wake_word_detection_sends_no_audio_until_voice_start(server, store, start):
    """The device reports the detection; the turn still opens with voice_start (4.10)."""
    device = MockDevice(base_url=server.url)
    running = await start(store, device, voice_frame_interval=0.005)
    record = await server.wait_registered(store.device_id)
    session = record.current
    assert session is not None
    handler_id, pcm = server.register_binary_handler(session)

    await device.handle(
        "set_settings",
        {
            "revision": 1,
            "schema_version": 3,
            "settings": {"voice": {"wake_word_enabled": True}},
        },
    )
    await wait_for(running.client.registered.is_set)
    running.client.announce_wake_word("okay_nabu")
    await wait_for(lambda: any(e["event"] == "wake_word_detected" for e in record.events))

    # A detection is not a turn: no turn id, no handler id, no PCM.
    assert device.voice_turn is None
    assert record.state["voice_turn"] == "idle"
    await asyncio.sleep(0.05)
    assert pcm == []
    assert not [e for e in record.events if e["event"] == "voice_turn_started"]

    # HA answers the detection with voice_start; that is what starts the stream.
    result = await server.send_command(
        record.device_id,
        "voice_start",
        {
            "turn_id": TURN,
            "handler_id": handler_id,
            "start_stage": "stt",
            "wake_word_phrase": "okay_nabu",
        },
    )
    assert result["ok"], result
    await wait_for(lambda: len(pcm) >= 2)
    started = [e for e in record.events if e["event"] == "voice_turn_started"]
    assert started[-1]["data"] == {"turn_id": TURN, "phrase": "okay_nabu"}
    assert (
        await server.send_command(
            record.device_id, "voice_stop", {"turn_id": TURN, "reason": "ended"}
        )
    )["ok"]
    await wait_for(lambda: device.voice_turn is None)


async def test_the_wake_word_hook_needs_a_connection(store, server):
    client = TLPClient(server.url, store, MockDevice(base_url=server.url))
    with pytest.raises(RuntimeError, match="not connected"):
        client.announce_wake_word()


async def test_voice_fixtures_drive_turns(server, store, start):
    """The voice command fixtures are sent as written, one turn at a time."""
    device = MockDevice(base_url=server.url)
    await start(store, device, voice_frame_interval=0.005)
    record = await server.wait_registered(store.device_id)
    session = record.current
    assert session is not None
    handler_id, pcm = server.register_binary_handler(session)

    async def send(fixture: str) -> dict:
        event = json.loads((FIXTURES / f"{fixture}.json").read_text())["event"]
        args = dict(event["args"])
        if "handler_id" in args:  # the fixture's ID is not this connection's
            args["handler_id"] = handler_id
        return await server.send_command(record.device_id, event["command"], args)

    assert (await send("command_voice_start"))["ok"]
    await wait_for(lambda: len(pcm) >= 2)
    assert (await send("command_voice_stop"))["ok"]
    await wait_for(lambda: device.voice_turn is None)

    # A wake_word turn carries no phrase, so its start event has only the id.
    assert (await send("command_voice_start.wake_word"))["ok"]
    await wait_for(lambda: len(pcm) >= 4)
    started = [e for e in record.events if e["event"] == "voice_turn_started"]
    assert started[-1]["data"] == {"turn_id": TURN}
    assert (await send("command_voice_stop"))["ok"]
    await wait_for(lambda: device.voice_turn is None)


async def test_voice_start_without_the_capability_is_refused(server, store, start):
    device = MockDevice(base_url=server.url, capabilities=["screen", "battery"])
    await start(store, device)
    record = await server.wait_registered(store.device_id)
    session = record.current
    assert session is not None
    assert "voice" not in record.capabilities
    handler_id, pcm = server.register_binary_handler(session)
    result = await server.send_command(
        record.device_id,
        "voice_start",
        {"turn_id": TURN, "handler_id": handler_id, "start_stage": "stt"},
    )
    assert not result["ok"]
    assert result["error"]["code"] == "unsupported_command"
    assert pcm == []
    assert "voice_turn" not in record.state  # declared only with the capability
