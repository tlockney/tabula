# 0015: Settings entities

Status: accepted
Date: 2026-11-21
Packet: P3.2

## Context

FEATURES F-HA-11 lists settings that HA should expose as entities, so that automations and the HA UI can drive screensaver configuration without the Tabularium. Before Phase 3, every entity was state-backed: it showed a key the device reports (PROTOCOL 4.4) and was gated by a capability (ADR 0009). Settings work differently. They are HA's own data (plan 5.6), they exist for every paired device whatever it declares, and a device on an older settings schema never receives keys added after its version (plan 5.4, `migrations.DOWNS`). A switch for a key the device will never see would be a permanent placeholder, which F-HA-02 rules out.

F-SSV-17 also asks for a switch over the separate screensaver backlight level. Schema v2 (P3.1) had the level (`screensaver.brightness`) but no key for that switch.

## Decision

1. **The entity set.** P3.2 adds these settings entities, all in the configuration category:
   - switches for the screensaver master switch (`screensaver.enabled`, F-SSV-01), the separate screensaver brightness (`screensaver.brightness_enabled`, F-SSV-17), the schedule (`schedule.enabled`, F-SSV-18) and the widgets (`widgets.shown`, F-WGT-01);
   - a number for the idle timeout (`screensaver.idle_timeout`, F-SSV-01), with seconds as the unit and bounds read from the schema;
   - a text entity for the clock background source (`screensaver.clock.background_source`, F-SSV-09).

   The existing screensaver brightness number and mode select stay as they are. They are state-backed, and their state keys already carry the live value.
2. **Gated by settings schema version, not capabilities.** A settings entity exists when the settings schema version the device registered with knows its key. `SettingsSchema.since(key)` derives that version from the frozen schemas: a leaf in the frozen v1 schema is 1, anything else is the current version. Before a device's first registration, it is assumed to speak the current version. `schema_version` is persisted with the device record, the same way `capabilities` is, so the entity set survives an HA restart before the tablet reconnects. A device that registers with a different version gains or loses entities through the same registration sync that handles capabilities.
3. **The value is HA's stored copy.** A settings entity reads the stored document, or the schema default when none exists. It refreshes on every save, whatever made it: the entity itself, the options flow or a service. A store-level signal (`SIGNAL_SETTINGS_UPDATE`) carries the refresh. HA is authoritative (plan 5.6), so the device never needs to echo the value back.
4. **Writes are one revision and one push.** Every write goes through `async_set_settings`, as in P1.5 and P2.3. Concurrent writes get consecutive revisions, because the save updates the in-memory document before it yields. Each push sends the document as it stands when the push runs.
5. **Available while paired, including offline.** A settings entity stays available while its device is disconnected. A write is stored, and the registration-time reconcile (PROTOCOL 4.2) pushes it. State-backed entities still go unavailable on disconnect (ADR 0009).
6. **The background source reloads on every write.** F-SSV-09 requires a write to load the image again even when the value is unchanged. HA's half is that every write, unchanged or not, is a new revision and a push (`async_save` always bumps). The device's half belongs to the clock-face packet (P3.3): it must reload the background on every applied `set_settings` that carries the key, not only when the value differs.
7. **A new schema key, added to v2 before any release.** The new key is `screensaver.brightness_enabled` (boolean, default true; ADR 0002). It is added to v2 rather than as a v3 bump, because no build with settings schema 2 has shipped. `DOWNS[2]` already prunes it for v1 devices. Its default of true keeps the Phase 1–2 behavior, which always applied the separate level. With the switch off, the screensaver holds no level of its own, and dim and black keep theirs.
8. **The idle timeout restarts the countdown.** F-HA-11 requires a new idle timeout to take effect by starting the countdown over. `ScreensaverController.applySettings` resets the idle start when the timeout changes. A push that leaves the timeout unchanged leaves the countdown running.

## Consequences

- Later settings entities follow the same pattern: a description with `setting=` and a `TabulaSettingEntity` subclass. No capability is needed.
- A schema v3 must add its own frozen copy, and `since()` must walk it. `since()` assumes the frozen v1 schema is the only older version.
- An offline write shows in HA immediately but reaches the device only when it reconnects. That is the plan 5.6 contract, now visible from the entity side.

## Alternatives considered

- **Gate by capability.** No capability describes "has settings", and inventing one would only restate the schema version.
- **Gate by the integration's schema version only (every entity for every device).** A schema-1 tablet would get widget and schedule switches it can never act on.
- **Unavailable while offline, like state-backed entities.** That would hide a value HA owns and block a write the protocol already reconciles.
- **A v3 bump for `brightness_enabled`.** That costs a frozen schema, a translation step and a codegen round for a key no released build knows.
