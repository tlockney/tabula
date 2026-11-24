# 0028: Intercom spike — device verdicts and the WebRTC engine

Status: accepted
Date: 2026-11-21
Packet: P9.0

## Context

ADR 0025 decided that intercom audio goes directly between devices over a native WebRTC library, with HA as directory and signaling path, and left the artifact, the audio settings and the per-device verdicts to P9.0. The packet asks whether such a library runs on each v1 device type, whether echo cancellation makes hands-free calls usable on tablet speakers, and what it costs.

What was available: two Fire HD 10 Plus units (KFTRPWI, Fire OS 7 / Android 9, API 28, arm64-v8a), both on the same Wi-Fi LAN, both running the paired production app. No Echo Show 8 has been through P2.7's bring-up yet, and no third device existed for the broadcast case.

The spike is its own application, `net.lockney.tabula.spike.intercom`, with its release variant disabled in Gradle, so installing it cannot replace, uninstall or unpair `net.lockney.tabula`. Signaling for the spike is a throwaway HTTP relay on the LAN (`tools/device/intercom_relay.py`), since TLP signaling does not exist yet. All measurements come from `tools/device/check_p90.sh`, `tools/device/check_p90_cost.sh` and `tools/device/check_p90_owner.sh`, with the device output pasted in the packet's Result.

## Decision

### The artifact

`io.github.webrtc-sdk:android:125.6422.07`, from Maven Central, BSD-3-Clause (its POM declares the 3-Clause BSD license). It resolves through the project's existing repositories with no extra source, its own `minSdkVersion` is 21 (inside our 24), and it runs on API 28 as measured. Its native library is the bulk of an intercom APK. Measured APK sizes from the build the runs used, one ABI per file:

| ABI | Native library | APK for that ABI alone |
| --- | --- | --- |
| arm64-v8a | 11 377 336 B | 13 064 604 B (the Fire HD 10 Plus) |
| armeabi-v7a | 6 536 680 B | 8 223 950 B |
| x86 | 12 145 328 B | 13 832 590 B |
| x86_64 | 12 725 808 B | 14 413 073 B |
| all four (universal) | 11 377 336 B | 44 494 363 B — what not to ship |

These are from the build the spike's runs used, pasted in the packet Result (step 2). Only arm64 and armeabi-v7a are v1 ABIs. The arm64 APK is 13.0 MB, and its 11.4 MB native library is almost all of it: an intercom build is dominated by WebRTC. One ABI per build (the packet's plan 5.9 note), or an ABI split, keeps that to a single library.

### Per device type

| Device | Verdict | What was measured |
| --- | --- | --- |
| Fire HD 10 Plus (11th gen, Fire OS 7 / API 28) | **Go** for two-way audio and listen-only, with the production guards below | Call connected on host candidates, Opus, zero loss, `rtt` 5.0 ms, audio both ways (below). `isBuiltInAcousticEchoCancelerSupported()` and `isBuiltInNoiseSuppressorSupported()` are **false** on both units, so only WebRTC's software echo cancellation, noise suppression and gain are available, and what they remove is not measured. A real two-way call in this house produced sustained feedback anyway, so the go is for the library and the audio path, not for hands-free use without the guards below. |
| Echo Show 8 (2nd gen) | **Not measured** | The packet says to include it if it is in v1 at the time. It is in v1 (ADR 0026) but no unit has been through P2.7's bring-up, so there is nothing to install on. P2.7 or a follow-up runs `check_p90.sh` against it with the same spike APK; nothing in this ADR's decision depends on Fire OS. |

### Audio settings

- **Codec:** Opus, the library's default. The selected codec reported by `getStats` is `audio/opus`, clock 48000, inband FEC on (`useinbandfec=1` in the offer). No bitrate cap: two devices on one LAN, and the default adapts to loss.
- **Capture:** mono, from `VOICE_COMMUNICATION`, through `JavaAudioDeviceModule`.
- **Echo cancellation, noise suppression, gain:** on, in the software path — the hardware canceller and suppressor are absent on the Fire HD 10 Plus (`hardwareAec=false hardwareNs=false`, pasted). ADR 0025 needs them for hands-free use and the library defaults are the only mechanism available. What they remove on these units is not measured (below); they cannot prevent feedback between two devices either way, because each end cancels only its own speaker (see "What a real call in the house changed" below).
- **Jitter buffer:** the library's default. Measured jitter was 5.0 ms at the answerer and 6–8 ms in the revoked-permission run, with the caller reporting none, so nothing in the spike tunes it.
- **No STUN or TURN:** `RTCConfiguration` carries an empty ICE server list. Every candidate was `typ host` on the LAN (192.168.1.129 ↔ 192.168.1.103), and ICE completed in 0.27–0.64 s across the pasted runs. This confirms ADR 0025 decision 3.
- **Audio route during a call matters.** Once the connection is up the device is in `MODE_IN_COMMUNICATION`, and a tone played on the media stream was nearly inaudible there; the tone test only produced a detectable tone at the far end once the tone played with `USAGE_VOICE_COMMUNICATION`. Tones, chimes and any sound the intercom itself makes during a call belong on the communication stream.

### What a real call in the house changed

The spike was also used as intended: a two-way call between the two tablets, in the rooms they live in, with software echo cancellation on at both ends. It produced **sustained acoustic feedback that the people in the house heard**. Software AEC alone did not prevent it, and that is the expected result rather than a defect in the library: each device's canceller removes only that device's own speaker from its own microphone, and never the other end's, so two speakers and two microphones in one acoustic space can always close a loop. ADR 0025 already refuses same-area calls for this reason; this run shows the risk is not limited to deliberate "both tablets in one room" testing.

So a production intercom needs all four of these, and they are requirements, not options:

1. **An explicit per-call user action or schedule.** No call starts or answers itself because a device is online, and ADR 0025 decision 1's auto-answer is allowed only where a person set it up for that call or a schedule did — a default-on auto-answer must not ship.
2. **A hard call time limit.** Every call ends on its own at a fixed maximum (a constant for ADR 0002, added by the Phase 9 packet that builds the engine), whether or not anyone is listening, so feedback cannot run unattended for an hour.
3. **A way to end every call from HA**, for every call state including one that is feeding back: `tabula.intercom_end` (ADR 0025 decision 5) must reach every device in the call and stop it without a person at the tablet.
4. **No auto-resume after reboot.** A call that was up when a device restarted does not come back by itself; the caller has to make a new call.

What this ADR does not answer: how loud the feedback was, what the far-end speech sounded like at 1 m and 3 m, and whether a time limit of N minutes is long enough. Those are **not measured, needs an owner-scheduled run** (`tools/device/check_p90_owner.sh`, below), and the time limit's value is chosen by the Phase 9 packet with the owner, not here.

### What the call must run in

A call is a **foreground service** holding a partial wake lock and a Wi-Fi high-performance lock. Measured: a call started with `am start-foreground-service` with no app in front connects and carries audio; it kept carrying audio with the dashboard in front on both kiosks (inbound packets 249 → 656 and 791 → 1194) and with the panel off (1365 → 2175). Without the locks the service would be a candidate for doze and the radio for power saving, which ADR 0025's hands-free requirement cannot tolerate; the spike holds both for the call's lifetime and the production engine must too.

### Listen-only

A device that must not open its microphone declares `intercom.listen` only, and the receiver uses a `RECV_ONLY` transceiver with `JavaAudioDeviceModule.setAudioRecordEnabled(false)`. Measured on the answerer with `RECORD_AUDIO` revoked from the spike app only: it connected, received (`packets in` climbing, `out 0`) and played, while the same app answering with its microphone path received audio too but sent nothing usable. So a device that has lost the microphone never loses the ability to hear a call, which is the Fire OS case in ADR 0025 decision 7.

### Constants (added to ADR 0002)

| Name | Value | Reasoning |
| --- | --- | --- |
| `INTERCOM_RING_LIMIT_S` | 45 s | Long enough to cross a house and answer hands-free, which is the reason intercom exists; short enough that a call into an empty room is over in under a minute. A phone rings for about 30 s; 45 s adds one walk-over. |
| `INTERCOM_CONNECT_TIMEOUT_S` | 10 s | ICE on the LAN took 0.3–0.6 s, so 10 s is an order of magnitude of margin for a busy Wi-Fi moment, and past it a caller should see "could not connect" rather than a call that never starts. Matches `COMMAND_TIMEOUT` so one number covers "HA asked, the device did not answer". |

### What happens to the throwaway paths

Kept, behind debug only, because every later Phase 9 packet re-runs them:

- The app: its own Gradle module, its own `applicationId`, no release variant (`androidComponents.beforeVariants` disables it), built only by `make android-spike-apk`. It shares no code with `:app`.
- The signaling relay, the log summariser and the three device checks stay in `tools/device/`. The three checks make a tablet listen and speak in a room, so each refuses to run (exit 2) unless `TABULA_AUDIO_OK=1` is set, with a message that audio device tests must be scheduled with the owner; that variable is only set for an owner-scheduled run.
- The spike-only flags, all named here: `role`, `relay`, `room`, `aec`, `listenOnly`, `toneAfter`, `toneLocalAt`, `recordSeconds`, `durationSeconds`. They exist to drive the measurements from ADB and are not a product interface.

ADR 0025's direction stands, with two changes to its detail. Decision 1's auto-answer is narrowed: it may only answer a call that a person or a schedule asked for, and the default stays off (the feedback finding above). Decision 5's `tabula.intercom_end` becomes a hard requirement of every call state, not only a convenience. Still open from that picture: the Echo Show 8 verdict (P2.7); broadcast, one sender to two receivers (**not measured, needs an owner-scheduled run**); the hard call time limit's value (the Phase 9 packet that builds the engine, with the owner); and the audio routes and locks below, which the production engine carries.

## Measurements

Two Fire HD 10 Plus units on one Wi-Fi LAN, `check_p90.sh`'s run on 2026-11-21 (full output in the packet's Result):

| Quantity | Caller (Office Kiosk) | Answerer (Desk Kiosk) |
| --- | --- | --- |
| Offer to ICE connected | 644 ms | 322 ms |
| Offer to first audio packet | 1 759 ms | 2 192 ms |
| Selected candidate pair | host/udp 192.168.1.129:60043 | host/udp 192.168.1.103:52650 |
| Round-trip time (median from `getStats`) | 5.0 ms | 5.0 ms |
| Jitter | not reported in that call (`n/a`); 6–8 ms in the revoked-permission run | 5.0 ms |
| Packets lost | 0 | 0 |

Those are the figures of the pasted step-4 call, the run that completed every step. Repeat runs spread around them — 426/266 ms and 634/290 ms to connected, 1 757/2 402 ms and 1 746/2 171 ms to first audio — and both are pasted in the packet Result; the first of the two failed question 4b and the second lost the caller mid-run, so the completed call is the one quoted.

Latency, tone test: a 1 kHz tone at amplitude 0.35 played at the caller, recorded at the answerer, **178 ms** from the logged play instant to the detected onset, with a clock-offset spread of ±8 ms (the two devices' clocks anchored to the relay's). That figure includes the `AudioTrack` start latency and the detection granularity, so the acoustic and transport part is lower; how much lower was not recorded, and no smaller figure is claimed. That is an instrumented number, not a judgment: **mouth-to-ear latency by the clap test is not measured, needs an owner-scheduled run**, as is hands-free quality at 1 m and 3 m.

Echo cancellation: both units report `hardwareAec=false hardwareNs=false` (pasted in the packet Result), so the only canceller available is WebRTC's software one, which the spike switches on for ordinary calls and off for the tone test so the tone reaches the far end. The tone test therefore proves the audio path, not the canceller: a tone at amplitude 0.35 played at the caller is recorded at the answerer (rms 904). **What the software canceller removes on these units is not measured, needs an owner-scheduled run.** Whatever it removes, it cannot prevent feedback between two devices: each end cancels only its own speaker, never the other's. A normal two-way call between the two tablets in this house produced sustained feedback the occupants heard (above). ADR 0025's refusal of same-area calls stands, and the four guards above are what make the feature safe to ship.

Cost: the ten-minute CPU, memory, heat and cost run **is not measured, needs an owner-scheduled run** (`tools/device/check_p90_cost.sh`, with `TABULA_AUDIO_OK=1` in a session the owner schedules). The one attempt got 90 s of its 600 s before the caller dropped off ADB; its three samples (about half of one core each for the app, the audio HAL a comparable share on the answerer, PSS around 28 500 kB, battery 22.0 °C) are pasted in the packet Result and are not extrapolated. `dumpsys cpuinfo` gave the two kiosks figures that contradicted each other in the same call, which is why the script reads the app's CPU from `/proc`. No ten-minute cost figure is claimed here.

## Consequences

- An intercom build is 13.0 MB of APK on arm64, and only one ABI per build.
- The Fire HD 10 Plus has no hardware canceller or suppressor; the software path is what the engine uses, and what it removes on these units is not measured. It is not enough for a call between two tablets in the same house, which fed back audibly. Hands-free quality at 1 m and 3 m, and the canceller's effect, are **not measured, needs an owner-scheduled run**; the four guards above are not optional until they are.
- The production call engine is a foreground service with a partial wake lock and a Wi-Fi lock, plays its own tones on the communication stream, and answers listen-only when the microphone is unavailable — and every call needs an explicit start or schedule, a hard time limit, an HA way to end it, and no resume after reboot.
- Broadcast remains unmeasured until a third device can run the spike; the Phase 9 broadcast packet must not assume the sender's cost with two receivers is twice a measured one, and there is no measured cost at all yet.
- The Echo Show 8 verdict waits on P2.7.
- The WebRTC dependency is a Maven Central artifact, so rule 7 holds; it is ~44 MB in the Gradle cache and the first build on a new machine downloads it.

## Alternatives considered

- **`io.github.webrtc-sdk:android-prefixed`:** the same library with prefixed native symbols, for an app that also links another WebRTC. Tabula has one, so the plain artifact is right.
- **Per-ABI Maven artifacts:** the group publishes only the combined artifact, so the size per ABI comes from Gradle's ABI splits rather than from separate dependency coordinates.
- **Relying on hardware echo cancellation alone:** unavailable on the Fire HD 10 Plus, which reports no hardware canceller or suppressor, so the software path is the only option.
- **Relying on software AEC alone for hands-free calls:** rejected by the real call, which fed back audibly with AEC on at both ends. Two speakers and two microphones in one acoustic space close a loop regardless of cancellation, so the four guards (explicit start, hard limit, HA hang-up, no resume) carry the safety.
- **Shipping auto-answer at the device's discretion:** rejected. "Answers because it is online" is how an unattended call feeding back runs for an hour; only a person or a schedule may start or answer one.
- **A media-stream tone during a call:** inaudible in `MODE_IN_COMMUNICATION`; rejected after the tone test failed twice with it.
- **Broadcast measured on two devices:** would have measured nothing of the second receiver. Left unanswered deliberately rather than reported approximately.
- **Keeping the spike out of the repo:** the Phase 9 packets need the same measurements on the Echo Show 8 and after any library upgrade, so the module and checks stay, debug-only and out of release builds.
