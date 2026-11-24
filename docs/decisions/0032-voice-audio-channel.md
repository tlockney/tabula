# 0032: Voice audio as binary frames on the TLP WebSocket

Status: accepted
Date: 2026-11-24
Packet: P7.2

## Context

ADR 0024 item 2 decided the shape: on a voice turn the integration registers an HA binary handler on the device's existing authenticated connection, tells the device its one-byte id, and the device prefixes that byte to 16 kHz, 16-bit mono PCM. It deferred the wire details (frame size, turn lifecycle, state key) to a Phase 7 packet.

The facts the design rests on, read from HA core 2025.1.0 (Apache-2.0, our floor):

- `ActiveConnection.async_register_binary_handler(handler)` returns `(handler_id, unregister)`. Ids start at 1 and are 1–255; once the table is full HA reuses freed slots, so a turn registers and unregisters its own handler.
- HA routes a binary message by its first byte only; the rest is the handler's byte string. Nothing else is added or checked.
- `assist_pipeline/const.py` fixes the audio: `SAMPLE_RATE` 16000, `SAMPLE_WIDTH` 2, mono, `SAMPLES_PER_CHUNK` 160 (10 ms), `BYTES_PER_CHUNK` 320, `DEFAULT_PIPELINE_TIMEOUT` 300 s. `assist_pipeline/run` streams those chunks to HA's own binary handler.
- TLP is a strict-sender protocol with capability gating (PROTOCOL 4.0 and 8): an additive feature is legal when a new capability announces it, so no protocol major bump is needed.

The mock device has to speak the channel without a microphone, so the protocol cannot depend on Android audio APIs; and CI has to exercise it, so it cannot depend on real audio either.

## Decision

1. **New capability `voice`**, declared by a device that can capture and stream. Nothing else about it changes; a device without it simply never sees these frames.

2. **A binary frame is one byte of handler id followed by exactly 320 bytes of PCM** (16 kHz, 16-bit signed little-endian, mono) — one HA chunk per frame, sent once per 10 ms of audio. The handler byte is the turn's, not the device's, so the integration can route by turn without parsing anything.

3. **Turn lifecycle.** `voice_start` opens a turn with a `turn_id`, the handler id, the pipeline start stage (`stt` when the device heard the wake word, `wake_word` when HA's engine does) and the phrase when there is one. The device then streams until `voice_stop`, its own `VOICE_TURN_MAX_S` limit, a capture error, or disconnect. It reports `voice_turn_started` / `voice_turn_ended` and mirrors the open turn in state key `voice_turn` (`idle` / `listening`).

4. **`voice_stop` is idempotent and the device times out on its own.** A stop for a closed turn gets `ok`, because the state it asks for already holds and a stop that arrives after the device's timeout is the normal case, not an error. The device stops at `VOICE_TURN_MAX_S` (HA's own default pipeline timeout) even if no stop arrives, so a lost frame cannot leave the microphone streaming.

5. **`voice_turn` carries only `idle` and `listening`.** Speaking is already `media_state`; a second axis saying the same thing would be a place for two truths to disagree. The packet offered `speaking` as an option; this ADR declines it.

6. **The binary frame has no JSON schema entry.** `schema/` describes JSON frames; the binary frame is a byte-array wrapper in code (`VoiceFrame.handlerId` + `payload`). `VOICE_CHUNK_BYTES` and `FRAME_MAX_BYTES` are separate rules (PROTOCOL 4.10).

7. **The mock device generates its PCM in memory** (silence, a tone, or a WAV file read into memory) and paces frames on the event loop. It never opens an audio device, so CI and laptops can run turn tests.

## Consequences

- P7.3 gets a defined channel and a mock that speaks it: a test can open a turn, see interleaved JSON and binary frames, and assert the pipeline received the audio.
- Audio costs 1 byte of framing per 10 ms; base64 in JSON would have cost a third more bytes and a copy.
- The device must keep a 10 ms send cadence. On a loaded device frames bunch and then catch up; HA's stream consumer buffers, and the turn limit bounds the worst case.
- Android keeps a `VoiceFrame` codec in core (no capture): P7.3 wires the microphone to it. The app side stays out of scope here.
- `voice_stop` being idempotent means the integration never has to reason about whether the device already timed out.

## Alternatives considered

- **Base64 PCM in `tabula/*` JSON frames.** Works without HA's binary API, but costs 4/3 the bytes, a copy each way, and puts 433-byte strings through the JSON path 100 times a second. HA's binary channel is exactly this use case.
- **One frame per 100 ms or per second, to cut message count.** HA's own consumer works in 10 ms chunks; batching would add latency at the pipeline and make the stream's rate differ from its audio clock.
- **HTTP upload per turn.** A second endpoint, a second auth path, and no way to interleave control with the audio. The screenshot endpoint already shows the cost of an out-of-band channel.
- **A Wyoming server on the device** (ADR 0022/0024 alternative). Another listener, another credential, and no use for the connection the device already holds.
- **Device-side only timeout, no `voice_stop`.** The integration often knows the pipeline is finished long before 300 s (VAD end, STT done); leaving the microphone open until the limit would be rude and would overlap a reply.
