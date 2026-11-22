# 0024: Voice satellite through a native `assist_satellite` entity

Status: accepted
Date: 2026-11-22
Packet: none (Phase 7 approach; P7.0 follows)

## Context

Phase 7 (PLAN 7.4) parks voice behind a Fire OS mic spike and names the shape only loosely: an `assist_satellite` entity, audio through the Assist pipeline API, wake word on the device. ADR 0001 item 7 recorded the base-class requirements and left one question open: TLP frames are JSON, so audio needs a binary channel.

Since that plan was written:

- The Echo Show 8's v1 status is under review (ADR 0007). If it runs in v1, it may do so on an OS other than Fire OS. It has a mic array and, off Fire OS, would not have mic-permission revocation, so it may be the best voice device we have. The P7.0 gate covers only Fire OS.
- FEATURES.md lists wake-word configuration, wake events and a microphone handoff as dropped with voice. The architect designs those here and in Phase 7 packets as independent design.

HA offers three ways for a device to take part in Assist. All facts below were read from HA core 2025.1.0 (our floor, ADR 0001 item 8), Apache-2.0:

- **Own `assist_satellite` entity.** The integration subclasses `AssistSatelliteEntity` and feeds PCM to `async_accept_pipeline_from_satellite(audio_stream, start_stage, end_stage, wake_word_phrase)`. Pipeline events (`run-start`, `wake_word-*`, `stt-*`, `intent-*`, `tts-start`, `tts-end` with the TTS URL, `run-end`, `error`) arrive in `on_pipeline_event`. At 2025.1 the only optional feature is `ANNOUNCE`; `START_CONVERSATION` arrived in a later release. Voice timers reach a device through `intent.async_register_timer_handler(hass, device_id, handler)`, the same hook HA's ESPHome and Wyoming satellites use. Audio format is 16 kHz, 16-bit, mono (`assist_pipeline/const.py`).
- **Wyoming.** The device runs a Wyoming satellite server on a TCP port; HA's `wyoming` integration connects to it and creates the satellite entity.
- **Direct pipeline calls.** The app calls the `assist_pipeline/run` WebSocket command itself, as the HA Companion app does. There is no satellite entity.

HA's WebSocket API also carries binary messages: `ActiveConnection.async_register_binary_handler(handler)` returns a one-byte handler ID, and binary frames whose first byte is that ID go to the handler (present at 2025.1; `assist_pipeline/run` uses it). Our device already holds an authenticated `/api/websocket` connection (PROTOCOL 4), so audio can ride it.

HA de-duplicates wake words across satellites: when a run carries a `wake_word_phrase`, a second detection of the same phrase within `WAKE_WORD_COOLDOWN` (2 s) is rejected with `duplicate_wake_up_detected`. The first device to report wins, not the closest.

## Decision

1. **Voice is an `assist_satellite` entity in the `tabula` integration**, created only for a device that declares the voice capability at registration (PROTOCOL 4.0). No Wyoming server, no second config entry.
   - The app declares it only on a device type that passed P7.0, and only while the device can actually capture (mic permission held, wake word loaded). Other devices, including any later low-spec targets such as photo frames, never expose a satellite and stay fully working without it.
   - This follows the project's general rule: each feature is offered per device capability, not assumed for every device.
2. **Audio rides the existing TLP connection as HA binary frames.** On a voice turn the integration registers a binary handler on the device's connection and tells the device the handler ID; the device sends 16 kHz, 16-bit mono PCM prefixed with that byte. Frame names, chunk size and limits go in PROTOCOL.md and ADR 0002 in Phase 7, as a capability-gated additive change (PROTOCOL 8; no major bump). `FRAME_MAX_BYTES` keeps applying to JSON frames.
3. **Wake word runs on the device**, and the device starts the pipeline at the STT stage with `wake_word_phrase` set, so HA's cross-device cooldown applies. The engine and models are chosen in P7.0 from options whose model license allows Apache-2.0 distribution (microWakeWord is the first candidate; its model license is confirmed in P7.0). openWakeWord's bundled pre-trained models are excluded: its README lists them as non-commercial. **Fallback** for a device type that cannot afford on-device detection: stream continuously and start at the wake-word stage, so HA's wake-word engine detects. This costs constant upstream audio and is opt-in per device.
4. **Responses play through the existing media path** (P2.5): the device fetches the `tts-end` URL with its credential, on the assistant audio channel (FEATURES F-TTS-07).
5. **Features by HA version.** `ANNOUNCE` from the floor. `START_CONVERSATION` only when the running HA has it (checked at setup, not by version string). Voice timers through `intent.async_register_timer_handler` feed the Phase 6 timer UI.
6. **Arbitration.** HA's first-wins cooldown is the baseline. Arbitration of our own design (for example, the integration holding detections from several Tabula devices for a short window and picking the strongest) is a Phase 7 packet, not a v1 requirement.
7. **P7.0 is widened and unparked.** It covers every device type in v1 (the Fire HD 10 Plus and the Echo Show 8 on whichever OS it runs) and measures mic persistence, wake-word CPU, and echo cancellation. It no longer waits for Phase 6; only the voice-timer packet depends on Phase 6 timers. Device types that fail get no voice.

## Consequences

- One pairing per device; voice settings live in the settings schema and the Tabularium like everything else.
- The integration gains audio buffering and pipeline-event forwarding, the largest single piece of Phase 7. The mock device grows a voice capability so this is testable in CI.
- PROTOCOL.md gains its first binary frame type. The Android app needs a foreground service of type microphone and, on Fire OS, a provisioning answer to permission revocation (P7.0).
- `START_CONVERSATION` is unavailable to users on HA releases older than the one that added it.
- Phase 8's "echo cancellation only if voice lands" now depends on what P7.0 finds about Android's `AcousticEchoCanceler` on each device.

## Alternatives considered

- **Wyoming server in the app.** Almost no integration code, and HA's own satellite handles the pipeline. Lost because it adds a second HA device and config entry per tablet, has HA connect to an unauthenticated TCP port on the tablet, has no announce at our floor, and splits voice state across two integrations, which makes our overlay, settings and arbitration harder.
- **Direct `assist_pipeline/run` calls.** The least code. Lost because there is no satellite entity: no announce, no voice timers, and HA cannot target the device as a satellite.
- **Server-side wake word for every device.** Simpler on the device, but every tablet would stream audio all the time. Kept only as the per-device fallback.
