# 0019: Illuminance sensor entity (P3.16)

Status: accepted
Date: 2026-11-21
Packet: P3.16

## Context

FEATURES F-HA-06 describes an ambient light sensor entity and left open whether to expose it at all. The tablet's light sensor already drives adaptive brightness on the device (P2.3, ADR 0011), but HA cannot see the reading. Thomas decided to expose it (2026-11-21): with it, HA automations can use the room's light level, and the readings are visible when tuning the brightness curve (ADR 0002) and the night-mode threshold (F-SSV-10, P3.4).

## Decision

1. **F-HA-06 ships.** A new `illuminance` state key (lux, number ≥ 0) is gated by a new `illuminance` capability, additive per PROTOCOL.md 8. The device declares the capability only when the tablet has a light sensor (F-HA-02); the reading comes from the same `LightSensor` listener that feeds adaptive brightness — no second listener.
2. **Rate limiting (F-HA-06)** is a relative change threshold and a minimum interval for sub-threshold changes, both in ADR 0002 (`ILLUMINANCE_CHANGE_THRESHOLD`, 20 %; `ILLUMINANCE_MIN_INTERVAL`, 30 s), applied by a plain-JVM filter in `android/core`. `STATE_MIN_INTERVAL` still coalesces on top. A supported key with no reading yet is omitted from `register` (PROTOCOL 4.2), and if the sensor stops reporting while the panel is off (F-SCR-14) the last value is held — never `null` or zero.
3. **The entity is not diagnostic.** It is a real measurement of the room, meant for automations ("lights on when the room is dark"), so the HA sensor has device class `illuminance`, unit `lx`, state class `measurement`, and no entity category. The diagnostic sensors (ADR 0009 item 4) report the tablet; this one reports the room.

## Consequences

- An HA illuminance entity as the source for adaptive brightness (F-SCR-05) stays deferred (ADR 0011).
- The integration ignores the `illuminance` capability and key on devices that never declare it; nothing else changes for them (PROTOCOL 4.0).

## Alternatives considered

- **Not exposing the reading.** Simpler, but it leaves the brightness curve and night-mode threshold tunable only by guesswork, and automations cannot use room light.
- **A diagnostic entity.** It would be disabled by default in HA and hidden from dashboards, which defeats the point: the value is for automations, not for debugging the tablet.
