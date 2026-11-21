# 0003: Repository and Android package

Status: accepted
Date: 2026-11-21
Packet: P0.1

## Context

The plan (section 5.2) requires the Android package name to be fixed before the first real install, because changing it forces every tablet to re-pair as a new device. The code needs one home that every executor can reach.

## Decision

- Repository: `github.com/tlockney/tabula`, a monorepo holding the Android app, the HA integration, the mock device, the panel, shared schema, docs and the website. Private until a pre-publication review against the public framing rule (AGENTS.md).
- Android `applicationId` and Kotlin namespace: `net.lockney.tabula`.
- License: Apache-2.0.

## Consequences

- Protocol fixtures and the settings schema live next to both consumers, so one pull request can change both ends.
- Publishing later needs no history rewrite; provenance stays intact.
- The package name is permanent for installed devices.

## Alternatives considered

- Separate repositories per component: version skew between app and integration becomes a cross-repo problem; rejected.
- A package under a generic or project domain: no such domain is owned; `net.lockney` is.
