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
   - `400 invalid_request`, `400 unsupported_protocol`, `409 already_pending` (with `retry_after`), `429 rate_limited` (with `retry_after`), `503 too_many_pending` (more than `PAIR_PENDING_CAP` pending devices). Errors use `http_error`.
   - The integration reads `protocol_version` before validating the rest of the body, so a device speaking a newer major gets `unsupported_protocol`, not `invalid_request`.
   - A repeat request for a `device_id` that already has a pending entry is accepted only with the same `nonce_hash` (a retry; the entry is unchanged). A different `nonce_hash` gets `409 already_pending` until the entry expires, so whoever sees a pairing request on the network cannot replace it with their own nonce.
3. The integration starts an `integration_discovery` flow for the device, bound to that pending entry's `nonce_hash`; confirming it pairs exactly that entry. The user opens it in HA and types the code shown on the tablet. After `PAIR_CODE_ATTEMPTS` wrong codes the pending entry is deleted and the device must start over.
4. On a correct code the integration creates the device's system user and refresh token (ADR 0001) and an HA device under the hub.
5. The device polls `POST /api/tabula/pair/claim` with `http_claim_request` (`device_id`, `nonce`) every `poll_interval` seconds:
   - `202` + `http_claim_pending` while the user has not confirmed.
   - `200` + `http_claim_response`: `credential` (`type: "refresh_token"`, `refresh_token`, `token_url: "/auth/token"`) and `ha_device_id`. Returned once; the pending record is then deleted.
   - `403 bad_nonce` if the nonce does not hash to the stored value. The pending entry is kept (a guessing attacker cannot cancel a real pairing) and the attempt counts toward the rate limit. A device that gets `bad_nonce` for its own nonce stops polling and shows a pairing error; something else on the network has interfered.
   - `404 expired` if no pending or confirmed record exists for `device_id` (expired, never paired, or already claimed). Terminal: the device stops polling and starts pairing again.
6. The device stores the refresh token (Keystore-backed) and discards nonce and code.

**Threat model over plain http.** Pairing resists guessing (code attempts, nonce, rate limits) and replacement (above), but not an on-path attacker on a plain-http LAN: the nonce travels in each claim poll and the refresh token in the claim response, so someone who can read the traffic can race the claim or copy the credential. That is the same exposure as every later request (plan 5.2). Use HTTPS to remove it; DEVICE.md says so.

## 3. Credential use

- Access token: `POST /auth/token` form `grant_type=refresh_token&refresh_token=…` with **no** `client_id`. Response `{access_token, expires_in, token_type}`. The device refreshes when less than `TOKEN_REFRESH_MARGIN` remains.
- `400 invalid_grant` or `403` from `/auth/token` is final: the credential was revoked. The device stops retrying (failures count toward HA's IP ban) and returns to pairing.
- The same access token authenticates the WebSocket, the screenshot upload and the dashboard (external auth, ADR 0001 item 3).

## 4. WebSocket session

Standard HA WebSocket API at `/api/websocket`: `auth_required` → `{"type":"auth","access_token":…}` → `auth_ok`. TLP messages are ordinary HA commands:

- Device-to-integration frames are HA commands `{"id": n, "type": "tabula/…", …}`. `id` must increase within a connection.
- The integration replies with HA's result envelope: `ack` (`success: true`, `result: null`), `register_result`, or `error_result`.
- Integration-to-device commands arrive as HA subscription events on the `tabula/subscribe` ID: `{"id": <subscribe id>, "type": "event", "event": {command}}`.
- **Connection binding:** every `tabula/*` command resolves the device from the authenticated user (`connection.user`); identity never comes from a frame. `device_id` in `tabula/register` must match the device bound to that user, else `unknown_device`, and it restates an identity the connection already proves. A device id inside a payload names a *different* device — `intercom_ring`'s `caller_device_id`, `intercom_start_request`'s `target_device_id`, `intercom_signal`'s `peer_device_id` — and says nothing about who sent the frame; it never authenticates anything.
- **Size:** no JSON frame may exceed `FRAME_MAX_BYTES` (UTF-8 JSON). Binary voice frames (4.10) are bounded by `VOICE_CHUNK_BYTES` instead. Larger data goes over HTTP.
- **Keepalive:** HA's own pings are answered by the WebSocket library and are invisible to the app, so the device sends HA's `ping` command (`{"id": n, "type": "ping"}` → `{"id": n, "type": "pong"}`) every `APP_PING_INTERVAL` and treats a missing `pong` after `PONG_TIMEOUT` as a dead connection.

### 4.0 Strict senders, tolerant receivers

The schema describes exactly what a sender of this version produces, and fixtures are checked against it strictly. Receivers are tolerant so the app and the integration can update independently (plan 5.4):

- A receiver ignores object members it does not know (it validates with `additionalProperties` relaxed), then validates the rest strictly.
- The integration ignores capabilities it does not know and answers, in `register_result.capabilities`, the subset it accepts. A capability name is one segment of `[a-z][a-z0-9_]{0,31}`: no dots, no colons, nothing a receiver would have to parse before it can ignore the name. A device that declared a name of another shape would have its whole `register` refused by an integration that predates the name, which is the failure this rule and section 8's capability gating exist to prevent, so new capabilities keep to that shape (`intercom_listen`, not `intercom.listen`).
- The device sends only state keys, events and frame types covered by the accepted capabilities (state keys and events defined in this document belong to v1 and are always covered by their capability or by none). A device with a newer feature therefore stays fully working against an older integration; the feature simply does not appear in HA.
- The integration sends only commands and arguments covered by the device's declared capabilities.

### 4.1 Session order

1. `tabula/register` (must be first; any other `tabula/*` before it gets `not_registered`).
2. `tabula/subscribe`.
3. Then, in any order: `tabula/state`, `tabula/result`, `tabula/event`, `tabula/log`, and binary voice frames while a turn is open (4.10).

The device waits up to `REGISTER_TIMEOUT` for each reply; on timeout it closes the socket and reconnects (section 7).

### 4.2 `register` → `register_result`

Device sends identity and versions (`device_id`, `protocol_version`, `schema_version`, `app_version`, `manufacturer`, `model`, `os_version`), `capabilities`, its applied `settings_revision` and its current `state`: every state key it supports, so HA starts from a complete picture. (The schema checks only that `state` is non-empty, since the same object type carries deltas.) A supported state key whose value the device does not have yet — the `illuminance` key before the light sensor's first reading, `last_wake_word` before the first detection — is omitted from `register` and sent in the first delta that has one; the schema requires a value, so there is no null placeholder.

Integration:

- Reads `protocol_version` first, before validating the rest of the frame. An unsupported major gets `unsupported_protocol` (section 8), a repair issue, and the device stays unavailable.
- Otherwise records the connection as the device's current one (see 4.9).
- Replies `register_result`: `ha_device_id`, the `protocol_version` it will speak, HA's `settings_revision`, `server_time` and the accepted `capabilities`.
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
| `screensaver_mode` | `clock` \| `weather` \| `photos` \| `website` \| `dashboard` \| `calendar` \| `dim` \| `black` | Mode used next time it starts (FEATURES F-SSV-05) |
| `screensaver_brightness` | 0–100 | |
| `next_screensaver` | date-time \| null | When the idle timer fires; null while active or disabled |
| `battery_level` | 0–100 | |
| `charging` | bool | |
| `network` | `wifi` \| `ethernet` \| `none` | |
| `wifi_rssi` | dBm \| null | |
| `illuminance` | 0–100000 | Ambient light, lux (P3.16); covered by the `illuminance` capability |
| `wake_word_active` | bool | Always present, because the APK always ships the detector and the capability is declared on every register: true while the microphone is open and the wake-word frontend is computing features (setting on, permission held, nothing playing), false otherwise (P7.1) |
| `last_wake_word` | date-time | Device clock at the last accepted detection; omitted until there is one (P7.1) |
| `voice_turn` | `idle` \| `listening` | `listening` while a voice turn is open and PCM is streaming on the binary channel, else `idle`; always present while the `voice` capability is declared. Speaking is `media_state`, not this key (ADR 0024) |
| `intercom_state` | `idle` \| `ringing` \| `in_call` \| `broadcasting` | Where the device is in a call: idle, ringing (a call is offered and unanswered), in_call (a two-way call is up, or it is playing a broadcast), broadcasting (it is sending one). Covered by the `intercom_listen` capability (P9.1, ADR 0025) |
| `current_page` | string \| null | Frontend path shown in the WebView, without query string or fragment |
| `volume` | 0–100 | Media stream volume |
| `muted` | bool | |
| `media_state` | `idle` \| `playing` \| `paused` | |
| `app_version` | string | |

The device coalesces changes and sends at most one `state` frame per `STATE_MIN_INTERVAL`. Unknown keys are invalid in v1; new keys arrive with a capability (section 8). A capability-gated key is sent only by a device that declares the covering capability, and that holds for `register` as much as for a delta: `register` carries every state key the device supports *among those its declared capabilities cover* (4.2's "every state key it supports" reads with this rule). The consequence is worth stating plainly, because `state` stays strict for unknown keys even in a tolerant receiver: an integration older than a key refuses the whole `register` frame over it, so a device gains a gated key only in a build that ships after the integration that knows the key. Gating keeps the key honest; only the integration's release order keeps the frame acceptable.

### 4.5 Commands (integration → device)

Every command event has `command_id` (unique per device for the integration's lifetime; a ULID works), `command` and `args`. The device must answer each with exactly one `tabula/result`, on the connection the command arrived on.

| Command (`$defs`) | `args` | Capability |
| --- | --- | --- |
| `set_settings` (`command_set_settings`) | `revision`, `schema_version`, `settings` (full document, validated against the settings schema, P0.5) | always |
| `screen` (`command_screen`) | `on`, `brightness` (at least one). A `brightness` turns adaptive brightness off on the device (plan 5.5); the device reports `brightness` and `adaptive_brightness: false` in one state delta | `screen` / `brightness` |
| `notify` (`command_notify`) | `message`; optional `title`, `level` (`info`, `success`, `warning`, `error`; FEATURES F-NTF-02), `duration` (s, 0 = until dismissed), `icon` (`mdi:…`), `chime`, `tag` (same tag replaces) | `notifications` |
| `play_media` (`command_play_media`) | `url` (absolute http(s) without userinfo, or a `/` path resolved against the device's HA base URL), `media_type`, `announce`, optional `volume`. The device never attaches its HA token when fetching it (ADR 0001 item 5). | `audio` |
| `media_control` (`command_media_control`) | `action`: pause, resume, stop, seek (needs `position`, seconds), next, previous, set_volume (needs `volume`), mute, unmute. `seek` on a stream that cannot seek, and `next`/`previous` without a queue, get `failed` (FEATURES F-MED-01) | `audio` |
| `screensaver` (`command_screensaver`) | `active`, optional `mode` | `screensaver` |
| `navigate` (`command_navigate`) | `path` starting with a single `/`. The device resolves it against its HA base URL and refuses (`invalid_args`) if the result leaves that origin (ADR 0001 item 3) | `navigation` |
| `go_home` (`command_go_home`) | `{}`. Load the start target (F-DSH-02, `dashboard.start_path`); with none set, HA's default page | `go_home` |
| `reload`, `restart_app`, `clear_cache` | `{}` | always |
| `log_subscription` (`command_log_subscription`) | `enabled`, optional minimum `level` | `logs` |
| `screenshot` (`command_screenshot`) | optional JPEG `quality` | `screenshot` (Phase 3) |
| `voice_start` (`command_voice_start`) | `turn_id`, `handler_id` (1–255, the byte that prefixes every binary frame of the turn), `start_stage` (`stt`: the device detected the wake word; `wake_word`: HA's engine runs on the stream), optional `wake_word_phrase` (absent for `wake_word`) | `voice` (Phase 7) |
| `voice_stop` (`command_voice_stop`) | `turn_id`, `reason` (`ended`, `cancelled`, `timeout`) | `voice` (Phase 7) |
| `intercom_ring` (`command_intercom_ring`) | `call_id`, `caller_device_id`, `caller_name` (1–64 chars, for the incoming-call screen), `mode` (`call` or `broadcast`) | `intercom_listen` |
| `intercom_signal` (`command_intercom_signal`) | `call_id`, `kind` (`offer`, `answer`, `candidate`), `payload` (the session description or candidate's own text, at most `INTERCOM_SIGNAL_MAX_BYTES`; the schema's `maxLength` is the same number in characters), optional `peer_device_id` (the other end of that one connection; present only in a caller's frames of a call with more than two devices, section 4.5's Intercom paragraphs) | `intercom_listen` |
| `intercom_end` (`command_intercom_end`) | `call_id`, `reason` (`hangup`, `decline`, `timeout`, `limit`, `error`, `disconnect`, `command`, `busy`) | `intercom_listen` |
| `intercom_start` (`command_intercom_start`) | `call_id`, `mode` (`call` or `broadcast`), `peers` (1–`INTERCOM_BROADCAST_MAX_TARGETS` of `{device_id, name}`, exactly one when `mode` is `call`) | `intercom_caller` |

The integration never sends a command whose capability the device did not declare.

**Settings revisions.** HA is authoritative (plan 5.6). The `set_settings` the integration sends after registration, when revisions differ, is applied whatever its revision, so a device whose revision is ahead of HA's (after an HA backup restore, or after the hub was removed and added again) converges on HA's copy. Within one connection, a `set_settings` with a lower revision than one already applied on that connection gets `stale_revision`. A device whose own settings schema is newer than `schema_version` applies the document and keeps its defaults for keys HA does not send.

**Intercom.** Capabilities are `intercom_listen` (can hear a call) and `intercom_talk` (can capture one); ADR 0025 decision 7 names the concepts, and P9.1's Result records why the wire spelling is single-segment. The three commands need `intercom_listen`, `intercom_start` is gated `intercom_caller` (below), and the device's own `intercom_start_request` event needs `intercom_talk`: starting a call means capturing one, so a listen-only device never sends it, and the integration resolves targets only for a device that declares the capability. A ring that arrives while a call is already up is refused with `result` NACK `busy` rather than answered, because a device can only be in one call; the integration turns that into the caller's `intercom_end` reason `busy` (section 6's `busy` is the same "cannot run it now"). Signaling is relayed, never interpreted: `intercom_signal`'s `payload` is the peer's own session description or ICE candidate, and HA copies it unchanged between devices of the same hub (ADR 0025 decision 4). `call_id` names one call and is chosen by whoever starts it: the integration for a call an HA service started, the device for one started on its screen (`intercom_start_request`), and every later frame for that call carries it. A ring is answered with `intercom_answer` or `intercom_decline` within `INTERCOM_RING_LIMIT_S`; a device that does neither is treated as having missed the call. `intercom_end` reaches every device in the call, in every state, and each answers it with one result; a device not in that call answers `ok` and changes nothing (ADR 0028 guard 3). `reason` `busy` is how the integration ends the caller's side when the callee refused the ring because it was already in a call.

**Intercom, the calling end.** `intercom_caller` is the third intercom capability: a build that can be the calling end of a call the integration started, a broadcast included. The caller of every call the integration starts receives `intercom_start`, whoever asked for the call: after the targets are selected and their rings settled, and before anything is relayed to it. That frame is the only thing that tells it the call id (for a call an HA service started) and the resolved targets (for a device-started area or all-devices request, which named something it cannot enumerate itself); a caller's own id is never in the peer list it gets back. The frame is **needed** — and a caller that does not declare `intercom_caller` is refused, never rung with no caller side — whenever the integration started the call or the mode is `broadcast`, a broadcast of one receiver included, because one resolved receiver is a target list and not a two-device call. It is not needed only for a call a device started from its own screen that named its one target, which that device already holds by id.

Separately from that gate, and as a rule of the integration rather than of the frame, a caller of any call declares `intercom_listen` and `intercom_talk`: a caller that cannot receive `intercom_signal` has no return path for the answer and the candidates, and a caller that cannot capture has nothing to send. A callee needs `intercom_listen` alone.

**Per-peer signaling.** A peer connection joins two devices, so a call of two devices has one connection and a broadcast has one per receiver, all star-shaped at the caller; the call stays one call in HA — one record, one limit, one `intercom_end` that reaches every receiver and every connection the caller holds. `intercom_signal`'s optional `peer_device_id` says which connection a frame belongs to: in the device's event it names the receiver the signal is for, and in the command the integration sends it names the device the signal came from. It appears only in a caller's frames of a call with more than two devices — in a two-device call the other device is the only candidate, so neither side omits anything it needs — and a receiver never sees it and never sends it, so a broadcast's receivers speak v1 unchanged. A caller's signal that arrives without it in a call of more than two devices has no connection to go to: the integration logs it and drops it, and the call survives. On a callee's signal the integration ignores whatever that member says and takes the id from the authenticated sender, so a receiver cannot address a frame to an arbitrary peer of the call.

The relay is a star, not a fan-out: a caller's signal goes to the peer it names (or, in a two-device call, to the other device), and a callee's signal goes to the caller alone. `mode` `broadcast` is relayed exactly like a `call`; there is no broadcast exception. Nothing is relayed to a device before it has the call: the order is always targets, rings, `intercom_start` to the caller, then signals, and a caller's signal that arrives earlier is held on the call record and flushed when the call becomes ready — bounded by the `COMMAND_TIMEOUT` on that one command result, never by a timer of its own. A resolved broadcast larger than `INTERCOM_BROADCAST_MAX_TARGETS` is refused with a validation error naming the devices, never trimmed.

### 4.6 `result`

`command_id`, `ok`; `error` (`code`, `message`) exactly when `ok` is false; optional `data` (for `set_settings`: `{"revision": n}` applied; no other command returns data in v1). The integration waits `COMMAND_TIMEOUT` (`SCREENSHOT_TIMEOUT` for `screenshot`) and raises `HomeAssistantError` on NACK or timeout. It matches a result by (connection, `command_id`): a result for an unknown or already-answered command, or one arriving on a different connection, is acknowledged and ignored.

`restart_app` is answered before the restart; `reload` and `clear_cache` when the reload has started.

### 4.7 `event`

`event` and `data` (each event's `data` has its own schema). The integration fires an HA bus event (plan 5.7) with `type` set to the TLP event name, plus `device_id` (HA) and `tabula_device_id`:

| `event` | `data` | HA bus event |
| --- | --- | --- |
| `notification_shown` | `tag` (if any) | `tabula_notification` |
| `notification_dismissed` | `tag` (if any), `reason`: `touch`, `timeout`, `replaced`, `command` | `tabula_notification` |
| `notification_action` | `tag`, `action` (reserved for actionable notifications) | `tabula_notification` |
| `screensaver_started` | `mode`, `trigger`: `idle`, `command`, `schedule` | `tabula_screensaver` |
| `screensaver_stopped` | `mode`, `trigger`: `touch`, `command`, `schedule` | `tabula_screensaver` |
| `media_finished` | `reason`: `ended`, `stopped`, `error` | `tabula_media` |
| `page_changed` | `path` (no query string or fragment) | `tabula_page` |
| `wake_word_detected` | `phrase` (`[a-z0-9_]{1,32}`), `probability` (0–255, the model's own scale) | `tabula_wake_word` |
| `voice_turn_started` | `turn_id`, optional `phrase` (the wake word the turn began with) | `tabula_voice` |
| `voice_turn_ended` | `turn_id`, `reason`: `command` (a `voice_stop`), `timeout` (the device's own limit), `error` (microphone or codec failed). The device's account of why the turn ended, not an echo of `voice_stop`'s `reason` (4.10) | `tabula_voice` |
| `intercom_answer` | `call_id` | `tabula_intercom` |
| `intercom_decline` | `call_id` | `tabula_intercom` |
| `intercom_signal` | `call_id`, `kind`, `payload` (same shape as the command), optional `peer_device_id` (the other end of that one connection; a caller of a call with more than two devices always sends it, a receiver never does) | `tabula_intercom` |
| `intercom_ended` | `call_id`, `reason` | `tabula_intercom` |
| `intercom_start_request` | `call_id`, `mode`, and either `target_device_id` (an HA device registry id) or `target_area_id` (an HA area id); `call` needs one of them, the two are exclusive, a `broadcast` may carry neither | `tabula_intercom` |

### 4.8 `log`

While a log subscription is on, the device sends batches of 1–`LOG_MAX_LINES` lines (`ts`, `level`, `tag`, `message`), at most one frame per `LOG_MIN_INTERVAL`. The subscription ends on `log_subscription {enabled: false}`, on disconnect, or after `LOG_SUBSCRIPTION_TTL` (the integration re-sends to extend). Lines never contain credentials, nonces or codes: the device redacts `Authorization` headers, `access_token=`, `authSig=` and `refresh_token` values, and strips query strings from URLs before logging. The same rule is why `current_page` and `page_changed` carry paths only.

### 4.9 Superseded connections

A device has one current connection. When a newer `tabula/register` succeeds for the same device (typically the device reconnected before HA noticed the old socket died):

- The integration makes the new connection current and stops sending commands on the old one. In HA 2025.1.0 and 2026.2.3, `ActiveConnection` (`homeassistant/components/websocket_api/connection.py`) has no method that closes its socket (`async_handle_close` is cleanup after a close); the server closes a socket only when its refresh token is revoked, which would also end the device's new connection. The development branch at 0fa84d1d adds `async_auth_revoked`, also tied to revocation. So the old socket stays open until it dies.
- Any later `tabula/*` frame on the old connection gets `error_result` `superseded` and has no effect, so stale state never overwrites fresh state. A device that receives `superseded` closes that socket.
- Commands still waiting for results on the old connection fail when their timeout expires (results are matched per connection, 4.6).
- The old connection closing does not change the device's availability; only the current connection's close does.

### 4.10 Binary voice frames (`voice`)

While a voice turn is open the device sends HA WebSocket **binary** messages on the same connection. The first byte is the `handler_id` from `voice_start`; the rest is exactly `VOICE_CHUNK_BYTES` (320) bytes of PCM: 16 kHz, 16-bit signed little-endian, mono. 320 bytes is 160 samples, 10 ms of audio, which is HA's own assist chunk (`SAMPLE_RATE` 16000, `SAMPLES_PER_CHUNK` 160 in `assist_pipeline/const.py` at the 2025.1 floor).

- A turn always opens with `voice_start`, which is the only frame carrying a `handler_id`. A device that detects the wake word itself has no handler id: it reports `wake_word_detected` (4.7) and waits, and the integration answers with `voice_start` (`start_stage` `stt`, `wake_word_phrase`) like any other turn. Nothing in the protocol lets a device open a turn on its own (ADR 0033 item 3).
- An empty binary message is a protocol violation, not something to ignore: HA answers `len(data) < 1` with `Disconnect("Received invalid binary message.")` and drops the whole connection, taking any open turn with it. Both codecs refuse to build one (`encode_frame`, `VoiceFrame`), and a transport must never send a zero-length binary message for any other reason.
- A binary frame has no `id`, no `type` and no device ID. The connection binds it to the device and the handler byte binds it to the turn.
- The device sends one frame per 10 ms of real time while the turn is open, starting after `voice_start` is acknowledged. It may interleave `tabula/*` JSON frames freely; the two are separate message types.
- The turn ends on `voice_stop` (device emits `voice_turn_ended` with `command`), after `VOICE_TURN_MAX_S` seconds (reason `timeout`), on a capture error (reason `error`), or when the connection closes (no event: there is nowhere left to send it). Nothing binary is sent outside an open turn.
- The device enforces `VOICE_TURN_MAX_S` itself, so a lost `voice_stop` cannot stream forever. The integration reads the timeout event as the end of its pipeline.
- `voice_stop` for a turn that is not open gets `ok`: the state it asks for already holds, and a stop that arrives after the device's own timeout is normal.
- The integration registers an HA binary handler for the turn (`ActiveConnection.async_register_binary_handler`, one byte id, 1–255) before sending `voice_start`, and unregisters it when the turn ends; HA reuses freed ids. Frames whose handler byte names no open turn are dropped.
- The mock device and the Android codec carry this frame as a byte-array wrapper with the handler byte split off (`VoiceFrame.handlerId` + `payload`); it has no JSON schema entry.

## 5. Screenshot upload (HTTP, authenticated)

`POST /api/tabula/screenshot`, `Authorization: Bearer <device user token>`, `Content-Type: image/jpeg`, header `X-Tabula-Command-Id: <command_id>`, body at most `SCREENSHOT_MAX_BYTES`. The device is resolved from the token (no device ID in the path; this replaces the `{device_id}` path in plan 5.1, for connection binding). The integration accepts the upload only if the command ID names a pending `screenshot` command for that device. Responses: `200 http_screenshot_response`, `400 invalid_request` (a non-JPEG `Content-Type` or an empty body; the media type is matched with parameters ignored), `401`, `403 forbidden` (not a Tabula device user), `409 no_pending_screenshot`, `413 too_large`. The device uploads first, then sends `tabula/result` for the `screenshot` command.

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
| `stale_revision` | `set_settings` revision older than one applied on this connection (4.5) |
| `superseded` | Frame on a connection that a newer registration replaced (4.9) |
| `busy` | Device cannot run it now (e.g. a restart is in progress) |
| `failed` | Anything else; `message` explains |

HA itself may also return its own codes (`unauthorized`, `unknown_command`, …) in `error_result`.

## 7. Reconnect and backoff

- The device keeps one WebSocket. On close or error it reconnects after a delay: attempt *n* (from 0) waits a uniformly random time in [d/2, d], d = min(`BACKOFF_MAX`, `BACKOFF_BASE` × 2ⁿ). *n* resets once a connection has stayed registered for `BACKOFF_RESET_AFTER`.
- A reconnect repeats the whole session order (register, subscribe). Registration is idempotent.
- If the integration's config entry unloads or reloads, the server closes the device's sockets; the device reconnects as above (ADR 0019).
- Commands in flight when a connection drops are lost; the integration's wait times out and the HA service call fails. The device never replays results on a new connection.
- The device's keepalive is the `ping` command (section 4); a missing `pong` ends the connection.
- While disconnected, the device keeps working from cached settings (plan 5.6) and buffers nothing except the latest full state, which it sends with `register`.

## 8. Versions and compatibility

- `protocol_version` is an integer major. This document is major 1.
- The integration supports its current major and the one before it (plan 5.4). It answers `register_result.protocol_version` with the major it will speak, which is the device's.
- Additive changes do not bump the major: new optional fields, new state keys, commands, events or frame types **gated by a new capability**. Both sides follow 4.0 (tolerant receivers, capability negotiation at registration).
- `intercom_start` (a new command, so a new frame `$defs`), the `intercom_caller` capability and `intercom_signal`'s optional `peer_device_id` are exactly that kind of change (ADR 0037): one new command, one new capability, one new optional member in two places, one new shared object, no new value in any existing enum, no major bump and no new schema version. A peer that predates them keeps seeing v1 frames it already handles.
- **Capability names.** A name stays one segment of `[a-z][a-z0-9_]{0,31}`, and nothing in an existing name changes. Tolerance for unknown members (4.0) does not cover a name that fails the pattern: a pattern failure rejects the frame, so a device declaring `intercom.listen` could not register with an integration older than that name, and the device could not discover that first because `register` is its first frame. A new capability is therefore always a new single-segment name that an older peer can ignore.
- **Enum extensions.** Ignoring unknown members does not cover a new value in an existing enum (a new screensaver mode, notification level or media action), which a strict receiver would reject. Such a value is additive only when gated by a capability: a sender uses it only if the peer declared (device) or accepted (integration) that capability. A receiver that still gets a value it does not know treats it as follows: a command argument → `invalid_args`; a state value → the key is ignored for that frame; an event `data` value → the event is forwarded with the value unchanged. v1 already lists every value FEATURES.md names, so v1 needs no such capability.
- Both sides read `protocol_version` before validating the rest of `tabula/register` or `http_pair_request`, so a mismatch is always reported as `unsupported_protocol`.
- Anything else (removing or renaming a field, changing a type or meaning, making a field required) bumps the major, adds `schema/tlp-v<N>.schema.json` and a new fixture directory, and keeps the previous ones for the N-1 conformance run (plan 8).
- `schema_version` versions the settings schema (P0.5) independently; translation rules are in plan 5.4.

## 9. Fixtures

```text
schema/fixtures/tlp/v1/<frame>[.<variant>].json          must validate as <frame>
schema/fixtures/tlp/v1/invalid/<frame>.<variant>.json    must fail validation as <frame>
```

`<frame>` is a `$defs` name from `x-tabula-frames`. Every frame type has at least one valid fixture. `make frames` (part of `make check`) runs `tools/validate_frames.py`, which checks both directions and the coverage. The mock device (P1.1), the integration tests and the Android codec (P2.0) all test against these files.
