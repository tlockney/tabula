# Status

Early development. Expect rough edges and changes to settings and the protocol. There are no releases yet; install from a checkout.

The first milestone, a Fire HD 10 Plus that boots into a dashboard, idles to a clock and takes brightness, notifications and TTS from Home Assistant, works on real hardware. Since then:

| Area | State |
| --- | --- |
| Dashboard, pairing, offline-first reconnect | Working |
| Screen control (brightness, screen on/off, ambient light sensor) | Working |
| Notifications, chime, TTS and media playback | Working |
| Screensavers: clock faces, photo slideshow, schedule, pixel shift | Working |
| Screenshots, start page, settings as Home Assistant entities | Working |
| Wake-word detection on the device | Implemented; hardware verification pending |
| Intercom (hands-free calls and broadcast between tablets, started from Home Assistant or by voice) | In progress. Design in [ADR 0025](https://github.com/tlockney/tabula/blob/main/docs/decisions/0025-intercom.md); WebRTC spike done |
| Voice satellite (Assist) | In progress. See [ADR 0024](https://github.com/tlockney/tabula/blob/main/docs/decisions/0024-voice-satellite.md) |
| Echo Show 8 (2nd gen) | Planned, on an owner-unlocked LineageOS install ([ADR 0026](https://github.com/tlockney/tabula/blob/main/docs/decisions/0026-echo-show-8-owner-unlocked-target.md)) |
| Presence from the tablet camera, weather and at-a-glance screensavers, website mode, corner widgets | Planned |
| Management panel (the Tabularium), recovery page, over-the-air updates | Planned |

Features are gated per device capability: a tablet without a working microphone still plays audio, for example.

The full roadmap is [`docs/PLAN.md`](https://github.com/tlockney/tabula/blob/main/docs/PLAN.md). Work items are in [GitHub issues](https://github.com/tlockney/tabula/issues).
