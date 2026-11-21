# 0002: Constants

Status: accepted
Date: 2026-11-21
Packet: P0.4

## Context

AGENTS.md and plan section 2 require every port, timeout, threshold, count and default to be chosen by the architect and recorded here, never taken from the original product's documentation or from memory of it. This ADR sets the values TLP v1 (`docs/PROTOCOL.md`) names and the Phase 1–2 behavior defaults that the settings schema (P0.5) will use.

Each value is chosen from first principles: the HA facts in ADR 0001, the Office Kiosk's constraints (plan section 3), common practice for similar protocols, and round numbers that are easy to reason about. None is load-bearing for security beyond what its reasoning says; all can change in a later ADR.

## Decision

### Pairing

| Name | Value | Reasoning |
| --- | --- | --- |
| `PAIR_CODE_DIGITS` | 6 | 10⁶ codes. With `PAIR_CODE_ATTEMPTS` the chance of guessing is 5 in a million per pending device, and the attacker also has to be the person at the HA UI. Six digits are easy to read off a wall tablet. |
| `PAIR_CODE_ATTEMPTS` | 5 | Room for typos; then the pending entry is deleted. |
| `NONCE_BYTES` | 32 | 256 bits; matches SHA-256's output. Base64url without padding is 43 characters. |
| `PAIR_PENDING_TTL` | 600 s | Long enough to walk from the tablet to a computer and confirm; short enough that stale entries clear themselves. |
| `PAIR_PENDING_CAP` | 8 | Far above a household's simultaneous pairings; bounds the discovery-flow flood an unauthenticated client can cause. |
| `PAIR_RATE_LIMIT` | 10 requests / 60 s per source IP | A device sends one request per attempt; 10 allows retries after errors. |
| `CLAIM_POLL_INTERVAL` | 3 s | Pairing feels immediate after the user confirms, at 200 requests over the whole TTL. |
| `RETRY_AFTER_DEFAULT` | 1 s | Assumed when a rate-limit response omits `retry_after`: long enough to matter, short enough not to stall pairing on a busy HA. |
| `CLAIM_RATE_LIMIT` | 60 requests / 60 s per source IP | Three times the polling rate, so a well-behaved device never hits it; caps nonce guessing (which is hopeless at 256 bits anyway). |
| `PAIR_MAX_BODY` | 4 KiB | The largest valid request is under 400 bytes. |

### Credential

| Name | Value | Reasoning |
| --- | --- | --- |
| `TOKEN_REFRESH_MARGIN` | 300 s | HA access tokens last 1800 s (ADR 0001). Refreshing with 5 minutes left survives a slow network and clock skew within HA's 10 s JWT leeway. |

### WebSocket session

| Name | Value | Reasoning |
| --- | --- | --- |
| `FRAME_MAX_BYTES` | 256 KiB | Well under HA's 4 MiB inbound limit (ADR 0001 item 4). A full `register` is about 1 KiB and a 200-line log batch under 100 KiB. Anything larger belongs on HTTP. |
| `REGISTER_TIMEOUT` | 10 s | HA answers in milliseconds on a LAN; 10 s tolerates a busy HA at startup without leaving the device hanging. |
| `COMMAND_TIMEOUT` | 10 s | Long enough for a WebView reload to start or audio to buffer; short enough that an automation's failed service call is noticed. |
| `SCREENSHOT_TIMEOUT` | 30 s | Capture, JPEG encode and upload on a weak SoC over Wi-Fi. |
| `STATE_MIN_INTERVAL` | 250 ms | Plan 5.5 targets under 1 s from device change to HA state; 250 ms coalesces bursts (a brightness slider) with plenty of margin. |
| `APP_PING_INTERVAL` | 30 s | The device's own `ping` command (HA's library pings are invisible to the app). Detects a dead socket within about 40 s, well inside the time someone notices a dashboard is stale, at negligible cost. |
| `PONG_TIMEOUT` | 10 s | A `pong` takes milliseconds on a LAN; 10 s tolerates a busy HA. |
| `RESTART_EXIT_DELAY` | 500 ms | After `restart_app` is answered (PROTOCOL 4.5: answered before the restart), the process waits this long before exiting so the result frame reaches HA on a slow uplink. |

### Reconnect

| Name | Value | Reasoning |
| --- | --- | --- |
| `BACKOFF_BASE` | 1 s | First retry is quick for a brief blip. |
| `BACKOFF_MAX` | 60 s | A restarting HA is back within a minute or two; capping at 60 s means the tablet recovers within a minute of HA's return. |
| `BACKOFF_RESET_AFTER` | 60 s | A connection must be stable for a minute before the next failure starts from `BACKOFF_BASE`, which stops tight reconnect loops against a half-broken HA. |

Jitter: uniform in [d/2, d] ("equal jitter"), so many tablets do not reconnect in lockstep after an HA restart, while each still retries reasonably soon.

### Logs and screenshots

| Name | Value | Reasoning |
| --- | --- | --- |
| `LOG_MAX_LINES` | 200 per frame | Bounded frame size (see `FRAME_MAX_BYTES`); also the schema's `maxItems`. |
| `LOG_MIN_INTERVAL` | 1 s | A live tail that does not load HA's event loop. |
| `LOG_SUBSCRIPTION_TTL` | 600 s | A forgotten log viewer stops streaming by itself; the panel re-sends to extend. |
| `SCREENSHOT_MAX_BYTES` | 5 MiB | A 1920×1200 JPEG at quality 95 is about 1–2 MiB; 5 MiB leaves headroom for larger panels. |

### Protocol limits in the schema

These appear as bounds in `schema/tlp-v1.schema.json` and are chosen here:

| Field | Bound | Reasoning |
| --- | --- | --- |
| `notify.message` | 1000 chars | More than fits on a card; stops accidental payload dumps. |
| `notify.title` | 200 chars | |
| `notify.duration` | 0–3600 s | 0 means until dismissed; an hour is the longest sensible timed card. |
| `command_id` | 8–64 chars of `[0-9A-Za-z_-]` | Room for a ULID or UUID; safe in logs and headers. |
| `error.message`, `http_error.message` | 500 chars | |
| `log_line.message` | 4000 chars | One stack trace frame group. |
| `screenshot.quality` | 10–95 | JPEG quality outside this range is useless or wasteful. |
| `app_version`, `manufacturer`, `model`, `os_version`, pair `name`/`model`, `tag`, `notification_action.action`, `log_line.tag` | 64 chars | Identifiers and labels; long enough for any real value. |
| `current_page`, `navigate.path`, `page_changed.path` | 2048 chars | Common URL length limit in browsers and proxies. |
| `play_media.url` | 4096 chars | Signed media URLs carry long query strings. |
| `wifi_rssi` | −127 to 0 dBm | The range Android reports. |
| `capabilities` (register) | 64 items, names `[a-z][a-z0-9_]{0,31}` | Room for many future capabilities while bounding the frame. |
| `credential.refresh_token` | at least 32 chars | HA refresh tokens are far longer; a shorter one is a bug. |

### Phase 1–2 behavior defaults

Defaults for the settings schema (P0.5) and the device's behavior before HA has sent any settings.

| Setting | Default | Reasoning |
| --- | --- | --- |
| Screensaver idle timeout | 300 s | Long enough not to interrupt someone reading the dashboard; short enough to save the panel. |
| Screensaver enabled | true | The first milestone idles to a clock. |
| Screensaver mode | `clock` | Same. |
| Screensaver brightness | 20 % | Readable clock without lighting the room at night. |
| Screen brightness (manual) | 60 % | Comfortable indoors on the HD 10 Plus; the user adjusts. |
| Adaptive brightness | false | Off until the curve (P2.3) is tuned on real hardware. |
| Keep screen on | true | A wall dashboard should not follow the phone-style screen timeout; the screensaver handles idling. |
| Notification duration | 10 s | Enough to read two lines from across a room; 0 means until dismissed (F-NTF-05). |
| Notification chime | true for `warning` and `error`, false otherwise | Sound for what needs attention only. |
| Notification level when missing or unknown | `info` | FEATURES F-NTF-02 fallback; the neutral level. |
| Notifications shown at once | 3 | More would cover the dashboard; older ones collapse. |
| Media volume | 50 % | Middle of the range; TTS is audible without startling. |
| Announce volume boost | none | `announce` plays at the current volume; no hidden volume jumps. |

### Settings bounds in the settings schema

These appear as bounds in `schema/settings.schema.json` (P0.5) and are chosen here, so P2.0's generated Kotlin can use `Int` everywhere:

| Field | Bound | Reasoning |
| --- | --- | --- |
| `screensaver.idle_timeout` | 1–86400 s | A non-zero floor: 0 would mean "start instantly", not "never" (a disabled screensaver already expresses never). A day is the longest sensible idle wait; longer than that is "leave it on", and 86400 sits far inside a Kotlin `Int`. |
| `notifications.max_visible` | 1–10 | A non-zero floor: a cap of 0 would hide every card. Ten full-width cards already cover a phone-height panel, so more would bury the dashboard rather than show anything. |

## Consequences

- PROTOCOL.md refers to these names; code defines each once (integration: `const.py`; device: a constants file) with a comment pointing here.
- P0.5 takes the behavior defaults from this table and adds their `PROVENANCE.md` rows ("independent design, ADR 0002") when it makes them settings.
- Changing a value means editing this ADR (or superseding it) in the same pull request as the code.

## Alternatives considered

- **Longer pairing codes or alphanumeric codes:** stronger, but harder to read and type; the attempt limit and the TTL already make guessing impractical.
- **Full jitter (uniform in [0, d]):** spreads load best, but can retry almost immediately after a failure; equal jitter keeps a minimum delay.
- **No frame size cap beyond HA's 4 MiB:** simpler, but large frames block the shared socket for commands; a low cap pushes bulk data to HTTP as plan 5.1 intends.
