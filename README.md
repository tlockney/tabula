# Tabula

Tabula turns wall-mounted Android tablets into Home Assistant kiosks. Each tablet (a *tabula*) shows HA dashboards, idles to native screensavers, and takes notifications, audio and screen control from HA.

It has two parts that speak one protocol, the Tabula Link Protocol (TLP):

- **Android app** (`android/`): Kotlin, Jetpack Compose and the system WebView. Dials out to HA over a single WebSocket.
- **HA integration** (`integration/`): a custom integration that pairs tablets, exposes them as standard HA entities and manages their settings.

Status: early development. The first milestone is one Fire HD 10 Plus booting into a dashboard, idling to a clock and taking brightness, notifications and TTS from HA. See [docs/PLAN.md](docs/PLAN.md).

## Build

Prerequisites: `make`, `git` and Python 3.13 (or [uv](https://docs.astral.sh/uv/)). Everything else is installed by the build.

```sh
make setup   # install pinned tools and dependencies
make check   # lint + tests; the same command CI runs
make site    # build the website into build/site
make help    # list all targets
```

[docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) covers cloud VMs, CI and the toolchain.

## Repository layout

| Path | Contents |
| --- | --- |
| `android/` | Android app (Gradle) |
| `integration/` | HA custom integration (`custom_components/tabula`) |
| `mockdevice/` | Python reference device for TLP: dev loop and conformance suite |
| `panel/` | Management panel (the Tabularium), built into the integration |
| `schema/` | Settings JSON Schema and golden TLP fixtures shared by both ends |
| `tools/` | Repo checks, codegen, device scripts, release scripts |
| `website/` | Project website (static site) |
| `docs/` | Plan, protocol, features, device runbook, ADRs, work packets |

Contributors and agents: read [AGENTS.md](AGENTS.md) first.

## Prior art

Tabula's feature set was informed by the public documentation of Kiosk Satellite. Tabula was built independently from that documentation, with no code or text reused; [PROVENANCE.md](PROVENANCE.md) records how.

## License

Apache-2.0. See [LICENSE](LICENSE).
