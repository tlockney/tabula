# How it works

Tabula has two parts that talk over one protocol, the Tabula Link Protocol (TLP).

- **Android app.** Kotlin, Jetpack Compose and the system WebView. It shows a Home Assistant dashboard, draws screensavers natively, and plays notifications and audio. It dials out to Home Assistant over a single WebSocket, so the tablet needs no open ports.
- **Home Assistant integration.** A custom integration, `tabula`. It pairs tablets, exposes each one as a Home Assistant device with standard entities, and stores and pushes its settings.

## Pairing

1. Install the integration and add the Tabula hub in Home Assistant.
2. Install the app on the tablet and enter your Home Assistant address.
3. Confirm the six-digit code shown on the tablet in Home Assistant.

Pairing gives the tablet its own Home Assistant credential. Deleting the hub entry revokes every paired tablet.

## Home Assistant is the source of truth

Settings are defined once in a JSON Schema. Home Assistant validates and stores them, and pushes changes to the tablet. If the tablet reconnects with a different revision, Home Assistant's copy wins.

## Offline behavior

The app keeps running when Home Assistant is unreachable and reconnects on its own. Paired tablets survive Home Assistant restarts and integration upgrades.

## Protocol

[`docs/PROTOCOL.md`](https://github.com/tlockney/tabula/blob/main/docs/PROTOCOL.md) specifies TLP. Golden frames in [`schema/fixtures/`](https://github.com/tlockney/tabula/tree/main/schema/fixtures) are shared by the Android app, the integration and a Python reference device, so all three are tested against the same data.

## Provenance

Tabula was built clean-room from public documentation, with no code or text reused. [`PROVENANCE.md`](https://github.com/tlockney/tabula/blob/main/PROVENANCE.md) records the prior art and how each behavior was designed.
