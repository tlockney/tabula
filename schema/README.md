# schema

- `tlp-v1.schema.json`: JSON Schema for every Tabula Link Protocol v1 frame (packet P0.4). `docs/PROTOCOL.md` explains it.
- `fixtures/tlp/v1/`: golden TLP frames, the contract both ends test against. `invalid/` holds frames that must fail. `make frames` checks both.
- `settings.schema.json`: the single source of truth for device settings (packet P0.5). Covers only what Phases 1–2 use; every property carries a role tag (`identity`, `connection`, `hardware`, `shared`) and every default comes from `docs/decisions/0002-constants.md`.
- `fixtures/settings/v1/`: settings documents. `defaults.json` must validate and equal the schema's own defaults; `invalid/` holds documents that must fail (checked by `tools/tests/test_settings_schema.py`, part of `make test`).
