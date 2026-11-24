# 0031: Release source for app updates

Status: proposed
Date: 2026-11-24
Packet: P5.7

## Context

Plan section 10 leaves one open question that Phase 5 needs answered: where update packages come from. F-UPD-01 says the check should run in the integration, not on the device, because the device talks only to HA; F-UPD-03 needs a version, release notes and per-architecture packages; F-UPD-05 constrains only the install path, not the source. The candidates are GitHub releases on the private repository (which needs a token on the HA side) and a static folder on the homelab.

The choice is small in code and large in operations: it decides whether a long-lived repository credential lives in Home Assistant, whether updates keep working when the WAN is down, and what publishing a release costs the owner. Devices never see either source directly — the integration reads it and serves the package through HA (P5.7) — so the decision can be changed later behind one adapter without touching the protocol, the update entity or the panel. Only P5.7 depends on it.

Constraints that shape the answer:

- The repository is private now and is intended to become public (AGENTS.md, "Public framing"). A GitHub-based source needs a token only while it stays private.
- Home Assistant holds configuration, backups and diagnostics that are often shared when asking for help. A secret placed in its config entry is a secret in all three.
- The installation is a single household on a homelab LAN; there is no multi-tenant release process and no external consumer.
- Release notes are optional (F-UPD-01): a release without them is still offered.
- The integration already verifies package identity, version and free space (F-UPD-03 item 4), so the source does not need to be trusted for authenticity; a checksum in a manifest is an extra guard, not the security boundary.

## Decision

Recommended: **a static release folder on the homelab**, read directly by the integration, default `/share/tabula/releases`, configurable on the hub entry.

- The folder holds the APKs, a manifest (schema version, channel, version, per-architecture file names, sha256, optional release notes) and nothing secret.
- Publishing is a script (`tools/release.py`, added by P5.7) that builds the manifest from a directory of APKs, so the manifest is never hand-edited.
- HAOS makes the folder reachable over Samba and includes it in HA backups; the integration only reads it.
- No repository credential exists anywhere in HA, and updates keep working with the WAN down, which is when a kiosk update is most likely to be run.

The owner decides; while this ADR is `proposed`, P5.7 does not start. If the owner picks GitHub releases, the "Decision" section is replaced by that choice and the consequences below change, but the packet's interface and the rest of its scope stand.

## Consequences

- Publishing is an owner action: build the APKs, run the release script, copy the folder. A CI job can produce the same folder as an artifact, but nothing publishes it automatically.
- There is no external release history beyond the folder's contents and the manifest; the repository's tags and commits remain the history of the source. The manifest gives every release a version, a channel and a checksum record.
- The folder can be wrong (a partial copy, an edited file, an old manifest). The integration tolerates a missing folder (no releases, no repair issue) and validates the manifest; the device still refuses a foreign package, an older build and a sha mismatch.
- The folder is LAN-only. A future remote installation or a public release channel needs a second source; the adapter interface keeps that additive, and the public repository is the natural place for it when the project gets there.
- ADR 0002 gains the update constants (check interval, batch size, size bound) in P5.7; none of them depend on the source.
- The homelab share path is an integration option, so a HA Core install without `/share` can point at any readable directory.

## Alternatives considered

**GitHub releases on the private repository, with a fine-grained read-only token on HA.**
Pros: releases are immutable-ish and versioned next to the code; release notes come from the tag; CI can attach the APKs and checksums in one step; the API and asset downloads are well documented. Cons: a long-lived repository credential sits in HA's config entry, and therefore in its backups and any diagnostics bundle; it expires or is revoked and updates stop with an error only the owner can interpret; HA needs internet access and GitHub API availability at check time; asset downloads redirect, so the integration must follow them; the rate limit is a non-issue with a token but another failure mode without one. The token's blast radius is the private repository's contents, which is exactly the material that is not ready to be public yet. Once the repository is public the token disappears, so the cost is temporary — but it is real for the whole time Phase 5 runs.

**GitHub releases with no token, after making the repository public.**
Not available today. Making the repository public is a deliberate project milestone, not something a Phase 5 packet may force; and until then this option is the previous one with a token.

**A homelab HTTP server in front of the folder.**
Adds a service to run, a port to secure and, if it leaves the LAN, TLS and credentials — for a folder HA can already read. No benefit on a single-host installation.

**HACS as the update channel.**
HACS updates custom integrations, not the Android app; it does not solve F-UPD at all and would add a dependency with its own rules.

**A release folder without a manifest (just APKs).**
No channel, no notes, no checksum, and the integration would have to parse each APK to discover its version before downloading it. The manifest is the smaller failure surface.
