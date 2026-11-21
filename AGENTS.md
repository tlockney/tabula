# AGENTS.md

Standing instructions for anyone working in this repository: people, agents and models alike. Tool-specific files (`CLAUDE.md`, `GEMINI.md`, `.cursorrules`, `.github/copilot-instructions.md`) are symlinks to this file. Do not add divergent rules elsewhere.

The repository is the only shared memory. Everything you need is in the repo plus your packet brief. Nothing depends on chat history or tool-specific memory.

## Start here

1. Read this file.
2. Read your packet in `docs/packets/` and every file it lists under "Read first".
3. Run `make setup`, then `make check` to confirm a clean baseline.

Background: `docs/PLAN.md` (the plan), `docs/decisions/` (ADRs), `docs/DEVELOPMENT.md` (toolchain and environments).

## Build and test

`make` is the only entry point. CI runs the same targets. Do not invoke component tools in ways the Makefile does not, and add a target when a new component needs one.

| Command | Does |
| --- | --- |
| `make setup` | Install pinned tools and dependencies (idempotent) |
| `make check` | Everything CI checks: lint, format check, repo checks, tests, site build |
| `make fmt` | Apply formatting and auto-fixable lint |
| `make test` | Tests only |
| `make site` | Build the website into `build/site` |
| `make help` | List targets |

Component targets (integration, mock device, Android, panel) are added by the packets that create those components.

## Repository layout

| Path | Contents | Arrives |
| --- | --- | --- |
| `docs/PLAN.md` | Implementation plan | P0.1 |
| `docs/FEATURES.md` | Behavior spec, written by the spec side only | P0.2 |
| `docs/PROTOCOL.md` | Tabula Link Protocol (TLP) | P0.4 |
| `docs/DEVICE.md` | ADB provisioning and recovery runbook | P2.1 |
| `docs/decisions/` | ADRs | ongoing |
| `docs/packets/` | Work packets and their results | ongoing |
| `schema/` | `settings.schema.json`, `fixtures/` (golden TLP frames) | P0.4, P0.5 |
| `integration/` | HA custom integration | P1.2 |
| `mockdevice/` | Python TLP reference device | P1.1 |
| `panel/` | Tabularium panel (Vite + TS) | Phase 5 |
| `android/` | Android Gradle project | P2.0 |
| `tools/` | Repo checks, codegen, frame validator, device and release scripts | ongoing |
| `website/` | Project website | P0.1 (placeholder) |

Generated output goes to `build/` (or a component's own tool-managed build directory) and is never committed.

## Code style

- Python: formatted and linted by ruff (config in `pyproject.toml`). Type hints on public functions. Tests with pytest.
- Kotlin, TypeScript: set by the packets that introduce them.
- Names: integration domain `tabula`; services `tabula.*`; events `tabula_*`; Android package `net.lockney.tabula`.
- Constants (ports, timeouts, thresholds, defaults) are chosen by the architect and recorded in `docs/decisions/0002-constants.md`. Do not invent them in code.

## Clean-room rules (binding)

Tabula's feature set was informed by the public documentation of Kiosk Satellite, which is licensed CC BY-NC-ND 4.0 (no derivatives). Tabula is built clean-room with a two-sided separation.

- **Spec side** reads that product's documentation and writes only `docs/FEATURES.md`, in our own words, with no constants. Spec-side instructions live outside this repo.
- **Build side** is everyone else (architect, implementer, reviewer, mechanic, hardware verifier). If you are reading this file, you are on the build side. You see only this repository.

Build-side rules:

- Never read Kiosk Satellite documentation, source code, APKs, decompilations or its plugin SDK interface files. Naming it as prior art is fine.
- If `docs/FEATURES.md` does not answer a question, the architect designs the answer in an ADR. Nobody goes to the original docs to check.
- One context never holds both sides. A session that has read the original docs does not write code here.
- Never fork, vendor or patch the original.
- Other prior art is allowed: Apache/MIT code that is not Kiosk Satellite, notably the HA Companion Android app and HA core and frontend. Record what you learned in an ADR and keep attribution for any adapted code.
- Prefer standard engineering over unusual techniques.
- Record every user-visible behavior in `PROVENANCE.md`: the FEATURES.md item ID it came from, or "independent design".

## Public framing

The repository will become public. The lineage is stated plainly in a few places, never hidden and never promoted.

- Kiosk Satellite may be named only in: the README's prior-art line, `docs/PLAN.md` (overview and sections 1–2), this file's clean-room rules, `PROVENANCE.md`, and ADRs that need it.
- Never in code, identifiers, UI strings, commit messages or release notes. `make check` enforces this for files.
- No "clone", "replacement", "port" or "free alternative to" wording. Describe Tabula's behavior, not how it compares.
- Never remove or rewrite provenance history.

## Work packets

Every unit of work is a packet in `docs/packets/`, written before execution starts. Template: `docs/packets/TEMPLATE.md`.

- The brief names every file to read. You never need the conversation that produced it.
- "Done when" lists commands. Run them and paste their output in the Result section.
- `Verification: hardware` packets have a CI part and a device part. A cloud executor completes the CI part and sets `Status: awaiting-hardware`. The hardware verifier runs the device part and pastes its output. Nobody reports success they have not observed.
- One packet, one branch, one pull request. A reviewer other than the implementer approves before merge.
- Work above your capability tier: hand it back with a note rather than guess.
- After three failed approaches, or when a timebox runs out: stop, record what you tried in the Result section and hand back to the architect.

## Work tracking

GitHub issues are the unit of delegation. Each packet has one issue (template "Packet"); work too small for a packet gets a "Task" issue with the same brief shape. GitHub Projects may be layered on later.

- The brief lives in the repo (`docs/packets/`), not in the issue, so it is versioned and reviewed with the code. The issue records who has the work, its state and its pull request.
- Labels: `packet` or `task`; `side:spec` or `side:build`; `tier:light`, `tier:standard` or `tier:deep`; `verification:hardware`; `awaiting-hardware`; `blocked`.
- Taking work: assign yourself (or comment that you have it) before starting. Dependencies are listed in the issue; do not start until they are closed.
- The pull request says `Closes #N`. Discussion that changes the brief is folded back into the packet file in the same pull request.

## Definition of done (every packet)

- All "Done when" commands pass, with output pasted into the packet's Result section (hardware steps pasted by the hardware verifier).
- `make check` passes.
- Business logic has tests. Pure config or scaffolding may not.
- `PROVENANCE.md` has an entry for any new user-visible behavior.
- No prose, identifiers or constants copied from the original product's documentation.
- The reviewer, not the implementer, has signed off.

## Git

- Branch per packet; pull request per branch.
- Commit messages describe the change. No tool or agent attribution trailers (`Co-Authored-By`, session links, "generated with" footers) and no links to chat threads or sessions. The same applies to pull request descriptions and issue comments.
- AI agents are used openly in this project; attribution is left out so history stays uniform across many tools, not to hide it.
