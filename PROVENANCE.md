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
| Screen as an HA light: on/off is the panel, brightness the panel level; on/off only when the tablet cannot dim | F-SCR-04 | P1.3 |
| Speaker as an HA media_player reporting volume, mute and playback state | F-TTS-01, F-TTS-07 | P1.3 |
| On-screen notifications as an HA notify entity | F-NTF-01 | P1.3 |
| Switches for screensaver active, keep screen on and adaptive brightness; number for screensaver brightness | F-HA-11 | P1.3 |
| Screensaver mode select | F-HA-10, F-SSV-05 | P1.3 |
| Buttons: restart app, reload dashboard, clear cache | F-HA-08 | P1.3 |
| Sensors: battery level, charging, current page, app version, next screensaver | F-HA-05, F-HA-07 | P1.3 |
| Network type sensor and Wi-Fi signal sensor (disabled by default), diagnostic | independent design (ADR 0009) | P1.3 |
| Connectivity binary sensor that stays available and reads off when the tablet's link drops | F-HA-09 (its open question settled as "reads off", ADR 0009) | P1.3 |
| Entities exist only for declared capabilities; a capability dropped at registration removes its entities | F-HA-02 | P1.3 |
| Losing the link makes every other entity unavailable; reconnecting refreshes them from the tablet's state; nothing is removed on disconnect | F-HA-04 | P1.3 |
| Entities of a paired tablet survive an HA restart (unavailable until it reconnects) | independent design | P1.3 |
| Per-device settings documents live in HA (revisioned); the device keeps a cached copy and HA pushes the current document when the tablet registers behind or ahead of the stored revision | F-MGT-01, PROTOCOL.md 4.2, 4.5 | P1.5 |
| The integration's options flow is the settings editor: pick a tablet, edit every non-hardware setting in one form generated from the settings schema; the entry's own options are untouched | F-MGT-01 | P1.5 |
| identity.name in the settings document is the tablet's display name in HA's device registry | F-MGT-03 | P1.5 |
| Device events fire on the HA bus as tabula_* events, one name per subject, a type field for the specific change, carrying the tablet's HA device id | F-EVT-01 | P1.5 |
| tabula_notification fires when a notification shows, is dismissed, or its action runs; tabula_screensaver on screensaver start and end | F-EVT-02, F-EVT-03 | P1.5 |
| tabula_media fires when media finishes; tabula_page when the page changes | independent design (PROTOCOL.md 4.7) | P1.5 |
| Deleting a tablet's HA device also deletes its stored settings document | independent design (plan 5.6) | P1.5 |
| Settings-mapped entity actions (keep screen on, adaptive brightness, screensaver brightness and mode) write the HA-owned settings document and push like any edit, never a bare command | F-MGT-01, F-HA-11 | P1.5 |
| Entity actions send device commands; an offline tablet, an undeclared capability, a refusal or no answer in time fails the action with an error | F-HA-14, PROTOCOL.md 4.6, ADR 0010 | P1.4 |
| `notify.send_message` shows a text notification on the tablet | F-NTF-01 | P1.4 |
| `tabula.notify` with title, level, duration, icon, chime and replace-by-tag | F-NTF-01, F-NTF-02, F-HA-14 | P1.4 |
| `tabula.navigate` opens an HA frontend path on the tablet | F-NAV-01 | P1.4 |
| `tabula.screensaver` starts or stops the screensaver, optionally in a given mode | F-SSV-03, F-SSV-05, F-HA-14 | P1.4 |
| `tts.speak` and `media_player.play_media` play on the tablet speaker; announcements are flagged; HA media is sent as a path the tablet fetches without its credential | F-TTS-01, F-TTS-02, ADR 0001 item 5, ADR 0010 | P1.4 |
| A manual brightness change through the screen light turns adaptive brightness off | plan 5.5; F-SCR-07 open question settled in ADR 0010 | P1.4 |
