# The Tabularium (`panel/`)

Tabula's management panel: a Vite + TypeScript + Lit 3 app that Home Assistant loads from the hub integration as a single ES module. Packet [P5.1](../docs/packets/P5.1-panel-shell-and-codegen.md) created it; plan 5.9 covers the whole panel.

- `src/panel.ts` — entry point: registers the `<tabula-panel>` element.
- `src/shell.ts` — the panel element: hub status line from `tabula/panel_info`, error state, hash-route skeleton.
- `src/strings.ts` — every user-visible string, in one table the later packets extend.
- `src/styles.css` — imported `?inline`, so the styles ship inside the module (HA fetches one file).
- `src/generated/settings.ts` — generated from `schema/settings.schema.json` by `tools/codegen_ts.py`; committed, never edited by hand.

The bundle is built with `make panel` (see `docs/DEVELOPMENT.md`, "Panel"), which stages `panel.js` into the integration. Nothing here is committed except the sources, the tests and the lockfile.

Data comes from the admin-only `tabula/panel_*` WebSocket commands (`integration/custom_components/tabula/panel_api.py`); the panel never reads device frames.
