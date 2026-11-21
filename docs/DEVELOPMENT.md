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
| JDK, Android SDK | set by P2.0 | Android app | Gradle wrapper (P2.0) |

`make setup` runs `tools/bootstrap.sh`, which installs uv from PyPI if it is missing and checks the Node version, then installs locked dependencies.

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

**Android builds** need a JDK and the Android SDK command-line tools. P2.0 adds a `make setup-android` target that installs them from registries and documents its network needs here.

Hardware steps (ADB against a real tablet) cannot run in a cloud VM. Packets mark them `Verification: hardware`; see AGENTS.md.

## CI

`.github/workflows/ci.yml` runs `make setup` and `make check` on every pull request and on pushes to `main`. Component packets extend `make check`; they do not add CI-only steps.

## Output

All generated output goes to `build/` at the repository root (Gradle keeps its own `build/` directories under `android/`). `make clean` removes it.
