# 0022: Screensaver engine polish (P3.14)

Status: accepted
Date: 2026-11-21
Packet: P3.14

## Context

P3.14 implements the three engine follow-ups ADR 0013 left to Phase 3: pixel shift (F-SSV-22), the screensaver fade (F-SSV-23) and dim mode's own backlight level (F-SSV-07). The settings schema v2 (P3.1) carries the keys — `screensaver.pixel_shift.enabled/interval/amplitude`, `screensaver.fade.enabled`, `screensaver.dim_brightness` — with bounds and defaults in ADR 0002. The engine is the P2.4 overlay plus the P3.8 gallery, and ADR 0013 item 4 left dim borrowing `screensaver.brightness`. Three questions needed answers the features and the earlier ADRs leave open.

## Decision

1. **The shift is an eight-position cycle one amplitude from rest.** The offsets cycle the eight neighbors of the resting position, clockwise from the top, and wrap back to the first (floorMod, so a late tick is still a valid offset). The walk never drifts, every step moves content, and each axis stays within ±amplitude, so content that fills the panel and is oversized once by the amplitude can never bare the panel's edge: the clock view is moved inside the overlay, and the gallery moves one container holding blur, scrim, slides and details, with negative margins equal to the amplitude while a shift runs. Black never shifts (F-SSV-22); dim draws nothing, so a view with no content simply has nothing to move. Both overlays read the settings when the screensaver starts, the same cadence as the gallery's own settings (ADR 0016 item 8), and put the content back at rest, without the oversize, when they stop.
2. **The fade keys on the mode on screen, and some modes never fade.** A `FadeRule` decides per mode: every overlay Tabula draws itself fades in on start and out on dismissal — clock, black, photos, weather, and the clock stand-in of an unbuilt mode (ADR 0013 item 3) — while the modes that show the live page itself (dim, the HA dashboard view when it ships) and web content (the website view, F-SSV-13/14) switch at once: fading the page the user is looking at would flash it (F-SSV-23's own carve-out). Keying on the mode on screen rather than the one configured makes the stand-in fade and leaves the exemption already right for the day the website view renders for real. The fade is an alpha animation of the overlay view, `SCREENSAVER_FADE_MS` long (ADR 0002), applied where each overlay is drawn.
3. **The gallery's fade-out keeps the last slide until the fade ends.** Stopping the engine at the fade's start would release the bitmaps at once and fade a black screen; instead the engine stops — and the bitmaps release — when the fade's end action runs, and a re-show cancels that end action and stops the engine itself, the transitions' cancellation rule (ADR 0016 item 4).
4. **Dim holds `dim_brightness` (closing ADR 0013 item 4's follow-up).** While dim shows, screen control holds the panel at `screensaver.dim_brightness`, no longer `screensaver.brightness` (F-SSV-07). Black keeps the lowest non-zero level; both keep their own levels even when `brightness_enabled` is off, since that switch governs only the shared screensaver level (F-SSV-17, P3.2). The rest of the brightness ownership — reported state, levels set meanwhile returning on dismissal, the held level never stored — is unchanged.

## Consequences

- An edit to any of the three settings lands the next time the screensaver starts, like the gallery's settings; a running screensaver keeps its current shift and fade.
- The gallery's content is oversized by the amplitude only while a shift runs; the margins go back to zero when it stops, so the photo's crop is the panel's own at rest.
- ADR 0013's Phase 3 follow-up remainder (pixel shift, fade, a separate dim level) is closed; the weather and remaining modes are the only screensaver items left to it.
- The website view (P3.10) needs no fade rule of its own: `FadeRule` already exempts it, and its mode value is what it will be shown as.

## Alternatives considered

- **Random offsets within ±amplitude.** It needs bookkeeping to avoid drift and an edge bareing on a long run; the cycle needs none, moves content on every step, and stays symmetric about rest.
- **Fading every mode.** Simpler, but the fade of dim or a web page flashes the very content the user left on screen — F-SSV-23 names these modes as the exception.
- **Stopping the gallery engine at fade-out start.** Releases the bitmaps at once, so the fade-out fades black; keeping the last slide to the fade's end matches the release discipline and the transition timing the gallery already uses.
- **A fade-out for the screensaver brightness level.** The backlight already fades by its own curve (ADR 0002), so the overlay's alpha is the only thing left to animate.
