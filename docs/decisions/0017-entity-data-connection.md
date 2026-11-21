# 0017: Entity data connection

Status: accepted
Date: 2026-11-21
Packet: P3.5

## Context

F-AAG-04 needs the screensaver's At a Glance row and HA-backed widgets to subscribe to their own entities over the device's own HA connection while the screensaver shows, and to unsubscribe when it closes. That must hold independently of the dashboard page. The weather scene (F-SSV-11) and the weather and forecast widgets (F-WGT-04, F-WGT-05) need forecasts. F-AAG-03 needs what HA's frontend uses to present a state: icon, translated state text and display precision. PLAN 5.1 says the device keeps one connection to HA and that the connection carries only `tabula/*` commands. Two questions follow: whether the subscription rides that socket or a second one, and which HA commands supply forecasts and display data.

The findings below come from reading HA core's source (Apache-2.0; `websocket_api`, `weather`, `config/entity_registry`, `frontend`; HA 2026.2 as pinned in this repo's dev environment). No code was adapted.

## Decision

1. **One socket.** The subscription rides the TLP WebSocket, which is HA's own `/api/websocket` already. `TlpClient` publishes an `HaChannel` for each registered connection. The channel withdraws when the connection ends. Commands on it take ids from the same increasing counter as the `tabula/*` frames, and the read loop routes `event` messages by subscription id. There is no second auth, keepalive, backoff loop or token refresh. A reconnect is the TLP client's (PROTOCOL 7), and `EntityDataConnection` subscribes again on the new channel. A dedicated socket would isolate a state burst from TLP frames, but the subscription is bounded by the explicit list (At a Glance at most 6, plus at most four widget and scene entities), so no burst arises. PLAN 5.1 is amended to say so.
2. **`subscribe_entities` with an explicit list, never an empty one.** HA reads a missing or empty `entity_ids` as every entity the user may read. `EntityNeeds` (core) builds the list from settings and the showing mode, and an empty need opens nothing. The events use HA's compressed format: the first event `a` holds `{s, a, c, lc, lu?}` for each listed entity that exists; later `a` events add entities, `c` events carry diffs (`+` with only the changed fields and the added or changed attributes, `-` with `{a: [removed keys]}`), and `r` events remove entities. A listed entity HA does not have shows as `unavailable`, never as a gap (F-AAG-03). Context ids and `lu` are ignored.
3. **Lifecycle.** `ScreensaverManager` hands `EntityNeeds.of(settings, shownMode)` to the connection after every outcome. The connection opens when the screensaver starts, re-targets when a settings change alters the need, and closes (`unsubscribe_events`) on dismissal. Calendar mode needs nothing (F-AAG-01, F-WGT-01). While no TLP connection exists, consumers keep the last-known data. Errors are logged and never stop the screensaver. Nothing is sent on a connection that has already gone.
4. **Forecasts: `weather/subscribe_forecast`.** HA removed forecasts from weather entity attributes, and this command is what the frontend uses: `{entity_id, forecast_type: daily|hourly|twice_daily}`, answered by events `{type, forecast: [...]}`. An unknown entity is refused with `invalid_entity_id` and an unsupported type with `forecast_not_supported`. Both leave the forecast empty, so the forecast widget shows nothing (F-WGT-05). The weather scene and the forecast widget use `daily`. The weather scene and weather widget also subscribe to `sun.sun` for time of day.
5. **Display data: the frontend's own calls, fetched once per subscription after the live data.**
   - `config/entity_registry/list_for_display` gives the platform (`pl`), icon override (`ic`), translation key (`tk`) and sensor display precision (`dp`).
   - `frontend/get_translations` with categories `entity_component` and `entity` gives state strings under `component.<domain>.entity_component.<device_class|_>.state.<state>` and `component.<platform>.entity.<domain>.<translation_key>.state.<state>`.
   - `frontend/get_icons` with the same two categories gives default and per-state icons.
   - Lookups fall back the way the frontend does: the entity's translation key, then its device class, then the domain. Icons take the `icon` attribute, then the registry override, then the translation-key icons, then the device-class icons, then the domain icons. With nothing found, the raw state is shown capitalized and no icon is given; P3.6's renderer then applies its own fallback.
   - The language is the device locale's language code. HA falls back to English for a language it lacks.
6. **Non-admin access.** The device's HA user is a non-admin system user (ADR 0001 item 2). None of the five commands is decorated `require_admin` in HA core. `subscribe_entities` filters by the user's read permission (`POLICY_READ`), as every state read does. `weather/subscribe_forecast`, `list_for_display`, `get_translations` and `get_icons` have no user check beyond authentication.

## Consequences

- P3.6, P3.7 and P3.11 read `EntityDataConnection.states`, `forecasts` and `display`, and need no HA calls of their own.
- The mock device's test server speaks this slice of HA core (`mockdevice/hacore.py`) in HA's real formats, with `/test/entity`, `/test/forecast`, `/test/display` and `/test/drop` hooks. A JVM test drives the real client over a socket against it.
- Display data is not refreshed while a subscription lasts. A translation or registry change shows the next time the screensaver starts.
- The black mode's hide-everything option (F-SSV-06) has no settings key yet, so black mode still opens the subscription when the row or widgets are configured. The key and the gate in `EntityNeeds` belong to the packet that adds the option.
- Per-slot overlay overrides (F-SSV-18) are the schedule's (P3.12): it will hand `EntityNeeds` the effective settings.

## Alternatives considered

- **A dedicated socket.** It would isolate the lifecycle and bursts, but it costs a second auth, keepalive and reconnect loop, and a second token-refresh path to keep correct. The bounded list removes the burst argument.
- **`subscribe_events` for `state_changed`.** A non-admin user may subscribe to it (HA filters by read permission), but it has no entity filter: every state change in the house arrives as full old and new state objects. `subscribe_entities` is the frontend's own filtered, compressed path.
- **Forecast attributes on the weather entity.** HA no longer provides them.
- **Display data from HA REST (`/api/states`).** It has neither translations nor display precision.
