# Tabula Link Protocol (TLP) v1

TLP is how a Tabula device and the `tabula` HA integration talk. It rides on HA's own HTTP and WebSocket APIs; there is no other listener on either side. Design background: plan section 5, ADR [0001](decisions/0001-ha-platform.md) (what HA supports) and ADR [0002](decisions/0002-constants.md) (every number named here).

- **Canonical schemas:** [`schema/tlp-v1.schema.json`](../schema/tlp-v1.schema.json). Each frame type below is a `$defs` entry of that file with the same name; `x-tabula-frames` lists them. Code loads that file; this document explains it.
- **Golden fixtures:** `schema/fixtures/tlp/v1/` (see "Fixtures"). `make frames` validates them.
- Names in this document in `code` are wire names. "Device" is the Android app or the mock device; "integration" is the HA side.

## 1. Overview

```text
Device                                              HA (tabula integration)
  │ POST /api/tabula/pair        (no auth)            │  starts a discovery flow
  │ POST /api/tabula/pair/claim  (no auth, polled)    │  user confirms code in HA
  │◄──── credential (system refresh token) ───────────│
  │ POST /auth/token             (refresh → access)   │  HA core
  │ WS  /api/websocket  auth                          │  HA core
  │ WS  tabula/register ─────────────────────────────►│  versions, capabilities, state
  │ WS  tabula/subscribe ────────────────────────────►│  command channel
  │◄──────────────────── command events (command_id) ─│
  │ WS  tabula/result / tabula/state / tabula/event ─►│
  │ POST /api/tabula/screenshot  (bearer)             │  binary upload
```

## 2. Pairing (HTTP, unauthenticated)

Both views are unauthenticated, rate-limited per source IP (`PAIR_RATE_LIMIT`, `CLAIM_RATE_LIMIT`) and accept JSON bodies of at most `PAIR_MAX_BODY`.

1. On first launch the device generates `device_id` (UUIDv4, lowercase) and persists it. For each pairing attempt it generates:
   - `nonce`: `NONCE_BYTES` random bytes, base64url without padding (43 characters). Never shown or logged.
   - `code`: `PAIR_CODE_DIGITS` random decimal digits, shown on screen with the first 8 characters of `device_id`.
2. `POST /api/tabula/pair` with `http_pair_request`: `device_id`, `code`, `nonce_hash` (lowercase hex SHA-256 of the nonce's ASCII string), `protocol_version`, optional `name`, `model`.
   - `202` + `http_pair_response`: `status: "pending"`, `expires_in` (= `PAIR_PENDING_TTL`), `poll_interval` (= `CLAIM_POLL_INTERVAL`).
   - `400 invalid_request`, `400 unsupported_protocol`, `429 rate_limited` (with `retry_after`), `503 too_many_pending` (more than `PAIR_PENDING_CAP` pending devices). Errors use `http_error`.
   - A repeat request for the same `device_id` replaces its pending entry (new code, new hash) and restarts the TTL.
3. The integration starts an `integration_discovery` flow for the device. The user opens it in HA and types the code shown on the tablet. After `PAIR_CODE_ATTEMPTS` wrong codes the pending entry is deleted and the device must start over.
4. On a correct code the integration creates the device's system user and refresh token (ADR 0001) and an HA device under the hub.
5. The device polls `POST /api/tabula/pair/claim` with `http_claim_request` (`device_id`, `nonce`) every `poll_interval` seconds:
   - `202` + `http_claim_pending` while the user has not confirmed.
   - `200` + `http_claim_response`: `credential` (`type: "refresh_token"`, `refresh_token`, `token_url: "/auth/token"`) and `ha_device_id`. Returned once; the pending record is then deleted.
   - `403 bad_nonce` if the nonce does not hash to the stored value. The pending entry is kept (a guessing attacker cannot cancel a real pairing) and the attempt counts toward the rate limit.
   - `404 expired` if no pending or confirmed record exists for `device_id` (expired, never paired, or already claimed). Terminal: the device stops polling and starts pairing again.
6. The device stores the refresh token (Keystore-backed) and discards nonce and code.

## 3. Credential use

- Access token: `POST /auth/token` form `grant_type=refresh_token&refresh_token=…` with **no** `client_id`. Response `{access_token, expires_in, token_type}`. The device refreshes when less than `TOKEN_REFRESH_MARGIN` remains.
- `400 invalid_grant` or `403` from `/auth/token` is final: the credential was revoked. The device stops retrying (failures count toward HA's IP ban) and returns to pairing.
- The same access token authenticates the WebSocket, the screenshot upload and the dashboard (external auth, ADR 0001 item 3).

## 4. WebSocket session

Standard HA WebSocket API at `/api/websocket`: `auth_required` → `{"type":"auth","access_token":…}` → `auth_ok`. TLP messages are ordinary HA commands:

- Device-to-integration frames are HA commands `{"id": n, "type": "tabula/…", …}`. `id` must increase within a connection.
- The integration replies with HA's result envelope: `ack` (`success: true`, `result: null`), `register_result`, or `error_result`.
- Integration-to-device commands arrive as HA subscription events on the `tabula/subscribe` ID: `{"id": <subscribe id>, "type": "event", "event": {command}}`.
- **Connection binding:** every `tabula/*` command resolves the device from the authenticated user (`connection.user`). `device_id` in `tabula/register` must match the device bound to that user, else `unknown_device`. No other frame carries a device ID.
- **Size:** no frame may exceed `FRAME_MAX_BYTES` (UTF-8 JSON). Larger data goes over HTTP.

### 4.1 Session order

1. `tabula/register` (must be first; any other `tabula/*` before it gets `not_registered`).
2. `tabula/subscribe`.
3. Then, in any order: `tabula/state`, `tabula/result`, `tabula/event`, `tabula/log`.

The device waits up to `REGISTER_TIMEOUT` for each reply; on timeout it closes the socket and reconnects (section 7).

### 4.2 `register` → `register_result`

Device sends identity and versions (`device_id`, `protocol_version`, `schema_version`, `app_version`, `manufacturer`, `model`, `os_version`), `capabilities`, its applied `settings_revision` and a full `state` snapshot.

Integration:

- Rejects an unsupported `protocol_version` with `unsupported_protocol` (section 8), raises a repair issue and leaves the device unavailable.
- Otherwise records the connection as the device's current one. A newer registration replaces an older connection for the same device; the integration closes the older socket.
- Replies `register_result`: `ha_device_id`, the `protocol_version` it will speak, HA's `settings_revision` and `server_time`.
- If HA's `settings_revision` differs from the device's, the integration sends `set_settings` right after the subscription opens (plan 5.6: HA is authoritative).

### 4.3 `subscribe`

No fields. `ack`, then command events for the life of the connection. One subscription per connection; a second gets `invalid_frame`.

### 4.4 `state` (state delta)

`state` holds only the keys that changed (at least one). Keys and units:

| Key | Type | Meaning |
| --- | --- | --- |
| `screen_on` | bool | Panel on |
| `brightness` | 0–100 | Actual panel brightness, percent |
| `adaptive_brightness` | bool | Light-sensor curve owns brightness (plan 5.5) |
| `keep_screen_on` | bool | Screen stays on while no screensaver |
| `screensaver_active` | bool | |
| `screensaver_mode` | `clock` \| `black` | Mode used next time it starts |
| `screensaver_brightness` | 0–100 | |
| `next_screensaver` | date-time \| null | When the idle timer fires; null while active or disabled |
| `battery_level` | 0–100 | |
| `charging` | bool | |
| `network` | `wifi` \| `ethernet` \| `none` | |
| `wifi_rssi` | dBm \| null | |
| `current_page` | string \| null | Frontend path shown in the WebView |
| `volume` | 0–100 | Media stream volume |
| `muted` | bool | |
| `media_state` | `idle` \| `playing` \| `paused` | |
| `app_version` | string | |

The device coalesces changes and sends at most one `state` frame per `STATE_MIN_INTERVAL`. Unknown keys are invalid in v1; new keys arrive with a capability (section 8).

### 4.5 Commands (integration → device)

Every command event has `command_id` (unique per connection), `command` and `args`. The device must answer each with exactly one `tabula/result`.

| Command (`$defs`) | `args` | Capability |
| --- | --- | --- |
| `set_settings` (`command_set_settings`) | `revision`, `schema_version`, `settings` (full document, validated against the settings schema, P0.5) | always |
| `screen` (`command_screen`) | `on`, `brightness` (at least one) | `screen` / `brightness` |
| `notify` (`command_notify`) | `message`; optional `title`, `level`, `duration` (s, 0 = until dismissed), `icon` (`mdi:…`), `chime`, `tag` (same tag replaces) | `notifications` |
| `play_media` (`command_play_media`) | absolute http(s) `url`, `media_type`, `announce`, optional `volume` | `audio` |
| `media_control` (`command_media_control`) | `action`: stop, pause, resume, set_volume (needs `volume`), mute, unmute | `audio` |
| `screensaver` (`command_screensaver`) | `active`, optional `mode` | `screensaver` |
| `navigate` (`command_navigate`) | `path` starting with `/` (HA frontend path only) | `navigation` |
| `reload`, `restart_app`, `clear_cache` | `{}` | always |
| `log_subscription` (`command_log_subscription`) | `enabled`, optional minimum `level` | `logs` |
| `screenshot` (`command_screenshot`) | optional JPEG `quality` | `screenshot` (Phase 3) |

The integration never sends a command whose capability the device did not declare.

### 4.6 `result`

`command_id`, `ok`; `error` (`code`, `message`) exactly when `ok` is false; optional `data` (for `set_settings`: `{"revision": n}` applied). The integration waits `COMMAND_TIMEOUT` (`SCREENSHOT_TIMEOUT` for `screenshot`) and raises `HomeAssistantError` on NACK or timeout. A result for an unknown or already-answered `command_id` is acknowledged and ignored.

`restart_app` is answered before the restart; `reload` and `clear_cache` when the reload has started.

### 4.7 `event`

`event` and `data`. The integration fires `tabula_<event family>` on the HA bus (plan 5.7) with `device_id` (HA) and `tabula_device_id` added.

| `event` | `data` |
| --- | --- |
| `notification_shown`, `notification_dismissed` | `tag` (if any), `reason` for dismissal: `touch`, `timeout`, `replaced`, `command` |
| `notification_action` | `tag`, `action` (reserved for actionable notifications) |
| `screensaver_started`, `screensaver_stopped` | `mode`, `trigger`: `idle`, `command`, `touch`, `schedule` |
| `media_finished` | `url`, `reason`: `ended`, `stopped`, `error` |
| `page_changed` | `path` |

### 4.8 `log`

While a log subscription is on, the device sends batches of 1–`LOG_MAX_LINES` lines (`ts`, `level`, `tag`, `message`), at most one frame per `LOG_MIN_INTERVAL`. The subscription ends on `log_subscription {enabled: false}`, on disconnect, or after `LOG_SUBSCRIPTION_TTL` (the integration re-sends to extend). Lines never contain credentials, nonces or codes.

## 5. Screenshot upload (HTTP, authenticated)

`POST /api/tabula/screenshot`, `Authorization: Bearer <access token>`, `Content-Type: image/jpeg`, header `X-Tabula-Command-Id: <command_id>`, body at most `SCREENSHOT_MAX_BYTES`. The device is resolved from the token (no device ID in the path; this replaces the `{device_id}` path in plan 5.1, for connection binding). Responses: `200 http_screenshot_response`, `401`, `403 forbidden` (not a Tabula device user), `413 too_large`. The device uploads first, then sends `tabula/result` for the `screenshot` command.

## 6. Errors

`error_code` values used in `error_result` and in `result.error`:

| Code | Meaning |
| --- | --- |
| `invalid_frame` | Frame fails its schema or arrives out of order |
| `unsupported_protocol` | Protocol major not supported |
| `unknown_device` | `device_id` does not match the device bound to this user |
| `not_registered` | `tabula/*` frame before `tabula/register` |
| `unsupported_command` | Device cannot run this command |
| `invalid_args` | Command args fail validation on the device |
| `stale_revision` | `set_settings` revision older than the applied one |
| `busy` | Device cannot run it now (e.g. a restart is in progress) |
| `failed` | Anything else; `message` explains |

HA itself may also return its own codes (`unauthorized`, `unknown_command`, …) in `error_result`.

## 7. Reconnect and backoff

- The device keeps one WebSocket. On close or error it reconnects after a delay: attempt *n* (from 0) waits a uniformly random time in [d/2, d], d = min(`BACKOFF_MAX`, `BACKOFF_BASE` × 2ⁿ). *n* resets once a connection has stayed registered for `BACKOFF_RESET_AFTER`.
- A reconnect repeats the whole session order (register, subscribe). Registration is idempotent.
- Commands in flight when a connection drops are lost; the integration's wait times out and the HA service call fails. The device never replays results on a new connection.
- HA pings every 55 s (ADR 0001 item 4); the device sends no keepalive of its own but treats `PING_TIMEOUT` without any inbound frame or pong as a dead connection.
- While disconnected, the device keeps working from cached settings (plan 5.6) and buffers nothing except the latest full state, which it sends with `register`.

## 8. Versions and compatibility

- `protocol_version` is an integer major. This document is major 1.
- The integration supports its current major and the one before it (plan 5.4). It answers `register_result.protocol_version` with the major it will speak, which is the device's.
- Additive changes do not bump the major: new optional fields, new state keys, commands, events or frame types **gated by a new capability**. A device ignores unknown optional fields in commands; the integration never sends anything a capability does not cover.
- Anything else (removing or renaming a field, changing a type or meaning, making a field required) bumps the major, adds `schema/tlp-v<N>.schema.json` and a new fixture directory, and keeps the previous ones for the N-1 conformance run (plan 8).
- `schema_version` versions the settings schema (P0.5) independently; translation rules are in plan 5.4.

## 9. Fixtures

```text
schema/fixtures/tlp/v1/<frame>[.<variant>].json          must validate as <frame>
schema/fixtures/tlp/v1/invalid/<frame>.<variant>.json    must fail validation as <frame>
```

`<frame>` is a `$defs` name from `x-tabula-frames`. Every frame type has at least one valid fixture. `make frames` (part of `make check`) runs `tools/validate_frames.py`, which checks both directions and the coverage. The mock device (P1.1), the integration tests and the Android codec (P2.0) all test against these files.
