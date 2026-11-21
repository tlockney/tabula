# 0008: Android toolchain and targetSdk

Status: accepted
Date: 2026-11-21
Packet: P2.0

## Context

P2.0 creates the Android Gradle project and must set minSdk, targetSdk and a reproducible toolchain. AGENTS.md rule 7 requires tools to come from package registries with pinned versions, and `docs/DEVELOPMENT.md` promises that any machine can build after `make setup` — but a Python-only machine still has to pass `make check`, so the Android steps must degrade gracefully. The packet also asks this ADR to record the Gradle wrapper distribution and the CI toolchain sources.

The v1 devices (plan section 3) are Fire HD 10 Plus on Fire OS 7 / Android 9 (API 28); the later PF1007L targets are low-spec Android, and the plan's "Baselines" line sets minSdk 24 (Android 7) so those frames stay reachable.

## Decision

### SDK levels

- **minSdk 24** — plan section 3, "Baselines" (Android 7, the PF1007L floor).
- **compileSdk 34, targetSdk 34** — Tabula is sideloaded over ADB, so Play's targetSdk policy does not apply; targetSdk only selects platform behavior changes. 34 matches the pinned SDK components (platform android-34, build-tools 34.0.0), is well inside the range the pinned AGP supports, and no v1 feature needs a newer level. Revisit per phase, with a new ADR, when a feature needs one.
- Java/Kotlin target: **17** for the Gradle build and bytecode.

The codec module is **plain Kotlin JVM, no Android framework dependencies** (packet requirement): `:core` tests on any JDK against `schema/fixtures/`, and the app module (`:app`) consumes it as a library. P2.1 must enable `coreLibraryDesugaring` in the app module, because the codec uses `java.time` and minSdk 24 predates it.

### Pinned toolchain

| Piece | Version | Pinned by |
| --- | --- | --- |
| Gradle distribution | 8.14.8 | `android/gradle/wrapper/gradle-wrapper.properties`: `distributionUrl` + `distributionSha256Sum` = `94da89692fc3ca6d2d0ce6bb48548762ea0f1897ba444286f5c7f3759c7af651` |
| Gradle wrapper jar | 8.14.8 | committed in `android/gradle/wrapper/gradle-wrapper.jar`; SHA-256 `ad16bca46cb71bb5887c546860d93992aeee35c1fdaf4a242b0a612375d937e0` matches the checksum Gradle publishes for this release |
| Android Gradle Plugin | 8.13.2 | `android/gradle/libs.versions.toml` (Google's Maven repository) |
| Kotlin + serialization plugin | 2.4.21 | `android/gradle/libs.versions.toml` (Maven Central / Gradle Plugin Portal) |
| kotlinx.serialization-json | 1.13.0 | `android/gradle/libs.versions.toml` (Maven Central) |
| JDK to run the build | 17 (17–21 accepted) | AGP 8.13's supported range; `tools/android_common.sh` finds one |
| Android SDK components | platform `android-34`, build-tools `34.0.0` | `tools/android_common.sh`; `make setup-android` installs them via `sdkmanager` when missing |

Everything resolves from Google's Maven repository and Maven Central (the registries `docs/DEVELOPMENT.md` names for later phases) plus the Gradle wrapper's pinned distribution from services.gradle.org. Nothing is fetched with `curl | sh` or from release pages; the wrapper download is SHA-256-checked. Repository layout is enforced with `RepositoriesMode.FAIL_ON_PROJECT_REPOS`.

### Build entry points

- `make setup-android` — verifies a JDK 17–21 and the SDK components; installs the components with `sdkmanager` (dl.google.com) when the SDK is present but incomplete.
- `make android-test` — `./gradlew --no-daemon test` with the discovered `JAVA_HOME`/`ANDROID_HOME`. Skips with a clear message when the JDK or SDK is missing, so Python-only machines pass `make check`.
- `make codegen-drift` — regenerates the Kotlin settings classes from `schema/settings.schema.json` in memory and fails on a diff (always runs; needs only Python).
- `make check` runs `android` (codegen drift + unit tests) alongside lint, frames, pytest and the site build.

### CI

`.github/workflows/ci.yml` installs the JDK with `actions/setup-java` (temurin 17) and the SDK command-line tools with `android-actions/setup-android`, both official actions, then runs the same `make setup-android` and `make check` as a laptop. The run sets `TABULA_REQUIRE_ANDROID=1`, which turns every skip in `tools/android_test.sh` into a failure, so CI cannot pass without the Android tests actually running.

## Consequences

- Any machine with git, make, Python 3.13 and Node 22 still passes `make check`; the Android steps are additive.
- Upgrading the toolchain means editing `libs.versions.toml`, the wrapper properties (both SHA-256s) and this ADR together.
- `:core` stays framework-free; Android-specific code arrives with P2.1 in `:app` (and later the modules plan section 6 sketches), each added by its own packet.
- compileSdk 34 will age; a later packet raises it with an ADR.

## Alternatives considered

- **AGP 9.x and Gradle 9.x:** current majors, but AGP 9's changed DSL (built-in Kotlin, new defaults) buys nothing for a codec module and moves more at once; AGP 8.13.2 is the last 8.x line with long field experience. Upgrade deliberately later.
- **A separate Gradle build for the codec, outside android/:** would test on a bare JVM even without the SDK, but splits the project into two builds for no consumer benefit; the app depends on the codec, and one build keeps `make` the single entry point.
- **Brew/apt-installed Gradle instead of the wrapper:** not registry-pinned or cross-platform; rejected (AGENTS.md rule 7).
