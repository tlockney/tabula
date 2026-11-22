# 0025: Intercom

Status: accepted
Date: 2026-11-22
Packet: none (Phase 9 approach; P9.0 follows)

## Context

Intercom was a future item (PLAN 4, Phase 9): HA as the directory, direct device-to-device PCM audio with our own framing, signaling over TLP. FEATURES.md defers it, so its behavior is independent design.

Thomas has made intercom the project's first-priority feature. The household need: a person who cannot always speak loudly enough to be heard from another room must be able to get someone's attention without walking over. His requirements:

- Hands-free: reaching the other person must not need someone at the receiving tablet.
- Start from HA: a dashboard button or a wireless (Zigbee) button, through ordinary HA automations.
- Call one device, shown by room, or broadcast to all devices.

Forces:

- Hands-free, two-way audio on tablet speakers needs acoustic echo cancellation, noise suppression and a jitter buffer. Raw PCM with our own framing would mean building all three.
- The Fire WebView has no WebRTC (PLAN 3), but a native WebRTC library inside the app does not depend on the WebView. Google's WebRTC (BSD-3-Clause) is published as prebuilt Android libraries on Maven Central (for example `io.github.webrtc-sdk:android`), which fits the registry-only rule (AGENTS.md rule 7). It brings echo cancellation, noise suppression, automatic gain, Opus and a jitter buffer.
- Fire OS may withdraw the mic permission (FEATURES F-KSK-21); P7.0 measures this. A device without a working mic can still play audio.
- The mic and audio-session work is shared with voice (ADR 0024), but intercom does not need STT, intents or a wake word to be useful.

## Decision

1. **Calls are two-way and can be answered automatically.** A call opens two-way audio. Each device has an auto-answer setting: off, only from listed devices, or from all Tabula devices. Default: off. Auto-answer always plays a short tone first, and the screen shows who is connected for the whole call. A call that is not auto-answered rings until someone taps answer or decline, or until a ring limit (a constant for ADR 0002).
2. **Broadcast is one-way and live.** The caller's voice plays on every target device; receivers' mics stay closed. Each receiver shows a reply button that places a two-way call back to the broadcaster.
3. **Audio goes directly between devices over native WebRTC.** The app bundles a prebuilt WebRTC Android library from Maven Central (artifact chosen in P9.0). Audio only, Opus, with the library's echo cancellation and noise suppression. Peers connect on the LAN with host candidates; no STUN or TURN server, and no audio passes through HA.
4. **HA is the directory and the signaling path.** The integration relays offers, answers and ICE candidates between devices as TLP commands and events, gated by an `intercom` capability (an additive change, PROTOCOL 8). Only devices of the same Tabula hub can call each other.
5. **Starting a call from HA.** A `tabula.intercom_call` service with a standard target (devices, or areas that resolve to Tabula devices, as in ADR 0010) and a mode (`call` or `broadcast`); with no target, broadcast goes to every intercom-capable device except the caller. The caller is a Tabula device given in the service data. A `tabula.intercom_end` service hangs up. Events `tabula_intercom_started`, `tabula_intercom_answered`, `tabula_intercom_missed` and `tabula_intercom_ended` let automations follow up (for example a phone notification when a call is missed). Each device also gets a sensor for intercom state (idle, ringing, in call, broadcasting).
6. **Starting a call on the device.** An intercom screen lists the other devices by HA area name (device name when no area is set), plus a broadcast button. It is reachable from the action registry (FEATURES action registry), so gestures, the side menu and dashboard links can open it or call a fixed target.
7. **Capabilities are per device.** A device declares `intercom.listen` (can receive audio) and, only while it can capture, `intercom.talk`. A listen-only device receives calls and broadcasts, and its screen says it cannot talk back. A device with neither declares nothing and is left out of targets.
8. **Voice activation is part of the first intercom release, through Assist.** Thomas requires that a call can be started by voice from the start; it is also what lets these devices take over from the household's existing voice speakers. On a device that is a voice satellite (ADR 0024), the integration registers intents such as "call the kitchen", "call Thomas" (a person mapped to a device or area in settings) and "broadcast …" that start the same flow. Buttons, HA services and the intercom screen work on every intercom device, satellite or not.
9. **Sequencing.** Intercom, plus the voice work it needs, is scheduled ahead of Phase 6. P9.0 (WebRTC) and P7.0 (mic and wake word) run in parallel. Phase 9 packets depend on P9.0 and the Phase 2 milestone. The Phase 7 packets for the satellite entity, audio transport and on-device wake word move up with them; the intercom voice-intent packet depends on those. Other Phase 7 work (voice timers, start-conversation, arbitration) keeps its place.

## Consequences

- The APK grows by the size of the WebRTC native libraries (one ABI per build where possible); P9.0 measures it.
- PLAN 5.9's "audio always goes directly between devices" holds. "Our own framing" is replaced by WebRTC's.
- Auto-answer opens a mic without a tap. It is off by default, limited to chosen callers, announced by a tone, and visible on screen for the whole call; PROVENANCE records it as independent design.
- Two devices in the same room can feed back; WebRTC echo cancellation handles one device's own speaker, not another device's. P9.0 checks the case and a call is refused between devices in the same area.
- The mock device gains the intercom capability (signaling only), so the integration side is tested in CI.

## Alternatives considered

- **Raw PCM with our own framing (the earlier plan).** No dependency, but echo cancellation, jitter handling and a codec would all be ours to build and tune, and hands-free two-way audio is unusable without them.
- **Audio relayed through HA.** Simpler networking, but adds latency and load on HA, and needs a binary relay in the integration. Direct LAN audio is the plan's existing rule.
- **Tap to answer only.** More private, but fails the core requirement when nobody is near the receiving tablet. Kept as the default (auto-answer off).
- **Voice activation as a later add-on.** Lost to Thomas's requirement that voice-started calls work from the first release.
- **Push-to-talk messages.** Simplest, no echo cancellation needed, but no live conversation.
- **SIP or a separate VoIP server.** Adds a server to run and configure; HA already knows every device.
