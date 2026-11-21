# 0001: HA platform

Status: accepted for items 1–5, 7 and 8; item 6 (device WebView version) pending hardware output
Date: 2026-11-21
Packet: P0.3

## Context

Plan section 5 rests on assumptions about Home Assistant (HA) and the Fire tablet WebView that nobody had checked: that a discovery flow can be started from our own HTTP view, which credential an integration-created user can use for both the WebSocket API and the dashboard, how the frontend authenticates inside an Android WebView, how custom WebSocket subscriptions behave, how TTS reaches a media player, and which HA version and WebView version we need. P0.3 tested each with throwaway code.

### Method

- A throwaway custom integration (`tabula_spike`: hub flow, discovery flow, pairing and claim views, three WebSocket commands, `notify`, `media_player` and `tts` platforms) plus a pytest suite built on pytest-homeassistant-custom-component. Per the packet, the spike code is not committed.
- Run against **HA 2026.2.3** (newest release on PyPI on 2026-11-21), **HA 2025.1.4** and **HA 2025.1.0** (pytest-homeassistant-custom-component 0.13.316, 0.13.205 and 0.13.201). All three produced the same evidence lines.
- A real HA 2026.2.3 instance (`hass -c`, onboarded through its REST API) with the shipped frontend (`home-assistant-frontend==20260128.6`), driven by headless Chromium through Playwright, for the dashboard checks.
- Source reading, all Apache-2.0: HA core (`home-assistant/core` at `0fa84d1`), HA frontend (`home-assistant/frontend` at `113f90e`) and the HA Companion Android app (`home-assistant/android` at `cdc8537`). Nothing was copied; what we learned is recorded here.

Evidence lines below are pasted from the spike's output (`EVIDENCE:` prefix stripped). Secrets are truncated with `…`.

## Findings

### 1. Hub entry plus discovery flow from a custom HTTP view: works

An unauthenticated `HomeAssistantView` calls `homeassistant.helpers.discovery_flow.async_create_flow(hass, DOMAIN, context={"source": SOURCE_INTEGRATION_DISCOVERY}, data={...})`. The flow's `async_step_integration_discovery` sets `unique_id` to the device ID and shows a confirm form that asks for the code shown on the tablet. On success it creates the user and credential, adds an HA device under the hub entry with `device_registry.async_get_or_create(config_entry_id=hub.entry_id, ...)`, and ends with `async_abort(reason="paired")`, which the UI shows as a message.

```text
hub flow: create_entry Tabula
second hub flow: abort already_configured
POST /pair -> 202 {'status': 'pending'}
flows in progress: [('integration_discovery', 'confirm')]
wrong code -> form {'code': 'bad_code'}
right code -> abort paired
claim wrong nonce -> 403
claim -> 200 {'type': 'refresh_toke…', 'refresh_token': '9a486a9b0fa1…', 'client_id': None}
claim again -> 202
```

On the real instance, the flow appears to the admin UI as a discovered device with its title placeholder:

```text
system discovered flows visible to admin UI: [('integration_discovery', 'confirm', {'device_id': 'cb5b6d5d'})]
system confirm: abort paired
```

**Constraint found:** the manifest must **not** set `"single_config_entry": true`. With it, once an entry exists HA aborts every new flow except ignore, reauth and reconfigure (`config_entries.py`, `async_init` and `async_finish_flow` both return `single_instance_allowed`), so device discovery would stop working after the hub is set up.

**Side effect of discovery:** pressing "Ignore" on a discovered tablet starts a flow with source `ignore` and the flow's `unique_id`, which creates a `tabula` config entry with source `ignore` keyed by the device ID (`config_entries.py`, `ConfigFlow.async_step_ignore`). Later discovery flows for that `unique_id` abort in `_abort_if_unique_id_configured`. Evidence (HA 2026.2.3):

```text
ignore -> create_entry
entries: [('user', 'hub'), ('ignore', '0f0e0d0c-0000-4000-8000-000000000001')]
flows left: 0
flows after re-pair request: 0
hub lookup by unique_id: user hub
```

P1.2 looks up the hub by its own `unique_id` (as above), never "the first entry", and documents that an ignored tablet is re-enabled from the integration's ignored list before it can pair again.

**Claim endpoint:** the spike answered `202 pending` for an unknown or already-claimed device. P1.2 must return a terminal error for those (plan 5.3: unknown device IDs are rejected) so the device stops polling. The hub singleton is enforced in the user step instead, with a fixed `unique_id` and `_abort_if_unique_id_configured()` (see "second hub flow" above).

### 2. Credential type: system user + system refresh token

Three variants were created by the confirm step, each in group `system-users` (non-admin), and each tested for the WebSocket API, a REST call, admin-only commands, the commands the frontend issues at startup, a service call and revocation.

| Variant | How created | WS + REST | Dashboard (external auth) | Expiry | Shown in Settings → People |
| --- | --- | --- | --- | --- | --- |
| **System user + system refresh token** | `async_create_system_user(name, group_ids=[GROUP_ID_USER])`, `async_create_refresh_token(user)` | yes | yes | never | no |
| Regular user + normal refresh token | `async_create_user(...)`, `async_create_refresh_token(user, client_id=...)` | yes | yes | 90 days after last use | yes |
| Regular user + long-lived access token | `async_create_refresh_token(user, client_name=..., token_type=long_lived_access_token)` | yes | yes | fixed (chosen at creation) | yes |

Evidence for the system variant (the other two are identical except `system_generated` and `token_type`):

```text
POST /auth/token -> 200 {'token_type': 'Bearer', 'expires_in': 1800}
ws auth -> auth_ok
whoami -> {'user_id': 'cc5b…', 'name': 'Tabula 0f0e0d0c', 'is_admin': False, 'system_generated': True, 'refresh_token_id': '941a…', 'token_type': 'system'}
admin-only config/auth/list -> False {'code': 'unauthorized', 'message': 'Unauthorized'}
frontend cmd auth/current_user -> True
frontend cmd get_panels -> True
frontend cmd frontend/get_themes -> True
frontend cmd get_config -> True
frontend cmd get_states -> True
frontend cmd lovelace/config -> False config_not_found   (no dashboard saved in the test instance; expected)
frontend cmd frontend/get_user_data -> True
call_service notify.send_message as device user -> True
GET /api/ with device token -> 200
```

The device exchanges its refresh token for a 30-minute access token at `POST /auth/token` (`grant_type=refresh_token`) **with no `client_id`**: HA's token endpoint accepts a missing `client_id` when the stored token has none (`components/auth/__init__.py`, `_async_handle_refresh_token`), and system tokens are required to have none (`auth/__init__.py`, `async_create_refresh_token`).

Why the system variant:

- Non-admin, with no expiry to manage on a device that may sit offline.
- Hidden from Settings → People, and HA refuses to edit or delete system-generated users through its own admin commands (`components/config/auth.py`, `cannot_modify_system_generated`). The integration owns the user's whole lifecycle, which matches plan 5.2 (deleting the device revokes the credential).
- Short-lived access tokens on the wire; the long-lived secret is the refresh token, kept in Keystore-backed storage.

**Fallback:** if a future HA release stops accepting refresh without `client_id`, or restricts system users in the frontend, switch to a regular non-admin user + normal refresh token with a fixed `client_id` URL. It passed every check above. Its costs: the user shows in Settings → People, and a token unused for 90 days expires (the device must re-pair).

**Constraint found (regular users only):** `async_create_user` makes the new user the **owner** (admin) if no other non-system user exists yet (`auth/__init__.py`, `_user_should_be_owner`). In a fresh test harness that produced `'is_admin': True`. A real instance always has an owner after onboarding, but P1.2 must assert `not user.is_admin` after creating any regular user. System users are never owners.

**Restrictions on refresh:**

- `local_only` users may refresh only from a local IP and never through the cloud relay (`components/http/auth_util.py`, `async_user_not_allowed_do_auth`). Decision: device users are created with `local_only=True` (wall tablets sit on the LAN; this limits what a stolen tablet's credential can do off-site). P1.2 adds a hub option to create them without it for remote tablets.
- `/auth/token` failures count toward HA's IP ban (`@log_invalid_auth` on the token view). A revoked tablet that keeps retrying could get its IP, or a reverse proxy's IP, banned. P2.1 treats `invalid_grant` or 403 from `/auth/token` as final: stop retrying and show the pairing screen.

**Orphaned credentials:** system users are invisible in the UI and their tokens never expire, so if the integration's files are removed without removing the hub entry, working credentials remain that the admin cannot see. P1.2 stores every device user ID, sweeps users it no longer knows at setup, and removes all of them in `async_remove_entry`. DEVICE.md (P2.1) documents how to remove leftover users.

**Revocation order matters:**

```text
live socket after async_remove_refresh_token -> 8   (CLOSE)
user still exists: True refresh tokens left: 0
--
live socket after user removal -> still open (no close within 2 s)
command on that socket after user removal -> False
GET /api/ after user removal -> 401
after user removal ws auth -> auth_invalid
```

Removing a refresh token fires HA's revoke callback, which closes live WebSockets authenticated with it (`components/websocket_api/auth.py`, `async_register_revoke_token_callback`). In the released versions tested (2025.1.x, 2026.2.3), removing the user alone invalidates new requests but leaves an open socket connected (its commands fail). HA core main (`0fa84d1`) now revokes a user's tokens inside `async_remove_user`, so newer releases close the socket either way. Revocation in P1.2: remove the device's refresh tokens first, then the user; that is correct on every version.

### 3. Frontend external auth in an Android WebView: works with all three credentials

How the frontend does it (`src/external_app/external_auth.ts`, `src/data/external.ts`): when the page URL has `external_auth=1`, the frontend replaces its login with `ExternalAuth`. To get a token it sets `window.externalAuthSetToken = (success, data) => …` and calls `window.externalApp.getExternalAuth(JSON.stringify({callback: "externalAuthSetToken", force?}))`. The app answers by evaluating `externalAuthSetToken(true, {"access_token": "…", "expires_in": N})`. Revocation uses `revokeExternalAuth` and callback `externalAuthRevokeToken` the same way. The callback names are fixed by the frontend. The external bus (`externalApp.externalBus(json)`, replies through `window.externalBus(...)`) carries `config/get`, `connection-status`, `theme-update` and navigation messages.

How the Companion app does it (`app/.../frontend/js/FrontendJsBridge.kt`, `.../session/ServerSessionManager.kt`, `common/.../AuthenticationRepositoryImpl.kt`):

- V1: `WebView.addJavascriptInterface(obj, "externalApp")` with `@JavascriptInterface` methods `getExternalAuth`, `revokeExternalAuth` and `externalBus`. Works with every HA version we support.
- V2 (HA 2026.4.2 and later, when `WebViewFeature.WEB_MESSAGE_LISTENER` is available): `WebViewCompat.addWebMessageListener(webView, "externalAppV2", …)`; the frontend posts `{type, payload}` envelopes. The app rejects messages from iframes and from origins other than the HA server, which V1 cannot do.
- It answers by `evaluateJavascript("${callback}(true, ${json})")` with only `access_token` and `expires_in`, and validates the callback name against the fixed constant before evaluating anything.
- It loads the dashboard URL with `external_auth=1` appended.

Real-frontend check: HA 2026.2.3, frontend 20260128.6, headless Chromium 157, `window.externalApp` injected before load with the V1 method names, answering `getExternalAuth` with the device's access token:

```text
{"mode":"system","url":"http://127.0.0.1:8123/home?external_auth=1","user":"Tabula cb5b6d5d","is_admin":false,"connected":true,"states":12,"panelUrl":"home","authCalls":1,"bus":["config/get","connection-status","theme-update"]}
{"mode":"normal","url":"http://127.0.0.1:8123/home?external_auth=1","user":"Tabula 92ec7c5d","is_admin":false,"connected":true,"states":12,"panelUrl":"home","authCalls":1,"bus":["config/get","connection-status","theme-update"]}
{"mode":"llat","url":"http://127.0.0.1:8123/home?external_auth=1","user":"Tabula 1ffd0720","is_admin":false,"connected":true,"states":12,"panelUrl":"home","authCalls":1,"bus":["config/get","connection-status","theme-update"]}
```

A screenshot showed the Overview dashboard rendered for user "Tabula cb5b6d5d" with no login page. No token was written to localStorage. The remaining Android-specific risk (the Fire WebView's JS engine) is item 6.

Decision for P2.2:

- Implement the V1 bridge for all versions; add V2 when the server reports 2026.4.2 or later and the WebView supports the listener. Mirror the Companion app's callback-name check, and answer `config/get` on the external bus (the frontend waits for it).
- V1 (`addJavascriptInterface`) exposes `externalApp` to every frame and every page the WebView loads, so any page reached by navigation could ask for a token. P2.2 answers `getExternalAuth` only when the WebView's current main-frame URL has the HA origin, and keeps top-level navigation on the HA origin (`shouldOverrideUrlLoading`: other origins are blocked or opened outside the WebView).
- `revokeExternalAuth` is sent when someone taps "Log out" in the frontend. A wall tablet must not lose its credential that way: the device answers `externalAuthRevokeToken(true)` without revoking anything. Revocation happens only from HA (plan 5.2).

### 4. Custom WebSocket subscriptions, size limits, caller identity: work

- **Subscription delivery:** the command stores `(connection, msg["id"])`, registers an unsubscribe callable in `connection.subscriptions[msg["id"]]` and returns `send_result`. Pushes use `connection.send_message(websocket_api.event_message(msg_id, payload))`.

  ```text
  subscription event -> {'id': 40, 'type': 'event', 'event': {'command_id': 'c1', 'type': 'noop'}}
  ```

- **Caller identity (connection binding):** `connection.user` and `connection.refresh_token_id` are set for every command (`whoami` output in item 2). The integration maps the user ID to the device; no payload field is trusted.
- **Message IDs** must increase within a connection; a lower ID is rejected. The device's client must use a monotonic counter.
- **Size limits:**

  ```text
  inbound echo 65536 bytes -> True {'len': 65536}
  inbound echo 1048576 bytes -> True {'len': 1048576}
  inbound echo 4194104 bytes -> True {'len': 4194104}
  inbound 4 MiB+ -> frame type 8 1009
  outbound 8 MiB event (aiohttp client default 4 MiB limit) -> 258 WebSocketError(<WSCloseCode.MESSAGE_TOO_BIG: 1009>, 'Message size 8388655 exceeds limit 4194304')
  ```

  Inbound (device to HA) frames over 4 MiB close the socket with 1009 (aiohttp's default `max_msg_size`; HA does not override it). Outbound frames are not limited by HA; the client's own limit applies. HA also drops a connection whose outgoing queue reaches 4096 pending messages (`websocket_api/const.py`, `MAX_PENDING_MSG`).

  Consequence for P0.4: keep TLP frames small (the architect sets a cap well under 4 MiB in ADR 0002) and send anything large over HTTP, as plan 5.1 already says.

- HA sends WebSocket pings every 55 s (`web.WebSocketResponse(heartbeat=55)`), so the device does not need its own keepalive to keep HA's side open.

### 5. NotifyEntity and `tts.speak` to a media player: work; URL needs no credential

```text
play_media media_type: music
play_media raw media_id: media-source://tts/tts.spike_tts?message=Dinner+is+ready&cache=true&tts_options=null
resolved url: /api/tts_proxy/MColJQbb4pDnaAEVEeJf7Q.mp3
processed url: http://192.168.1.10:8123/api/tts_proxy/MColJQbb4pDnaAEVEeJf7Q.mp3
play_media kwargs: {'announce': True, 'extra': {}}
fetch TTS URL without auth -> 200 b'ID3fake-mp3-Dinn'
fetch TTS URL with device token -> 200
notify log: [('notify', 'Front', 'Door open')]
```

- `tts.speak` calls `play_media` with a `media-source://tts/…` ID. The entity resolves it with `media_source.async_resolve_media` and makes it absolute with `media_player.async_process_play_media_url`, which uses HA's configured internal or external URL. The media player must declare `PLAY_MEDIA` (and `MEDIA_ANNOUNCE` to receive `announce=True`).
- `/api/tts_proxy/{token}` is unauthenticated (`requires_auth = False`); the random token is the access control. The device fetches it with or without its credential. Other media-source URLs are signed (`authSig`) by `async_process_play_media_url`, so they also need no header.
- The integration sends the device an absolute URL. If HA's internal URL is unset or unreachable from the tablet, playback fails; P1.4 should raise a repair issue when no usable URL is configured.
- `notify.send_message` targeting a `NotifyEntity` reaches `async_send_message(message, title)`.

### 6. Office Kiosk WebView against the frontend's browser floor

Frontend floor (`.browserslistrc` at `113f90e`): browsers that fail the modern target get the legacy build, whose floor is **Chrome/Chromium 67** (first with BigInt). The file itself notes that on Fire tablets the Amazon WebView build decides support, not the Fire OS major, and that Fire OS 7 WebViews are above it.

Device check (hardware verifier). Read-only commands:

```sh
adb devices -l
adb shell getprop ro.product.model
adb shell getprop ro.build.version.release
adb shell getprop ro.build.version.sdk
adb shell getprop ro.build.version.name
adb shell getprop ro.build.mktg.fireos
adb shell pm list packages | grep -iE 'webview|chrome'
adb shell dumpsys webviewupdate
adb shell dumpsys package com.amazon.webview.chromium | grep -E 'versionName|versionCode|lastUpdateTime|firstInstallTime'
adb shell dumpsys package com.android.webview | grep -E 'versionName|versionCode|lastUpdateTime'
```

How WebView updates arrive on Fire OS (**unverified**; no source checked in this spike): Fire OS is expected to ship Amazon's own WebView package (`com.amazon.webview.chromium`) rather than Google's, updated by Amazon rather than Google Play. The `dumpsys webviewupdate` and `lastUpdateTime` lines in the device output will confirm or correct this.

Additional targets (stated 2026-11-21): a second Fire HD 10 Plus and two Echo Show 8 (2nd gen). The second Fire tablet takes the same check and the same provisioning. The Echo Show is outside v1 scope in plan section 4; whether it can run a sideloaded app with the system WebView, and with what WebView version, is **unverified**. The same read-only commands decide the WebView question once ADB access exists on one; the architect revisits plan section 4 with that output.

PF1007L frames: they need a WebView of Chromium 67 or later to run today's frontend at all, and the frontend raises its floor over time. Before committing to them, run the same commands on one; anything below 67 rules them out for the dashboard (the native screensaver could still run).

### 7. `assist_satellite` requirements (notes for Phase 7)

From `components/assist_satellite/entity.py` and `const.py` (HA 2026.2.3):

- Subclass `AssistSatelliteEntity`. Abstract methods: `async_get_configuration() -> AssistSatelliteConfiguration` (available wake words, active wake word IDs, maximum active), `async_set_configuration(config)` and `on_pipeline_event(event)`.
- Optional features (`AssistSatelliteEntityFeature`): `ANNOUNCE` (implement `async_announce`) and `START_CONVERSATION` (implement `async_start_conversation`).
- Audio enters HA through `async_accept_pipeline_from_satellite(audio_stream, start_stage, end_stage, wake_word_phrase)`: an async iterator of PCM chunks. Wake word detection on the device is the supported path; HA rejects a pipeline that starts at the wake-word stage while it is intercepting a wake word.
- The entity can point at a pipeline select and a VAD sensitivity select (`_attr_pipeline_entity_id`, `_attr_vad_sensitivity_entity_id`).
- Dependencies: `assist_pipeline`, `stt`, `tts`. Audio can ride the existing device connection; this needs a binary channel decision in Phase 7 because TLP frames are JSON.

### 8. Minimum HA version: 2025.1.0 (a chosen floor, known to work)

Every check in items 1, 2, 4 and 5 produced identical evidence on HA 2025.1.0, 2025.1.4 and 2026.2.3. Versions before 2025.1.0 were not tried, so this is a floor we chose and verified, not a measured minimum. On both 2025.1 runs the harness reported a teardown error after the tests passed (a lingering-thread assertion in the older test plugin, not a behavior difference):

```text
2025.1.0: 6 passed, 1 error in 7.41s
2025.1.4: 6 passed, 1 error in 7.79s
2026.2.3: 6 passed in 7.03s
```

Not re-tested at the floor: item 3 ran only on HA 2026.2.3 with frontend 20260128.6 (the V1 bridge it uses is older than 2025.1), and the Chromium 67 floor comes from frontend main, not the frontend shipped with 2025.1. `assist_satellite` exists in all three versions.

The full integration suite is now verified across the supported range: `make test-ha-matrix` runs it against one HA release per leg (selected by the pytest-homeassistant-custom-component pin), CI runs all three legs, and all 148 tests pass on the floor, the pin and the newest release (local run, 2026-11-21):

```text
phacc 0.13.201 -> HA 2025.1.0, Python 3.13: 148 passed
phacc 0.13.316 -> HA 2026.2.3, Python 3.13: 148 passed
phacc 0.13.375 -> HA 2026.10.4, Python 3.14: 148 passed
```

Two harness differences are handled in the test scaffolding, not in the integration:

- The floor harness (phacc 0.13.201) predates the teardown exemption for pycares's `_run_safe_shutdown_loop` thread, which any aiohttp client session starts for the process (aiodns is a homeassistant dependency). The test conftests start the thread once before any test's thread snapshot, so the teardown check stays active on every leg. This is the same lingering-thread assertion the spike's floor runs reported above.
- HA 2026.10 (aiohttp 3.14) leaves the websocket heartbeat timer scheduled when auth revocation closes a live connection: aiohttp 3.14 stopped cancelling prior heartbeat handles to cut TimerHandle churn, and the harness teardown predates an exemption for that. The one lifecycle test exercising that close declares the harness's `expected_lingering_timers` fixture; the socket is HA's websocket_api, and tabula only calls the auth API.

Mid-range behavior (verified in HA sources at 2025.8.0 through 2025.12.0, not run as a matrix leg): on those releases the `helpers.service` fallback logs one deprecation line per service call. HA deprecated that wrapper when `helpers.target` appeared, but `TargetSelection` only exists from 2026.1, so 2025.8-2025.12 must use the deprecated import; the integration works, at the cost of HA's log line.

- `hacs.json` and the integration manifest declare `homeassistant: 2025.1.0` (set in P1.2).
- CI tests the integration against the floor, the pin and the latest HA release (three `test-ha-matrix` legs).
- Python: 3.13 on the floor and pin; HA 2026.10 requires 3.14, and the suite passes on it (ADR 0004 governs the integration's own requirement).
- The external-bus V2 bridge is used only when the server reports 2026.4.2 or later.

## Decision

1. Pairing uses an unauthenticated view that starts an `integration_discovery` flow; the hub is a singleton enforced by `unique_id`, without `single_config_entry`. A confirmed device becomes an HA device under the hub entry, and the flow ends with an abort message.
2. Each device gets an integration-created **system user** in the user group (`local_only=True` by default) and a **system refresh token**. The device trades it for 30-minute access tokens at `/auth/token` without a `client_id`. Fallback: regular non-admin user + normal refresh token with a fixed `client_id`.
3. Revocation removes the device's refresh tokens, then its user.
4. The dashboard authenticates through the frontend's external auth bridge (V1 always, V2 from HA 2026.4.2).
5. Device commands flow down one custom WebSocket subscription; the device is resolved from `connection.user`. TLP frames stay well under 4 MiB; large data goes over HTTP.
6. TTS reaches the device as an absolute `/api/tts_proxy/…` URL that needs no credential.
7. Minimum HA version: 2025.1.0. WebView floor: Chromium 67, pending the device check for the Office Kiosk.

## Consequences

- Plan sections 5.2, 5.3 and 5.8 hold as written; 5.2's open question is answered (system user + refresh token).
- P1.2 must: not set `single_config_entry`; enforce the hub singleton by `unique_id` and skip ignored entries; return a terminal error from claim for unknown or claimed devices; create device users with `local_only=True` (hub option to disable); revoke tokens before deleting users; track device user IDs, sweep orphans at setup and remove them all in `async_remove_entry`; assert regular users are not admins if the fallback is ever used.
- P2.1/P2.2 must: refresh access tokens before `expires_in`; treat `invalid_grant`/403 from `/auth/token` as final (re-pair, no retry loop); use a monotonic WebSocket message ID; implement V1 external auth, answer `config/get`, answer tokens only to the HA origin, keep navigation on the HA origin, and acknowledge `revokeExternalAuth` without revoking.
- We depend on HA accepting refresh without `client_id` for system tokens. That code path is the same one HA uses for its own system tokens, but if it changes the fallback costs one P1.2 change and a re-pair.

## Alternatives considered

- **Long-lived access token per device:** works everywhere, but it is a bearer token valid for years on the wire, and the user shows in Settings → People. Rejected in favor of short-lived access tokens.
- **Regular user + refresh token:** works; kept as the fallback because the user is visible and editable outside the integration, and an idle token expires after 90 days.
- **One config entry per device:** would allow plain `async_create_entry` at the end of discovery, but plan 5.3 wants one hub owning pairing, storage and the panel. Rejected.
- **Injecting a token into the frontend's localStorage:** rejected in plan 5.8; external auth works, so it is not needed.
