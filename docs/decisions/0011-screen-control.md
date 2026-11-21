# 0011: Screen control on the device (P2.3)

Status: accepted
Date: 2026-11-21
Packet: P2.3

## Context

P2.3 builds screen control on the tablet: the backlight, panel power, keep-on and adaptive brightness. FEATURES F-SCR-01 to F-SCR-08 and F-SCR-14 describe the behavior. ADR 0010 item 5 put the brightness ownership rule on the device: a `screen` command with `brightness` turns adaptive off there, and the integration sends no separate settings change. Building it surfaced four questions that FEATURES and the earlier ADRs leave open.

## Decision

1. **HA's settings copy follows a manual level.** ADR 0010 item 5 left HA's stored settings document unchanged when the light turned adaptive off on the device. The next push of any unrelated setting then carried `adaptive_brightness: true` again, and the tablet turned adaptive back on. Now the screen light writes `screen.brightness` and `screen.adaptive_brightness: false` to the settings store as one revision after the `screen` command succeeds. That revision is pushed like any edit. The device already applied it, so applying it again changes nothing. This amends the last sentence of ADR 0010 item 5; the rest of that item stands.
2. **Brightness on start (F-SCR-02).** The device applies its stored screen settings at start only once HA has sent a settings document (`settings_revision > 0`). A freshly installed app leaves the system brightness alone, as F-SCR-02 asks, and no new setting is needed: pairing with HA is the opt-in. Until then the device reports the backlight's real level.
3. **The curve is fixed for now.** F-SCR-06 describes user-adjustable points. The settings schema has no curve fields yet, so the device uses the default curve from ADR 0002 (`BRIGHTNESS_CURVE`). The curve code takes any monotone point list, so adding the setting later is a schema change and a few lines. The sensor source is the tablet's own light sensor only. An HA illuminance entity as the source (F-SCR-05) is deferred. `adaptive_brightness` is declared only when the tablet has a light sensor.
4. **Mechanisms.**
   - Backlight: `Settings.System.SCREEN_BRIGHTNESS` with WRITE_SETTINGS. Auto-brightness is switched to manual on each write. The fallback is the window's `screenBrightness`.
   - Keep-on: the window flag `FLAG_KEEP_SCREEN_ON`. Screen wake locks are deprecated.
   - Panel off: `DevicePolicyManager.lockNow` through a device admin whose only policy is force-lock. Without the admin, the command is refused with `failed` and nothing changes.
   - Panel on: a wake lock with `ACQUIRE_CAUSES_WAKEUP`, released at once, then the dashboard is brought to the front. A swipe-only lock screen is cleared; a secured one is left alone.
   - 0 % maps to the lowest non-zero backlight level. On many panels 0 switches the backlight off, and switching the panel off is a separate action.
   - A manual level from HA is applied at once. Only adaptive changes fade.

## Consequences

- HA's settings document, the device's stored copy and the `adaptive_brightness` state agree after a manual change, and stay that way across pushes and restarts.
- A light-entity brightness change now costs one settings revision and push in addition to the command. That is cheap, and it is the price of decision 1.
- Screen control runs for the service's lifetime, independent of the HA connection, so adaptive brightness works offline.
- Follow-ups: curve settings and the HA-entity source (F-SCR-05, F-SCR-06); F-SCR-07's curve rescaling for the management panel and the JS bridge; F-SCR-09's screensaver levels (P2.4); F-SCR-13's opt-in for secured lock screens.

## Alternatives considered

- **Device reports the change and HA reconciles from state.** HA could watch `adaptive_brightness: false` in a state delta and write the store. But state also changes for other reasons, such as a push being applied, and telling them apart needs more protocol. The light knows exactly when it caused the change.
- **A dedicated "brightness on start" setting.** It would be closer to F-SCR-02's wording, but it adds a schema field that pairing already covers.
- **A screen-bright wake lock for keep-on.** It is deprecated, and unlike a window flag it is not released when the dashboard leaves the screen.
