# Provenance

This file records where Tabula's behavior comes from. It is append-only: entries are corrected by new entries, never removed or rewritten.

## Prior art and process

Tabula's feature set was informed by the public documentation of Kiosk Satellite, an Android kiosk app for Home Assistant licensed CC BY-NC-ND 4.0. That license forbids derivative works of its code and documentation, so Tabula is built clean-room:

- **Spec side.** Only spec-side contexts read the original documentation, which is kept outside this repository. Their single output is `docs/FEATURES.md`: behavior described in our own words, with our own names and no constants.
- **Build side.** Everyone who designs or writes code sees only this repository. Constants are chosen by the architect and recorded in ADRs.
- No source code, APK, decompilation or SDK interface file of the original was read by either side.

The full rules are in `AGENTS.md` and `docs/PLAN.md` section 2.

## Human exception

Thomas Lockney, who directs the project, has read the original documentation. To limit carry-over:

- His design input to the build side goes through `docs/FEATURES.md` (spec side) or an ADR written as independent design with its reasoning stated.
- He does not supply constants from memory. The architect picks them and records the reasoning in an ADR.

## Restricted names

The names below may appear only in the files listed under "Public framing" in `AGENTS.md`. `make check` reads this list and fails if a name appears anywhere else. One name per line, as a list item.

- Kiosk Satellite

## Feature provenance

One row per user-visible behavior. Source is a `docs/FEATURES.md` item ID or "independent design" (with the ADR, if any).

| Behavior | Source | Packet |
| --- | --- | --- |
| Device settings: device name and HA base URL (connection keys, the only ones recovery may change) | F-MGT-03, F-MGT-11 | P0.5 |
| Device settings: screen brightness, adaptive brightness, keep screen on | F-SCR-01, F-SCR-04, F-SCR-05 | P0.5 |
| Device settings: screensaver enabled, idle timeout, mode, screensaver brightness | F-SSV-01, F-SSV-05, F-SSV-17 | P0.5 |
| Device settings: notification default duration, default level, per-level chime, max visible | F-NTF-02, F-NTF-05, F-NTF-07, F-NTF-09 | P0.5 |
| Device settings: media volume | F-TTS-07 | P0.5 |
| Default values for every v1 setting | independent design (ADR 0002) | P0.5 |
| Announcements play at the current volume; no volume boost | independent design (ADR 0002) | P0.5 |
| Pairing flow: unauthenticated pair and claim HTTP endpoints, the user confirms a code shown on the tablet, the tablet receives a per-device credential | independent design (ADR 0001, PROTOCOL.md section 2) | P1.2 |
| One HA device per paired tablet; re-pairing after a reset creates a new device and the old entry stays until the user deletes it | F-HA-01 | P1.2 |
| The tablet signs in with its pairing credential only; credentials are per device and never shared | F-DSH-01, F-MGT-03 | P1.2 |
| The device reports its capabilities at registration and HA adopts the reported set (entities arrive in P1.3) | F-HA-02 | P1.2 |
| Deleting the HA device revokes that tablet's credential and user | F-HA-01, ADR 0001 item 4 | P1.2 |
| Registering with an unsupported protocol major is refused and raises a repair issue for that device | independent design (ADR 0001, PROTOCOL.md 4.2) | P1.2 |
| Diagnostics report device summaries and pending pairing state, never credentials | independent design (ADR 0001) | P1.2 |
| The tablet's pairing screen asks for the HA address, then shows the code and the device ID to confirm in Home Assistant | independent design (ADR 0001, PROTOCOL.md section 2) | P2.1 |
| The pairing credential is stored on the device encrypted under an Android Keystore key and excluded from backups | independent design (plan 5.2, ADR 0001) | P2.1 |
| A foreground service with an ongoing notification keeps the HA connection alive, holds a Wi-Fi wake lock, and starts on boot after one manual launch | F-KSK-09 | P2.1 |
| Diagnostic state reported to HA while connected: battery level, charging, network type, Wi-Fi signal strength, app version | F-HA-05, F-HA-09 | P2.1 |
| A credential revoked in HA returns the tablet to its pairing screen | independent design (ADR 0001 item 2) | P2.1 |
