# integration

Home Assistant custom integration `custom_components/tabula`: hub entry, pairing, device registry, entity platforms, services, settings storage, diagnostics and the panel host.

Created in packet P1.2 (see `docs/PLAN.md` section 7.2). Becomes a uv workspace member; its tests run under `make test`.

Install it on a Home Assistant instance (2025.1.0 or later, ADR 0001 item 8)
by copying `custom_components/tabula` into HA's `config/custom_components/`,
restarting HA and adding the Tabula hub: docs/DEVICE.md, "Installing the
integration on Home Assistant".
