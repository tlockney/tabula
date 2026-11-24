"""Voice turns as a native ``assist_satellite`` entity (P7.3; ADR 0024, ADR 0034).

The device hears its wake word itself (P7.1) and reports ``wake_word_detected``;
it cannot open a turn on its own, because only ``voice_start`` carries the HA
binary handler id (PROTOCOL.md 4.10, ADR 0033 item 3). So the integration drives
the turn:

1. a detection starts a turn: register a binary handler on the device's
   connection, send ``voice_start``, then hand the handler's PCM to HA's
   pipeline through ``async_accept_pipeline_from_satellite``;
2. pipeline events map back to TLP: the transcript ends capture (``voice_stop``),
   ``tts-end`` plays the reply on the device's media path, an error or a
   duplicate wake word cancels the turn without leaking the handler;
3. the turn is closed on ``run-end``, the device's own ``voice_turn_ended``,
   a disconnect, or entity removal. Nothing is left registered in any case.

Everything is gated on the ``voice`` capability: a device that cannot capture
never gets the entity (F-HA-02), and the entity is unavailable while the device
is offline like every other Tabula entity.
"""

from __future__ import annotations

import asyncio
import logging
from collections.abc import Callable
from dataclasses import dataclass, field
from typing import Any

import yarl
from homeassistant.components.assist_pipeline import (
    PipelineEvent,
    PipelineEventType,
    PipelineStage,
)
from homeassistant.components.assist_satellite import (
    AssistSatelliteAnnouncement,
    AssistSatelliteConfiguration,
    AssistSatelliteEntity,
    AssistSatelliteEntityFeature,
)
from homeassistant.config_entries import ConfigEntry
from homeassistant.core import Event, HomeAssistant, callback
from homeassistant.exceptions import HomeAssistantError
from homeassistant.helpers.entity_platform import AddEntitiesCallback
from homeassistant.util.ulid import ulid_now

from . import DeviceRecord, DeviceSession, TabulaHub
from .const import DOMAIN, VOICE_REPLY_TIMEOUT
from .entity import TabulaEntity, TabulaEntityDescription, setup_platform

_LOGGER = logging.getLogger(__name__)

SATELLITE = TabulaEntityDescription(
    key="satellite",
    translation_key="satellite",
    capabilities=frozenset({"voice"}),
    # A turn opening or closing, and the device starting or finishing playback.
    state_keys=frozenset({"voice_turn", "media_state"}),
)

# ANNOUNCE is at the 2025.1 floor; START_CONVERSATION arrived later. The flag is
# taken from the running HA's enum, never from a version string (ADR 0024
# decision 5), so the older release simply never offers the feature.
START_CONVERSATION = AssistSatelliteEntityFeature(
    getattr(AssistSatelliteEntityFeature, "START_CONVERSATION", 0)
)
FEATURES = AssistSatelliteEntityFeature.ANNOUNCE | START_CONVERSATION

# HA's code for "another satellite answered this wake word first" (assist_pipeline
# DuplicateWakeUpDetectedError). The cooldown is HA's, so this is the only thing
# a rejected turn reports.
DUPLICATE_WAKE_WORD = "duplicate_wake_up_detected"


class AudioStream:
    """One turn's PCM, as the async iterator the pipeline consumes.

    A plain iterator rather than an async generator: a turn can close while the
    pipeline is no longer reading (it has its transcript), and a generator left
    suspended would need an explicit ``aclose`` from a callback that cannot
    await. ``close()`` wakes a pending read with ``StopAsyncIteration``.
    """

    def __init__(self) -> None:
        self._chunks: asyncio.Queue[bytes | None] = asyncio.Queue()
        self.closed = False

    def feed(self, chunk: bytes) -> None:
        """One binary frame's payload from the device's handler."""
        if not self.closed:
            self._chunks.put_nowait(chunk)

    def close(self) -> None:
        """No more audio: the pipeline's stream ends at the next read."""
        if not self.closed:
            self.closed = True
            self._chunks.put_nowait(None)

    def __aiter__(self) -> AudioStream:
        return self

    async def __anext__(self) -> bytes:
        chunk = await self._chunks.get()
        if chunk is None:
            raise StopAsyncIteration
        return chunk


@dataclass
class Turn:
    """One open voice turn: its connection, handler byte and audio."""

    turn_id: str
    session: DeviceSession
    handler_id: int
    unregister: Callable[[], None]
    phrase: str | None
    audio: AudioStream = field(default_factory=AudioStream)
    task: asyncio.Task | None = None


class TabulaSatellite(TabulaEntity, AssistSatelliteEntity):
    """The tablet as an Assist satellite: one turn at a time, no audio locally."""

    _attr_supported_features = FEATURES

    def __init__(
        self, hub: TabulaHub, record: DeviceRecord, description: TabulaEntityDescription
    ) -> None:
        super().__init__(hub, record, description)
        self._turn: Turn | None = None
        # Resolved when the device reports the reply finished (media_finished or
        # media_state leaving playing); bounded by VOICE_REPLY_TIMEOUT.
        self._reply: asyncio.Future | None = None
        self._reply_playing = False

    # --- HA satellite configuration ----------------------------------------

    @callback
    def async_get_configuration(self) -> AssistSatelliteConfiguration:
        """No wake words are configurable from HA.

        The phrase belongs to the device's own model (ADR 0029/0030); HA is told
        which one fired on each turn instead of choosing it, so there is nothing
        to list or activate here.
        """
        return AssistSatelliteConfiguration(
            available_wake_words=[], active_wake_words=[], max_active_wake_words=0
        )

    async def async_set_configuration(self, config: AssistSatelliteConfiguration) -> None:
        """Refuse a wake-word selection: the device owns its phrase."""
        if config.active_wake_words:
            raise HomeAssistantError(
                translation_domain=DOMAIN, translation_key="wake_word_not_configurable"
            )

    # --- lifecycle ----------------------------------------------------------

    async def async_added_to_hass(self) -> None:
        await super().async_added_to_hass()
        self.async_on_remove(self.hass.bus.async_listen("tabula_wake_word", self._on_wake_word))
        self.async_on_remove(self.hass.bus.async_listen("tabula_voice", self._on_voice_event))
        self.async_on_remove(self.hass.bus.async_listen("tabula_media", self._on_media_event))

    async def async_will_remove_from_hass(self) -> None:
        self._close_turn("cancelled", cancel_pipeline=True)
        await super().async_will_remove_from_hass()

    @callback
    def _handle_update(self, keys: set[str] | None) -> None:
        """Refresh on our state keys, and drop a turn whose connection went away."""
        if keys is None and (turn := self._turn) is not None:
            record = self.record
            current = record.current if record is not None else None
            if current is None or current is not turn.session:
                self._close_turn("cancelled", cancel_pipeline=True)
        if keys is not None and "media_state" in keys:
            self._track_playback(self.device_state.get("media_state"))
        super()._handle_update(keys)

    # --- starting a turn ----------------------------------------------------

    @callback
    def _on_wake_word(self, event: Event) -> None:
        """A detection on this device opens a turn (PROTOCOL.md 4.10)."""
        if event.data.get("tabula_device_id") != self.device_id:
            return
        self._start_turn(event.data.get("phrase"))

    @callback
    def _start_turn(self, phrase: str | None) -> None:
        if not self.available:
            return
        if self._turn is not None:
            # One turn per device: the handler byte identifies a single turn.
            _LOGGER.debug("Wake word ignored on %s: a turn is open", self.device_id[:8])
            return
        assert self.platform.config_entry is not None
        self.platform.config_entry.async_create_background_task(
            self.hass, self._async_begin_turn(phrase), f"{self.entity_id}_turn"
        )

    async def _async_begin_turn(self, phrase: str | None) -> None:
        """Register the handler, ask the device to start, then run the pipeline."""
        record = self.record
        session = record.current if record is not None else None
        if record is None or session is None or self._turn is not None:
            return
        turn = self._open_turn(session, phrase)
        args: dict[str, Any] = {
            "turn_id": turn.turn_id,
            "handler_id": turn.handler_id,
            "start_stage": "stt",
        }
        if phrase is not None:
            # The phrase rides along so HA's cross-device cooldown applies
            # (ADR 0024 decision 3).
            args["wake_word_phrase"] = phrase
        try:
            await self.hub.async_command(record, "voice_start", args)
        except HomeAssistantError as err:
            _LOGGER.warning("voice_start to %s failed: %s", self.device_id[:8], err)
            self._close_turn("cancelled", cancel_pipeline=False)
            return
        assert self.platform.config_entry is not None
        turn.task = self.platform.config_entry.async_create_background_task(
            self.hass, self._async_run_pipeline(turn), f"{self.entity_id}_pipeline"
        )

    def _open_turn(self, session: DeviceSession, phrase: str | None) -> Turn:
        """Register this turn's binary handler and record the turn."""
        audio = AudioStream()

        def handler(_hass: HomeAssistant, _connection: Any, payload: bytes) -> None:
            audio.feed(payload)

        handler_id, unregister = session.connection.async_register_binary_handler(handler)
        turn = Turn(
            turn_id=ulid_now(),
            session=session,
            handler_id=handler_id,
            unregister=unregister,
            phrase=phrase,
            audio=audio,
        )
        self._turn = turn
        _LOGGER.debug("Voice turn %s opened on %s", turn.turn_id, self.device_id[:8])
        return turn

    async def _async_run_pipeline(self, turn: Turn) -> None:
        try:
            await self.async_accept_pipeline_from_satellite(
                turn.audio,
                start_stage=PipelineStage.STT,
                wake_word_phrase=turn.phrase,
            )
        except asyncio.CancelledError:
            raise
        except Exception:  # a pipeline failure ends the turn, not the integration
            _LOGGER.exception("Voice pipeline on %s failed", self.device_id[:8])
        finally:
            self._close_turn("ended", cancel_pipeline=False)

    # --- pipeline events ----------------------------------------------------

    @callback
    def on_pipeline_event(self, event: PipelineEvent) -> None:
        """Map the pipeline's stages onto the turn and the device."""
        turn = self._turn
        if turn is None:
            return
        if event.type is PipelineEventType.ERROR:
            code = (event.data or {}).get("code", "")
            if code == DUPLICATE_WAKE_WORD:
                _LOGGER.info(
                    "Wake word %s on %s was answered by another satellite; turn %s cancelled",
                    turn.phrase,
                    self.device_id[:8],
                    turn.turn_id,
                )
            else:
                _LOGGER.warning(
                    "Voice turn %s on %s failed: %s", turn.turn_id, self.device_id[:8], code
                )
            self._close_turn("cancelled", cancel_pipeline=False)
        elif event.type is PipelineEventType.STT_END:
            # The transcript is in; the microphone is not needed any more and
            # closing it now keeps the device from streaming into the reply.
            self._close_capture(turn, "ended")
        elif event.type is PipelineEventType.TTS_END:
            url = ((event.data or {}).get("tts_output") or {}).get("url")
            if url:
                self._schedule_reply(url)
        elif event.type is PipelineEventType.RUN_END:
            self._close_turn("ended", cancel_pipeline=False)

    # --- closing a turn -----------------------------------------------------

    @callback
    def _on_voice_event(self, event: Event) -> None:
        """The device's own account of a turn ending (PROTOCOL.md 4.7)."""
        turn = self._turn
        if turn is None or event.data.get("tabula_device_id") != self.device_id:
            return
        if event.data.get("type") != "voice_turn_ended":
            return
        if event.data.get("turn_id") != turn.turn_id:
            return
        reason = event.data.get("reason")
        if reason == "command":
            # The echo of our own voice_stop (PROTOCOL.md 4.10), which already
            # closed capture at stt-end. It says nothing we did not send, and
            # the turn is still open for the reply: run-end closes it.
            return
        # timeout and error mean the device's audio stopped early, so the
        # pipeline has nothing left to read.
        self._close_turn("cancelled", cancel_pipeline=reason in ("timeout", "error"))

    @callback
    def _close_turn(self, reason: str, *, cancel_pipeline: bool) -> None:
        """Close the open turn: capture, handler and pipeline task."""
        turn = self._turn
        if turn is None:
            return
        self._turn = None
        self._close_capture(turn, reason)
        task = turn.task
        if (
            cancel_pipeline
            and task is not None
            and task is not asyncio.current_task()
            and not task.done()
        ):
            task.cancel()
        # However the turn ended, it cannot leave the entity listening or
        # responding: HA's base class only goes idle on a run-end it produces
        # itself, and skips that when the run started TTS. A reply still on its
        # way resets the entity when it finishes (ADR 0034 item 4).
        if self._reply is None or self._reply.done():
            self.tts_response_finished()
        _LOGGER.debug("Voice turn %s closed (%s)", turn.turn_id, reason)

    @callback
    def _close_capture(self, turn: Turn, reason: str) -> None:
        """Stop the microphone on the device and unregister this turn's handler.

        Idempotent: a turn whose capture is already closed (the transcript
        arrived, the connection dropped) sends nothing twice.
        """
        if turn.audio.closed:
            return
        turn.audio.close()
        turn.unregister()
        record = self.record
        if record is None or record.current is not turn.session:
            return
        assert self.platform.config_entry is not None
        self.platform.config_entry.async_create_background_task(
            self.hass, self._async_voice_stop(turn.turn_id, reason), f"{self.entity_id}_stop"
        )

    async def _async_voice_stop(self, turn_id: str, reason: str) -> None:
        try:
            await self.async_command("voice_stop", {"turn_id": turn_id, "reason": reason})
        except HomeAssistantError as err:
            # The turn is over either way; the device times out on its own
            # (ADR 0033 item 4).
            _LOGGER.debug("voice_stop for %s failed: %s", turn_id, err)

    # --- the reply and announcements ---------------------------------------

    @callback
    def _schedule_reply(self, url: str) -> None:
        """Play one reply, replacing any reply still being awaited.

        A second ``tts-end`` while the first reply is open (a turn started while
        the previous reply still plays) must not be dropped: the newest reply is
        the one the user is waiting for, and the superseded one is over (ADR
        0034 item 6).
        """
        self._resolve_reply()
        reply = self.hass.loop.create_future()
        self._reply = reply
        self._reply_playing = False
        assert self.platform.config_entry is not None
        self.platform.config_entry.async_create_background_task(
            self.hass, self._async_play_reply(url, reply), f"{self.entity_id}_reply"
        )

    async def _async_play_reply(self, url: str, reply: asyncio.Future) -> None:
        """Play the pipeline's reply, then let the entity return to idle."""
        try:
            await self._async_play_url(url, announce=False)
            await self._async_wait_for_playback(reply)
        except HomeAssistantError as err:
            _LOGGER.warning("Reply to %s did not play: %s", self.device_id[:8], err)
        except TimeoutError:
            _LOGGER.debug(
                "Reply on %s reported no end within %ss", self.device_id[:8], VOICE_REPLY_TIMEOUT
            )
        finally:
            # Only the reply that is still current resets the entity: a newer
            # one may already have taken over (see _schedule_reply).
            if self._reply is reply:
                self._reply = None
                self._reply_playing = False
                self.tts_response_finished()

    async def _async_play_url(self, url: str, *, announce: bool) -> None:
        """Fetch and play one URL through the device's media path (ADR 0024 item 4)."""
        args = {"url": str(yarl.URL(url)), "media_type": "audio", "announce": announce}
        await self.async_command("play_media", args)

    async def _async_wait_for_playback(self, reply: asyncio.Future) -> None:
        async with asyncio.timeout(VOICE_REPLY_TIMEOUT):
            await asyncio.shield(reply)

    @callback
    def _on_media_event(self, event: Event) -> None:
        """The device finished the reply (PROTOCOL.md 4.7, media_finished)."""
        if event.data.get("tabula_device_id") != self.device_id:
            return
        self._resolve_reply()

    @callback
    def _track_playback(self, media_state: Any) -> None:
        """Return to idle when the device's media leaves ``playing``.

        A device reports ``media_finished`` too; either signal ends the wait, so
        a lost event cannot leave the entity responding until the timeout.
        """
        if media_state == "playing":
            self._reply_playing = True
        elif self._reply_playing:
            self._reply_playing = False
            self._resolve_reply()

    @callback
    def _resolve_reply(self) -> None:
        if self._reply is not None and not self._reply.done():
            self._reply.set_result(None)

    async def async_announce(self, announcement: AssistSatelliteAnnouncement) -> None:
        """Play an announcement on the device's media path.

        HA 2026.x resolves a pre-announcement (a short chime) and expects the
        satellite to play it first; where the running HA has no such field the
        lookup is empty and only the announcement plays.

        Returns once the device accepts playback: the reply's end is the
        device's to report, and there is no length bound to wait against for
        arbitrary announcement media (ADR 0034 item 6).
        """
        preannounce = getattr(announcement, "preannounce_media_id", None)
        if preannounce:
            await self._async_play_url(preannounce, announce=True)
        await self._async_play_url(announcement.media_id, announce=True)

    async def async_start_conversation(
        self, start_announcement: AssistSatelliteAnnouncement
    ) -> None:
        """Play the start message, then open a turn for the user's reply.

        Only reachable on HA releases that have ``START_CONVERSATION``.
        """
        await self.async_announce(start_announcement)
        await self._async_begin_turn(None)


async def async_setup_entry(
    hass: HomeAssistant, entry: ConfigEntry, async_add_entities: AddEntitiesCallback
) -> None:
    hub = TabulaHub.get(hass)
    assert hub is not None
    entry.async_on_unload(
        setup_platform(hass, hub, async_add_entities, [SATELLITE], TabulaSatellite)
    )
