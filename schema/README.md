# schema

- `tlp-v1.schema.json`: JSON Schema for every Tabula Link Protocol v1 frame (packet P0.4). `docs/PROTOCOL.md` explains it.
- `fixtures/tlp/v1/`: golden TLP frames, the contract both ends test against. `invalid/` holds frames that must fail. `make frames` checks both.
- `settings.schema.json`: the single source of truth for device settings (packet P0.5).
