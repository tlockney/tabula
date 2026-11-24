# 0032: At a Glance row — icons, fitting and placement

Status: accepted
Date: 2026-11-24
Packet: P3.6

## Context

P3.6 draws F-AAG-01 to F-AAG-04's row of entity states on the screensaver, the
first consumer of P3.5's entity data connection. FEATURES settles the behavior:
a few entities in a user-defined order, placement per mode, two lines in
portrait, text shrinking before names are truncated, icon plus name plus value
on a pill or as plain text, an icon background that follows the entity's
active/inactive state the way HA's tiles do, text color that never changes with
state, HA's icon or the domain default, numeric states rounded to HA's display
precision with their unit, blank values dropping out, unknown and unavailable
still showing, an independent subscription, and a night scheme.

What FEATURES and the earlier ADRs leave open is the engineering the packet
names: how a device renders an `mdi:` icon offline and what the fallback is,
how the wrap, the shrink and the truncation interact, which states count as
active, exactly where the row sits per mode and what happens when it cannot fit
at all, and how a missing entity is told apart from an entity with no value.
(ADR 0031 is taken by the Phase 5 release-source packet; numbers are next free
at the time of writing.)

## Decision

1. **No icon font: a curated vector glyph set keyed by `mdi` name.** Tabula
   draws eleven glyphs with Canvas paths in the app module — lock, unlock, door,
   garage, light, power, motion, thermometer, battery, water, alert — each on a
   unit box so one painter serves every scale. A chip's glyph comes from the
   `mdi:` name HA would show (the entity's `icon` attribute, the registry's
   override, or `frontend/get_icons`, resolved by `DisplayData.icon`), through a
   name table; a name outside the table falls back to the entity domain's entry
   (lock, light, switch, input_boolean, cover, garage_door, fan, sensor,
   binary_sensor), and a domain outside that falls back to the domain's first
   letter, drawn in the icon box. The MDI font was declined for v1: the font
   plus its name-to-codepoint map is roughly 1.2 MB of assets, the map would
   have to be generated or fetched at build time behind a hash pin (the
   wake-word model's precedent, ADR 0030 item 8), and it would add a font
   license and notice to the repository. The row's job — doors, locks, garage
   at a glance — is covered by the set, and an unknown icon reads no worse as
   the domain's initial than as a glyph the user did not set. Nothing is
   fetched at build time and no asset is added.
2. **Text sizes are sp × density × scale, never Android's font scale.** The row
   is laid out for a wall panel, so a user's system font-size preference must
   not break the fit the planner computed; `at_a_glance.scale` is the only
   scale control (F-AAG-02). This is the one place in the app that ignores the
   font scale, deliberately.
3. **Active means "not a state HA's tiles draw as off".** The inactive set is
   `off`, `unknown`, `unavailable`, `locked`, `closed`, `not_home`, `idle`,
   `standby`, `disconnected`, `disarmed` (ADR 0002's `GLANCE_INACTIVE_STATES`).
   `unlocked`, `open`, `on` and every sensor value are active, so an unlocked
   door and an open garage are the amber chips someone glances for, and a
   locked door is quiet. Only the icon's tint and box change; the text color
   never does (F-AAG-02).
4. **Placement belongs to the mode, and two modes never draw the row.** Clock
   draws it under the date line; black draws it centered; photos, website,
   weather and the dashboard draw it at the bottom (photos and website are
   wired by the packets that build those modes); dim draws nothing, because
   F-SSV-07's point is that the dashboard stays visible through it; calendar
   draws nothing (F-AAG-01), which the planner decides now even though calendar
   mode is not built. The planner returns the placement with the row so the
   view never re-derives it.
5. **The fit is wrap, then shrink, then truncate.** The planner packs chips
   greedily at 100 % into one line in landscape or two in portrait. If that
   does not fit it steps the text down by `GLANCE_SHRINK_STEP` to
   `GLANCE_MIN_TEXT_SCALE` (75 %). Only at the floor does it truncate names,
   with `GLANCE_ELLIPSIS`, to each chip's share of the combined line width;
   values are never truncated. A row that still does not fit keeps the lines it
   has and drops the tail chips, which the plan reports as `omitted` and the
   view logs. FEATURES gives two lines and one shrink-before-truncate rule, not
   an unbounded layout; a logged drop is diagnosable, a silently vanished row
   is not.
6. **A blank value drops out; a missing entity does not.** A blank state drops
   its chip (F-AAG-03). An entity HA does not have at all (`EntityState.missing`,
   which the subscription marks for ids it was asked for and HA did not send)
   shows as `Unavailable` in the inactive color: a configured chip that
   silently vanished would look like a bug, and F-AAG-03 already says unknown
   and unavailable still show.
7. **Numerals take HA's display precision and the raw unit.** A numeric state is
   rounded half-up to the entity's `display_precision` from
   `config/entity_registry/list_for_display` and followed by its
   `unit_of_measurement`, e.g. `21.5 °C`; anything else uses HA's own state
   text (the translation chain `DisplayData.stateText` already implements).
   The half-up applies to the parsed double, not to the decimal text: HA's
   frontend formats a JS number, so a value written `2.675` — stored just below
   the halfway point — shows `2.67` at precision 2 on HA's own screens. Tabula
   rounds the same value to agree with the screen it sits next to, and
   `GlanceFeedTest` pins it. This lives in the JVM feed (`GlanceFeed`), so the
   formatting is tested without a device.
8. **Night is consumed through `NightState`, not polled.** The view registers a
   listener while a run shows and removes it on dismissal; a transition
   re-renders the row. `screensaver.night_mode`'s `hide_overlays` hides the row
   at night; otherwise the row switches to the night accent color and pill,
   with icons in that one color. The constructor's default stays
   `NightState.NEVER`, so the row honestly draws its day colors until P3.4
   lands the real flag; whichever of P3.4 and P3.6 merges last wires it.
9. **The row moves with the pixel shift.** While the clock shows, the row takes
   the same offset as the face and the background image: it is static content on
   a panel that stays on for hours, which is exactly what the shift protects
   (F-SSV-22).

## Consequences

- The glyph set is a code table, not an asset: a new glyph is a drawing routine
  plus two map entries, with no build step, license or notice change.
- Icons outside the set show the domain's initial — legible, generic, and
  consistent. The corner widgets (P3.11) can reuse `GlanceIcons`, the night
  palette and `NightState`.
- Unit strings are the attribute HA sends, untranslated; HA's frontend
  translates them through its own resources, which a later packet can adopt
  from `frontend/get_translations`.
- A row that cannot fit at 75 % with truncated names drops its tail chips. Six
  chips on a narrow panel is the case to watch; the device check logs the
  count, and P3.11 can revisit if it turns out to be common.
- The row is drawn from the activity's own overlay, so it does not depend on
  the dashboard page (F-AAG-04); dismissing the screensaver closes the
  subscription with it (P3.5's lifecycle).
- Follow-ups: the now-playing view's optional row (Phase 8), the black mode's
  hide-everything key (F-SSV-06, an ADR 0017 consequence), and P3.4's night
  flag for the clock and this row alike.

## Alternatives considered

- **Bundle the MDI webfont and its metadata.** It is what the Companion app
  does and would render any icon exactly. It costs about 1.2 MB of assets, a
  build-time fetch with a hash pin, and a license notice, for a row that shows a
  handful of door, lock and garage states. Declined for v1; a later packet can
  add it behind the same `GlanceGlyph` seam without touching the planner.
- **Render HA's SVG path data.** `frontend/get_icons` returns icon *names*, not
  paths; the path data lives in HA's frontend bundle, which is not part of the
  API.
- **Shrink indefinitely instead of truncating.** That contradicts F-AAG-01's
  "text shrinks before names are truncated" — truncation is the stated floor —
  and would end in text nobody can read from the couch.
- **Drop the row entirely when it cannot fit.** A configured row that vanishes
  is indistinguishable from a broken one.
- **Poll `NightState.isNight` on every draw.** The interface is observable by
  contract (ADR 0002's NightState paragraph); polling re-reads per frame and
  skips the seam the pre-commit exists for.
- **Let the row ignore the pixel shift.** It is exactly the kind of static,
  long-lived content the shift exists to protect.
