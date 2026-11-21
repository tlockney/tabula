# 0016: Gallery core

Status: accepted
Date: 2026-11-21
Packet: P3.8

## Context

P3.8 builds F-SSV-12's photo slideshow minus the HA media-browser source (P3.9): a folder on the device, photos picked with the system picker, and a single image URL fetched fresh per slide. FEATURES F-SSV-12 names the behavior but leaves several questions the ADRs did not answer: what shows when the playlist is empty or the source is not built, how the user reaches the picker on a kiosk with no device-side settings UI, when exactly a replaced slide's bitmap is released (a crossfade needs both bitmaps alive), what "retries later" means for a failed fetch, and how the details overlay picks its corner before widgets exist.

## Decision

1. **Photos is a built mode; empty or unbuilt content shows the clock stand-in.** `ScreensaverController.BUILT_MODES` gains Photos. When the gallery cannot render — `media_browser` until P3.9, an empty folder or picker playlist, a blank URL — the overlay shows the clock (ADR 0013 item 3's stand-in), and the lifecycle events still report Photos: the mode is built; only its content is missing, like weather's pick-an-entity prompt (F-SSV-11). A fetched URL that has not loaded yet shows black, not the clock, because content is on its way.
2. **The picker hangs off the service's ongoing notification.** The kiosk has no device-side settings UI to attach a "pick photos" button to, so the foreground notification carries an action that launches `PhotoPickActivity`: the SAF document picker (no broad storage permission, F-SSV-12) with the picked photos copied into the app's external files dir, which survives reboots and is adb-pushable for hardware checks. No storage permission is requested anywhere: the folder source reads paths the OS already lets the app read (app-scoped dirs on a provisioned kiosk; a user with photos elsewhere uses the picker).
3. **The cadence restarts when a slide shows, and every failure is one interval away.** The next slide is due `slide_interval` after the current one was shown (and an edge tap resets that clock, F-SSV-12). A failed fetch keeps the current photo (F-SSV-12) and tries again at the next tick; a failed decode does the same and the tick moves to the next photo, so one corrupt file never stalls or hot-loops a playlist.
4. **Bitmap lifetime: a release accompanies every show.** The outcome that shows slide N+1 also releases slide N — immediately for the `none` transition (the letter of the packet's "released before the next is shown"), and when the transition ends for the crossfade/slide/zoom/pan-and-zoom, the soonest the old bitmap is unneeded. At most two decoded bitmaps are alive at once (JVM invariant, fake bitmaps). The engine applies a transition-timed release `GALLERY_TRANSITION_MS + GALLERY_RELEASE_MARGIN_MS` after the show: the view clears the old ImageView at the animation's end, and the margin covers the animation's start lag and main-thread jitter, so a recycled bitmap is never drawn. Stopping flushes pending releases at once.
5. **`pan_zoom` fades in, then pans and zooms across the slide's display.** It is the only transition that keeps running while the slide shows (F-SSV-12's "slow pan-and-zoom"); the fade-in matches the other transitions, and the motion spans the rest of the interval. Every source in this packet is stills, so the "stills only" clause holds automatically; videos arrive with P3.9.
6. **Edge zones are left-back, right-forward.** Reading direction (LTR) is the least surprising mapping, and the left edge is the one a mounted tablet's reach actually covers. The zones are `GALLERY_EDGE_ZONE_FRACTION` wide (ADR 0002); a tap outside them dismisses (F-SSV-04's exception), and edge taps do not dismiss. The URL source steps through its history (`GALLERY_URL_HISTORY`, a ring of files in the cache dir) without re-fetching, fetching fresh only when stepping forward past the newest fetch.
7. **The details overlay takes the first free corner, bottom first.** `detailsCorner(occupied)` picks from BOTTOM_LEFT, BOTTOM_RIGHT, TOP_RIGHT, TOP_LEFT; the occupied set is empty until the widget packet (P3.11) passes its corners in (F-WGT-02).
8. **Gallery settings apply when the slideshow starts.** A settings edit mid-run is picked up the next time the screensaver starts; live mid-run reconfiguration arrives with the schedule packet, which FEATURES F-SSV-18 requires to be live. The URL fetch reuses the P2.5 media fetch unchanged (no credential, redirects within bounds, cancellable, capped).

## Consequences

- The slideshow is the first mode with a bitmap pipeline; its decode/release invariant is the pattern P3.9's videos and media-browser photos extend.
- The picker copies duplicate names beside earlier copies instead of overwriting, so a re-pick never silently replaces a photo; pruning the picker dir is manual (adb) for now.
- Follow-ups: the `slide` TLP command and HA buttons (P3.9), the media-browser source (P3.9), live schedule-driven reconfiguration (F-SSV-18), and widget corners displacing the details overlay (P3.11).

## Alternatives considered

- **Refuse the photos mode until content exists.** A `failed` command reply would make the HA mode select partly unusable for a mode that is built but unpopulated; the clock stand-in matches ADR 0013's precedent.
- **A dedicated settings activity for picking.** More surface than a notification action for a kiosk whose settings live in HA; the notification is always present (the service is foreground).
- **Streaming decodes through BitmapFactory region APIs.** Over-engineering for 1080p-class photos on a low-RAM panel; sampled decode at panel resolution holds one screen-sized bitmap (~9 MiB) plus one in transition.
- **View-driven releases (the ImageView calls back when its animation ends).** Tightly couples the engine to the view and leaks bitmaps if the callback never comes; the timed margin keeps the engine testable on a plain JVM and bounded by a constant.
