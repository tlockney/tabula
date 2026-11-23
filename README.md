# Tabula

Tabula turns wall-mounted Android tablets into Home Assistant (HA) kiosks. Each tablet (a *tabula*) shows an HA dashboard, idles to native screensavers, and takes notifications, audio and screen control from HA.

It has two parts that speak one protocol, the Tabula Link Protocol (TLP):

- **Android app** (`android/`): Kotlin, Jetpack Compose and the system WebView. Dials out to HA over one WebSocket.
- **HA integration** (`integration/`): a custom integration, domain `tabula`. Pairs tablets, exposes each as standard HA entities and services, and stores its settings.

Tabula needs HA 2025.1.0 or later. Tablets reach HA over the local network. There is no cloud service.

## Status

Early development. Expect rough edges and changes to settings and the protocol.

The first milestone, a Fire HD 10 Plus that boots into a dashboard, idles to a clock and takes brightness, notifications and TTS from HA, works on real hardware. Since then:

| Area | State |
| --- | --- |
| Dashboard, pairing, offline-first reconnect | Working |
| Screen control (brightness, screen on/off, ambient light sensor) | Working |
| Notifications, chime, TTS and media playback | Working |
| Screensavers: clock faces, photo slideshow, schedule, pixel shift | Working |
| Screenshots, start page, settings as HA entities | Working |
| Wake-word detection on the device | Implemented; hardware verification pending |
| Intercom (hands-free calls and broadcast between tablets, started from HA or by voice) | In progress. Design in [ADR 0025](docs/decisions/0025-intercom.md); WebRTC spike done |
| Voice satellite (Assist) | In progress. See [ADR 0024](docs/decisions/0024-voice-satellite.md) |
| Echo Show 8 (2nd gen) | Planned, on an owner-unlocked LineageOS install ([ADR 0026](docs/decisions/0026-echo-show-8-owner-unlocked-target.md)) |
| Presence from the tablet camera, weather and at-a-glance screensavers, website mode, corner widgets | Planned |
| Management panel (the Tabularium), recovery page, over-the-air updates | Planned |

Features are gated per device capability: a tablet without a working microphone still plays audio, for example. The full roadmap is in [docs/PLAN.md](docs/PLAN.md).

There are no releases yet. Install from a checkout.

## Install

You need a checkout, `make`, `git`, Python 3.13 (or [uv](https://docs.astral.sh/uv/)), an HA instance, and an Android tablet with USB debugging on.

1. **HA integration.** Copy `integration/custom_components/tabula` into HA's `custom_components/`, restart HA, then add the Tabula hub under Settings → Devices & services.
2. **Android app.** Build and install the APK, then apply the ADB grants:

   ```sh
   make setup-android
   make android-apk
   ANDROID_SERIAL=<serial> tools/device/provision.sh
   ```

3. **Pair.** Open the app, enter your HA address, and confirm the six-digit code in HA.

[docs/DEVICE.md](docs/DEVICE.md) has the full steps, per-device checks and recovery. The first target is the Fire HD 10 Plus (Fire OS 7). The app supports Android 7 (API 24) and later; other tablets are untested.

## Build and test

```sh
make setup   # install pinned tools and dependencies
make check   # lint, format, repo checks, tests, site build; the same command CI runs
make help    # list all targets
```

[docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) covers the toolchain, cloud VMs and CI. A Python reference device (`mockdevice/`) lets you develop the integration without a tablet.

## Documentation

| | |
| --- | --- |
| [docs/PLAN.md](docs/PLAN.md) | Plan, scope and roadmap |
| [docs/FEATURES.md](docs/FEATURES.md) | Behavior specification |
| [docs/PROTOCOL.md](docs/PROTOCOL.md) | Tabula Link Protocol |
| [docs/DEVICE.md](docs/DEVICE.md) | Provisioning, hardware checks, recovery |
| [docs/decisions/](docs/decisions/) | Architecture decision records |

## Repository layout

| Path | Contents |
| --- | --- |
| `android/` | Android app (Gradle) |
| `integration/` | HA custom integration (`custom_components/tabula`) |
| `mockdevice/` | Python reference device for TLP: dev loop and conformance suite |
| `panel/` | Management panel (the Tabularium) |
| `schema/` | Settings JSON Schema and golden TLP fixtures shared by both ends |
| `tools/` | Repo checks, codegen, device and release scripts |
| `website/` | Project website (static site) |
| `docs/` | Plan, protocol, features, device runbook, ADRs, work packets |

## Contributing

Work is split into self-contained packets and tracked in GitHub issues. People and AI agents both contribute. Read [AGENTS.md](AGENTS.md) first, then [CONTRIBUTING.md](CONTRIBUTING.md).

## Prior art

Tabula's feature set was informed by the public documentation of Kiosk Satellite. Tabula was built independently from that documentation, with no code or text reused; [PROVENANCE.md](PROVENANCE.md) records how.

## License

Apache-2.0. See [LICENSE](LICENSE). Third-party components are listed in [docs/THIRD-PARTY.md](docs/THIRD-PARTY.md).
