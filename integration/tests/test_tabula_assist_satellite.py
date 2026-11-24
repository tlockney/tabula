"""Voice turns as an Assist satellite entity (P7.3; ADR 0024, ADR 0034).

The device's wake word opens a turn, its PCM rides the binary channel
(PROTOCOL.md 4.10) into HA's pipeline, and the pipeline's reply goes back out
on the device's media path. HA's pipeline is replaced by a fake here so the
stages can be observed from the integration's side without an STT engine, an
intent agent or a TTS engine; the device is the TLP test client, or the real
mock device for the end-to-end turn.
"""

from __future__ import annotations

import asyncio

import aiohttp
import pytest
from custom_components.tabula import TabulaHub
from custom_components.tabula.const import DOMAIN
from helpers import Paired, frame_fixture, pair_device, uid, ws_connect
from homeassistant.components.assist_pipeline import (
    PipelineEvent,
    PipelineEventType,
    PipelineStage,
)
from homeassistant.components.assist_satellite import AssistSatelliteAnnouncement
from homeassistant.core import Event, HomeAssistant, callback
from homeassistant.helpers import entity_registry as er
from mockdevice.client import TLPClient
from mockdevice.device import ALL_CAPABILITIES, MockDevice
from mockdevice.store import DeviceStore

PHRASE = "okay_nabu"
PREANNOUNCE_FILENAME = "preannounce.mp3"
TTS_URL = "/api/tts_proxy/reply.mp3"
VOICE_CAPS = [*frame_fixture("register")["capabilities"], "voice"]
CHUNK = 320  # VOICE_CHUNK_BYTES: 10 ms of 16 kHz, 16-bit mono (ADR 0002)


def satellite_id(hass: HomeAssistant, paired: Paired) -> str | None:
    return er.async_get(hass).async_get_entity_id(
        "assist_satellite", DOMAIN, f"{paired.device_id}_satellite"
    )


def state_of(hass: HomeAssistant, paired: Paired) -> str | None:
    entity_id = satellite_id(hass, paired)
    state = hass.states.get(entity_id) if entity_id else None
    return state.state if state is not None else None


async def until(predicate, timeout: float = 5.0) -> None:
    async with asyncio.timeout(timeout):
        while not predicate():
            await asyncio.sleep(0.02)


@pytest.fixture(name="fake_pipeline")
def fake_pipeline_fixture(monkeypatch):
    """Install a fake pipeline in HA's satellite module; the test owns it.

    The base class imports the function into its own namespace, so this is the
    one seam that replaces HA's pipeline run without an STT/TTS engine.
    """

    def install(**kwargs) -> FakePipeline:
        pipeline = FakePipeline(**kwargs)
        monkeypatch.setattr(
            "homeassistant.components.assist_satellite.entity.async_pipeline_from_audio_stream",
            pipeline,
        )
        return pipeline

    return install


class FakePipeline:
    """What HA's ``async_pipeline_from_audio_stream`` would do, without engines.

    It reads a fixed number of chunks (the VAD's end of speech), reports the
    stages HA's satellite state machine knows, and either produces a reply or
    fails with one of HA's error codes.
    """

    def __init__(
        self,
        chunks: int = 4,
        tts_url: str | None = TTS_URL,
        error: str | None = None,
        tts_urls: list[str] | None = None,
        tts_error: str | None = None,
        delay: float = 0.0,
    ) -> None:
        self.chunks = chunks
        self.tts_url = tts_url
        self.error = error
        # A pause between the transcript and the reply, as a real intent stage
        # plus synthesis takes.
        self.delay = delay
        self.tts_error = tts_error
        # A reply per url, for the case of a second tts-end before the first
        # reply has finished playing.
        self.tts_urls = tts_urls if tts_urls is not None else ([tts_url] if tts_url else [])
        self.calls: list[dict] = []
        self.audio: list[bytes] = []

    async def __call__(
        self,
        hass,
        *,
        event_callback,
        stt_stream,
        wake_word_phrase=None,
        start_stage=None,
        end_stage=None,
        **kwargs,
    ) -> None:
        self.calls.append(
            {
                "wake_word_phrase": wake_word_phrase,
                "start_stage": start_stage,
                "end_stage": end_stage,
            }
        )
        event_callback(PipelineEvent(PipelineEventType.RUN_START))
        if self.error is not None:
            event_callback(
                PipelineEvent(PipelineEventType.ERROR, {"code": self.error, "message": self.error})
            )
            event_callback(PipelineEvent(PipelineEventType.RUN_END))
            return
        event_callback(PipelineEvent(PipelineEventType.STT_START))
        async for chunk in stt_stream:
            self.audio.append(chunk)
            if len(self.audio) >= self.chunks:
                break
        event_callback(
            PipelineEvent(PipelineEventType.STT_END, {"stt_output": {"text": "turn it on"}})
        )
        event_callback(PipelineEvent(PipelineEventType.INTENT_START))
        event_callback(
            PipelineEvent(
                PipelineEventType.INTENT_END,
                {"intent_output": {"conversation_id": "01JCVINTENT000000000000A0"}},
            )
        )
        if self.delay:
            await asyncio.sleep(self.delay)
        if not self.tts_urls:
            event_callback(PipelineEvent(PipelineEventType.RUN_END))
            return
        event_callback(PipelineEvent(PipelineEventType.TTS_START))
        if self.tts_error is not None:
            # A run that reached the TTS stage and then failed: no reply.
            event_callback(
                PipelineEvent(
                    PipelineEventType.ERROR,
                    {"code": self.tts_error, "message": self.tts_error},
                )
            )
            event_callback(PipelineEvent(PipelineEventType.RUN_END))
            return
        for url in self.tts_urls:
            event_callback(
                PipelineEvent(
                    PipelineEventType.TTS_END,
                    {"tts_output": {"url": url, "media_id": "media-source://tts/reply"}},
                )
            )
        event_callback(PipelineEvent(PipelineEventType.RUN_END))


class Device:
    """The TLP side of a session: one reader, replies by id, commands queued.

    A real device reads whatever arrives and answers what it must, so the
    reader cannot assume a reply comes before the next command: the
    integration may send a command event and a result in either order.
    """

    def __init__(self, client, paired: Paired) -> None:
        self.client = client
        self.paired = paired
        self.next_id = 2
        self.commands: asyncio.Queue[dict] = asyncio.Queue()
        self._replies: dict[int, asyncio.Future] = {}
        self._reader = asyncio.create_task(self._read())

    async def _read(self) -> None:
        while True:
            frame = await self.client.recv(timeout=30.0)
            if frame["type"] == "event" and "event" in frame:
                self.commands.put_nowait(frame["event"])
                continue
            future = self._replies.pop(frame["id"], None)
            if future is not None and not future.done():
                future.set_result(frame)

    def _id(self) -> int:
        self.next_id += 1
        return self.next_id

    async def send(self, frame: dict, timeout: float = 5.0) -> dict:
        future = asyncio.get_running_loop().create_future()
        self._replies[frame["id"]] = future
        await self.client.send(frame)
        async with asyncio.timeout(timeout):
            return await future

    async def register(self, capabilities: list[str] | None = None) -> None:
        overrides: dict = {"id": self._id(), "device_id": self.paired.device_id}
        if capabilities is not None:
            overrides["capabilities"] = capabilities
        reply = await self.send(frame_fixture("register", **overrides))
        assert reply["success"], reply

    async def subscribe(self) -> None:
        assert (await self.send(frame_fixture("subscribe", id=self._id())))["success"]

    async def command(self, name: str, timeout: float = 5.0) -> dict:
        """The next command event of this name."""
        async with asyncio.timeout(timeout):
            while True:
                command = await self.commands.get()
                if command["command"] == name:
                    return command

    async def ack(self, command: dict, ok: bool = True) -> None:
        reply = await self.send(
            frame_fixture("command_result", id=self._id(), command_id=command["command_id"], ok=ok)
        )
        assert reply["success"], reply

    async def event(self, name: str, data: dict) -> None:
        reply = await self.send(frame_fixture("event", id=self._id(), event=name, data=data))
        assert reply["success"], reply

    async def state(self, **delta: object) -> None:
        reply = await self.send(frame_fixture("state_delta", id=self._id(), state=delta))
        assert reply["success"], reply

    async def audio(self, handler_id: int, frames: int = 4, marker: int = 0) -> None:
        """Send PCM the way the device does: handler byte, then one HA chunk."""
        for index in range(frames):
            payload = bytes([(index + marker) % 256]) * CHUNK
            await self.client.ws.send_bytes(bytes([handler_id]) + payload)

    async def close(self) -> None:
        self._reader.cancel()
        await self.client.close()


async def open_turn(device: Device) -> dict:
    """Fire the wake word and return the ``voice_start`` command."""
    await device.event("wake_word_detected", {"phrase": PHRASE, "probability": 231})
    return await device.command("voice_start")


def session_connection(hass: HomeAssistant):
    """The device's live ActiveConnection (one per registered session)."""
    hub = TabulaHub.get(hass)
    assert hub is not None
    assert len(hub.sessions) == 1
    return next(iter(hub.sessions))


async def test_full_turn_streams_audio_and_plays_the_reply(
    hass_setup, server, hub, session, paired, fake_pipeline
):
    """A detected phrase runs a pipeline on the device's PCM and speaks the reply."""
    pipeline = fake_pipeline(chunks=4)
    client = await ws_connect(session, server, paired.refresh_token)
    device = Device(client, paired)
    await device.register(VOICE_CAPS)
    await device.subscribe()
    await hass_setup.async_block_till_done()

    entity_id = satellite_id(hass_setup, paired)
    assert entity_id is not None
    assert hass_setup.states.get(entity_id).state == "idle"

    # The entity's whole state history, so the turn's stages can be read back.
    seen: list[str] = []

    @callback
    def watch(event: Event) -> None:
        if event.data["entity_id"] == entity_id:
            seen.append(event.data["new_state"].state)

    hass_setup.bus.async_listen("state_changed", watch)

    start = await open_turn(device)
    args = start["args"]
    assert args["start_stage"] == "stt"
    assert args["wake_word_phrase"] == PHRASE
    assert 1 <= args["handler_id"] <= 255
    await device.ack(start)

    await device.audio(args["handler_id"], frames=4, marker=7)
    await until(lambda: len(pipeline.audio) == 4)

    # The fake consumed its chunks and reported the transcript: capture stops.
    stop = await device.command("voice_stop")
    assert stop["args"] == {"turn_id": args["turn_id"], "reason": "ended"}
    await device.ack(stop)
    await device.event("voice_turn_ended", {"turn_id": args["turn_id"], "reason": "command"})
    await device.state(voice_turn="idle")

    # tts-end goes out on the media path, on the device's own connection.
    play = await device.command("play_media")
    assert play["args"]["url"] == TTS_URL
    assert play["args"]["announce"] is False
    await device.ack(play)
    await device.state(media_state="playing")
    await device.event("media_finished", {"reason": "ended"})
    await hass_setup.async_block_till_done()

    # One turn, one phrase, and the audio the pipeline saw is the device's PCM.
    assert pipeline.calls[0]["wake_word_phrase"] == PHRASE
    assert pipeline.calls[0]["start_stage"] is PipelineStage.STT
    assert [len(chunk) for chunk in pipeline.audio] == [CHUNK] * 4
    assert pipeline.audio[0][0] == 7

    await until(lambda: state_of(hass_setup, paired) == "idle")
    for stage in ("listening", "processing", "responding"):
        assert stage in seen, seen

    # The turn's binary handler is gone: nothing is left registered.
    assert all(handler is None for handler in session_connection(hass_setup).binary_handlers)
    await device.close()


async def test_a_duplicate_wake_word_cancels_the_turn(
    hass_setup, server, hub, session, paired, fake_pipeline
):
    """HA's cross-device cooldown rejection ends the turn cleanly, without a reply."""
    fake_pipeline(error="duplicate_wake_up_detected")
    client = await ws_connect(session, server, paired.refresh_token)
    device = Device(client, paired)
    await device.register(VOICE_CAPS)
    await device.subscribe()
    await hass_setup.async_block_till_done()

    start = await open_turn(device)
    assert start["args"]["wake_word_phrase"] == PHRASE
    await device.ack(start)

    stop = await device.command("voice_stop")
    assert stop["args"]["reason"] == "cancelled"
    await device.ack(stop)

    await until(lambda: state_of(hass_setup, paired) == "idle")
    assert all(handler is None for handler in session_connection(hass_setup).binary_handlers)
    # Nothing is played: the turn lost the race, so there is nothing to say.
    await until(lambda: device.commands.empty())
    remaining = []
    while not device.commands.empty():
        remaining.append(device.commands.get_nowait()["command"])
    assert remaining == []
    await device.close()


async def test_announce_plays_through_the_media_path(hass_setup, server, hub, session, paired):
    """``assist_satellite.announce`` reaches the device as play_media commands.

    On HA releases that resolve a pre-announcement the chime goes out first, as
    its own command; the announcement itself is always the last one.
    """
    hass_setup.config.internal_url = server
    client = await ws_connect(session, server, paired.refresh_token)
    device = Device(client, paired)
    await device.register(VOICE_CAPS)
    await device.subscribe()
    await hass_setup.async_block_till_done()

    entity_id = satellite_id(hass_setup, paired)
    assert entity_id is not None
    call = asyncio.create_task(
        hass_setup.services.async_call(
            "assist_satellite",
            "announce",
            {"entity_id": entity_id, "media_id": "/api/tts_proxy/announce.mp3"},
            blocking=True,
        )
    )
    urls = []
    # HA 2026.x has the pre-announcement field; the 2025.1 floor does not.
    if "preannounce_media_id" in AssistSatelliteAnnouncement.__dataclass_fields__:
        pre = await device.command("play_media")
        assert pre["args"]["announce"] is True
        assert pre["args"]["url"].endswith(PREANNOUNCE_FILENAME)
        await device.ack(pre)
        urls.append(pre["args"]["url"])
    play = await device.command("play_media")
    assert play["args"]["announce"] is True
    assert play["args"]["url"].endswith("/api/tts_proxy/announce.mp3")
    await device.ack(play)
    urls.append(play["args"]["url"])
    await call
    assert len(urls) in (1, 2)
    assert urls[-1].endswith("/api/tts_proxy/announce.mp3")
    await device.state(media_state="playing")
    await until(lambda: state_of(hass_setup, paired) == "idle")
    await device.close()


async def test_a_second_reply_replaces_the_one_still_playing(
    hass_setup, server, hub, session, paired, fake_pipeline
):
    """A tts-end arriving while the previous reply plays is not dropped."""
    other_url = "/api/tts_proxy/reply-2.mp3"
    fake_pipeline(chunks=1, tts_urls=[TTS_URL, other_url])
    client = await ws_connect(session, server, paired.refresh_token)
    device = Device(client, paired)
    await device.register(VOICE_CAPS)
    await device.subscribe()
    await hass_setup.async_block_till_done()

    start = await open_turn(device)
    await device.ack(start)
    await device.audio(start["args"]["handler_id"], frames=1)
    stop = await device.command("voice_stop")
    await device.ack(stop)

    first = await device.command("play_media")
    assert first["args"]["url"] == TTS_URL
    await device.ack(first)
    # The first reply is still open (no media_finished): the second must play.
    second = await device.command("play_media")
    assert second["args"]["url"] == other_url
    await device.ack(second)
    await device.event("media_finished", {"reason": "ended"})
    await until(lambda: state_of(hass_setup, paired) == "idle")
    await device.close()


async def test_a_disconnect_mid_turn_leaks_nothing(
    hass_setup, server, hub, session, paired, fake_pipeline
):
    """The socket dies with a turn open: the turn and its handler go with it."""
    pipeline = fake_pipeline(chunks=8)
    client = await ws_connect(session, server, paired.refresh_token)
    device = Device(client, paired)
    await device.register(VOICE_CAPS)
    await device.subscribe()
    await hass_setup.async_block_till_done()

    start = await open_turn(device)
    await device.ack(start)
    connection = session_connection(hass_setup)
    await device.audio(start["args"]["handler_id"], frames=2)
    await until(lambda: len(pipeline.audio) == 2)

    await device.close()
    await until(lambda: state_of(hass_setup, paired) == "unavailable")
    await hass_setup.async_block_till_done()

    assert all(handler is None for handler in connection.binary_handlers)
    hub_runtime = TabulaHub.get(hass_setup)
    assert hub_runtime is not None
    assert hub_runtime.devices[paired.device_id].current is None


async def test_the_device_timing_out_ends_the_turn(
    hass_setup, server, hub, session, paired, fake_pipeline
):
    """The device's own turn limit (``voice_turn_ended`` timeout) closes our side."""
    pipeline = fake_pipeline(chunks=8)
    client = await ws_connect(session, server, paired.refresh_token)
    device = Device(client, paired)
    await device.register(VOICE_CAPS)
    await device.subscribe()
    await hass_setup.async_block_till_done()

    start = await open_turn(device)
    await device.ack(start)
    connection = session_connection(hass_setup)
    await device.audio(start["args"]["handler_id"], frames=1)
    await until(lambda: len(pipeline.audio) == 1)

    await device.event(
        "voice_turn_ended", {"turn_id": start["args"]["turn_id"], "reason": "timeout"}
    )
    stop = await device.command("voice_stop")
    assert stop["args"]["reason"] == "cancelled"
    await device.ack(stop)

    await until(lambda: all(handler is None for handler in connection.binary_handlers))
    assert state_of(hass_setup, paired) == "idle"
    await device.close()


async def test_no_entity_without_the_voice_capability(hass_setup, server, hub, session, paired):
    """A device that cannot capture never gets a satellite (F-HA-02)."""
    client = await ws_connect(session, server, paired.refresh_token)
    device = Device(client, paired)
    await device.register()  # the golden register frame: no voice
    await device.subscribe()
    await hass_setup.async_block_till_done()

    assert satellite_id(hass_setup, paired) is None
    await device.close()


async def test_two_devices_run_turns_in_parallel(
    hass_setup, server, hub, session, paired, monkeypatch
):
    """Each tablet has its own satellite, its own handler byte and its own PCM."""
    first = FakePipeline(chunks=2)
    second = FakePipeline(chunks=2)
    pending = [first, second]

    async def route(hass, **kwargs):
        # Turns are opened in order below, so the nth call is the nth device.
        await pending.pop(0)(hass, **kwargs)

    monkeypatch.setattr(
        "homeassistant.components.assist_satellite.entity.async_pipeline_from_audio_stream",
        route,
    )
    other = await pair_device(hass_setup, server, uid())
    devices = []
    try:
        for one in (paired, other):
            client = await ws_connect(session, server, one.refresh_token)
            device = Device(client, one)
            await device.register(VOICE_CAPS)
            await device.subscribe()
            devices.append(device)
        await hass_setup.async_block_till_done()
        assert satellite_id(hass_setup, paired) is not None
        assert satellite_id(hass_setup, other) is not None

        starts = []
        for device in devices:
            start = await open_turn(device)
            starts.append(start)
            await device.ack(start)
        for index, device in enumerate(devices):
            await device.audio(starts[index]["args"]["handler_id"], frames=2, marker=index)
        await until(lambda: len(first.audio) == 2 and len(second.audio) == 2)

        # Two independent handler tables: each connection's first id is 1, and
        # each pipeline saw only its own device's PCM.
        assert [s["args"]["handler_id"] for s in starts] == [1, 1]
        assert first.audio[0][0] == 0
        assert second.audio[0][0] == 1
        assert first.calls[0]["wake_word_phrase"] == PHRASE
        assert second.calls[0]["wake_word_phrase"] == PHRASE
    finally:
        for device in devices:
            await device.close()


async def test_a_full_turn_against_the_mock_device(
    hass_setup, server, hub, session, tmp_path, fake_pipeline
):
    """The real mock device hears its wake word, streams PCM and plays the reply."""
    pipeline = fake_pipeline(chunks=3)
    paired = await pair_device(hass_setup, server, uid())
    store = DeviceStore(
        tmp_path / "voice.json",
        device_id=paired.device_id,
        refresh_token=paired.refresh_token,
        ha_device_id=paired.ha_device_id,
    )
    store.save()
    device = MockDevice(capabilities=ALL_CAPABILITIES)
    # On-device wake word on, as the settings push would leave it (P7.1).
    device.settings = {"voice": {"wake_word_enabled": True}}
    registered = asyncio.Event()
    client = TLPClient(server, store, device, on_registered=lambda result: registered.set())
    task = asyncio.create_task(_run_client(client))
    try:
        await asyncio.wait_for(registered.wait(), 10)
        await hass_setup.async_block_till_done()
        await until(lambda: satellite_id(hass_setup, paired) is not None)

        client.announce_wake_word(PHRASE)
        await until(lambda: len(pipeline.audio) == 3, timeout=10)
        assert all(len(chunk) == CHUNK for chunk in pipeline.audio)
        assert pipeline.calls[0]["wake_word_phrase"] == PHRASE

        # The integration stops the microphone and the device stops streaming.
        await until(lambda: device.voice_turn is None, timeout=10)
        await until(lambda: len(device.played) == 1, timeout=10)
        assert device.played[0]["url"] == TTS_URL
        # The mock never finishes a track by itself; stopping it is what a
        # device does when playback ends, and the entity returns to idle.
        await device.handle("media_control", {"action": "stop"})
        await until(lambda: state_of(hass_setup, paired) == "idle", timeout=10)
        await hass_setup.async_block_till_done()
        assert device.state["voice_turn"] == "idle"
        assert client.voice_frames_sent >= 3
    finally:
        client.stop()
        await asyncio.wait_for(task, 10)


async def _run_client(client: TLPClient) -> None:
    async with aiohttp.ClientSession() as session:
        await client.run(session)


async def test_the_turn_ended_echo_does_not_cancel_the_reply(
    hass_setup, server, hub, session, paired, fake_pipeline
):
    """The device's ``command`` echo must not close the turn before tts-end.

    PROTOCOL 4.10 makes the device answer ``voice_stop`` with
    ``voice_turn_ended`` reason ``command``. That arrives long before a slow
    intent stage and synthesis finish, and the reply is the point of the turn.
    """
    fake_pipeline(chunks=1, delay=0.3)
    client = await ws_connect(session, server, paired.refresh_token)
    device = Device(client, paired)
    await device.register(VOICE_CAPS)
    await device.subscribe()
    await hass_setup.async_block_till_done()

    start = await open_turn(device)
    await device.ack(start)
    await device.audio(start["args"]["handler_id"], frames=1)
    stop = await device.command("voice_stop")
    await device.ack(stop)
    # The device answers the stop at once, as it does on real hardware.
    await device.event(
        "voice_turn_ended", {"turn_id": start["args"]["turn_id"], "reason": "command"}
    )

    play = await device.command("play_media", timeout=5.0)
    assert play["args"]["url"] == TTS_URL
    await device.ack(play)
    await device.event("media_finished", {"reason": "ended"})
    await until(lambda: state_of(hass_setup, paired) == "idle")
    await device.close()


async def test_an_error_after_tts_start_returns_to_idle(
    hass_setup, server, hub, session, paired, fake_pipeline
):
    """A pipeline that reaches tts-start and then fails leaves nothing hanging."""
    fake_pipeline(chunks=1, tts_error="tts_failed")
    client = await ws_connect(session, server, paired.refresh_token)
    device = Device(client, paired)
    await device.register(VOICE_CAPS)
    await device.subscribe()
    await hass_setup.async_block_till_done()

    entity_id = satellite_id(hass_setup, paired)
    start = await open_turn(device)
    await device.ack(start)
    await device.audio(start["args"]["handler_id"], frames=1)
    await device.ack(await device.command("voice_stop"))

    # HA leaves a run that started TTS in ``responding`` at run-end, so the
    # reset has to come from the integration.
    await until(lambda: state_of(hass_setup, paired) == "idle")
    assert hass_setup.states.get(entity_id).state == "idle"
    await hass_setup.async_block_till_done()
    sent: list[str] = []
    while not device.commands.empty():
        sent.append(device.commands.get_nowait()["command"])
    assert "play_media" not in sent
    assert all(handler is None for handler in session_connection(hass_setup).binary_handlers)
    await device.close()
