# 0018: Clock faces and background image (P3.3)

Status: accepted
Date: 2026-11-21
Packet: P3.3

## Context

P3.3 builds the configurable clock (F-SSV-08: digital and analog faces with font, weight, 12/24-hour, AM/PM, seconds, date line, size, colors) and the optional background image (F-SSV-09: local file or URL, re-fetch interval, darkening layer, reload on every settings write, failed fetch keeps the previous image). The v2 schema (P3.1) carries the keys and bounds, and ADR 0013 pins the rendering to plain framework views inside the screensaver overlay. The schema leaves several questions open: which options the analog face honors, what the date line shows, how the app-language fallback is decided, what credential a background URL fetch carries, what "retries later" means without inventing a constant, and what the size percent's base sizes are.

## Decision

1. **Option mapping per face (F-SSV-08).** Digital uses every key. Analog is 12-hour by nature: `hour_24` does not apply, `show_seconds` is the second hand, and the AM/PM marker and date line draw under the dial. Font family, weight, size percent and text color apply to both (the dial's ticks and hands draw in the text color). The analog dial is a custom `onDraw` view — a dial is exactly the case ADR 0013 item 1 allows.
2. **The date line and its language.** The date line is the full weekday, month and day, no year: a wall clock knows the year, and dropping it keeps the line legible at the date base size. The time digits follow the system locale. The date uses the app language (F-SSV-08) when the app ships a translation for it — decided against the app's assets locale list, not a hand-kept list — and the system language otherwise; until the app ships translations, the system language wins, matching P2.4.
3. **No credential on the background fetch, ever.** The URL fetch rides the P2.5 media fetch: no Authorization header (the F-SSV-09 packet brief asks this to be recorded), redirects followed within the ADR 0002 bounds, https-to-http refused, cap and read timeout included. A background URL is user-chosen, often not on the HA origin; attaching the device credential would send it to wherever the user pointed. Local file paths decode directly. Both decode through the P3.8 decoder: bounds first, sampled down to panel resolution. A fetched URL lands in a cache file deleted after the decode, so no partial file survives anything.
4. **"Retries later" without a new constant.** A failed load keeps the previous image and retries at the next re-fetch tick when an interval is configured; with no interval, the next retry is the next settings write or the next screensaver start — both of which F-SSV-09 already defines as fresh loads. No retry timer constant is invented.
5. **The re-fetch interval applies to URLs only** (F-SSV-09 says "a URL may also be re-fetched"). A local file re-reads at each start and on each settings write, never on a timer.
6. **Every settings write reloads the background while a face shows.** `set_settings` bumps a settings-revision state; the activity collects it and re-applies the clock settings to the overlay whenever a face is drawn (any clock-showing mode, including the ADR 0013 stand-in), so an automation can refresh a fixed-address image with a no-change write (F-SSV-09). A load still in flight is superseded by generation: its late answer is dropped, its bitmap released, never drawn. The showing image stays until the fresh one draws, and a failed reload leaves it there (F-SSV-09).
7. **Font weight across the pinned minSdk.** API 26+ uses Android's weight axis (`Typeface.create(family, weight, italic)`); older builds step to bold at weight >= 600 and normal below (minSdk 24, ADR 0008). A family name that does not resolve falls back to the system default family, as the schema's `font_family` doc already promises.
8. **Base sizes.** The 100 % point of `screensaver.clock.size` — the digital time, the date line and the analog dial — are recorded in ADR 0002 (`CLOCK_TIME_BASE_SP`, `CLOCK_DATE_BASE_SP`, `CLOCK_DIAL_FRACTION`), chosen to keep a size-100 face looking like P2.4's fixed one. The AM/PM marker derives at half the date line's size; that ratio is layout math and lives in `ClockLayout` with the other layout arithmetic (the P3.3 review's nit), not as a constant.

One behavior change from P2.4 comes with the settings: 12/24-hour time now follows the `hour_24` setting instead of the system format, because the v2 schema owns the key (F-SSV-08). The default (12-hour with the marker) matches the wall-clock reading the fixed face already showed.

## Consequences

- Night mode (P3.4) re-renders the face colors through the same re-apply path the settings write uses; the background's hide-at-night option will sit where the darkening layer sits.
- The reload-on-write rule is observable from HA: writing the same background source twice produces two loads and two draws on the tablet.
- The clock's background engine pauses with the window like the gallery (F-SSV-02), so no fetch or decode runs against a dark screen.
- Pixel shift (F-SSV-22) moves the face and the background image together, the background oversized by the amplitude while a shift runs so no offset bares the panel's edge (ADR 0022's mechanism, as the gallery's container); fade (F-SSV-23) animates the whole overlay's alpha, face and background together. The darkening layer is a uniform overlay and stays full-screen.

## Alternatives considered

- **Attach the credential to HA-origin background URLs.** Convenient for authenticated camera stills, but it would make the credential's destination depend on a string the user types; the blanket no-credential rule (ADR 0001 item 5) is simpler and safer, and signed URLs cover the real cases.
- **A fixed retry interval for failed fetches.** Would need a new constant and would double with the user's own re-fetch interval; riding the configured interval (or the write signal) needs nothing new.
- **Re-fetching local files on the interval too.** A file can change under a fixed path, but watching the disk on a timer costs more than the rare case; the settings write already covers an explicit refresh.
- **Keeping the system 12/24 format.** P2.4 had no setting to follow; F-SSV-08 makes it a configured option, and a configured option must not silently track the system.
