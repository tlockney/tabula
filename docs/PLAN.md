# Tabula implementation plan (v3)

Nov 21, 2026 · Thomas Lockney

> Imported into the repository on 2026-11-21 (P0.1). This file is now the canonical copy; change it by pull request. Section 6 was updated at import to match ADRs 0004 and 0005.

Tabula is an Apache-2.0 Android kiosk app plus a Home Assistant integration, built clean-room from public docs only. Phases 0–2 (dashboard, clock, HA control on the Fire tablets, and on the Echo Shows if P0.6 finds them feasible) are the first milestone. The plan works with any mix of people, agents and models.

## Overview

> Supersedes v2. Section 11 lists what changed from v2 and why.

**Goal:** build an independently licensed Android kiosk app and Home Assistant integration for wall-mounted tablets. Kiosk Satellite is the main prior art: its public documentation informed the feature set, through the clean-room process in section 2. Tabula uses a purpose-built HA integration rather than ESPHome, and an in-HA management panel rather than peer-to-peer fleet management.

**Architecture:** two deliverables share one protocol.

1. A Kotlin Android app embeds the system WebView for HA dashboards. It opens a single outbound WebSocket to HA and renders screensavers and overlays natively.
2. An HA custom integration (`custom_components/tabula`) pairs and registers devices over that WebSocket. It exposes standard HA entity platforms per device, routes commands back down the same socket, and serves a management panel.

HA is the directory, the settings store and the update orchestrator. Until the recovery page lands (Phase 4), ADB is the recovery path.

**Tech stack:**

- Android: Kotlin, Jetpack Compose, system WebView.
- Integration: Python, tested with pytest + pytest-homeassistant-custom-component.
- Panel: TypeScript/Vite.
- Shared settings definition: JSON Schema.

**Naming:** Tabula (each wall device is a tabula). The management panel is the Tabularium.

| Thing | Name |
| --- | --- |
| Integration | `tabula` |
| Entities | `has_entity_name`; IDs derived by HA from the device name |
| Services / events | `tabula.*` / `tabula_*` |
| Android package | net.lockney.tabula (applicationId and Kotlin namespace; decided 2026-11-21) |
| Protocol | Tabula Link Protocol (TLP) |
| Internal URL scheme | `tabula://` |

## 1. How this plan is executed (any agent or model)

The repository is the only shared memory. Anyone picking up work, whether a person or any model in any tool, must be able to do it from the repo plus a written brief. Nothing depends on chat history, tool-specific memory or a particular vendor's features.

### 1.1 Canonical instruction files

- `AGENTS.md` (repo root) is the single source of standing instructions for the build side: build/test commands, code style, the build-side clean-room rules (section 2), the packet workflow (1.3) and the definition of done. It never contains doc cache paths or excerpts.
- Tool-specific files (`CLAUDE.md`, `.cursorrules`, `GEMINI.md`, `.github/copilot-instructions.md`, …) are symlinks to `AGENTS.md` or one line pointing at it. They never hold divergent rules.
- The spec-side brief (cache paths, spec rules) lives outside the repo and is handed only to spec-side contexts.
- `docs/` holds everything a build-side executor needs: `FEATURES.md`, `PROTOCOL.md`, `decisions/` (ADRs), `DEVICE.md` and `packets/`.

### 1.2 Roles

These are roles, not models. Any capable executor can fill one, but one context may hold only one clean-room side at a time (section 2).

| Role | Side | Does | Typical tier |
| --- | --- | --- | --- |
| Spec author | Spec | Reads the cached Kiosk Satellite docs and writes `docs/FEATURES.md` in our own words, without constants | Deep |
| Spec reviewer | Spec | Checks FEATURES.md for close paraphrase, leaked constants and missing behavior | Deep |
| Architect | Build | Writes ADRs, protocol, schema, constants and the packet breakdown for each phase | Deep |
| Implementer | Build | Executes one packet: code + tests + docs | Standard (Deep for security, protocol, concurrency) |
| Reviewer | Build | Independently reviews a finished packet against its brief and the definition of done | Deep; preferably a different model family from the implementer |
| Mechanic | Build | Scaffolding, renames, formatting, regenerating fixtures | Light |
| Hardware verifier | Build | Runs ADB/device checks on the target devices (section 3) and pastes output into the packet | Thomas (owns the Mac and the devices) |

Capability tiers are defined by what the work needs, so the plan never names a model:

- **Deep:** design judgment, security, cross-cutting reasoning.
- **Standard:** well-specified implementation with tests.
- **Light:** mechanical edits whose correctness a test or diff shows directly.

An executor handed work above its tier should return it with a note rather than guess.

### 1.3 Work packets

Every unit of work is a packet, written as a markdown file in `docs/packets/` before execution starts. Phase 0–2 tasks in section 7 are packet-sized. Later phases get their own breakdown before they start.

```markdown
# P1.3 — Entity platforms
Side: build   Tier: standard   Depends on: P1.2   Timebox: (optional)
Verification: ci | hardware

## Why
(one paragraph: what this enables and where it fits)

## Read first
(explicit file list; nothing else is assumed known)

## Do
(numbered steps)

## Out of scope
(what not to touch)

## Done when
(commands to run and expected results; files that must exist)

## Hand back
(what the executor writes in the packet's "Result" section)
```

Rules:

- **Self-contained:** the brief names every file to read. An executor never needs the conversation that produced the packet.
- **Verification is executable:** "Done when" lists commands, e.g. `pytest integration/tests/test_entities.py -q`, or a scripted ADB check.
- **Hardware split:** packets marked `Verification: hardware` have a CI-runnable part and a device part. A cloud executor completes the CI part and marks the packet `awaiting-hardware`. The hardware verifier runs the device script and pastes its output. Nobody reports success they have not observed.
- **Result section:** the executor appends what changed, the verification output (pasted, not paraphrased), any deviations from the brief and new open questions. The next executor continues without context transfer.
- **One packet, one branch, one PR.** The reviewer approves before merge. Packets with no unmet dependency can run in parallel on separate branches (see the dependency table in 7.0).
- **Escalate** after three failed approaches, or when a timebox runs out: stop, record what was tried in the Result section and hand back to the architect.

### 1.4 Definition of done (every packet)

- All "Done when" commands pass, and their output is pasted into the Result section. Hardware steps are pasted by the hardware verifier.
- Business logic has tests. Pure config or scaffolding may not.
- PROVENANCE.md has an entry if the packet introduced a user-visible behavior: which FEATURES.md item it came from, or "independent design".
- No new prose, identifiers or constants copied from the Kiosk Satellite docs (section 2).
- The reviewer, not the implementer, has signed off.

## 2. Clean-room discipline (binding)

Kiosk Satellite is licensed CC BY-NC-ND 4.0, which forbids derivatives of its code and docs. The core rule is a two-sided separation.

**Spec side.** Only spec-side contexts may read the cached Kiosk Satellite docs, which stay outside the repo. Their locations are listed only in the spec-side brief, never in the repo, AGENTS.md or any build-side brief.

- The spec side's single output is `docs/FEATURES.md`. It describes behavior in our own words, uses our own names and contains no quoted or closely paraphrased text.
- FEATURES.md carries no constants: no ports, timeouts, thresholds, counts or default values. Where a behavior needs one, the entry says "constant: architect picks".

**Build side.** Every other context (architect, implementer, reviewer, mechanic, hardware verifier) sees only the repo.

- Build-side briefs never include doc cache paths or excerpts.
- If FEATURES.md doesn't answer a question, the architect designs the answer. Nobody goes back to the docs to "check something".
- One agent context never holds both sides. A session that read the doc cache does not write code for this repo.

**The human exception.** Thomas has read the docs and will also direct the build. That is recorded once in PROVENANCE.md. To limit carry-over:

- Thomas's design input to the build side goes through FEATURES.md (spec side) or an ADR written as independent design with its reasoning stated.
- Thomas does not supply constants from memory. The architect picks them and records the reasoning in an ADR.

**Other rules:**

- Never read Kiosk Satellite source. That includes its GitHub repo, APK decompilation and its Apache-2.0 plugin SDK interface files. Mentioning them by name as prior art is fine.
- Never fork, vendor or patch the original in any phase.
- Choose our own constants. Ports, timeouts, thresholds, gesture counts and defaults are picked by the architect and recorded in an ADR (`docs/decisions/0002-constants.md`, extended as needed).
- Other prior art is allowed. The build side may read Apache/MIT code that is not Kiosk Satellite, notably the HA Companion Android app (Apache-2.0) and HA core and frontend. Record what was learned in an ADR, and keep attribution if any code is adapted.
- Patents: CC licenses grant no patent rights. Prefer standard engineering over unusual techniques the docs describe.
- PROVENANCE.md at the repo root records, per feature, whether it came from FEATURES.md (by item ID) or independent design.
- License: Apache-2.0 (decided). It is compatible with adapting Companion-app code under attribution.

**Public framing.** The repo will become public, and this plan will live in it. The lineage is stated openly and plainly, never hidden and never promoted. Provenance records are what show the separation worked, so they stay intact.

- The README presents Tabula on its own terms. One "Prior art" line names Kiosk Satellite and says Tabula was built independently from public documentation, with no code or text reused.
- Kiosk Satellite is named only in the README's prior-art line, this plan's overview and sections 1–2, AGENTS.md's clean-room rules, PROVENANCE.md and ADRs that need it. It never appears in code, identifiers, UI strings, commit messages or release notes.
- No "clone", "replacement", "port" or "free alternative to" wording anywhere. Describe Tabula's behavior, not how it compares.
- Never remove or rewrite provenance history to obscure the lineage.
- Before the repo goes public, review the README, docs and this plan against this rule.

## 3. Context and assumptions

- **v1 targets:**

  | Device | Count | Status |
  | --- | --- | --- |
  | Amazon Fire HD 10 Plus (11th gen, Fire OS 7 / Android 9, API 28), 1920×1200 | 2 | First target. "Office Kiosk" is built and accepted first (Phase 2); the second unit is provisioned from DEVICE.md alone |
  | Amazon Echo Show 8 (2nd gen), 1280×800 expected (to be confirmed by P0.6) | 2 | In v1, gated on the P0.6 feasibility check; boot start checked early in P2.1; brought up in P2.7 after the Fire tablets pass P2.6 |

  All units are managed over ADB from the Mac.
- **Later targets:** PF1007L photo frames (low-spec Android). They need a WebView of Chromium 67 or later (ADR 0001); check one before committing to them.
- **Baselines:** minSdk 24 (Android 7), revisited after P0.3's WebView check. targetSdk is set in an ADR during P2.0. Design for weak SoCs, and lay out the native UI (screensavers, cards, pairing screen) for both 1920×1200 and the Echo Show's resolution (expected 1280×800) from the start.
- **Fire OS constraints:**
  - No launcher replacement and no device owner, so lockdown means start-on-boot + screen pinning + overlay.
  - `RECORD_AUDIO` can be revoked at boot or on Wi-Fi reconnect.
  - Amazon WebView has no WebRTC.
  - Provisioning is done over ADB (`pm grant`, `appops set`, deviceidle whitelist, `dpm set-active-admin`). `DEVICE.md` documents it from Phase 2.
  - Silent APK install needs Shizuku or device owner.
- **Echo Show 8 constraints (unverified until P0.6):**
  - Expected: no app store for third-party apps, so Tabula must be sideloaded over ADB. Whether ADB can be enabled without exploits is the open question.
  - The Alexa home screen owns the device. Whether a sideloaded app stays in the foreground is checked in P0.6; whether it can start on boot is checked in P2.1, the first packet with an app that has a boot receiver.
  - Exploits, rooting, bootloader unlocking and firmware downgrades are out of bounds. If the device needs any of them, it moves to Deferred.
- **HA:** one local instance, a handful of devices. The minimum HA version and the supported frontend browser floor are set in the platform spike (P0.3).
- **Network:** HA may be served over plain http on the LAN. Section 5.2 covers what that means for credentials.

## 4. Scope

| Feature area | Decision |
| --- | --- |
| ESPHome integration point | Handled by the `tabula` custom integration |
| Peer fleet management | Handled by the Tabularium panel + profiles in HA |
| LED control / Echo Dot | Dropped |
| Immich screensaver source | Dropped (possible future plugin) |
| Camera views + device camera | Deferred: design hooks only |
| Echo Show 8 (2nd gen) target | In v1, gated on P0.6 (feasibility) and brought up in P2.7 |
| Other Echo Show models, Portal, Raspberry Pi targets | Dropped for v1 |
| Bluetooth proxy | Deferred indefinitely (no free path without ESPHome) |
| Plugin ecosystem | Deferred: keep the `window.tabula` JS bridge + action registry as the seam |
| Voice satellite | Parked until Phase 6 lands; then gated on a Fire OS mic spike |
| Intercom | Kept as future (Phase 9), designed but not scheduled |

In scope across phases:

- Dashboard shell, screen control, screensavers (clock, weather, gallery, website, black), At a Glance.
- Notifications + TTS, alarms, timers, calendar.
- Kiosk/lockdown mode, launcher role (where allowed), navigation, gestures.
- Dashboard optimizations, self-update, media.

## 5. Architecture

### 5.1 One connection, HA owns everything

```text
Android app ──(HA WebSocket API, per-device credential)──► HA
   tabula/register     identity, protocol version, schema version, capabilities
   tabula/state        pushes entity state deltas
   tabula/result       ACK/NACK for a command, keyed by command_id
   tabula/event        lifecycle events (notification, screensaver, …)
   tabula/log          log lines while a log subscription is open
 ◄─ tabula/subscribe   long-lived subscription: server pushes command frames

HTTP (authenticated HomeAssistantView):
   POST /api/tabula/screenshot                binary JPEG upload; device from token

HTTP (unauthenticated, rate-limited; pairing only):
   POST /api/tabula/pair                       {device_id, code, nonce_hash}
   POST /api/tabula/pair/claim                 {device_id, nonce} → credential
```

- No on-device listener is needed for management. The device dials out, which works across VLANs.
- Commands flow down the subscription. Each command frame carries a `command_id`; the device answers with `tabula/result {command_id, ok, error?}`. The integration awaits the result with a timeout and raises `HomeAssistantError` on NACK or timeout.
- Binary data goes over HTTP, not the WebSocket. That includes screenshots and anything large later.
- Reconnect: the device re-dials with jittered exponential backoff. Re-registration is idempotent; the newest connection replaces the old one.
- **HA core data on the same socket (ADR 0017):** while the screensaver shows, the device also sends a few HA core commands on this connection. It subscribes with `subscribe_entities` to the explicit list its overlays need, subscribes to forecasts with `weather/subscribe_forecast`, and fetches display data with `list_for_display`, `get_translations` and `get_icons`. These commands use the same id counter as the `tabula/*` frames. TLP itself is unchanged.
- **Connection binding:** every `tabula/*` command resolves the device from the authenticated user, never from a `device_id` in the payload. A device's credential cannot read or act for another device.

### 5.2 Pairing and credentials

**What pairing buys.** HA has no per-entity permissions in practice. A non-admin HA user can still call any service, including locks and alarm panels, and the dashboard needs that user anyway. So a stolen tablet's credential can still control the house until it is revoked. Pairing gives three things: no admin rights (no config changes, no new users or integrations), one credential per device, and one-click revocation. Section 9 records the residual risk.

**Flow:**

1. On first run the device generates a random `nonce` (never shown, never logged) and a short pairing `code` (shown on screen with the `device_id`).
2. The device calls `POST /api/tabula/pair` with `{device_id, code, nonce_hash}`. This view is unauthenticated, rate-limited per source IP, and caps pending devices (limit chosen by the architect). Pending entries expire after a short TTL.
3. The pending device appears in HA as a discovered Tabula device (`SOURCE_INTEGRATION_DISCOVERY` flow). The user confirms it and enters the code shown on the tablet. The code binds the HA entry to the physical tablet.
4. On confirmation the integration creates a dedicated non-admin HA user for that device and a credential (type settled by P0.3).
5. The device polls `POST /api/tabula/pair/claim` with `{device_id, nonce}`. The integration releases the credential only if the nonce matches the stored hash, then deletes the pending record. Knowing the `device_id` alone gets an attacker nothing.

**Revocation:** deleting the device in HA deletes its user and invalidates the credential.

**Transport:** over plain http, the pairing claim and the WebSocket carry the credential in cleartext on the LAN. The plan supports http for the first device and documents the risk in DEVICE.md; HTTPS (local cert or reverse proxy) is recommended.

**Storage on device:** the credential and nonce live in EncryptedSharedPreferences backed by the Android Keystore. They are excluded from backups and never logged.

Which credential type works for both the WebSocket API and the dashboard frontend (system user + refresh token, or a regular non-admin user + LLAT) is unverified; P0.3 settles it. Fallback if pairing is blocked: a manually created non-admin user + LLAT entered over ADB. That is acceptable for the first device, but it is not the design.

**Device identity:** `device_id` is a random UUID generated on first launch and persisted in app storage. A factory reset or reinstall produces a new device; the old one is deleted from HA. The Android package name is fixed before the first real install (P0.1), because changing it also forces a new device.

### 5.3 Integration lifecycle

- **Hub config entry:** one singleton entry, created once by the user (Settings → Add integration → Tabula). It owns the WebSocket commands, pairing views, storage and panel. Each paired tablet is an HA device under the hub, not its own config entry.
- **Disconnect:** entities go unavailable and the device stays registered. Nothing is removed or unloaded automatically. Deleting a device is always a user action.
- Unknown or unpaired device IDs are rejected with no side effects.
- **Diagnostics:** the hub implements HA's diagnostics platform (config entry + per device), with credentials redacted.

### 5.4 Versioning and compatibility

The app (APK) and the integration (HACS) update independently, so both ends declare versions at registration.

- `tabula/register` carries `protocol_version` (integer major) and `schema_version` (settings schema).
- The integration supports the current protocol major and the one before it. On an unsupported major it rejects registration with a reason, raises an HA repair issue and leaves the device unavailable.
- Additive changes (new optional fields, new capabilities, new frame types gated by capabilities) do not bump the major.
- Settings schema: every schema change bumps `schema_version`. The integration stores settings at its current schema version and translates down for devices reporting an older one (dropping unknown keys). A device ignores unknown keys. Migrations live in `custom_components/tabula/migrations/` with tests.
- Settings `revision` is separate: it counts data changes, not schema changes.

### 5.5 Entity model: reuse standard HA platforms

Standard platforms mean standard HA services work with no Tabula-specific glue. Entities are created only for capabilities the device declares, so the entity set grows as the app does.

| Platform | Entity | Arrives | Why it matters |
| --- | --- | --- | --- |
| light | Screen (brightness; on/off = panel) | Phase 1 | Normal light automations |
| media\_player | Device speaker | Phase 1 | `tts.speak` and `media_player.play_media` work natively; Music Assistant later |
| notify (NotifyEntity) | On-screen notifications | Phase 1 | `notify.send_message` works; `tabula.notify` adds type, duration, icon, chime |
| switch | Screensaver active, keep screen on, adaptive brightness; kiosk lockdown later | Phase 1 (lockdown: Phase 4) |  |
| number | Screensaver brightness | Phase 1 | Volume lives on media\_player |
| select | Screensaver mode, dashboard view | Phase 1 |  |
| button | Restart app, reload dashboard, clear cache | Phase 1 |  |
| sensor / binary\_sensor | Battery, charging, connectivity, current page, next screensaver, app version | Phase 1 |  |
| image | Latest screenshot (on demand) | Phase 3 | Not camera, to avoid confusion with deferred camera work |
| update | App version + install | Phase 5 | Needs update orchestration |
| assist\_satellite | Voice | Phase 7 | HA's native custom-satellite path |

State flows from `tabula/state` deltas into entities. Coalesce updates; target under one second from device change to HA state.

**Brightness ownership:** the adaptive-brightness switch decides who drives the panel. While it is on, the sensor curve sets brightness, and a manual change through the light entity turns adaptive off. While it is off, the light entity's value holds. The light entity always reports the actual level.

### 5.6 Settings are data, not entities

- Settings are defined once in a JSON Schema (`schema/settings.schema.json`). The integration validates against it in Python; Kotlin is generated in P2.0 and TS in Phase 5.
- Stored per device in an HA Store with a monotonically increasing revision. A change is pushed as a `set_settings` command, and the device ACKs with the revision it applied.
- **HA is authoritative.** At registration the device reports its applied revision. If it differs, the integration pushes its copy. Settings changed on the device (only possible through the recovery page, Phase 4) are limited to connection keys and are reported back, not merged.
- The device caches settings locally so it keeps working while HA is down.
- Before the panel exists (Phases 1–4), settings are edited through the hub's options flow, which opens with a device picker and then a per-device form: a native HA UI with no frontend work.
- Each schema property carries a role tag (`identity`, `connection`, `hardware`, `shared`). Profiles (Phase 5) may only manage `shared` keys, enforced server-side.

### 5.7 Events

Fired on the HA bus as `tabula_notification`, `tabula_screensaver`, `tabula_alarm`, `tabula_timer`, … with `device_id` (HA device registry ID) and `tabula_device_id` in the payload.

### 5.8 Dashboard WebView

- Authenticate the HA frontend through its external auth / external bus interface (`window.externalApp`), the mechanism the Companion app uses. Do not inject tokens into localStorage.
- The external bus is also the channel for `tabula://` navigation and future plugin hooks.
- **Browser floor:** the HA frontend supports only recent Chromium versions. P0.3 records the Office Kiosk's WebView version against that floor and how WebView updates arrive on Fire OS.
- **Background disconnect:** the frontend drops its socket when the page is hidden. Phase 2 measures whether this triggers in practice before adding any shim. The screensaver's WebView-hiding optimization (Phase 4) must be designed together with this behavior.

### 5.9 Management panel and recovery

- **Tabularium:** a custom panel registered by the hub entry and served as a static bundle: device list, settings editor, profiles, logs, screenshots and update orchestration (Phase 5).
- **Recovery before Phase 4:** ADB only, documented in DEVICE.md (clear app data, or `adb push` a connection config file that the app reads on start).
- **Recovery page (Phase 4):** a minimal on-device HTTP page to re-point the HA URL/credential or reset settings. It is the most sensitive surface on the device, because re-pointing the URL can send the dashboard to a hostile server.
  - Off by default. Enabled from HA (a switch) or over ADB, and it turns itself off after a timeout chosen by the architect.
  - The PIN is set from HA (stored hashed on the device) or over ADB. There is no default PIN; with no PIN set, the page does not start.
  - Rate-limited, with lockout after repeated failures.
  - The port is our own choice, recorded in PROTOCOL.md. Nothing else goes through it.
- **Peer features** (intercom, wake-word arbitration) use HA only as the directory. Audio always goes directly between devices.

## 6. Repository layout

```text
tabula/
├── AGENTS.md                    build-side executor instructions; tool files point here
├── PROVENANCE.md
├── LICENSE                      Apache-2.0
├── README.md
├── Makefile                     single build entry point; CI runs the same targets (ADR 0004)
├── pyproject.toml, uv.lock      Python tooling; uv workspace root
├── .github/workflows/           CI: make setup && make check
├── docs/
│   ├── PLAN.md                  this plan
│   ├── DEVELOPMENT.md           toolchain, cloud VMs, CI
│   ├── FEATURES.md              spec-side output: behavior in our words, no constants
│   ├── PROTOCOL.md              TLP v1
│   ├── DEVICE.md                ADB provisioning + recovery runbook (Fire HD 10 Plus, Echo Show 8)
│   ├── decisions/               ADRs (0001 HA platform, 0002 constants, …)
│   └── packets/                 work packets + results
├── schema/
│   ├── settings.schema.json     single source of truth for settings
│   └── fixtures/                golden TLP frames, used by both ends
├── integration/
│   ├── custom_components/tabula/   __init__, config_flow (hub + discovery + options),
│   │                               pairing, device, ws_api, entity platforms,
│   │                               services, storage, migrations, diagnostics, panel
│   ├── tests/
│   └── hacs.json
├── mockdevice/                  Python TLP reference device (dev loop + conformance)
├── panel/                       Vite + TS (Phase 5), built into the integration
├── android/                     Gradle project (core, ha, web, screen, screensaver,
│                                overlay, kiosk, audio, service; voice later)
├── tools/                       repo checks, bootstrap, codegen, frame validator, device and release scripts
└── website/                     project website, VitePress (ADR 0005)
```

## 7. Phases

Phases 0–2 are written as packets. Phases 3–9 are a roadmap, and each gets its own packet breakdown before it starts.

### 7.0 Packets and dependencies (Phases 0–2)

| Packet | Side, tier | Depends on | Verification |
| --- | --- | --- | --- |
| P0.1 Repo and charter | build, light | none | ci |
| P0.2 Feature spec | spec, deep | P0.1 | review |
| P0.2r Spec review | spec, deep | P0.2 | review |
| P0.3 HA platform spike | build, deep | P0.1 | dev HA |
| P0.4 TLP v1 + constants | build, deep | P0.3 | ci |
| P0.5 Settings schema v1 | build, standard | P0.2r, P0.4 | ci |
| P0.6 Echo Show 8 feasibility | build, standard | none | hardware |
| P1.1 Mock device | build, standard | P0.4 | ci |
| P1.2 Hub, pairing, registration | build, deep | P1.1 | ci |
| P1.3 Entity platforms | build, standard | P1.2 | ci |
| P1.4 Commands and services | build, standard | P1.3 | ci |
| P1.5 Settings, options flow, events | build, standard | P1.2, P0.5 | ci |
| P2.0 Kotlin codec + codegen | build, standard | P0.4, P0.5 | ci |
| P2.1 Service, pairing, HA client | build, deep | P1.2, P2.0 | hardware |
| P2.2 Dashboard WebView | build, deep | P2.1 | hardware |
| P2.3 Screen control | build, standard | P2.1, P1.4 | hardware |
| P2.4 Screensaver engine | build, standard | P2.1, P1.4 | hardware |
| P2.5 Notifications and audio | build, standard | P2.4 | hardware |
| P2.6 Offline-first check | build, standard | P2.2–P2.5 | hardware |
| P2.7 Echo Show 8 bring-up | build, standard | P0.6 (go), P2.6 | hardware |

Parallel tracks: P0.2 (spec side), P0.3 and P0.6 (build side) run at the same time. After P0.4, the integration track (P1.x) and the Android codec (P2.0) run in parallel.

### 7.1 Phase 0 — Charter, spec, spike, protocol

**P0.1 — Repo and charter** (build, light)

- Create the monorepo, LICENSE (Apache-2.0), .gitignore, AGENTS.md (from sections 1–2 of this plan, with all doc cache paths removed), PROVENANCE.md (including the human-exception note), README.md (per the public framing rule in section 2), `docs/packets/TEMPLATE.md` and symlinked tool instruction files.
- Use the private repo tlockney/tabula and the package name net.lockney.tabula (both decided), and record them in an ADR.
- Done when: the initial commit exists; AGENTS.md contains the build-side clean-room rules and the definition of done; a grep for the cache directory name returns nothing.

**P0.2 — Feature spec** (spec, deep), then **P0.2r — review** (spec, deep, preferably a different model family)

- Read the cached docs and write `docs/FEATURES.md`: one entry per in-scope behavior, with a stable ID (F-SCR-03) and the behavior in our words. No constants.
- Exclude dropped and deferred items except as one-line hooks.
- The reviewer compares the result against the docs for close paraphrase, leaked constants and gaps.
- Done when: every in-scope area has entries and the review notes are resolved.
- This is the only packet that ever touches the doc cache.

**P0.3 — HA platform spike** (build, deep, timebox: 3 working days)

Use throwaway code against a dev HA instance to confirm or correct the assumptions this plan rests on:

1. Hub entry + `SOURCE_INTEGRATION_DISCOVERY` flow triggered from a custom HTTP view.
2. Which credential type an integration-created, non-admin user can use for (a) custom WebSocket commands and (b) loading the dashboard via external auth.
3. Frontend external auth in an Android WebView (read how the Companion app does it).
4. Custom `websocket_api` subscription delivery, practical message-size limits, and resolving the caller's user inside a command (for connection binding).
5. NotifyEntity and media\_player `play_media` receiving a `tts.speak` URL, and whether that URL is fetchable with the device credential.
6. The Office Kiosk's WebView version against the HA frontend's supported-browser floor, and how WebView updates arrive on Fire OS. Note what this means for PF1007L frames.
7. `assist_satellite` base class requirements (notes only, for Phase 7).
8. The minimum HA version that supports all of the above.

Output: `docs/decisions/0001-ha-platform.md`, recording what worked (with evidence), what didn't and the chosen fallbacks. If items 1–3 or 6 fail, the architect revises sections 5.2, 5.3 or 5.8 before Phase 1. If the timebox runs out, hand back with findings so far.

**P0.4 — TLP v1** (build, deep; depends on P0.3)

- Write `docs/PROTOCOL.md`: pairing and claim, register/registered (protocol and schema versions, capabilities, settings revision), state deltas, command frames with `command_id`, result frames, `set_settings`, events, log subscription, the HTTP screenshot upload, reconnect/backoff rules and the version compatibility rules (5.4).
- Write `docs/decisions/0002-constants.md` with the protocol's timeouts, backoff bounds, rate limits and the Phase 1–2 behavior defaults.
- Every frame type gets a golden fixture in `schema/fixtures/`.
- Done when: `tools/validate_frames.py` validates all fixtures against the JSON Schema definitions in PROTOCOL.md.

**P0.5 — Settings schema v1** (build, standard; depends on P0.2r, P0.4)

- Write `schema/settings.schema.json` covering only what Phases 1–2 use: identity, connection, screen, screensaver, notifications and audio. Kiosk, gestures and updates are added by the phases that build them.
- `schema_version: 1`, a role tag on every property, defaults taken from ADR-0002.
- Python loads it directly.
- Done when: the schema accepts a fixture of defaults and rejects a set of known-bad fixtures (pytest).

**P0.6 — Echo Show 8 feasibility** (build, standard; hardware)

- Research whether ADB can be enabled on the Echo Show 8 (2nd gen) through its own settings, with no exploits, root or firmware changes.
- Hardware, read-only: the ADR 0001 item 6 device check plus display, sensor and home-activity queries. Then a sideload test with the F-Droid client APK.
- Go if ADB is available by those means, a sideloaded app launches, stays resumed while idle and can be relaunched after leaving it, and the WebView is Chromium 67 or later. Start on boot is not part of the gate; P2.1 checks it.
- Done when: a new ADR states go or no-go with pasted device output. On no-go, the Echo Show moves to Deferred everywhere it appears (sections 3, 4, 7.0, 7.3 and 9) and P2.7 is closed as not planned.

### 7.2 Phase 1 — Integration core, driven by the mock device

**P1.1 — Mock device** (standard; depends on P0.4)

- A Python CLI that speaks TLP: pairing and claim → register → state pushes → command handling with `tabula/result` ACKs (configurable failure and delay modes) → reconnect.
- It validates its own frames against the fixtures.
- A conformance suite (`mockdevice/conformance/`) replays the fixture sequences against any TLP server and checks responses.
- Done when: mock unit tests pass and every fixture round-trips through the mock's codec. End-to-end pairing against HA is checked in P1.2.

**P1.2 — Hub, pairing, registration** (deep; depends on P1.1)

- Hub config flow, pairing and claim views, discovery flow, per-device user creation per ADR-0001, WebSocket command registration with connection binding, version checks, device registry entries, newest-connection-wins, diagnostics.
- Tests:
  - Pairing happy path, end to end with the mock (`python -m mockdevice pair --ha http://…`).
  - Wrong code is rejected.
  - Claim with the wrong nonce is rejected; the credential is released once only.
  - Rate limit and pending cap hold; pending entries expire.
  - An unpaired device\_id is rejected with no side effects.
  - A device's credential cannot act for another device.
  - An unsupported protocol major is rejected with a repair issue.
  - Double registration leaves one device on the latest connection.
  - Deleting the device revokes its credential.
  - Diagnostics output contains no credential.

**P1.3 — Entity platforms** (standard; depends on P1.2)

- The section 5.5 platforms marked Phase 1, created from declared capabilities.
- Availability follows the connection. Nothing is removed on disconnect.
- Tests: a state delta reaches the right entity in under one second; disconnect makes all entities unavailable; reconnect restores them; an undeclared capability creates no entity.

**P1.4 — Commands and services** (standard; depends on P1.3)

- Command dispatch with `command_id`, result awaiting and timeouts.
- `tabula.*` services (entity-targeted) and the standard-platform service handlers.
- Brightness ownership rule (5.5).
- Tests: `notify.send_message` and `tabula.notify` reach the mock and get ACKed; a NACK or timeout raises `HomeAssistantError`; `tts.speak` targeting the media\_player sends `play_media` with a URL the mock can fetch with its credential; a manual brightness change turns adaptive off.

**P1.5 — Settings, options flow, events** (standard; depends on P1.2, P0.5)

- Store-backed per-device settings with revisions, `set_settings` push + ACK, HA-authoritative drift handling at registration, schema-version translation scaffold.
- An options flow (device picker, then per-device form) and the event bus relay.
- Tests: settings round-trip; an options-flow edit reaches the mock; a mock with a stale revision gets HA's copy on registration; events appear on the bus with both IDs.

**Phase 1 exit:** from the HA UI, pair the mock and see its full Phase 1 entity set. An automation can notify it, speak TTS to it and start its screensaver. A mock-side state change shows in HA within a second. Settings can be edited from the options flow. The mock conformance suite runs in CI.

### 7.3 Phase 2 — Android MVP on the Office Kiosk

**P2.0 — Kotlin codec and settings codegen** (standard; depends on P0.4, P0.5)

- Gradle project skeleton (minSdk per ADR-0001, targetSdk per a new ADR), the TLP frame codec, and Kotlin settings classes generated from the schema by `tools/codegen`.
- Plain JVM unit tests run the codec against `schema/fixtures/`.
- Done when: `./gradlew test` passes in CI, and regenerating the settings classes produces no diff.

**P2.1 — Service, pairing, HA client** (deep; depends on P1.2, P2.0; hardware)

- Foreground service, boot receiver, pairing screen (code + device\_id), nonce and claim, Keystore-backed credential storage, persisted device\_id and the WS client (auth, register with versions, command loop, ACKs, backoff, heartbeat). Battery, charging, connectivity and app-version sensors.
- Start `docs/DEVICE.md`: the ADB provisioning steps this phase needs (install, `pm grant`, `appops set` for WRITE\_SETTINGS, deviceidle whitelist, `dpm set-active-admin`) as a script in `tools/device/`, plus ADB recovery steps.
- Done when (hardware): the tablet pairs and appears in HA; airplane mode makes it unavailable; reconnect brings it back (ADB-scripted check + HA state query). If P0.6 said go, the same build is provisioned on one Echo Show per DEVICE.md, launched and paired, then rebooted, and the hardware verifier records whether the service starts on boot. If P0.6 has not concluded, this observation is skipped and noted.

**P2.2 — Dashboard WebView** (deep; depends on P2.1; hardware)

- Full-screen WebView with external-auth bootstrap, `tabula://` interception and `app://` passthrough.
- Measure the frontend's background-disconnect behavior and record the results in an ADR before writing any shim.
- Done when (hardware): the dashboard loads with the device credential; internal links stay in the WebView; screen off/on doesn't reload.

**P2.3 — Screen control** (standard; depends on P2.1, P1.4; hardware)

- Brightness via `WRITE_SETTINGS` (window-dimming fallback), keep-on wake lock and panel off/on (device-admin lock + wake).
- Adaptive brightness from the light sensor, using a curve we design, and the brightness ownership rule.
- Done when (hardware): the HA light entity controls the real panel; adaptive brightness keeps working with the network down; a manual change turns adaptive off.

**P2.4 — Screensaver engine** (standard; depends on P2.1, P1.4; hardware)

- Idle timer, Compose overlay, Black and Digital Clock modes, dismiss on touch, an external HA trigger to dismiss and the next-screensaver sensor.
- Timings come from ADR-0002.
- Done when (hardware): the tablet idles into the clock, and an HA automation can start and dismiss it.

**P2.5 — Notifications and audio** (standard; depends on P2.4; hardware)

- Notification cards (stacking, duration, type, icon, chime), the media\_player playing HA TTS URLs and lifecycle events.
- Done when (hardware): an HA automation shows a card with a chime and a TTS announcement, and the events are visible in HA.

**P2.6 — Offline-first check** (standard; after P2.2–P2.5; hardware)

- With HA stopped, the screensaver, clock, brightness curve and cached dashboard behave sensibly. The device re-dials and recovers when HA returns.
- Written as an ADB-scripted acceptance test in `tools/device/`.

**P2.7 — Echo Show 8 bring-up** (standard; depends on P0.6 with a go result, P2.6; hardware)

- Provision one Echo Show from DEVICE.md, add an Echo Show section and make `tools/device/` handle both device types.
- Run the P2.1–P2.6 hardware checks on it and fix the differences: screen size, available sensors, Alexa home-screen behavior, device-admin lock and wake.
- Done when (hardware): the P2.6 acceptance script passes on one Echo Show, and the second is provisioned from DEVICE.md alone and pairs. This is the Echo Show exit below.

**Phase 2 exit:** the Office Kiosk boots into Tabula, shows the dashboard, idles to a clock, takes brightness, notifications and TTS from HA, and survives an HA restart. The second Fire HD 10 Plus is provisioned from DEVICE.md alone and passes the P2.6 acceptance script. P2.6 checks this exit.

**Echo Show exit** (only if P0.6 said go; checked by P2.7): one Echo Show passes the P2.6 acceptance script, and the second is provisioned from DEVICE.md alone and pairs.

### 7.4 Phases 3–9 — Roadmap

Each phase gets a packet breakdown by the architect (`docs/packets/P{n}-*.md`) before execution, informed by what the previous phase taught.

- **Phase 3 — Screensavers and display depth.**
  - Additional clock faces and night mode.
  - Weather screensaver: Canvas animation on API < 33, AGSL optional on 33+.
  - Gallery and website modes.
  - At a Glance chips via a dedicated, explicit-list `subscribe_entities` connection that closes with the screensaver.
  - Corner widgets and a time-based schedule.
  - Screenshots: capture on device, HTTP upload view, image entity.
- **Phase 4 — Kiosk, launcher, navigation, gestures, recovery, optimization.**
  - Kiosk mode (pinning, watchdog, shields, PIN exit) and lockdown mode; kiosk lockdown switch.
  - Launcher role on generic Android only, with a crash fuse.
  - Single-WebView navigation, and a gesture/action registry (the future plugin seam).
  - Recovery page (5.9).
  - Shizuku setup added to DEVICE.md.
  - Optimization, benchmark first: idle-behind-screensaver CPU on the HD 10 Plus, target < 15%. Hide or pause the WebView behind opaque screensavers. The frontend entity-filter shim is only built if the benchmark still misses target, because it patches HA frontend internals and will break on upgrades.
- **Phase 5 — The Tabularium.**
  - Panel registration, device list, schema-driven settings editor (TS codegen starts here), log viewer, screenshots, command buttons.
  - Profiles: `shared`-role keys only, diff and push, drift report.
  - Update orchestration via the update entity (release source per section 10; staggered). Depends on Phase 4's Shizuku work for silent install on Fire OS; otherwise each update needs a tap on the device.
  - Recovery-page controls (enable, PIN, timeout).
- **Phase 6 — Household.**
  - Alarms via AlarmManager (they work with HA down), with ringing UI, snooze, sunrise ramp and events.
  - Timers in v1 mirror HA timer entities onto the device. Assist voice timers come with Phase 7 through `assist_satellite`.
  - Read-only calendar views and a calendar screensaver.
- **Phase 7 — Voice (gated).** P7.0 is a mic-persistence spike on each Fire OS device type in use (the Fire tablet, and the Echo Show if it is in v1 and runs Fire OS). For each device type that passes, implement an `assist_satellite` entity in the integration and stream audio through the Assist pipeline API (per the ADR-0001 notes): wake word on device, overlay, chimes, cross-device arbitration of our own design. Device types that fail get no voice; voice may then target other hardware only.
- **Phase 8 — Media (gated).** Following other HA media players, a DLNA renderer, a now-playing overlay, Music Assistant. EQ, echo cancellation and AirPlay only if voice lands.
- **Phase 9 — Intercom (future).** HA as the directory. Direct device-to-device PCM audio with our own framing. Signaling over TLP.

Hooks to preserve, not build:

- A `camera_view` slot in navigation, plus an ADR noting the WebRTC gap in the Fire WebView.
- The `window.tabula` bridge + action registry as the plugin seam, with an ADR on the manifest concept.

### 7.5 Packets and dependencies (Phase 3)

Written by the architect in issue #21's breakdown (`docs/packets/P3.*`, issues #59–#72). The settings schema bumps to v2 (P3.1); the only TLP change is P3.9's capability-gated `slide` command, which is additive and does not bump the protocol major (PROTOCOL 8). Device facts the Phase 2 hardware checks (#15–#20) have not verified yet are handled inside the packets: P3.4 (light sensor at panel-off), P3.7 (animation CPU headroom) and P3.10 (the on-device dashboard WebView — external-auth bootstrap and link handling, settled by #16's run or a 1-working-day spike) each start with a timeboxed device spike. The night-mode flag (`NightState`, never-night default) is pre-committed in P3.1's ADR 0002 extension, so P3.6 and P3.11 do not wait on P3.4's hardware-dependent work.

| Packet | Side, tier | Depends on | Verification |
| --- | --- | --- | --- |
| P3.1 Settings schema v2 and options flow | build, deep | none | ci |
| P3.2 Settings entities | build, standard | P3.1 | ci |
| P3.3 Clock faces and background image | build, standard | P3.1 | hardware |
| P3.4 Clock night mode | build, standard | P3.3 | hardware |
| P3.5 Entity data connection (subscribe_entities) | build, deep | P3.1 | ci |
| P3.6 At a Glance row | build, standard | P3.5 | hardware |
| P3.7 Weather screensaver | build, deep | P3.5 | hardware |
| P3.8 Gallery core (photo slideshow) | build, deep | P3.1 | hardware |
| P3.9 Gallery media browser and slide buttons | build, standard | P3.8 | hardware |
| P3.10 Website mode | build, standard | P3.1, #16 or spike | hardware |
| P3.11 Corner widgets | build, standard | P3.5, P3.6 | hardware |
| P3.12 Screensaver schedule | build, standard | P3.1 | hardware |
| P3.13 Screenshots (capture, upload, image entity) | build, standard | none | hardware |
| P3.14 Engine polish (pixel shift, fade, dim level) | build, standard | P3.1 | hardware |

Parallel tracks after P3.1: the clock track (P3.3 → P3.4), the entity-data track (P3.5 → P3.6 → P3.11, and P3.5 → P3.7), the gallery track (P3.8 → P3.9), and P3.10, P3.12, P3.13 and P3.14 independently. P3.13 has no Phase 3 dependency and can run immediately. Night wiring lands in whichever of P3.4, P3.6 and P3.11 merges last, against the pre-committed `NightState` contract. Integration-side work (P3.1, P3.2, P3.13's view and entities, P3.9's buttons) is separated from Android rendering wherever the sides can move at their own pace.

Out of Phase 3 scope, recorded for later: F-SSV-15 (HA dashboard view mode), F-SSV-19/F-SSV-20 (panel off after a while, wake behavior), F-SSV-24 (HA presence source), the postpone action, and F-WGT-07/F-WGT-08 (alarm and calendar widgets, Phase 6).

## 8. Testing and validation

- **Protocol:** golden fixtures in `schema/fixtures/` are the contract. The mock device, the integration tests and the Android codec tests all validate against them.
- **Integration:** pytest with pytest-homeassistant-custom-component; the mock device's conformance suite runs in CI.
- **Versioning:** once a second protocol major exists, CI also runs the conformance suite with the previous major's fixtures.
- **Android:** JVM unit tests for the codec, settings and curves; Robolectric only where Android APIs are involved; ADB-scripted acceptance per phase exit (screenshots, logcat assertions, `dumpsys` CPU).
- **CI (GitHub Actions):** schema + fixture validation, codegen-drift check, integration pytest, panel build (Phase 5+), Android assemble + unit tests. Hardware tests stay manual but scripted, run by the hardware verifier.
- **Evidence rule:** a packet or phase is "passing" only with pasted command output. A claim without output counts as unverified.

## 9. Risks and tradeoffs

| Risk | Mitigation |
| --- | --- |
| Stolen tablet can still control the house (non-admin users can call any service) | Accepted residual risk. No admin rights, per-device credential, one-click revocation; DEVICE.md says to revoke first |
| Credential sniffed on a plain-http LAN | Documented; HTTPS recommended in DEVICE.md |
| Pairing/credential design doesn't work as assumed | P0.3 spike before any Phase 1 code; documented fallback (manual non-admin user + LLAT) |
| Fire OS WebView too old for the HA frontend | Checked in P0.3 before Phase 1; PF1007L frames not committed to until checked |
| Echo Show 8 cannot run a sideloaded app without exploits | P0.6 decides before any Echo Show code; no exploits allowed; on no-go the Echo Show moves to Deferred and the Fire tablets carry the milestone |
| Echo Show 8 blocks start on boot | Checked in P2.1 on one Echo Show, before the rest of Phase 2; if blocked, the architect decides in an ADR whether a manual start is acceptable or the Echo Show moves to Deferred |
| App and integration versions drift | Version negotiation at registration, N-1 support, repair issue on mismatch |
| Single WS per device; HA restarts | Jittered re-dial; offline-first behavior is a Phase 2 acceptance test (P2.6) |
| Frontend internals change (external auth, background disconnect, entity filter) | Use only the documented external-auth interface; measure before shimming; entity filter is conditional |
| Recovery page used to re-point a tablet | Off by default, no default PIN, auto-off timeout, lockout; Phase 4 |
| Voice on Fire OS (mic revocation) | Gated spike; voice may be non-Fire only |
| Silent updates on Fire OS | Shizuku in Phase 4; otherwise a manual tap |
| Weather animation on API 28 | Canvas path first; AGSL optional |
| Clean-room leakage through executors | Two-sided separation; cache paths only in the spec-side brief; no constants in FEATURES.md; spec review by a second model family |
| Clean-room leakage through Thomas | Recorded in PROVENANCE.md; design input via FEATURES.md or reasoned ADRs; no constants from memory |
| Hardware verification bottleneck | Packets split CI vs hardware parts; device checks scripted so one session covers several packets |
| Mixed executors produce inconsistent work | Packets, AGENTS.md, cross-family review, executable done-criteria |
| Effort | Phases 0–2 are the first milestone ("dashboard + clock + HA control"); everything else is roadmap |

## 10. Open questions

None of these block Phase 0. Decided: package name net.lockney.tabula; repo tlockney/tabula (private).

- **Credential type for paired devices:** answered by P0.3.
- **Release source for updates:** GitHub releases on the private repo (needs a token on the HA side) or a static folder on the homelab? Needed by Phase 5.
- **HTTPS on the local HA:** already in place, or http for now? Affects DEVICE.md only.

## 11. Changes from v2

| Area | v2 | v3 | Why |
| --- | --- | --- | --- |
| Threat model | "Not a whole-house compromise" | Residual risk stated: non-admin users can call any service | HA has no per-entity permissions |
| Credential pickup | Returned to the waiting device | Claimed with a hidden nonce whose hash was sent at pairing | device\_id is public; it can't authorize pickup |
| Connection binding | Unstated | Device resolved from the authenticated user; spoofing test | One device must not act for another |
| Pairing view | Rate-limited | Rate limit + pending cap + TTL | Unauthenticated view could flood discovery |
| Transport / storage | Unstated | http risk documented; Keystore-backed storage | Credential handling was undefined |
| Recovery page | On before pairing, PIN source undefined, no packet | ADB until Phase 4; then off by default, no default PIN, auto-off | Most sensitive surface; was unscheduled |
| AGENTS.md | Built from sections 1–2, including cache paths | Cache paths only in a spec-side brief | Build side would see the paths |
| Constants | Chosen by spec author in FEATURES.md | FEATURES.md has none; architect picks in ADR-0002 | The spec author has seen the docs' values |
| Human role | Unaddressed | Human exception recorded; input via FEATURES.md or ADRs | Thomas reads both sides |
| Dependencies | None stated | Table in 7.0; Depends-on per packet | Parallel work needs them |
| P1.1 vs P1.2 | P1.1 done-when needed P1.2's pairing | P1.1 fixture-only; end-to-end pairing in P1.2 | Circular dependency |
| Provisioning | DEVICE.md in Phase 4 | Starts in P2.1 | Phase 2 already needs ADB grants |
| Kotlin codegen | Promised for Phase 2, no packet | P2.0 | Gap |
| Entity scope | All platforms in P1.3 | Arrival phase per platform; image in Phase 3, update in Phase 5 | Nothing fed them in Phases 1–2 |
| Log subscription | In protocol, no consumer | Protocol only; viewer in Phase 5 | Scheduled explicitly |
| Versioning | None | Protocol + schema versions at registration; N-1 support; migrations | APK and HACS update independently |
| WebView floor | Unchecked | P0.3 item 6 | HA frontend drops old browsers |
| Settings conflicts | Unstated | HA authoritative; brightness ownership rule | Ambiguous behavior |
| Package name | Before public release | Before first real install | Changing it forces re-pairing |
| Hardware checks | Executors mark unverified | Hardware verifier role; CI/hardware split | Cloud executors can't reach the tablet |
| P0.3 timebox | None | 3 working days | Spikes expand |
| Settings schema v1 | All areas incl. kiosk, gestures, updates | Phases 1–2 areas only | Avoid designing ahead of need |
| Entity IDs | Forced `tabula_{device}_{key}` | `has_entity_name`, HA-derived | Current HA practice |
| Diagnostics | None | HA diagnostics platform, redacted | Cheap and useful for debugging |
| Doc formatting | Template and frame diagram garbled by angle-bracket placeholders | Placeholders use parentheses and braces | Rendering fix |
| Public framing | Unaddressed; goal framed as reproducing the original | Lineage stated plainly in a few named places; no comparison wording; provenance never scrubbed | Repo and this plan go public later |
