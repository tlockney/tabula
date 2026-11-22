# 0002: Constants

Status: accepted
Date: 2026-11-21
Packet: P0.4

## Context

AGENTS.md and plan section 2 require every port, timeout, threshold, count and default to be chosen by the architect and recorded here, never taken from the original product's documentation or from memory of it. This ADR sets the values TLP v1 (`docs/PROTOCOL.md`) names and the Phase 1–2 behavior defaults that the settings schema (P0.5) will use.

Each value is chosen from first principles: the HA facts in ADR 0001, the Office Kiosk's constraints (plan section 3), common practice for similar protocols, and round numbers that are easy to reason about. None is load-bearing for security beyond what its reasoning says; all can change in a later ADR.

## Decision

### Pairing

| Name | Value | Reasoning |
| --- | --- | --- |
| `PAIR_CODE_DIGITS` | 6 | 10⁶ codes. With `PAIR_CODE_ATTEMPTS` the chance of guessing is 5 in a million per pending device, and the attacker also has to be the person at the HA UI. Six digits are easy to read off a wall tablet. |
| `PAIR_CODE_ATTEMPTS` | 5 | Room for typos; then the pending entry is deleted. |
| `NONCE_BYTES` | 32 | 256 bits; matches SHA-256's output. Base64url without padding is 43 characters. |
| `PAIR_PENDING_TTL` | 600 s | Long enough to walk from the tablet to a computer and confirm; short enough that stale entries clear themselves. |
| `PAIR_PENDING_CAP` | 8 | Far above a household's simultaneous pairings; bounds the discovery-flow flood an unauthenticated client can cause. |
| `PAIR_RATE_LIMIT` | 10 requests / 60 s per source IP | A device sends one request per attempt; 10 allows retries after errors. |
| `CLAIM_POLL_INTERVAL` | 3 s | Pairing feels immediate after the user confirms, at 200 requests over the whole TTL. |
| `RETRY_AFTER_DEFAULT` | 1 s | Assumed when a rate-limit response omits `retry_after`: long enough to matter, short enough not to stall pairing on a busy HA. |
| `CLAIM_RATE_LIMIT` | 60 requests / 60 s per source IP | Three times the polling rate, so a well-behaved device never hits it; caps nonce guessing (which is hopeless at 256 bits anyway). |
| `PAIR_MAX_BODY` | 4 KiB | The largest valid request is under 400 bytes. |

### Credential

| Name | Value | Reasoning |
| --- | --- | --- |
| `TOKEN_REFRESH_MARGIN` | 300 s | HA access tokens last 1800 s (ADR 0001). Refreshing with 5 minutes left survives a slow network and clock skew within HA's 10 s JWT leeway. |

### WebSocket session

| Name | Value | Reasoning |
| --- | --- | --- |
| `FRAME_MAX_BYTES` | 256 KiB | Well under HA's 4 MiB inbound limit (ADR 0001 item 4). A full `register` is about 1 KiB and a 200-line log batch under 100 KiB. Anything larger belongs on HTTP. |
| `REGISTER_TIMEOUT` | 10 s | HA answers in milliseconds on a LAN; 10 s tolerates a busy HA at startup without leaving the device hanging. |
| `COMMAND_TIMEOUT` | 10 s | Long enough for a WebView reload to start or audio to buffer; short enough that an automation's failed service call is noticed. |
| `SCREENSHOT_TIMEOUT` | 30 s | Capture, JPEG encode and upload on a weak SoC over Wi-Fi. |
| `STATE_MIN_INTERVAL` | 250 ms | Plan 5.5 targets under 1 s from device change to HA state; 250 ms coalesces bursts (a brightness slider) with plenty of margin. |
| `APP_PING_INTERVAL` | 30 s | The device's own `ping` command (HA's library pings are invisible to the app). Detects a dead socket within about 40 s, well inside the time someone notices a dashboard is stale, at negligible cost. |
| `PONG_TIMEOUT` | 10 s | A `pong` takes milliseconds on a LAN; 10 s tolerates a busy HA. |
| `RESTART_EXIT_DELAY` | 500 ms | After `restart_app` is answered (PROTOCOL 4.5: answered before the restart), the process waits this long before exiting so the result frame reaches HA on a slow uplink. |

### Reconnect

| Name | Value | Reasoning |
| --- | --- | --- |
| `BACKOFF_BASE` | 1 s | First retry is quick for a brief blip. |
| `BACKOFF_MAX` | 60 s | A restarting HA is back within a minute or two; capping at 60 s means the tablet recovers within a minute of HA's return. |
| `BACKOFF_RESET_AFTER` | 60 s | A connection must be stable for a minute before the next failure starts from `BACKOFF_BASE`, which stops tight reconnect loops against a half-broken HA. |

Jitter: uniform in [d/2, d] ("equal jitter"), so many tablets do not reconnect in lockstep after an HA restart, while each still retries reasonably soon.

### Logs and screenshots

| Name | Value | Reasoning |
| --- | --- | --- |
| `LOG_MAX_LINES` | 200 per frame | Bounded frame size (see `FRAME_MAX_BYTES`); also the schema's `maxItems`. |
| `LOG_MIN_INTERVAL` | 1 s | A live tail that does not load HA's event loop. |
| `LOG_SUBSCRIPTION_TTL` | 600 s | A forgotten log viewer stops streaming by itself; the panel re-sends to extend. |
| `SCREENSHOT_MAX_BYTES` | 5 MiB | A 1920×1200 JPEG at quality 95 is about 1–2 MiB; 5 MiB leaves headroom for larger panels. |
| `SCREENSHOT_JPEG_QUALITY` | 80 | The quality the Android app encodes at when the command sends no hint. 80 is visually clean on a dashboard-sized image at roughly half the bytes of 95, and it sits inside the schema's 10–95 range so a hinted capture can be compared against the default. |

### Protocol limits in the schema

These appear as bounds in `schema/tlp-v1.schema.json` and are chosen here:

| Field | Bound | Reasoning |
| --- | --- | --- |
| `notify.message` | 1000 chars | More than fits on a card; stops accidental payload dumps. |
| `notify.title` | 200 chars | |
| `notify.duration` | 0–3600 s | 0 means until dismissed; an hour is the longest sensible timed card. |
| `command_id` | 8–64 chars of `[0-9A-Za-z_-]` | Room for a ULID or UUID; safe in logs and headers. |
| `error.message`, `http_error.message` | 500 chars | |
| `log_line.message` | 4000 chars | One stack trace frame group. |
| `screenshot.quality` | 10–95 | JPEG quality outside this range is useless or wasteful. |
| `app_version`, `manufacturer`, `model`, `os_version`, pair `name`/`model`, `tag`, `notification_action.action`, `log_line.tag` | 64 chars | Identifiers and labels; long enough for any real value. |
| `current_page`, `navigate.path`, `page_changed.path` | 2048 chars | Common URL length limit in browsers and proxies. |
| `play_media.url` | 4096 chars | Signed media URLs carry long query strings. |
| `wifi_rssi` | −127 to 0 dBm | The range Android reports. |
| `illuminance` | 0–100000 lx | Sunlight is about 100000 lx and Android light sensors top out below it, so the bound admits every real reading while keeping the number sane. The floor is why a supported key with no value yet is omitted rather than sent as a placeholder (PROTOCOL.md 4.2): the schema requires a number, never null or negative. |
| `capabilities` (register) | 64 items, names `[a-z][a-z0-9_]{0,31}` | Room for many future capabilities while bounding the frame. |
| `credential.refresh_token` | at least 32 chars | HA refresh tokens are far longer; a shorter one is a bug. |

### Screen control (P2.3)

| Name | Value | Reasoning |
| --- | --- | --- |
| `BRIGHTNESS_CURVE` | (1 lx, 5 %), (10 lx, 15 %), (100 lx, 40 %), (1000 lx, 75 %), (10000 lx, 100 %) | Five points, one per decade of light, so the logarithmic light axis (F-SCR-06) gets one handle per decade. 1 lx is a dark room at night, where a 5 % panel is visible without lighting the room. 100 lx is ordinary indoor lighting, where 40 % is comfortable. 10000 lx is daylight near a window, which needs a full panel. Readings below the first point or above the last are clamped to the ends. It is a starting shape to be tuned on the Office Kiosk; adaptive brightness defaults to off until then. |
| `ADAPTIVE_CHANGE_THRESHOLD` | 3 percentage points | The curve's output changes the panel only when it moves by more than this from the level last applied. One or two points are invisible on the HD 10 Plus, so sensor noise and passing shadows cause no visible step (F-SCR-08). Slow drift still accumulates against the last applied level, so it is eventually followed. |
| `ADAPTIVE_MIN_INTERVAL` | 2 s | The minimum gap between two adaptive changes. Someone walking past the sensor does not make the panel pump, and a light switched on is followed within a couple of seconds (F-SCR-08). |
| `ADAPTIVE_FADE` | 800 ms | An adaptive change fades over this time instead of jumping (F-SCR-08). It is short enough to finish well inside `ADAPTIVE_MIN_INTERVAL`, so fades never overlap. |
| `FADE_FRAME` | 50 ms | The gap between two backlight writes during a fade. 20 writes a second look continuous at backlight step sizes, and the settings writes stay cheap. |

Manual brightness from HA (the light entity, the `screen` command) is applied at once, without a fade: the user asked for that level.

### Screensaver engine (P2.4)

| Constant | Value | Reasoning |
| --- | --- | --- |
| `SCREENSAVER_EVENT_BUFFER` | 16 events | Screensaver start and stop events wait here for the TLP session; the oldest are dropped past this. A connected session drains it at once, and one that registers drops what came before, so the bound only caps memory while HA is down. |

### Notifications and media (P2.5)

| Constant | Value | Reasoning |
| --- | --- | --- |
| `NOTIFICATION_EVENT_BUFFER` | 16 events | Notification shown and dismissed events wait here for the TLP session, with the same bounds and drop-oldest rule as `SCREENSAVER_EVENT_BUFFER`: a burst of cards while HA is down cannot grow without limit, a connected session drains it at once, and one that registers drops what came before. |
| `MEDIA_EVENT_BUFFER` | 16 events | `media_finished` events wait here for the TLP session; same reasoning. Playback only starts from an HA command, so at most a couple are ever pending. |
| `MEDIA_MAX_BYTES` | 50 MiB | The media player downloads the audio it plays rather than streaming it, so the fetch is JVM-testable and its redirects stay under Tabula's control (PROTOCOL 4.5; ADR 0001 item 5 still holds: no credential is ever attached). The cap bounds a runaway or endless stream, which would otherwise fill storage: HA TTS clips are well under 1 MiB, and ordinary music files and podcast episodes are a few to a few dozen MiB, so 50 MiB passes every real clip and fails only what could never be a sensible announcement — with `media_finished` reason `error`. |
| `MEDIA_MAX_REDIRECTS` | 5 | The media fetch follows redirects (the P2.1 no-redirect rule exists so a *credential* cannot be re-sent somewhere else, and this fetch carries none, ADR 0001 item 5) but refuses a downgrade from https to http. Common CDN and signed-URL chains are one or two hops; a chain longer than 5 is a misconfiguration or a loop. |
| `MEDIA_READ_TIMEOUT` | 30 s | The media download's socket read timeout, set explicitly instead of the HTTP library's default (P2.5 review nit). HA TTS clips are well under 1 MiB on a LAN and even a multi-MiB music file lands in seconds; a stalled or silently dead server is failed after 30 s instead of holding the load slot (and the speaker) forever. Same tolerance for a weak SoC on Wi-Fi as `SCREENSHOT_TIMEOUT`. |
| `CHIME_VOLUME` | 100 (ToneGenerator scale, top) | The notification chime generator runs at the top of its scale, so the OS notification stream is what decides loudness (F-NTF-09). A per-device notification volume (F-NTF-10) has no settings field yet; when one lands it replaces this constant. |
| `CARD_BACKGROUND` | 0xEE1C1C1C (ARGB) | A near-opaque dark card body (P2.5): whatever is behind — dashboard or screensaver — shows through faintly, and the severity accent stripe carries the color (F-NTF-02). |

The idle timeout, mode and brightness defaults are in "Phase 1–2 behavior defaults" below.

### Gallery core (P3.8)

Photo-slideshow constants (F-SSV-12, packet P3.8). Same rules: chosen here only.

| Constant | Value | Reasoning |
| --- | --- | --- |
| `GALLERY_TRANSITION_MS` | 800 ms | The slide, zoom and crossfade animations' duration. A slide change on a wall panel should read as a gentle change, not a flicker, and 800 ms matches `ADAPTIVE_FADE` — a pace already chosen for this panel — while staying far under the shortest slide interval (5 s), so no two transitions ever overlap. |
| `GALLERY_RELEASE_MARGIN_MS` | 1000 ms | How long after a transition starts the replaced slide's bitmap is released. The draw runs synchronously on the main thread and cancels both layers' animations before placing a slide (ADR 0016 item 4), so the old layer is provably unreferenced at the animation's end; the margin covers the animation's start lag and the main thread's jitter past it, and with the transition stays under half the shortest interval, so the old bitmap never survives into the next slide. |
| `GALLERY_SMART_FILL_TOLERANCE` | 10 % of the panel's aspect ratio | Smart fill's "shapes nearly match" line (F-SSV-12): within it, the small crop of always-fill beats bars (a 3:2 photo on a 16:10 panel crops 6 %); beyond it, the photo keeps its full frame over blurred, darkened bars (a 4:3 photo would lose 17 % of its width). One line, no per-photo tuning. |
| `GALLERY_EDGE_ZONE_FRACTION` | 8 % of the panel width | The edge-tap zones' width (F-SSV-12). At a 1200 px panel that is 96 px — a thumb's arc from the bezel — while a deliberate dismiss tap aimed at the middle of a 1200 px panel cannot land inside 96 px of an edge by accident. |
| `GALLERY_URL_HISTORY` | 8 photos | The URL source's history for stepping back (F-SSV-12): enough to review the last few fetches of a slow-changing source, while the ring's disk use stays bounded (8 × a screen-sized JPEG is a few MiB). |

### Clock faces (P3.3)

The faces' base sizes — the 100 % point of `screensaver.clock.size` (F-SSV-08). Same rules as above: chosen here only.

| Constant | Value | Reasoning |
| --- | --- | --- |
| `CLOCK_TIME_BASE_SP` | 120 sp | The digital time at size 100. P2.4's fixed face already used 120 sp and read from across a room; it stays as the scale's anchor so an upgraded document at 100 % looks the same as before (ADR 0018). |
| `CLOCK_DATE_BASE_SP` | 32 sp | The date line at size 100: roughly a quarter of the time's height — legible at a glance without competing with it. P2.4's fixed face used 32 sp. |
| `CLOCK_DIAL_FRACTION` | 0.60 of the panel's smaller side | The analog dial's diameter at size 100. Six tenths of a 1200 px panel is 720 px — hands that resolve from across a room — while leaving room under the dial for the date line (ADR 0018). |

### Settings schema v2 (P3.1)

Phase 3 keys for `schema/settings.schema.json` (schema_version 2). Same rules as above: bounds and defaults live here only. Every value is a round number chosen from the feature's own text (docs/FEATURES.md) and the Office Kiosk's panel (plan 3). All keys are `shared` role. `media_browser` is in the photos source enum from day one so P3.9 adds no schema bump.

**Dashboard start target and idle return (`dashboard`, F-DSH-02/F-DSH-15, P3.15).**

| Field | Bound / default | Reasoning |
| --- | --- | --- |
| start_path | 0–2048 chars, empty or `^/(?![/\\])[^\s?]*$` (single leading slash, optional fragment, no query), default "" (HA's default page) | The same shape `navigate` already carries (its 2048-char URL bound, ADR 0002), minus the query string: a start target with a query would break the launch load, which appends the external-auth parameter. Empty is the neutral default: existing tablets keep opening HA's default page, and "go home" without a target is still meaningful (back to the default). The fragment stays because F-DSH-02 lands on it (a pop-up a view opens by fragment). |
| return_home_after | 0–86400 s, default 0 (never) | 0 is "never", not "instantly": F-DSH-15 calls the return optional, and the default must keep existing tablets' behavior unchanged. A day is the longest sensible idle wait (matches `screensaver.idle_timeout`'s ceiling); the tablet that truly never resets sets 0. |

**Clock mode (`screensaver.clock`, F-SSV-08/F-SSV-09).** Nothing is bundled: font family and weight name system fonts only.

| Field | Bound / default | Reasoning |
| --- | --- | --- |
| face | `digital` \| `analog`, default `digital` | The two faces F-SSV-08 settles; the digital clock reads best from across a room and is what the first milestone idles to. |
| font_family | 1–64 chars, default `sans-serif` | A system font family name; `sans-serif` is Android's default family and always resolves. |
| font_weight | 100–900, default 400 | Android's font-weight axis; 400 is the system's "normal", so nothing changes until the user asks. |
| hour_24 | default false | 12-hour with an AM/PM marker (below) is the wall-clock reading in the kiosk's locale. |
| show_seconds | default false | Seconds tick every frame on a low-RAM panel; off unless asked for. |
| show_am_pm | default true | Pair with 12-hour: the marker is what makes 12-hour legible. |
| show_date | default true | A wall clock wants the date; F-SSV-08 lists it as an option. |
| size | 25–400 %, default 100 | Percent of the mode's base size; the floor keeps a misconfigured tiny clock visible, the ceiling keeps it on the panel. |
| text_color, background_color | `#RRGGBB` or `#AARRGGBB` (Android order), defaults `#FFFFFFFF` (white) / `#FF000000` (black) | Android's `Color.parseColor` forms; alpha-first 8-digit matches what the device parses without translation. White on black is the readable-from-across-the-room pair. |
| background_source | 0–2048 chars, default "" (none) | Empty, a local file path, or a URL; 2048 is the common URL limit used elsewhere (ADR 0002). |
| background_refresh | 0–86400 s, default 0 (never) | F-SSV-09's re-fetch interval; a day is the longest sensible periodic refresh, 0 disables it. |
| background_darken | 0–100 %, default 40 | The readability layer's darkening strength; 40 keeps the face readable over a mid-tone photo without hiding it. |

**Clock night mode (`screensaver.night_mode`, F-SSV-10).** Reads the tablet's own light sensor only.

| Field | Bound / default | Reasoning |
| --- | --- | --- |
| enabled | default false | Needs a light sensor many panels lack (F-SSV-10); off until the user opts in. |
| light_threshold | 0–10000 lux, default 10 | 10 lx is a dim evening room — dark enough that a full-brightness clock lights the room. The bound is the ambient range Android reports. |
| hysteresis_lux | 0–1000 lux, default 2 | Small against the threshold so flicker at the boundary does not toggle night mode every few seconds. |
| hide_background | default true | The background image defeats the night scheme's dark background (F-SSV-10). |
| hide_overlays | default true | Widgets and At a Glance in full color would light the room (F-SSV-10). |
| power_off_delay | 0–1440 minutes, default 0 (never) | Minutes of night mode before the panel turns off (F-SSV-10); a day bounds it, 0 keeps the panel on. The countdown itself is F-SSV-19 (later phase); the key no-ops until then. |
| black_screen_alternative | default false | The alternative in F-SSV-10 for sensors that stop reporting at panel-off; opt-in. |

**Photo slideshow (`screensaver.photos`, F-SSV-12).** The `media_browser` note at the top of this section applies to `source`: it is a value now so P3.9 adds no schema bump.

| Field | Bound / default | Reasoning |
| --- | --- | --- |
| source | `folder` \| `picker` \| `url` \| `media_browser`, default `folder` | F-SSV-12's four sources. A folder is the zero-setup default: it needs only a path — no on-device picker interaction, no HA round trip. |
| folder_path | 0–2048 chars, default "" | The device-local folder for the folder source (F-SSV-12); 2048 is the URL-length bound used elsewhere in ADR 0002 and far beyond any real path. Empty with any other source. |
| image_url | empty or `http(s)://`, 0–4096 chars, default "" | The single-image URL source (F-SSV-12), fetched fresh for each slide; the scheme and length bounds match `play_media.url` (ADR 0002). |
| slide_interval | 5–3600 s, default 300 | Five seconds is the shortest a photo registers on a wall panel; an hour makes the slideshow effectively one static photo. Five minutes is the digital-frame rhythm. |
| transition | `none` \| `crossfade` \| `slide` \| `zoom` \| `pan_zoom` \| `random`, default `crossfade` | F-SSV-12's list; `pan_zoom` is the slow pan-and-zoom (stills only). Crossfade never fully covers either photo, so nothing pops mid-change on a slow panel. |
| fit | `letterbox` \| `smart_fill` \| `fill`, default `smart_fill` | F-SSV-12's three fits. Smart fill is the least-lossy default on a mixed collection: the full photo with only a small crop when shapes nearly match, blurred darkened bars when they differ a lot. |
| shuffle | default false | In-order is the neutral default; shuffle suits curated frames and is one switch away (F-SSV-12). |
| include_subfolders | default false | Explicit scope by default: a stray subfolder of screenshots never joins the playlist unnoticed (F-SSV-12). |
| edge_taps | default true | The edge zones are the slideshow's only remote-free control on a wall tablet (F-SSV-12); on by default, and a tap elsewhere still dismisses. |
| exif_details | default false | The EXIF overlay is for curating a collection, not for reading across a room (F-SSV-12); off unless asked for. |

**Weather scene (`screensaver.weather`, F-SSV-11), website (`screensaver.website`, F-SSV-13/14), dim level, pixel shift, fade.**

| Field | Bound / default | Reasoning |
| --- | --- | --- |
| weather.entity_id | HA entity id or empty, default "" | Empty is F-SSV-11's "pick an entity" prompt state. |
| weather.place_name | 0–64 chars, default "" | An optional label; 64 is the identifiers bound (ADR 0002). |
| weather.clock_overlay, weather.info_bar | defaults false / true | The scene alone is the default view; the info bar carries what the scene alone cannot (temperature, wind), the clock is an extra overlay. |
| weather.lightning | default false | F-SSV-11's optional lightning; a flashy effect is opt-in. |
| website.url | empty or `http(s)://`, 0–4096 chars, default "" | A WebView loads http(s); 4096 matches `play_media.url` (ADR 0002). |
| website.zoom | 25–500 %, default 100 | The range the WebView accepts without layout breaking; 100 is no zoom. |
| website.double_tap_dismiss | default false | Single-tap dismissal is the default everywhere (F-SSV-04); double-tap is the option (F-SSV-14). |
| brightness_enabled | default true | The switch over the separate screensaver level (F-SSV-17, an HA switch since P3.2). On by default because Phases 1–2 always applied `screensaver.brightness`; a v1 document upgrades without a behavior change. |
| dim_brightness | 0–100 %, default 10 | Dim mode's own level (F-SSV-07), replacing its borrow of `screensaver.brightness` (ADR 0013 item 4). 10 % keeps a dashboard outline visible in a dim room. |
| pixel_shift.enabled / interval / amplitude | default false / 10–86400 s, default 300 / 1–64 px, default 4 | Image-retention shift (F-SSV-22). Five minutes moves content often enough to prevent burn-in and rarely enough to be invisible; 4 px is under what an across-the-room viewer resolves. |
| fade.enabled | default false | In/out fade (F-SSV-23) as an option; its duration is P3.14's to add with its own reasoning if it needs one. |

**At a Glance (`at_a_glance`, F-AAG-01/F-AAG-02).**

| Field | Bound / default | Reasoning |
| --- | --- | --- |
| entities | 0–6 entity ids, default [] | F-AAG-01's "small bounded number": six one-line pills fill a 1920-wide panel's row (two lines wrapped) without becoming a dashboard. |
| style | `pill` \| `plain`, default `pill` | F-AAG-02's two styles; the pill is the readable default on photo and scene backgrounds. |
| hide_names | default false | Names are the row's context (F-AAG-02); hiding them is the large-value variant. |
| monochrome_icons | default false | HA's icon colors carry state; monochrome is the flat-style option. |
| scale | 50–200 %, default 100 | Half to double; the same range as the widgets' scale below. |
| font | 1–64 chars, default `sans-serif` | Same reasoning as the clock's font family. |

**Corner widgets (`widgets`, F-WGT-01, F-WGT-03–06, F-WGT-09).**

| Field | Bound / default | Reasoning |
| --- | --- | --- |
| shown | default true | The global switch hides all widgets (F-WGT-01); shown is the natural state once any widget is configured. |
| scale | 50–200 %, default 100 | Half to double; matches At a Glance. |
| font | 1–64 chars, default `sans-serif` | The global font with per-widget override later (F-WGT-01). |
| vignette | 0–100 %, default 0 | The adjustable dark vignette behind widgets (F-WGT-01) defaults off: it is a legibility fix for photo backgrounds, not a look every dashboard wants. |
| per-widget `enabled` | default false each | Widgets are opt-in; a corner assignment survives a disabled widget (F-WGT-01). |
| corners | `tl` \| `tr` \| `bl` \| `br`; defaults: small_clock `tl`, weather `tr`, forecast `br`, battery `bl`, entity `tr` | Five widgets, four corners: the defaults cannot be collision-free, and it does not matter — only enabled widgets draw, the device resolves a shared corner (F-WGT-01), and the defaults spread the common set as widely as possible. |
| small_clock: show_date / hour_24 / color | defaults true / false / `#FFFFFFFF` (white) | Mirrors the big clock's date and 12-hour defaults (F-WGT-03). |
| weather: entity_id, place_name | default "" | Same shapes as the weather scene's fields (F-WGT-04); hidden until an entity is chosen. |
| forecast: entity_id / days / include_today | default "" / 1–10, default 5 / default true | A work week of rows at the default (F-WGT-05); ten is every day a panel can fit with day name, icon and high/low. |
| battery: show_percent / only_when_low | defaults true / false | F-WGT-06's options; the percentage is the widget's point. |
| entity: entity_id / attribute / name | defaults "" / 0–64 chars, default "" (the state) / 0–64 chars, default "" (the entity's name) | F-WGT-09; empty strings mean "not overridden", which the device reads as state, own name. 64 chars is the identifiers-and-labels bound (ADR 0002): an attribute key and a widget label are labels. |
| entity.visible_values | 0–10 strings of 0–64 chars each, default [] | F-WGT-09's show-only-for-these-values list; ten distinct values covers every practical binary/multi state without becoming a table, and 64 chars per value is the identifiers-and-labels bound (ADR 0002). |

**Schedule (`schedule`, F-SSV-18).**

| Field | Bound / default | Reasoning |
| --- | --- | --- |
| enabled | default false | Off until slots are configured (F-SSV-18). |
| slots | 0–12 items, default [] | A slot lasts until the next starts and the last wraps past midnight (F-SSV-18); 12 start times spread finer than every other waking hour, and more would make the options-flow list unreadable. |
| slot.start | `HH:MM` (24-hour), required | Local time, minute resolution: F-SSV-18 is daily time slots, nothing finer. |
| slot.mode | the screensaver mode enum, default `clock` | Same values as `screensaver.mode` (F-SSV-05). |
| slot.brightness / power_off_delay / presence_dismiss / widgets / at_a_glance | nullable (`null` = inherit), bounds as the global keys (0–100 %, 0–1440 min, booleans) | F-SSV-18's "null/absent means inherit": the schema carries nulls so a partial slot inherits the global values, and a set value overrides. Overrides can only turn off globally configured overlays, which the device enforces. `power_off_delay` is carried now and no-ops until F-SSV-19's countdown ships. |

**NightState (pre-committed interface).** The night-mode flag is a plain-JVM interface in `android/core`, `net.lockney.tabula.core.screensaver.NightState` with two members — `isNight: Boolean` and `addListener`, a listener registration that returns a removal function — plus a never-night default (`NightState.NEVER`, false, whose listeners never fire). P3.4 implements it from the light sensor against `screensaver.night_mode`'s threshold and hysteresis, firing listeners on each transition; P3.6 (At a Glance) and P3.11 (widgets) consume it for the night color. Landing the interface in P3.1 — before the hardware-dependent P3.4 — lets those packets compile against the contract; the never-night default is the honest pre-P3.4 behavior: night mode never engages, so consumers render their day colors. The interface is observable, not polled: consumers register a listener and re-render on each transition instead of re-reading the flag every frame. The observation seam is a plain listener rather than a coroutine flow because `android/core` carries no coroutine dependency; P3.4 owns the threading guarantees.

### Ambient light sensor reporting (P3.16)

F-HA-06's rate limit for the illuminance state key. Same rules: chosen here only. The filter that applies them is plain JVM code in `android/core` (packet P3.16), and `STATE_MIN_INTERVAL` still coalesces bursts on top of both values.

| Name | Value | Reasoning |
| --- | --- | --- |
| `ILLUMINANCE_CHANGE_THRESHOLD` | 20 % of the last value sent | A real change in room light — a lamp switched on, a curtain opened, dusk crossing a threshold — moves the lux reading by several tens of percent, while sensor quantization and noise around a steady level is a few percent (the same reasoning as `ADAPTIVE_CHANGE_THRESHOLD`). 20 % separates the two: a step beyond it goes out at once (F-HA-06 "a real change goes out at once"), oscillation below it does not. From total dark (last value 0 lx) any positive reading is a step, since 20 % of zero is zero. |
| `ILLUMINANCE_MIN_INTERVAL` | 30 s | The most generous rate at which sub-threshold drift reaches HA (F-HA-06: one update per interval so HA's recorder is not flooded): at most 120 updates per hour while the light drifts, and none at all in a steady room, where identical readings produce nothing. Slow daylight drift crosses the 20 % threshold in a couple of minutes anyway; covering and uncovering the sensor moves in seconds. Because Android light sensors report on change, a sub-threshold change reaches HA only when the next reading arrives past the interval: a room that drifts and then holds steady keeps HA's older value, an error bounded by the 20 % threshold (review, PR #108) - acceptable, not a bug. |

### Phase 1–2 behavior defaults

Defaults for the settings schema (P0.5) and the device's behavior before HA has sent any settings.

| Setting | Default | Reasoning |
| --- | --- | --- |
| Screensaver idle timeout | 300 s | Long enough not to interrupt someone reading the dashboard; short enough to save the panel. |
| Screensaver enabled | true | The first milestone idles to a clock. |
| Screensaver mode | `clock` | Same. |
| Screensaver brightness | 20 % | Readable clock without lighting the room at night. |
| Screen brightness (manual) | 60 % | Comfortable indoors on the HD 10 Plus; the user adjusts. |
| Adaptive brightness | false | Off until the curve (P2.3) is tuned on real hardware. |
| Keep screen on | true | A wall dashboard should not follow the phone-style screen timeout; the screensaver handles idling. |
| Notification duration | 10 s | Enough to read two lines from across a room; 0 means until dismissed (F-NTF-05). |
| Notification chime | true for `warning` and `error`, false otherwise | Sound for what needs attention only. |
| Notification level when missing or unknown | `info` | FEATURES F-NTF-02 fallback; the neutral level. |
| Notifications shown at once | 3 | More would cover the dashboard; older ones collapse. |
| Media volume | 50 % | Middle of the range; TTS is audible without startling. |
| Announce volume boost | none | `announce` plays at the current volume; no hidden volume jumps. |

### Settings bounds in the settings schema

These appear as bounds in `schema/settings.schema.json` (P0.5) and are chosen here, so P2.0's generated Kotlin can use `Int` everywhere:

| Field | Bound | Reasoning |
| --- | --- | --- |
| `screensaver.idle_timeout` | 1–86400 s | A non-zero floor: 0 would mean "start instantly", not "never" (a disabled screensaver already expresses never). A day is the longest sensible idle wait; longer than that is "leave it on", and 86400 sits far inside a Kotlin `Int`. |
| `notifications.max_visible` | 1–10 | A non-zero floor: a cap of 0 would hide every card. Ten full-width cards already cover a phone-height panel, so more would bury the dashboard rather than show anything. |

## Consequences

- PROTOCOL.md refers to these names; code defines each once (integration: `const.py`; device: a constants file) with a comment pointing here.
- P0.5 takes the behavior defaults from this table and adds their `PROVENANCE.md` rows ("independent design, ADR 0002") when it makes them settings.
- Changing a value means editing this ADR (or superseding it) in the same pull request as the code.

## Alternatives considered

- **Longer pairing codes or alphanumeric codes:** stronger, but harder to read and type; the attempt limit and the TTL already make guessing impractical.
- **Full jitter (uniform in [0, d]):** spreads load best, but can retry almost immediately after a failure; equal jitter keeps a minimum delay.
- **No frame size cap beyond HA's 4 MiB:** simpler, but large frames block the shared socket for commands; a low cap pushes bulk data to HTTP as plan 5.1 intends.
