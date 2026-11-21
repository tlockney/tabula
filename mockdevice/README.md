# mockdevice

Python reference device for the Tabula Link Protocol (`docs/PROTOCOL.md`). Three uses:

- **Development loop** for the HA integration: pair it, run it, send commands from HA.
- **Test double** for integration tests: `mockdevice.testserver.TestServer` is an in-process TLP server, and `mockdevice.client.TLPClient` a scriptable device.
- **Conformance suite** (`conformance/`) that replays the golden fixtures against any TLP server.

## CLI

```text
uv run tabula-mockdevice --ha http://homeassistant.local:8123 pair [--name NAME] [--model MODEL] [--timeout S]
uv run tabula-mockdevice --ha http://homeassistant.local:8123 run \
    [--capabilities screen,brightness,...] [--fail notify,...] [--drop reload,...] [--delay S]
```

`pair` prints a six-digit code; confirm it in Home Assistant. The credential is saved to `--store` (default `~/.config/tabula-mockdevice/device.json`, mode 0600). `run` connects, registers, answers commands and reconnects with backoff until interrupted. `--fail` makes commands NACK with `failed`, `--drop` makes them never answer, `--delay` delays every answer.

## Tests

`make test` runs the unit tests (`tests/`) and the conformance suite against the in-process server. To check a real Home Assistant, pair a dedicated mock device first, then:

```text
TABULA_HA_URL=http://homeassistant.local:8123 TABULA_STORE=~/.config/tabula-mockdevice/device.json make conformance
```

The suite registers that device, sends state and opens extra connections; it creates no pairing entries.
