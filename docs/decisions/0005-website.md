# 0005: Website

Status: accepted
Date: 2026-11-21
Packet: P0.1

## Context

Tabula needs a project website eventually: what it is, how to install it, and the user-facing docs. It should live in this repo so docs and code change together, and build with a static site generator.

## Decision

- VitePress (Node), in `website/`, pinned by `website/package-lock.json`. Chosen by Thomas, from good experience on another project.
- `make site` builds into `build/site`; `make check` builds it, so a broken site fails CI.
- Content (2026-11-23): landing page, a short guide (how it works, install, entities, contributing) and a status page, written for the site and linking to `docs/` on GitHub for the detail. Packets, plan and ADRs are not published on the site.
- Hosting: GitHub Pages, deployed by `.github/workflows/site.yml`, which runs only on manual dispatch until Thomas confirms hosting. `SITE_BASE` sets the base path (`/tabula/` for the project URL, `/` for a custom domain). Pages needs the repository to be public, or a plan that allows private Pages.
- Domain: undecided. Adding one means setting `SITE_BASE` to `/` and a `CNAME` file under `website/public/`.

## Consequences

- Node is needed from P0.1 rather than from Phase 5. The panel will reuse the same Node toolchain.
- VitePress renders Markdown, so user docs can be written once and published directly. Internal docs (packets, plan) stay unpublished unless chosen.
- Site content is subject to the public framing rule like any other file.

## Alternatives considered

- Zensical or MkDocs Material (Python, via uv): one fewer toolchain now, but Zensical is pre-1.0 and MkDocs core has stalled.
- Hugo: mature, but a Go binary normally fetched from GitHub releases, which restricted VMs block.
- Astro: more than a docs-centered project site needs.
