# 0004: Build entry point and toolchain

Status: accepted
Date: 2026-11-21
Packet: P0.1

## Context

Work is done by people and agents on varied machines, often short-lived cloud VMs with restricted network egress. The repo holds Python (integration, mock device, tools), Kotlin (Android), TypeScript (panel, website) and docs. Each executor needs one obvious, reproducible way to build and check everything, and CI must not diverge from it.

## Decision

- **Entry point:** a root `Makefile`. Targets: `setup`, `check`, `lint`, `fmt`, `test`, `site`, `site-dev`, `clean`, `help`. Component packets add targets (for example `android-test`) and extend `check`. CI runs `make setup` and `make check` only.
- **Python:** uv, with a root `pyproject.toml` and `uv.lock`. The integration and mock device become uv workspace members when they arrive. Python 3.13 (`.python-version`); P0.3 may raise it to match the minimum HA version.
- **Node:** npm with committed lockfiles, Node 22+ (`.node-version`). The website uses it now; the panel from Phase 5.
- **Android:** the Gradle wrapper, added in P2.0, invoked through make targets.
- **Bootstrap:** `tools/bootstrap.sh` installs only from package registries (uv from PyPI) and checks versions. No `curl | sh`, no GitHub release downloads.
- **Lint and policy:** ruff for Python; `tools/check_repo.py` enforces the instruction-file symlinks and the public framing rule, reading restricted names from `PROVENANCE.md` so they never appear in code.

## Consequences

- `git clone && make setup && make check` works on any Linux or macOS VM with Python and Node that can reach PyPI and npm.
- Make is a thin dispatcher: real build logic stays in each ecosystem's own tool (uv, npm, Gradle), so components remain buildable on their own.
- Windows needs WSL. Acceptable: no executor targets native Windows.

## Alternatives considered

- **mise or asdf** to pin all toolchains: convenient, but its installer and many plugins download from GitHub releases, which restricted VMs block. Can be added later as an optional layer.
- **just or Task:** nicer syntax, but an extra binary to install before anything works; make is already present everywhere.
- **Dev container as the only path:** good for Codespaces, but not every agent environment runs containers. A dev container can be added later on top of `make setup`.
- **Gradle as the single build for everything:** awkward for Python and Node, and slow to start for non-Android work.
