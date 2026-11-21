# 0010: Commands and services (P1.4)

Status: accepted
Date: 2026-11-21
Packet: P1.4

## Context

PROTOCOL.md 4.5 and 4.6 define the command frames and how results are matched. Plan 5.5 and FEATURES F-HA-14 ask for `tabula.*` services for actions that take parameters. ADR 0001 item 5 found that `tts.speak` reaches a media player as a media-source ID and expected P1.4 to send an absolute URL, with a repair issue when HA has no usable URL. Settings writes are P1.5. P1.4 needs answers for each of these.

## Decision

1. **Dispatch.** `TabulaHub.async_command` refuses a command whose capability the device did not declare, and one for a device with no current, subscribed connection. It sends the event with a ULID `command_id` on the current connection's subscription, after strict validation of the frame. It waits `COMMAND_TIMEOUT` (`SCREENSHOT_TIMEOUT` for `screenshot`). A result settles the command only when it arrives on the connection the command went out on. A NACK, a timeout, or the connection closing first raises `HomeAssistantError` with the device's error code and message. A closed connection fails its waiting commands at once rather than at their timeout. A superseded connection's commands still wait for the timeout, because that socket may still be open (PROTOCOL.md 4.9).
2. **HA-hosted media goes out as a `/` path.** `media_player.play_media` resolves media-source IDs, signs HA paths that need auth (`async_process_play_media_url`), and keeps them relative. The tablet resolves the path against the base URL it already uses to reach HA, which PROTOCOL.md 4.5 allows. HA's internal and external URL settings are therefore not needed, and the repair issue ADR 0001 item 5 anticipated is not raised. External URLs pass through unchanged. `/api/tts_proxy/…` needs no credential, so the tablet fetches without its token.
3. **Services are targeted the HA way.** `tabula.notify` (all notification fields), `tabula.navigate` (an HA frontend path) and `tabula.screensaver` (start or stop, optional mode) take a standard target. Devices are used directly; entities and areas resolve to the Tabula devices they belong to. Every targeted tablet gets the command. If any of them fails, the call raises (F-HA-14: nothing fails silently). A target with no Tabula tablet is a validation error.
4. **One command path.** P1.5 landed first with its own send-and-await helper for `set_settings`. That helper is removed. The settings push and the entity actions now both go through `TabulaHub.async_command`, so the settings push also gets connection matching and fails at once on disconnect. The settings entities (keep screen on, adaptive brightness, screensaver brightness and mode) write through the P1.5 settings store, which bumps the revision and pushes the document. An invalid value is raised as a `HomeAssistantError`.
5. **Brightness ownership is enforced on the device.** A `screen` command that carries `brightness` turns adaptive brightness off on the tablet, and the device reports both keys back in one state delta. The integration sends no separate settings change for it. The light always shows the level the device reports. This settles the open question in F-SCR-07 for the light entity: the plan's rule applies, because an HA user who sets a level expects that level to hold. F-SCR-07's curve rescaling remains the design for brightness written by the management panel and the JS bridge, which arrive in later phases.

## Consequences

- Test-only dependencies `mutagen` and `ha-ffmpeg` (the pins of HA 2026.2.3's `tts` and `ffmpeg` manifests) are in the integration's dev group so `tts.speak` runs end to end in tests.
- The announce and stop services with TTS voice options (F-TTS) and the other F-HA-14 services arrive with the features they drive.
