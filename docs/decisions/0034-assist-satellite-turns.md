# 0034: The assist satellite turn driver

Status: accepted
Date: 2026-11-24
Packet: P7.3

## Context

ADR 0024 fixed the shape: voice is a native `assist_satellite` entity in the `tabula` integration, the device's PCM rides the authenticated WebSocket as binary frames (ADR 0033), the device runs its own wake word, and replies play through the existing media path. P7.3 had to fill in how a turn is actually driven, and HA's base class leaves several of those choices to the integration.

Read from HA core 2025.1.0 (our floor), 2026.2.3 (our pin) and 2026.10.4, Apache-2.0:

- `AssistSatelliteEntity.async_accept_pipeline_from_satellite(audio_stream, start_stage, end_stage, wake_word_phrase)` runs the pipeline and awaits it; the subclass supplies the audio as an `AsyncIterable[bytes]` and receives every stage in `on_pipeline_event`.
- The entity's `state` is a `@final` property over an internal machine that the base class advances from pipeline events (`listening` at STT start, `processing` at intent start, `responding` at TTS start, and back to `idle` at `run-end` without TTS or when `tts_response_finished()` is called).
- `ActiveConnection.async_register_binary_handler(handler)` returns a one-byte id and an unregister callback; the handler is called as `handler(hass, connection, payload)` with the handler byte already stripped. 2025.1 and 2026.2.3 agree on that signature.
- `AssistSatelliteEntityFeature` has `ANNOUNCE` at the floor; `START_CONVERSATION` was added later, and with it the `async_start_conversation` hook. `async_get_configuration` / `async_set_configuration` exist at the floor but only the assist_satellite WebSocket API reads them.
- HA's duplicate-wake-word cooldown raises `DuplicateWakeUpDetectedError`, which surfaces as a pipeline `error` event whose `code` is `duplicate_wake_up_detected`.

## Decision

1. **The integration starts every turn; the device never does.** A `wake_word_detected` event (P7.1) opens a turn: register the binary handler, send `voice_start` (`start_stage` `stt`, the phrase), then run the pipeline over the handler's PCM. This is the only order the protocol allows (PROTOCOL 4.10) and it keeps the handler alive before any audio can arrive.

2. **The pipeline's stream is a plain async iterator, not an async generator.** A turn can close while the pipeline is no longer reading (it has its transcript), and a suspended generator would need an `aclose()` from a callback that cannot await. Closing the stream wakes a pending read with `StopAsyncIteration`. Its queue is deliberately unbounded: HA's consumer buffers too, and a slow consumer then costs memory rather than dropping audio, bounded by the device's own `VOICE_TURN_MAX_S` (about 10 MB at worst).

3. **Stages map onto the turn.** STT end stops capture: `voice_stop` `ended`, handler unregistered, stream closed — the microphone is not needed for the reply, and closing it early is what keeps a reply from being captured. TTS end plays `tts_output.url` with `play_media` (`announce` false), the same media path as any other audio (ADR 0024 item 4). A pipeline `error` cancels the turn: the handler goes, `voice_stop` `cancelled` goes out, and the duplicate-wake-word code is logged as the expected outcome rather than a failure. `run-end` closes whatever is still open.

4. **Every exit closes the turn, and closing is idempotent.** Turn end (`run-end`), the device's own `voice_turn_ended` with reason `timeout` or `error`, a superseded or closed connection, and entity removal all run the same close: stream closed, handler unregistered, `voice_stop` sent when the connection that opened the turn is still the current one, pipeline task cancelled when the audio stopped early.

   The reason `command` is **not** an exit. It is the device acknowledging the `voice_stop` we sent at `stt-end` (PROTOCOL 4.10), it arrives within one round trip, and a real intent stage plus synthesis takes longer than that — closing the turn on it would drop the reply that is the point of the turn. Capture is already closed by then, so there is nothing left for the echo to do; `run-end` closes the turn after the reply is scheduled.

   However the close was reached, it cannot leave the entity `listening` or `responding`: HA's base class only returns to idle on a `run-end` it produces itself, and skips even that when the run reached TTS. So the close calls `tts_response_finished()` — HA's own "back to idle" hook — unless a reply is still pending, in which case that reply resets the entity when it finishes. This holds for a cancelled pipeline, a pipeline error after `tts-start`, a failed `voice_start`, and a disconnect.

5. **The entity is available exactly when the device is, and the entity's state is HA's.** `state` is final in the base class, so the turn's stages drive it; the device's `voice_turn` key is the device's own account of the microphone and is not a second state. The wake-word phrase and the pipeline come from HA's standard satellite configuration: no wake word is offered for selection, because the phrase belongs to the device's model (ADR 0029, 0030) and HA is told which one fired instead of choosing it.

6. **ANNOUNCE returns when the device accepts playback, and plays the pre-announcement first.** The reply wait is bounded by `VOICE_REPLY_TIMEOUT` (ADR 0002) because the device owns playback and the integration cannot compute a media duration; an announcement has no such bound, so `async_announce` returns once `play_media` is acknowledged and the device reports the end on the bus. HA 2026.x also resolves a pre-announcement chime (`preannounce_media_id`) and expects the satellite to play it before the message, so it goes out as its own `play_media` first; on 2025.1 the field does not exist and only the message plays. `START_CONVERSATION` is offered only when the running HA's enum has it, and its hook plays the start message then opens a turn for the reply. A second `tts-end` while a reply is still being awaited replaces the waiting reply rather than being dropped, so a turn that starts during a previous reply is not left unanswered. One known and accepted limit: `media_finished` carries no reply identity, so after a replacement the superseded reply's end can release the new one's wait a little early — state accuracy of a second or two, never a lost reply.

7. **The platform's HA dependencies come from the release, not from a pin.** Importing `assist_satellite` pulls in HA's conversation and audio-pipeline code, which needs `hassil`, `home-assistant-intents`, `pymicro-vad` and `pyspeex-noise` — all in HA's `requirements.txt`, none declared by the wheel — and on 2026.10 `gazetteer-matcher` as well. The dev group pins them for 2026.2.3 and `tools/ha_matrix.sh` installs each leg's own pins from that release's `package_constraints.txt`, so one release's pin cannot break another leg.

## Consequences

- `integration/custom_components/tabula/assist_satellite.py` is the Phase 7 base that voice-started intercom (P9.6) builds on: it owns the turn lifetime, not the audio path.
- The test harness sets up the core `homeassistant` component and `assist_satellite`'s dependency chain, because forwarding the platform happens on every hub setup, not only on voice tests.
- A device that drops mid-turn leaves nothing registered: the handler table on its connection is empty and the pipeline task is cancelled.
- The entity reports `responding` until the device reports the reply finished or a minute passes; a lost `media_finished` costs state accuracy, not a stuck turn.
- Adding HA releases to the matrix means adding nothing here, but changing the four dependency pins is now a per-release chore that the matrix script does for itself.

## Alternatives considered

- **Let the device open a turn after its own detection.** Would save one round trip, but `voice_start` is the only frame that carries the handler id, so the device would have to invent one and the integration would have to trust it. Rejected in ADR 0033 as well.
- **Await the whole pipeline inside the wake-word callback.** The callback is a bus listener; HA would block the event bus while STT and TTS run. The turn runs as a background task on the entry instead.
- **Block `async_announce` until `media_finished`.** Closer to the base class's contract, but there is no defensible bound for arbitrary media, and a lost event would hold HA's announce service open indefinitely.
- **Override `state` to follow `voice_turn`.** Not possible (`@final`) and not desirable: two sources for one state would disagree during the reply, when the microphone is closed and the speaker is not.
- **Pin hassil and friends once for every HA release.** The pins differ per release (hassil 2.1.0 at the floor, 3.5.0 at the pin, 3.12.1 at the newest), so a single pin would fail two legs of the matrix.
