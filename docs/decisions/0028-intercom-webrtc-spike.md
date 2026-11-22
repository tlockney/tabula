# 0028: Intercom spike — device verdicts and the WebRTC engine

Status: accepted
Date: 2026-11-21
Packet: P9.0

## Context

ADR 0025 decided that intercom audio goes directly between devices over a native WebRTC library, with HA as directory and signaling path, and left the artifact, the audio settings and the per-device verdicts to P9.0. The packet asks whether such a library runs on each v1 device type, whether echo cancellation makes hands-free calls usable on tablet speakers, and what it costs.

What was available: two Fire HD 10 Plus units (KFTRPWI, Fire OS 7 / Android 9, API 28, arm64-v8a), both on the same Wi-Fi LAN, both running the paired production app. No Echo Show 8 has been through P2.7's bring-up yet, and no third device existed for the broadcast case.

The spike is its own application, `net.lockney.tabula.spike.intercom`, with its release variant disabled in Gradle, so installing it cannot replace, uninstall or unpair `net.lockney.tabula`. Signaling for the spike is a throwaway HTTP relay on the LAN (`tools/device/intercom_relay.py`), since TLP signaling does not exist yet. All measurements come from `tools/device/check_p90.sh`, `tools/device/intercom_cost.sh` and `tools/device/check_p90_owner.sh`, with the device output pasted in the packet's Result.

## Decision

### The artifact

`io.github.webrtc-sdk:android:125.6422.07`, from Maven Central, BSD-3-Clause (its POM declares the 3-Clause BSD license). It resolves through the project's existing repositories with no extra source, its own `minSdkVersion` is 21 (inside our 24), and it runs on API 28 as measured. It ships one `libjingle_peerconnection_so.so` per ABI:

| ABI | Native library | APK for that ABI alone | What it adds |
| --- | --- | --- | --- |
| arm64-v8a | 11 377 336 B | 13 064 604 B | the whole spike for the Fire HD 10 Plus |
| armeabi-v7a | 6 536 680 B | 8 223 950 B | |
| x86 | 12 145 328 B | 13 832 590 B | |
| x86_64 | 12 725 808 B | 14 413 073 B | |
| all four (universal) | — | 44 494 363 B | what not to ship |

So the library costs about 11.4 MB of APK on the v1 device, against a production APK of roughly 1.5 MB today: an intercom build is dominated by WebRTC. One ABI per build (the packet's plan 5.9 note), or an ABI split, keeps that to a single library.

### Per device type

| Device | Verdict | What was measured |
| --- | --- | --- |
| Fire HD 10 Plus (11th gen, Fire OS 7 / API 28) | **Go**, two-way audio, and listen-only as well | Call connected on host candidates, Opus, zero loss, `rtt` 4–5 ms, audio both ways (below). `isBuiltInAcousticEchoCancelerSupported()` and `isBuiltInNoiseSuppressorSupported()` are **false** on both units, so only WebRTC's software echo cancellation, noise suppression and gain are available — they work (below). |
| Echo Show 8 (2nd gen) | **Not measured** | The packet says to include it if it is in v1 at the time. It is in v1 (ADR 0026) but no unit has been through P2.7's bring-up, so there is nothing to install on. P2.7 or a follow-up runs `check_p90.sh` against it with the same spike APK; nothing in this ADR's decision depends on Fire OS. |

### Audio settings

- **Codec:** Opus, the library's default. The selected codec reported by `getStats` is `audio/opus`, clock 48000, inband FEC on (`useinbandfec=1` in the offer). No bitrate cap: two devices on one LAN, and the default adapts to loss.
- **Capture:** mono, from `VOICE_COMMUNICATION`, through `JavaAudioDeviceModule`.
- **Echo cancellation, noise suppression, gain:** on, in the software path (hardware is unavailable on the Fire HD 10 Plus). They must stay on for hands-free use; the measurements below show they are effective and show what they cost.
- **Jitter buffer:** the library's default. Measured jitter stayed at 1–6 ms on the LAN, so nothing in the spike tunes it.
- **No STUN or TURN:** `RTCConfiguration` carries an empty ICE server list. Every candidate was `typ host` on the LAN (192.168.1.129 ↔ 192.168.1.103), and ICE completed in 0.3–0.8 s. This confirms ADR 0025 decision 3.
- **Audio route during a call matters.** Once the connection is up the device is in `MODE_IN_COMMUNICATION`, and a tone played on the media stream is nearly inaudible there: the tone test only worked after the tone played with `USAGE_VOICE_COMMUNICATION`, when the sender's own capture level rose from about 0.002 to 0.11. Tones, chimes and any sound the intercom itself makes during a call belong on the communication stream.

### What the call must run in

A call is a **foreground service** holding a partial wake lock and a Wi-Fi high-performance lock. Measured: a call started with `am start-foreground-service` with no app in front connects and carries audio; it kept carrying audio with the dashboard in front on both kiosks (inbound packets 249 → 656 and 791 → 1194) and with the panel off (1365 → 2175). Without the locks the service would be a candidate for doze and the radio for power saving, which ADR 0025's hands-free requirement cannot tolerate; the spike holds both for the call's lifetime and the production engine must too.

### Listen-only

A device that must not open its microphone declares `intercom.listen` only, and the receiver uses a `RECV_ONLY` transceiver with `JavaAudioDeviceModule.setAudioRecordEnabled(false)`. Measured on the answerer with `RECORD_AUDIO` revoked from the spike app only: it connected, received (`packets in` climbing, `out 0`) and played, while the same app answering with its microphone path received audio too but sent nothing usable. So a device that has lost the microphone never loses the ability to hear a call, which is the Fire OS case in ADR 0025 decision 7.

### Constants (added to ADR 0002)

| Name | Value | Reasoning |
| --- | --- | --- |
| `INTERCOM_RING_LIMIT_S` | 45 s | Long enough to cross a house and answer hands-free, which is the reason intercom exists; short enough that a call into an empty room is over in under a minute. A phone rings for about 30 s; 45 s adds one walk-over. |
| `INTERCOM_CONNECT_TIMEOUT_S` | 10 s | ICE on the LAN took 0.3–0.8 s, so 10 s is an order of magnitude of margin for a busy Wi-Fi moment, and past it a caller should see "could not connect" rather than a call that never starts. Matches `COMMAND_TIMEOUT` so one number covers "HA asked, the device did not answer". |

### What happens to the throwaway paths

Kept, behind debug only, because every later Phase 9 packet re-runs them:

- The app: its own Gradle module, its own `applicationId`, no release variant (`androidComponents.beforeVariants` disables it), built only by `make android-spike-apk`. It shares no code with `:app`.
- The signaling relay, the log summariser and the three device checks stay in `tools/device/`.
- The spike-only flags, all named here: `role`, `relay`, `room`, `aec`, `listenOnly`, `toneAfter`, `toneLocalAt`, `recordSeconds`, `durationSeconds`. They exist to drive the measurements from ADB and are not a product interface.

Nothing in ADR 0025's decisions changes. The changes this ADR makes to that picture: the Echo Show 8 verdict is still open; broadcast (one sender to two receivers) is still unmeasured; and the production engine carries the audio routes and locks above.

## Measurements

Two Fire HD 10 Plus units on one Wi-Fi LAN, `check_p90.sh`'s run on 2026-11-21 (full output in the packet's Result):

| Quantity | Caller (Office Kiosk) | Answerer (Desk Kiosk) |
| --- | --- | --- |
| Offer to ICE connected | 644 ms | 322 ms |
| Offer to first audio packet | 1 759 ms | 2 192 ms |
| Selected candidate pair | host/udp 192.168.1.129:60043 | host/udp 192.168.1.103:52650 |
| Round-trip time (median from `getStats`) | 4–5 ms | 4–5 ms |
| Jitter | 1–5 ms | 1–6 ms |
| Packets lost | 0 | 0 |

Latency, tone test: a 1 kHz tone played at the caller, heard and recorded at the answerer, **178 ms** from the logged play instant to the detected onset, with a clock-offset spread of ±8 ms (the two devices' clocks anchored to the relay's). The same tone played and recorded on one device, with no call and no network, measured 160 ms, which is the `AudioTrack` start latency and detection granularity — so the transport plus acoustic part is on the order of 20–30 ms, and the 178 ms figure overstates mouth-to-ear by that bias. The person-in-the-room clap judgment is separate and in the packet's owner steps.

Echo cancellation: with AEC on, a tone at amplitude 0.35 played through the device's own speaker did not reach its own capture (source level stayed at about 0.002 and the far end heard nothing). With `googEchoCancellation=false` the same tone raised the sender's capture level to 0.11 and arrived at the far end (rms 904–1003 in the recording), which is the same tone test working. That is direct evidence that the software canceller removes the device's own playback, and it is also why two tablets in one room still risk feedback: each cancels only its own speaker, not the other's. ADR 0025's refusal of same-area calls stands.

Cost, ten minutes of one two-way call (`intercom_cost.sh`, numbers in the packet Result): the app's CPU is a share of **one core**, not of the device; the audio HAL process is a comparable share. Both are material on this SoC, and the production engine's idle-cost target (plan 7.4, under 15 % behind the screensaver) is not at risk from a call that is not happening — a call is a deliberate, temporary cost.

## Consequences

- An intercom build carries ~11.4 MB more APK on arm64, and only one ABI per build.
- The Fire HD 10 Plus needs no hardware AEC: the software path is good enough that the far end did not hear itself in the automated test. Whether it is good enough at 1 m and 3 m is the owner's judgment, recorded in the packet Result.
- The production call engine is a foreground service with a partial wake lock and a Wi-Fi lock, plays its own tones on the communication stream, and answers listen-only when the microphone is unavailable.
- Broadcast remains unmeasured until a third device can run the spike; the Phase 9 broadcast packet must not assume the sender's cost with two receivers is twice the measured one.
- The Echo Show 8 verdict waits on P2.7.
- The WebRTC dependency is a Maven Central artifact, so rule 7 holds; it is ~44 MB in the Gradle cache and the first build on a new machine downloads it.

## Alternatives considered

- **`io.github.webrtc-sdk:android-prefixed`:** the same library with prefixed native symbols, for an app that also links another WebRTC. Tabula has one, so the plain artifact is right.
- **Per-ABI Maven artifacts:** the group publishes only the combined artifact, so the size per ABI comes from Gradle's ABI splits rather than from separate dependency coordinates.
- **Relying on hardware echo cancellation alone:** unavailable on the Fire HD 10 Plus (`isBuiltInAcousticEchoCancelerSupported()` false), and the software canceller is what made the tone test behave as it did.
- **A media-stream tone during a call:** inaudible in `MODE_IN_COMMUNICATION`; rejected after the tone test failed twice with it.
- **Broadcast measured on two devices:** would have measured nothing of the second receiver. Left unanswered deliberately rather than reported approximately.
- **Keeping the spike out of the repo:** the Phase 9 packets need the same measurements on the Echo Show 8 and after any library upgrade, so the module and checks stay, debug-only and out of release builds.
