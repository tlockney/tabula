# 0006: Work tracking and attribution

Status: accepted
Date: 2026-11-21
Packet: P0.1

## Context

Work is delegated across several agents and tools as well as people. The plan makes the repository the only shared memory and puts each brief in `docs/packets/`. Thomas also wants GitHub issues as the unit of delegation, possibly with GitHub Projects later, and wants history free of per-tool signatures.

## Decision

- One GitHub issue per packet, from the "Packet" issue form. Smaller work uses the "Task" form, which has the same brief headings.
- The packet file stays the brief and the record of results. The issue tracks assignment, state, dependencies and the pull request (`Closes #N`).
- Labels encode side, tier and hardware verification so issues can be filtered per executor type.
- Commits, pull requests and issue comments carry no tool or agent attribution and no links to chat threads. AI use is acknowledged in AGENTS.md, not per change.

## Consequences

- An executor can be handed just an issue number: the issue points to the packet, the packet lists everything to read.
- Briefs change through pull requests and get reviewed; issues stay short.
- Labels are defined in `.github/labels.txt` and synced by a workflow on merge to `main`.

## Alternatives considered

- Brief in the issue body only: easy to hand out, but unversioned, outside the repo and lost to anyone working from a clone.
- No issues, packets only: no assignment or state visible across many concurrent agents.
