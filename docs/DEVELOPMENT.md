# Development

## Principles

- **One entry point.** `make` targets are the only build interface. CI calls the same targets, so a green `make check` locally means a green CI run.
- **Registry-only toolchain.** Every tool comes from a package registry (PyPI, npm; later Maven Central and Google's Maven repository) and is pinned by a lockfile. Nothing is fetched with `curl | sh` or from GitHub release pages, which restricted cloud VMs often block.
- **Any machine.** A fresh Linux or macOS VM with `git`, `make`, Python 3.13 and Node 22 can build everything. No IDE, container or account is required.

ADR [0004](decisions/0004-build-and-toolchain.md) records the reasoning.

## Prerequisites

| Tool | Version | Used for | Pinned by |
| --- | --- | --- | --- |
| make, git | any recent | entry point | — |
| Python | 3.13 | integration, mock device, tools | `.python-version` |
| uv | 0.9+ | Python environments and dependencies | `uv.lock` |
| Node | 22+ | website; panel from Phase 5 | `.node-version`, `website/package-lock.json` |
| JDK | 17–21 | Android build | ADR [0008](decisions/0008-android-toolchain-and-targetsdk.md), `tools/android_common.sh` |
| Android SDK | platform 34, build-tools 34.0.0 | Android app | `tools/android_common.sh` |

`make setup` runs `tools/bootstrap.sh`, which installs uv from PyPI if it is missing and checks the Node version, then installs locked dependencies.

The Android toolchain is opt-in: `make setup-android` verifies a JDK 17–21 and the Android SDK components (platform `android-34`, build-tools `34.0.0`) and installs the components with `sdkmanager` when the SDK is present but incomplete. Versions and reasoning are in ADR [0008](decisions/0008-android-toolchain-and-targetsdk.md).

**Network needs of `make setup-android`** (all pinned or checksum-verified, per the registry-only principle):

- `dl.google.com` — SDK components, via `sdkmanager "platforms;android-34" "build-tools;34.0.0"`.
- `services.gradle.org` — the Gradle wrapper distribution, pinned by `distributionSha256Sum` in `android/gradle/wrapper/gradle-wrapper.properties`.
- Google's Maven repository (`maven.google.com`) and Maven Central — AGP, Kotlin and library dependencies, pinned in `android/gradle/libs.versions.toml`.

Machines without a JDK or Android SDK skip the Android steps in `make check` with a message. CI installs the JDK with `actions/setup-java` and the command-line tools with `android-actions/setup-android`, sets `TABULA_REQUIRE_ANDROID=1` and runs the same targets, so the Android tests always run there.

The Makefile isolates uv from your own uv settings. It points `UV_CONFIG_FILE` at `tools/uv.toml` (which holds no settings) and unsets `UV_EXCLUDE_NEWER`, `UV_RESOLUTION`, `UV_PRERELEASE` and `UV_FORK_STRATEGY`, so a user- or system-level `uv.toml` cannot change resolution. Without this, a global setting such as a rolling `exclude-newer` makes `uv sync --locked` reject `uv.lock`. Project settings stay in `pyproject.toml` (`[tool.uv]`). If you run `uv` directly instead of through `make`, set `UV_CONFIG_FILE="$PWD/tools/uv.toml"` from the repo root (or an absolute path) and leave those variables unset, or you may see the same lockfile error. Index settings such as `UV_INDEX_URL` are left alone, so package mirrors keep working.

## Common tasks

Work happens in a git worktree, one per branch (AGENTS.md, "Git"):

```sh
make worktree BRANCH=p1-3-entity-platforms
cd .worktrees/p1-3-entity-platforms
make setup      # once per worktree; safe to re-run
make check      # before every push
make fmt        # fix formatting
make site-dev   # website with live reload
```

## Cloud VMs

Any VM that can reach PyPI and npm works.

**Claude Code cloud environments.** Run `make setup` from the repository root in the environment's setup script (or at the start of a session), with a network policy that allows PyPI and the npm registry.

**GitHub Codespaces and other hosted VMs.** Use an image with Python 3.13 and Node 22 (for example the default Codespaces universal image) and run `make setup`.

**Android builds** need a JDK 17–21 and the Android SDK command-line tools. Run `make setup-android`; its network needs and the JDK/SDK sources are listed under Prerequisites above. On GitHub Actions the JDK comes from `actions/setup-java` and the command-line tools from `android-actions/setup-android`.

Hardware steps (ADB against a real tablet) cannot run in a cloud VM. Packets mark them `Verification: hardware`; see AGENTS.md.

## Panel

The Tabularium is a Vite + TypeScript + Lit 3 app in `panel/`, built into one ES module and served by the hub entry (packet P5.1; plan 5.9).

```sh
make panel         # build build/panel/panel.js and stage it into the integration
make panel-check   # Prettier check, ESLint, tsc --noEmit, Vitest, then the build
make panel-dev     # rebuild the bundle on every change
```

`make setup` installs the panel's npm dependencies from `panel/package-lock.json`; `make check` runs `panel-check`, so a TypeScript error fails CI like a Python one. `make fmt` formats the panel sources with Prettier.

**Delivery.** Home Assistant serves only files inside a component's own directory, so `make panel` stages the build into `integration/custom_components/tabula/panel/` (gitignored, never committed). A source checkout must run `make panel` before the integration is started, or before a release is packaged. Without a staged bundle the integration loads normally, logs a warning and skips the panel.

**Dev loop.** The bundle is fetched by the HA frontend as a module, so there is no dev server against a live HA instance: change the sources, run `make panel`, then reload the integration (or hard-reload the browser tab to pick up the new `?v=` module URL).

**Generated file.** `panel/src/generated/settings.ts` is written by `tools/codegen_ts.py` from `schema/settings.schema.json` (`make codegen-ts`, `make codegen` for both languages) and gated by `make codegen-drift`. Do not edit it by hand, and do not format it: Prettier and ESLint ignore the directory.

## CI

`.github/workflows/ci.yml` runs `make setup` and `make check` on every pull request and on pushes to `main`. Component packets extend `make check`; they do not add CI-only steps.

## Output

All generated output goes to `build/` at the repository root (Gradle keeps its own `build/` directories under `android/`). `make clean` removes it.
