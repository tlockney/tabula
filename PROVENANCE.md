# Provenance

This file records where Tabula's behavior comes from. It is append-only: entries are corrected by new entries, never removed or rewritten.

## Prior art and process

Tabula's feature set was informed by the public documentation of Kiosk Satellite, an Android kiosk app for Home Assistant licensed CC BY-NC-ND 4.0. That license forbids derivative works of its code and documentation, so Tabula is built clean-room:

- **Spec side.** Only spec-side contexts read the original documentation, which is kept outside this repository. Their single output is `docs/FEATURES.md`: behavior described in our own words, with our own names and no constants.
- **Build side.** Everyone who designs or writes code sees only this repository. Constants are chosen by the architect and recorded in ADRs.
- No source code, APK, decompilation or SDK interface file of the original was read by either side.

The full rules are in `AGENTS.md` and `docs/PLAN.md` section 2.

## Human exception

Thomas Lockney, who directs the project, has read the original documentation. To limit carry-over:

- His design input to the build side goes through `docs/FEATURES.md` (spec side) or an ADR written as independent design with its reasoning stated.
- He does not supply constants from memory. The architect picks them and records the reasoning in an ADR.

## Restricted names

The names below may appear only in the files listed under "Public framing" in `AGENTS.md`. `make check` reads this list and fails if a name appears anywhere else. One name per line, as a list item.

- Kiosk Satellite

## Feature provenance

One row per user-visible behavior. Source is a `docs/FEATURES.md` item ID or "independent design" (with the ADR, if any).

| Behavior | Source | Packet |
| --- | --- | --- |
| Device settings: device name and HA base URL (connection keys, the only ones recovery may change) | F-MGT-03, F-MGT-11 | P0.5 |
| Device settings: screen brightness, adaptive brightness, keep screen on | F-SCR-01, F-SCR-04, F-SCR-05 | P0.5 |
| Device settings: screensaver enabled, idle timeout, mode, screensaver brightness | F-SSV-01, F-SSV-05, F-SSV-17 | P0.5 |
| Device settings: notification default duration, default level, per-level chime, max visible | F-NTF-02, F-NTF-05, F-NTF-07, F-NTF-09 | P0.5 |
| Device settings: media volume | F-TTS-07 | P0.5 |
| Default values for every v1 setting | independent design (ADR 0002) | P0.5 |
| Announcements play at the current volume; no volume boost | independent design (ADR 0002) | P0.5 |
| Pairing flow: unauthenticated pair and claim HTTP endpoints, the user confirms a code shown on the tablet, the tablet receives a per-device credential | independent design (ADR 0001, PROTOCOL.md section 2) | P1.2 |
| One HA device per paired tablet; re-pairing after a reset creates a new device and the old entry stays until the user deletes it | F-HA-01 | P1.2 |
| The tablet signs in with its pairing credential only; credentials are per device and never shared | F-DSH-01, F-MGT-03 | P1.2 |
| The device reports its capabilities at registration and HA adopts the reported set (entities arrive in P1.3) | F-HA-02 | P1.2 |
| Deleting the HA device revokes that tablet's credential and user | F-HA-01, ADR 0001 item 4 | P1.2 |
| Registering with an unsupported protocol major is refused and raises a repair issue for that device | independent design (ADR 0001, PROTOCOL.md 4.2) | P1.2 |
| Diagnostics report device summaries and pending pairing state, never credentials | independent design (ADR 0001) | P1.2 |
| Screen as an HA light: on/off is the panel, brightness the panel level; on/off only when the tablet cannot dim | F-SCR-04 | P1.3 |
| Speaker as an HA media_player reporting volume, mute and playback state | F-TTS-01, F-TTS-07 | P1.3 |
| On-screen notifications as an HA notify entity | F-NTF-01 | P1.3 |
| Switches for screensaver active, keep screen on and adaptive brightness; number for screensaver brightness | F-HA-11 | P1.3 |
| Screensaver mode select | F-HA-10, F-SSV-05 | P1.3 |
| Buttons: restart app, reload dashboard, clear cache | F-HA-08 | P1.3 |
| Sensors: battery level, charging, current page, app version, next screensaver | F-HA-05, F-HA-07 | P1.3 |
| Network type sensor and Wi-Fi signal sensor (disabled by default), diagnostic | independent design (ADR 0009) | P1.3 |
| Connectivity binary sensor that stays available and reads off when the tablet's link drops | F-HA-09 (its open question settled as "reads off", ADR 0009) | P1.3 |
| Entities exist only for declared capabilities; a capability dropped at registration removes its entities | F-HA-02 | P1.3 |
| Losing the link makes every other entity unavailable; reconnecting refreshes them from the tablet's state; nothing is removed on disconnect | F-HA-04 | P1.3 |
| Entities of a paired tablet survive an HA restart (unavailable until it reconnects) | independent design | P1.3 |
| Per-device settings documents live in HA (revisioned); the device keeps a cached copy and HA pushes the current document when the tablet registers behind or ahead of the stored revision | F-MGT-01, PROTOCOL.md 4.2, 4.5 | P1.5 |
| The integration's options flow is the settings editor: pick a tablet, edit every non-hardware setting in one form generated from the settings schema; the entry's own options are untouched | F-MGT-01 | P1.5 |
| identity.name in the settings document is the tablet's display name in HA's device registry | F-MGT-03 | P1.5 |
| Device events fire on the HA bus as tabula_* events, one name per subject, a type field for the specific change, carrying the tablet's HA device id | F-EVT-01 | P1.5 |
| tabula_notification fires when a notification shows, is dismissed, or its action runs; tabula_screensaver on screensaver start and end | F-EVT-02, F-EVT-03 | P1.5 |
| tabula_media fires when media finishes; tabula_page when the page changes | independent design (PROTOCOL.md 4.7) | P1.5 |
| Deleting a tablet's HA device also deletes its stored settings document | independent design (plan 5.6) | P1.5 |
| Settings-mapped entity actions (keep screen on, adaptive brightness, screensaver brightness and mode) write the HA-owned settings document and push like any edit, never a bare command | F-MGT-01, F-HA-11 | P1.5 |
| Entity actions send device commands; an offline tablet, an undeclared capability, a refusal or no answer in time fails the action with an error | F-HA-14, PROTOCOL.md 4.6, ADR 0010 | P1.4 |
| `notify.send_message` shows a text notification on the tablet | F-NTF-01 | P1.4 |
| `tabula.notify` with title, level, duration, icon, chime and replace-by-tag | F-NTF-01, F-NTF-02, F-HA-14 | P1.4 |
| `tabula.navigate` opens an HA frontend path on the tablet | F-NAV-01 | P1.4 |
| `tabula.screensaver` starts or stops the screensaver, optionally in a given mode | F-SSV-03, F-SSV-05, F-HA-14 | P1.4 |
| `tts.speak` and `media_player.play_media` play on the tablet speaker; announcements are flagged; HA media is sent as a path the tablet fetches without its credential | F-TTS-01, F-TTS-02, ADR 0001 item 5, ADR 0010 | P1.4 |
| A manual brightness change through the screen light turns adaptive brightness off | plan 5.5; F-SCR-07 open question settled in ADR 0010 | P1.4 |
| The tablet's pairing screen asks for the HA address, then shows the code and the device ID to confirm in Home Assistant | independent design (ADR 0001, PROTOCOL.md section 2) | P2.1 |
| The pairing credential is stored on the device encrypted under an Android Keystore key and excluded from backups | independent design (plan 5.2, ADR 0001) | P2.1 |
| A foreground service with an ongoing notification keeps the HA connection alive, holds a Wi-Fi wake lock, and starts on boot after one manual launch | F-KSK-09 | P2.1 |
| Diagnostic state reported to HA while connected: battery level, charging, network type, Wi-Fi signal strength, app version | F-HA-05, F-HA-09 | P2.1 |
| A credential revoked in HA returns the tablet to its pairing screen | independent design (ADR 0001 item 2) | P2.1 |
| A terminal pairing failure (refused request, bad nonce, unreachable server) shows an error on the pairing screen instead of failing silently | independent design (PROTOCOL.md section 2.5) | P2.1 |
| The main screen is a full-screen WebView of the HA frontend, signed in with the pairing credential through the frontend's external-auth bridge; no login page is shown and no token is injected into the page | F-DSH-01, ADR 0001 item 3 | P2.2 |
| Dashboard links stay in the WebView; top-level navigation off the HA origin is blocked, and links naming app actions run them without the page navigating | F-DSH-01, F-DSH-09, ADR 0001 item 3 | P2.2 |
| tabula://reload and tabula://clear-cache links on a dashboard run the app's reload and clear-cache actions; an unknown action or scheme shows a toast with the reason | F-DSH-09 | P2.2 |
| app://package links on a dashboard open another installed app by package name; an uninstalled package shows a toast | F-DSH-10 | P2.2 |
| The reload and clear-cache buttons in HA drive the dashboard WebView, failing with an error when no dashboard is showing; tabula.navigate opens an HA frontend path in the WebView, refusing paths that would leave the HA origin | F-HA-08, F-NAV-01, PROTOCOL.md 4.5 | P2.2 |
| The current-page sensor follows the page the WebView has finished loading and the frontend's client-side route changes (reported through the WebView's visited-history callback), as a path without query string or fragment | F-NAV-02, PROTOCOL.md 4.4 | P2.2, P3.15 |
| The dashboard survives the screen turning off and on without reloading; the frontend itself suspends its socket after five minutes hidden and reconnects on return without a reload | F-DSH-01, ADR 0012 | P2.2 |
| The tablet writes the system backlight and switches system auto-brightness to manual; without that permission it dims only its own window | F-SCR-03 | P2.3 |
| HA's screen light sets the panel level and switches the panel off (device admin lock) and on (wake) | F-SCR-04, F-SCR-14 | P2.3 |
| Without the device admin, switching the panel off is refused with an error and the panel stays on | F-SCR-14 | P2.3 |
| The keep-screen-on setting stops the system display timeout while the dashboard window shows | F-SCR-01 | P2.3 |
| Adaptive brightness follows the tablet's light sensor through a five-point curve on a logarithmic light axis, smooth and monotone, and keeps working while HA is unreachable | F-SCR-05, F-SCR-06; curve points in ADR 0002 | P2.3 |
| Adaptive changes apply only past a change threshold and a minimum interval, and fade instead of jumping; a held reading is rechecked after the interval | F-SCR-08; constants in ADR 0002 | P2.3 |
| A fresh install leaves the system brightness alone until HA has sent settings | F-SCR-02 (default chosen in ADR 0011) | P2.3 |
| A manual level from the screen light is also written to HA's stored settings (brightness and adaptive off), so later settings pushes keep adaptive off | independent design (ADR 0011) | P2.3 |
| A swipe-only lock screen is cleared when the panel wakes; a secured one stays | F-SCR-13 (unsecured case only) | P2.3 |
| After the idle timeout with no touch the tablet shows a full-screen clock; the timer pauses while the panel is off or Tabula is not in front, and a master switch turns the idle start off | F-SSV-01, F-SSV-02 | P2.4 |
| Touch or a key press dismisses the screensaver and restarts the idle timer; the dismissing touch does not reach the dashboard | F-SSV-04 | P2.4 |
| Screensaver modes on the tablet: a digital clock with the date (following the system 12/24-hour setting and language), black at the lowest backlight, and dim (backlight lowered over the live dashboard); modes not built yet show the clock | F-SSV-05, F-SSV-06, F-SSV-07, F-SSV-08 (digital face only); stand-in from ADR 0013 | P2.4 |
| The screensaver brightness holds the backlight while it shows, and the previous level returns on dismissal, including levels HA set meanwhile | F-SSV-17 (dim level from ADR 0013) | P2.4 |
| HA can start the screensaver while the idle start is switched off | independent design (ADR 0013) | P2.4 |
| Each start and stop is sent to HA as a `tabula_screensaver` event with mode and cause, and the next idle start time is reported to HA | F-SSV-26, F-SSV-01 | P2.4 |
| HA can put a card on the tablet's screen: cards stack newest-first at the top, above the dashboard, the native screens and the screensaver, and each card removes itself when its lifetime runs out | F-NTF-01, F-NTF-07, F-NTF-12, F-SSV-21 | P2.5 |
| A card's severity sets its stock glyph and accent color; a missing or unknown level falls back to the device setting's level, and a card's own duration, chime and tag override the settings | F-NTF-02, F-NTF-05, F-NTF-09; defaults in ADR 0002 | P2.5 |
| Cards chime per severity (warning and error by default), played as a synthesized tone on the OS notification stream | F-NTF-09; chime defaults in ADR 0002 | P2.5 |
| Tapping a card removes only that card and does nothing else — not even the idle timer, so a card over the screensaver leaves the screensaver up | F-NTF-08, F-NTF-12 | P2.5 |
| A repeated tag replaces the earlier card; the visible-card cap evicts the oldest | F-NTF-07, F-NTF-08; cap in ADR 0002 | P2.5 |
| Each card's appearance and exit reaches HA as a `tabula_notification` event with the tag and the reason (shown, replaced, timed out, touched) | F-NTF-08, F-EVT-02, PROTOCOL 4.7 | P2.5 |
| The tablet's speaker is an HA media_player that plays TTS and media URLs, fetched with no credential, and a new playback replaces the current one | F-TTS-01, F-TTS-02, F-TTS-06, ADR 0001 item 5, PROTOCOL 4.5 | P2.5 |
| The media fetch follows redirects (up to five hops, never a https-to-http downgrade), since it carries no credential for the P2.1 no-redirect rule to protect | ADR 0001 item 5; bounds in ADR 0002 | P2.5 |
| The media download is cancellable — a replaced or stopped load stops fetching at once — and capped (MEDIA_MAX_BYTES); an over-cap or endless stream ends the playback with media_finished reason error | F-TTS-10; cap in ADR 0002 | P2.5 |
| Downloaded audio is deleted when its playback ends, is replaced, or fails to decode, and any leftover is swept at service start, so a long-running tablet never accumulates files | independent design (PR review) | P2.5 |
| Play, pause, stop, seek, volume and mute reach the device's media channel, reported as volume, muted and media state; next and previous fail (no queue), and stopping nothing is a no-op | F-MED-01, F-TTS-06, F-TTS-07, PROTOCOL 4.4-4.5 | P2.5 |
| Every playback reaches a terminal state: the end, a stop or a fetch/decode failure is reported as a `tabula_media` (`media_finished`) event with its reason | F-TTS-10, PROTOCOL 4.7 | P2.5 |
| The media volume is a device setting HA owns (audio.media_volume) and the device applies to its music stream; a per-call volume applies for that playback | F-TTS-07, plan 5.6, ADR 0002 | P2.5 |
| Settings schema v2: clock face options (digital/analog, system font family and weight, 12/24-hour, seconds, AM/PM, date, size, colors), optional background image (path or URL) with a re-fetch interval and a darkening layer | F-SSV-08, F-SSV-09 | P3.1 |
| Settings schema v2: clock night mode keys (light threshold and hysteresis in lux from the tablet's own sensor, hide background, hide overlays, night power-off delay, black-screen alternative) | F-SSV-10 | P3.1 |
| Settings schema v2: weather scene keys (weather entity, place name, clock overlay, info bar, lightning) | F-SSV-11 | P3.1 |
| Settings schema v2: photo slideshow keys (folder/picker/URL/media-browser source, interval, transition, fit, shuffle, subfolders, edge taps, EXIF details) | F-SSV-12 | P3.1 |
| Settings schema v2: website mode keys (URL, zoom, double-tap dismissal) | F-SSV-13, F-SSV-14 | P3.1 |
| Settings schema v2: dim mode has its own backlight level (screensaver.dim_brightness), no longer borrowing the screensaver brightness | F-SSV-07; ADR 0013 item 4 follow-up | P3.1 |
| Settings schema v2: pixel shift (enabled, interval, amplitude) and fade (enabled) | F-SSV-22, F-SSV-23 | P3.1 |
| Settings schema v2: At a Glance keys (bounded entity list in display order, pill/plain style, hide names, monochrome icons, scale, font) | F-AAG-01, F-AAG-02 | P3.1 |
| Settings schema v2: corner widget keys (global shown/scale/font/vignette; per-widget enabled, corner and options for the small clock, weather, forecast, battery and entity widgets) | F-WGT-01, F-WGT-03 to F-WGT-06, F-WGT-09 | P3.1 |
| Settings schema v2: the screensaver schedule (enabled; bounded slots of start time, mode, and brightness/power-off/presence/widgets/At a Glance overrides where null means inherit) | F-SSV-18 | P3.1 |
| A stored settings document from schema v1 loads as v2 with nested defaults filled recursively, without bumping its revision | plan 5.4, plan 5.6 | P3.1 |
| A device on settings schema v1 receives its settings document translated down: every v2-only key, nested ones included, is pruned against the frozen v1 schema | plan 5.4, PROTOCOL 4.5 | P3.1 |
| The options flow edits the list-valued settings (At a Glance entities, widget visible values, schedule slots) as multi-line text — one item per line, YAML for the slots — parsed and validated against the schema on submit | independent design (packet P3.1) | P3.1 |
| HA switches for the screensaver master switch, the separate screensaver brightness, the screensaver schedule and the screensaver widgets; a number for the idle timeout; a text entity for the clock background source. Each shows HA's stored setting and writes it as one revision and one push | F-HA-11, F-SSV-01, F-SSV-09, F-SSV-17, F-SSV-18, F-WGT-01 | P3.2 |
| Settings entities exist when the device's settings schema version knows their key, and stay available while the device is offline (a write lands when it reconnects) | independent design (ADR 0015) | P3.2 |
| A switch over the separate screensaver backlight level (screensaver.brightness_enabled): off, the screensaver keeps the panel's current level; dim and black keep theirs | F-SSV-17 | P3.2 |
| A new idle timeout starts the screensaver countdown over; an unchanged one leaves it running | F-HA-11 | P3.2 |
| Writing the clock background source is always a new revision and push, identical value or not | F-SSV-09 | P3.2 |
| While the screensaver shows, the device subscribes to exactly the entities its At a Glance row, widgets and weather scene need, over its own HA connection, and unsubscribes when it closes. Last-known values stay through a dropped connection, and it subscribes again on reconnect | F-AAG-04, F-SSV-11, F-WGT-04, F-WGT-09 | P3.5 |
| A configured entity HA does not have shows as unavailable, never as a gap | F-AAG-03 | P3.5 |
| Daily forecasts for the weather scene and forecast widget come from HA's forecast subscription. A weather entity without a daily forecast yields none | F-SSV-11, F-WGT-05 | P3.5 |
| States are shown with HA's translations, icons and display precision, falling back as HA's frontend does | F-AAG-03 | P3.5 |
| Calendar mode opens no entity subscription | F-AAG-01, F-WGT-01 | P3.5 |
| The screenshot capture action asks the device for a capture; the image entity shows the latest stored capture and refreshes when a new one lands | F-MGT-07, F-HA-12, PROTOCOL 5 | P3.13 |
| The image entity serves the screen-off placeholder (a designed slate, not a black frame) whenever no capture is stored: with the screen off the device captures and uploads nothing and still ACKs | F-MGT-07, ADR 0014 | P3.13 |
| The `last_capture` timestamp records when a capture was started by the action, is set only when the capture succeeds, persists across restarts, and is untouched by ordinary image fetches | F-HA-12 | P3.13 |
| The image entity, the capture button and the last-capture sensor exist only when the tablet declared the screenshot capability | F-HA-02 | P3.13 |
| Losing the link makes the screenshot entities unavailable with the rest of the device, and a stored capture survives the loss and the restore | F-HA-04 | P3.13 |
| The screensaver's photos mode shows a slideshow: the playlist is built when the screensaver starts (new files appear next time), with shuffle and include-subfolders for the folder source; an empty playlist or the unbuilt media-browser source shows the clock stand-in | F-SSV-12; stand-in from ADR 0013 item 3 | P3.8 |
| Photos picked through the system document picker are copied into app storage, surviving reboots with no broad storage permission; the picker is launched from the service's ongoing notification | F-SSV-12; launcher surface from ADR 0016 | P3.8 |
| Slides change with none, crossfade, slide, zoom or slow pan-and-zoom transitions (random picks per slide), and fit the panel letterboxed, cropped, or smart-filled — a small crop when shapes nearly match, blurred darkened bars filling the panel when they differ | F-SSV-12; constants in ADR 0002 | P3.8 |
| Photos are decoded at panel resolution and the previous slide's bitmap is released as the next shows (immediately with no transition, at the transition's end otherwise); at most two decoded slides are alive at once | F-SSV-12; ADR 0016 | P3.8 |
| The URL source fetches its photo fresh for every slide: the current photo stays until the next has loaded, a failed fetch keeps it and retries an interval later, and a bounded history lets edge taps step back through recent photos | F-SSV-12 | P3.8 |
| Tapping near the left edge steps back one slide, near the right edge steps forward, and the slide interval restarts; a tap anywhere else dismisses the screensaver | F-SSV-12, F-SSV-04 | P3.8 |
| An optional EXIF details overlay shows the date taken and the camera (no location) in a free corner, bottom corners first | F-SSV-12, F-WGT-02 | P3.8 |
| A playlist in which every photo fails to decode (e.g. HEIC on an old build) falls back to the clock stand-in too, not a black screen | F-SSV-12; ADR 0016 item 1 | P3.8 |
| The slideshow pauses while Tabula's window is not in front (a powered-off panel included) and resumes where it was, so no decode or fetch runs against a dark screen | F-SSV-02; ADR 0016 item 9 | P3.8 |
| The pairing screen asks for the device name HA will show. The default is the tablet's own name (or model) plus the first four characters of its device id, so identical tablets pair under different names | F-MGT-03 | #79 |
| On re-pairing, the name field prefills the name HA last set in the settings, else the name typed at the last pairing | F-MGT-03 | #100 |
| The integration imports and runs on every supported HA release: target extraction prefers `homeassistant.helpers.target` (from HA 2026.1) and falls back to `homeassistant.helpers.service` below it, so the floor 2025.1.0 through the latest release all load (ADR 0001 item 8) | independent design (ADR 0001 item 8) | #83 |
| Unloading or reloading the Tabula entry closes every device's WebSocket, so the tablet reconnects through its backoff and re-registers within the reconnect bound instead of sitting on a dead session (ADR 0019) | independent design (#97) | #97 |
| The app opens `dashboard.start_path` on launch, after a crash or restart, and on go home; the path is validated like `navigate`'s (single leading slash, origin-checked, fragment kept), and an invalid or off-origin value falls back to HA's default page with a warning. Changing the setting does not move the page on screen | F-DSH-02, F-NAV-03; ADR 0021 | P3.15 |
| After `dashboard.return_home_after` seconds without touch the dashboard returns to its start target (0 = never, the default; a configured `start_path` is required — empty, or `/` (which the frontend redirects client-side to its default panel and the page can never rest on), leaves the return inert, and `go_home` still falls back to the default page). The timer keeps running behind a screensaver and while the panel is off, is reset by any touch and by any page change (client-side route changes included, observed through the WebView's visited-history callback), skips navigation when already on the target, and restarts when the delay changes | F-DSH-15, F-OPT; page-change reset and the configured-target requirement independent design (ADR 0021) | P3.15 |
| A `go_home` command (capability `go_home`) and a "Go to start page" button; `restart_app` reopens on the start target and `reload` keeps the current page | F-NAV-03, F-HA-08, PROTOCOL 4.5 | P3.15 |
| The start target is a text setting (a frontend path) and the return delay a number, in the options flow and as settings entities, until the page catalog replaces the text field with a select | F-HA-10, F-HA-11; ADR 0021 | P3.15 |
| The illuminance sensor: an HA sensor entity with device class illuminance, unit lx and state class measurement, created only when the tablet declared the illuminance capability (a light sensor, from the same listener that drives adaptive brightness); a real change goes out at once and oscillation is rate-limited (relative threshold 20 %, one sub-threshold update per 30 s), the key is omitted before the first reading, and the last value is held if the sensor stops reporting while the panel is off | F-HA-06, F-HA-02, F-HA-04, F-SCR-14; constants in ADR 0002, entity in ADR 0020 | P3.16 |
| A card chime plays on the media stream, so the tablet's silent mode does not mute it; its loudness is the `audio.chime_volume` setting, a share of the media volume (default 100), exposed in HA as a config number | F-NTF-09; ADR 0026, default in ADR 0002 | #121 |
