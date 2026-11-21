# 0013: Screensaver engine (P2.4)

Status: accepted
Date: 2026-11-21
Packet: P2.4

## Context

P2.4 builds the screensaver on the tablet: the idle timer, the overlay, dismissal, HA start and stop, the next-screensaver sensor and the `tabula_screensaver` events. FEATURES F-SSV-01 to F-SSV-08, F-SSV-17 and F-SSV-26 describe the behavior; PROTOCOL 4.4, 4.5 and 4.7 carry it. The packet brief asks for a Compose overlay. Building it surfaced five questions that FEATURES, the protocol and the earlier ADRs leave open. (ADR 0012 is taken by P2.2.)

## Decision

1. **Plain framework views, not Compose.** The overlay is a `FrameLayout` with two `TextView`s, added over the activity's content. The pinned compileSdk 34 toolchain (ADR 0008) predates current Compose runtimes, and the rest of the app uses plain views. A clock and a black screen need nothing Compose adds. This deviates from the brief's "Compose overlay"; a later packet that needs Compose bumps compileSdk with its own ADR.
2. **The master switch governs only the idle start.** With `screensaver.enabled` off, the idle timer does not run and `next_screensaver` is null, and turning it off dismisses a running screensaver (F-SSV-01). An explicit start from HA (the switch or `tabula.screensaver`) still works: it is a direct request, and the switch otherwise would make the HA "screensaver active" switch silently do nothing.
3. **Modes not built yet show the clock.** P2.4 renders clock, black and dim. Asked for another mode (weather, photos, website, dashboard, calendar: Phase 3), the overlay shows the clock, and events report the mode actually shown. `screensaver_mode` keeps reporting the configured mode, as PROTOCOL 4.4 defines it (the mode used next time). The `screensaver` command's optional `mode` applies to that start only and does not change the setting.
4. **Brightness while it shows (F-SSV-17).** Screen control holds the panel at the screensaver brightness while clock mode shows, at the lowest non-zero level for black (F-SSV-06), and at the screensaver brightness for dim (F-SSV-07). The settings schema has no separate dim level yet, so dim uses `screensaver.brightness`. Decisions made meanwhile (a manual level from HA, adaptive brightness) update the reported state and return when the screensaver is dismissed. The held level is never stored, so a restart during the night does not adopt it as the day level.
5. **An explicit null for `next_screensaver`.** The device's state deltas leave out keys that did not change, so a Kotlin null cannot also mean "null on the wire". `StateDelta.NO_NEXT_SCREENSAVER` stands for the protocol's null and is encoded as JSON `null`. The time is reported in whole seconds, and only when it changes, so a touch moves the sensor at most once a second (and deltas are coalesced, PROTOCOL 4.4).

The idle timer pauses while the panel is off or Tabula's window is not in front, and starts over when both are back (F-SSV-02). Touch, and any key, restarts it and dismisses a running screensaver, and the overlay takes that touch so it does not also press the dashboard (F-SSV-04). Events are sent as soon as the session is registered; events from before that are dropped, because registration carries the current state.

## Consequences

- HA's screensaver switch, the `tabula.screensaver` service and the idle timer all drive one controller, and every start and stop reaches HA as a `tabula_screensaver` event with the mode and cause.
- The screensaver runs for the service's lifetime, independent of the HA connection.
- Follow-ups: F-SSV-02's dismissal when another app takes the foreground (P2.4 pauses the timer but leaves the screensaver state as is; the `stopped` triggers have no cause for it yet); an HA entity for the master switch and the idle timeout (F-SSV-01, Phase 3 settings entities); clock faces and options (F-SSV-08 to F-SSV-10); a separate dim level; the remaining modes, schedule, overlays, pixel shift and fade (Phase 3).

## Alternatives considered

- **Compose with a compileSdk bump.** It would follow the brief to the letter but change the toolchain for two text views; ADR 0008's pin was deliberate.
- **The master switch also refuses an HA start.** Simpler to state, but the HA "screensaver active" switch would then appear broken whenever the idle screensaver is off, which is a common setup for manual or automation-only use.
- **Refusing modes that are not built.** A `failed` reply would make HA's mode select partly unusable until Phase 3; the clock is a safe stand-in, and the event says what showed.
