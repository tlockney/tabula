package net.lockney.tabula.core

/**
 * Protocol constants the codec enforces. Every value is chosen in
 * docs/decisions/0002-constants.md; do not change one without editing that ADR
 * in the same pull request.
 */
object Constants {
    /** TLP major version this build speaks (docs/PROTOCOL.md 8). */
    const val PROTOCOL_VERSION = 1

    /** FRAME_MAX_BYTES: largest frame the device puts on the wire, UTF-8 bytes. */
    const val FRAME_MAX_BYTES = 256 * 1024

    /** LOG_MAX_LINES: most log lines in one tabula/log frame. */
    const val LOG_MAX_LINES = 200

    /** LOG_MIN_INTERVAL: ms between two log frames; the protocol's 1 s floor. */
    const val LOG_MIN_INTERVAL_MS = 1000L

    /** LOG_SUBSCRIPTION_TTL: ms a log subscription lives without a refresh. */
    const val LOG_SUBSCRIPTION_TTL_MS = 600_000L

    /** LOG_BUFFER_LINES: lines the device's log ring holds (P5.4, ADR 0002). */
    const val LOG_BUFFER_LINES = 2000

    /**
     * VOICE_CHUNK_BYTES: PCM bytes in one binary voice frame — 160 samples of
     * 16 kHz, 16-bit mono, 10 ms of audio (PROTOCOL.md 4.10, ADR 0002).
     */
    const val VOICE_CHUNK_BYTES = 320

    // --- Client behavior (docs/decisions/0002-constants.md) ---------------------------
    // Every value below is chosen in that ADR; do not change one without editing
    // the ADR in the same pull request. Seconds unless the name says otherwise.

    /** PAIR_CODE_DIGITS: random decimal digits in the pairing code. */
    const val PAIR_CODE_DIGITS = 6

    /** NONCE_BYTES: random bytes behind the base64url pairing nonce. */
    const val NONCE_BYTES = 32

    /** CLAIM_POLL_INTERVAL: s between pair/claim polls; the server's response overrides it. */
    const val CLAIM_POLL_INTERVAL_S = 3.0

    /** RETRY_AFTER_DEFAULT: s assumed when a rate-limit response omits retry_after. */
    const val RETRY_AFTER_DEFAULT_S = 1.0

    /** TOKEN_REFRESH_MARGIN: s of access-token life left before the device refreshes. */
    const val TOKEN_REFRESH_MARGIN_S = 300.0

    /** REGISTER_TIMEOUT: s to wait for each HA reply during the session handshake. */
    const val REGISTER_TIMEOUT_S = 10.0

    /** APP_PING_INTERVAL: s between the device's own HA ping commands. */
    const val APP_PING_INTERVAL_S = 30.0

    /** PONG_TIMEOUT: s to wait for the pong before treating the connection as dead. */
    const val PONG_TIMEOUT_S = 10.0

    /** RESTART_EXIT_DELAY: ms the process waits after restart_app's result frame before it exits. */
    const val RESTART_EXIT_DELAY_MS = 500L

    /** RESTART_RELAUNCH_DELAY: ms after that exit before the system relaunches the app. */
    const val RESTART_RELAUNCH_DELAY_MS = 1000L

    /** STATE_MIN_INTERVAL: minimum gap between two tabula/state frames, milliseconds. */
    const val STATE_MIN_INTERVAL_MS = 250L

    /** BACKOFF_BASE: s; the first reconnect retry delay (before equal jitter). */
    const val BACKOFF_BASE_S = 1.0

    /** BACKOFF_MAX: s; the reconnect delay cap (before equal jitter). */
    const val BACKOFF_MAX_S = 60.0

    /** BACKOFF_RESET_AFTER: s a connection must stay registered for the attempt counter to reset. */
    const val BACKOFF_RESET_AFTER_S = 60.0

    // --- Screen control (docs/decisions/0002-constants.md, "Screen control") ------

    /** BRIGHTNESS_CURVE: default adaptive curve as (lux, percent) points, one per decade. */
    val BRIGHTNESS_CURVE: List<Pair<Double, Int>> = listOf(
        1.0 to 5,
        10.0 to 15,
        100.0 to 40,
        1000.0 to 75,
        10000.0 to 100,
    )

    /** ADAPTIVE_CHANGE_THRESHOLD: percentage points the curve must move before the panel follows. */
    const val ADAPTIVE_CHANGE_THRESHOLD = 3

    /** ADAPTIVE_MIN_INTERVAL: ms between two adaptive brightness changes. */
    const val ADAPTIVE_MIN_INTERVAL_MS = 2000L

    /** ADAPTIVE_FADE: ms an adaptive change takes to fade to its new level. */
    const val ADAPTIVE_FADE_MS = 800L

    /** FADE_FRAME: ms between two backlight writes during a fade. */
    const val FADE_FRAME_MS = 50L

    /** SCREENSAVER_EVENT_BUFFER: screensaver events kept while they wait for the session (P2.4). */
    const val SCREENSAVER_EVENT_BUFFER = 16

    /** SCREENSAVER_FADE_MS: ms the screensaver overlay fades in or out over (P3.14, F-SSV-23). */
    const val SCREENSAVER_FADE_MS = 800L

    /** NOTIFICATION_EVENT_BUFFER: notification events kept while they wait for the session (P2.5). */
    const val NOTIFICATION_EVENT_BUFFER = 16

    /** MEDIA_EVENT_BUFFER: media_finished events kept while they wait for the session (P2.5). */
    const val MEDIA_EVENT_BUFFER = 16

    /** MEDIA_MAX_BYTES: largest audio file the media player downloads before playing it (P2.5). */
    const val MEDIA_MAX_BYTES = 50L * 1024 * 1024

    /** WAKE_WORD_CHIME_GUARD_MS: ms detection stays closed after a chime sounds
     * (P7.1, ADR 0002): the beep has no completion callback, so the tone's
     * length plus a short tail is the window the microphone stays shut. */
    const val WAKE_WORD_CHIME_GUARD_MS = 500L

    /** MEDIA_MAX_REDIRECTS: redirect hops the media fetch follows before giving up (P2.5). */
    const val MEDIA_MAX_REDIRECTS = 5

    /** MEDIA_READ_TIMEOUT: s a media download may wait on one socket read (P2.5). */
    const val MEDIA_READ_TIMEOUT_S = 30.0

    /** SCREENSHOT_JPEG_QUALITY: the capture's JPEG quality when the command
     * sends none; inside the schema's 10-95 range (P3.13, 0002-constants.md). */
    const val SCREENSHOT_JPEG_QUALITY = 80

    // --- Intercom (docs/decisions/0002-constants.md, "Intercom") ------------------

    /** INTERCOM_RING_LIMIT_S: s an unanswered call rings before it ends (P9.1). */
    const val INTERCOM_RING_LIMIT_S = 45

    /** INTERCOM_CONNECT_TIMEOUT_S: s from the offer to connected audio (P9.1). */
    const val INTERCOM_CONNECT_TIMEOUT_S = 10

    /** INTERCOM_CALL_LIMIT_S: the hard call limit, from the moment a call is up
     * (P9.1). Provisional: the owner confirms or changes it. */
    const val INTERCOM_CALL_LIMIT_S = 300

    /** INTERCOM_SIGNAL_MAX_BYTES: the largest one offer, answer or candidate
     * payload, in UTF-8 bytes (P9.1). */
    const val INTERCOM_SIGNAL_MAX_BYTES = 4096

    /**
     * CARD_BACKGROUND: a notification card's near-opaque dark body, ARGB
     * (P2.5). Not const: the literal needs the Long-to-Int narrowing.
     */
    val CARD_BACKGROUND: Int = 0xEE1C1C1C.toInt()

    // --- Gallery (docs/decisions/0002-constants.md, "Gallery core") --------------

    /** GALLERY_TRANSITION_MS: ms the crossfade, slide and zoom animations take (P3.8). */
    const val GALLERY_TRANSITION_MS = 800L

    /** GALLERY_RELEASE_MARGIN_MS: ms after a transition starts that the replaced slide's bitmap is released (P3.8). */
    const val GALLERY_RELEASE_MARGIN_MS = 1000L

    /** GALLERY_SMART_FILL_TOLERANCE: aspect-ratio difference, as a fraction of the panel's, under which smart fill crops (P3.8). */
    const val GALLERY_SMART_FILL_TOLERANCE = 0.10

    /** GALLERY_EDGE_ZONE_FRACTION: the width of each edge-tap zone, as a fraction of the panel's width (P3.8). */
    const val GALLERY_EDGE_ZONE_FRACTION = 0.08f

    /** GALLERY_URL_HISTORY: URL-source photos kept for stepping back (P3.8). */
    const val GALLERY_URL_HISTORY = 8

    // --- Clock faces (docs/decisions/0002-constants.md, "Clock faces") ------------

    /** CLOCK_TIME_BASE_SP: the digital time's text size at size=100 % (P3.3, ADR 0018). */
    const val CLOCK_TIME_BASE_SP = 120f

    /** CLOCK_DATE_BASE_SP: the date line's text size at size=100 % (P3.3, ADR 0018). */
    const val CLOCK_DATE_BASE_SP = 32f

    /** CLOCK_DIAL_FRACTION: the analog dial's diameter at size=100 %, as a fraction of the panel's smaller side (P3.3, ADR 0018). */
    const val CLOCK_DIAL_FRACTION = 0.60f

    // --- Illuminance reporting (docs/decisions/0002-constants.md,
    // "Ambient light sensor reporting") -------------------------------------

    /**
     * ILLUMINANCE_CHANGE_THRESHOLD: relative change (a fraction of the last
     * value sent) beyond which a reading goes out at once (P3.16, F-HA-06).
     */
    const val ILLUMINANCE_CHANGE_THRESHOLD = 0.20

    /**
     * ILLUMINANCE_MIN_INTERVAL: ms between two sub-threshold sends. Drift
     * slower than the threshold is reported at most this often (P3.16).
     */
    const val ILLUMINANCE_MIN_INTERVAL_MS = 30_000L

    // --- At a Glance row (docs/decisions/0002-constants.md, "At a Glance (P3.6)") --

    /** GLANCE_VALUE_BASE_SP: a chip's value text size at scale=100 %, before the fit's own shrink (P3.6). */
    const val GLANCE_VALUE_BASE_SP = 28f

    /** GLANCE_NAME_BASE_SP: a chip's name text size at scale=100 % (P3.6). */
    const val GLANCE_NAME_BASE_SP = 18f

    /** GLANCE_ICON_BASE_SP: a chip's icon glyph box at scale=100 % (P3.6). */
    const val GLANCE_ICON_BASE_SP = 30f

    /** GLANCE_CHIP_PADDING_SP: a chip's horizontal padding at scale=100 % (P3.6). */
    const val GLANCE_CHIP_PADDING_SP = 14f

    /** GLANCE_CHIP_GAP_SP: the gap between two chips on a line, at scale=100 % (P3.6). */
    const val GLANCE_CHIP_GAP_SP = 12f

    /** GLANCE_LINE_GAP_SP: the gap between the row's two lines, at scale=100 % (P3.6). */
    const val GLANCE_LINE_GAP_SP = 10f

    /** GLANCE_CHIP_RADIUS_SP: the pill's corner radius and the icon box's, at scale=100 % (P3.6). */
    const val GLANCE_CHIP_RADIUS_SP = 12f

    /** GLANCE_ROW_GAP_SP: the gap between the clock's date line and the row beneath it (P3.6). */
    const val GLANCE_ROW_GAP_SP = 20f

    /** GLANCE_ROW_EDGE_SP: the row's minimum distance from the panel's side edges (P3.6). */
    const val GLANCE_ROW_EDGE_SP = 24f

    /** GLANCE_MAX_CHIPS: chips the row draws, the schema's `at_a_glance.entities` bound (P3.6). */
    const val GLANCE_MAX_CHIPS = 6

    /** GLANCE_MAX_LINES_LANDSCAPE: lines the row may use in landscape (P3.6, F-AAG-01). */
    const val GLANCE_MAX_LINES_LANDSCAPE = 1

    /** GLANCE_MAX_LINES_PORTRAIT: lines the row wraps onto in portrait (P3.6, F-AAG-01). */
    const val GLANCE_MAX_LINES_PORTRAIT = 2

    /** GLANCE_MIN_TEXT_SCALE: the floor the fit's text shrink stops at before names are truncated (P3.6). */
    const val GLANCE_MIN_TEXT_SCALE = 0.75f

    /** GLANCE_SHRINK_STEP: the text shrink step the fit tries, 100 % down to the floor (P3.6). */
    const val GLANCE_SHRINK_STEP = 0.05f

    /** GLANCE_ELLIPSIS: the marker a name truncated at the shrink floor ends with (P3.6). */
    const val GLANCE_ELLIPSIS = "…"

    /** GLANCE_VALUE_LARGE_SCALE: how much larger a value is drawn when names are hidden (F-AAG-02, P3.6). */
    const val GLANCE_VALUE_LARGE_SCALE = 1.25f

    /**
     * GLANCE_PILL_BG: the chip pill's body, ARGB (P3.6, F-AAG-02). Half-opaque
     * black so the white text and the icon keep contrast over any clock
     * background image (the value is chosen in ADR 0002, At a Glance (P3.6)).
     */
    val GLANCE_PILL_BG: Int = 0x8C000000.toInt()

    /** GLANCE_TEXT: the chip's text color, day scheme (F-AAG-02: never changes with state). */
    val GLANCE_TEXT: Int = 0xFFFFFFFF.toInt()

    /** GLANCE_ICON_ACTIVE: the icon tint while the entity is in an active state, as HA's tiles show it (F-AAG-02). */
    val GLANCE_ICON_ACTIVE: Int = 0xFFFFB300.toInt()

    /** GLANCE_ICON_ACTIVE_BG: the icon box behind an active icon, as HA's tiles show it (F-AAG-02). */
    val GLANCE_ICON_ACTIVE_BG: Int = 0x33FFB300

    /** GLANCE_ICON_INACTIVE: the icon tint while the entity is off, closed, locked, unknown or unavailable (F-AAG-02). */
    val GLANCE_ICON_INACTIVE: Int = 0xFFBDBDBD.toInt()

    /** GLANCE_ICON_INACTIVE_BG: the icon box behind an inactive icon (F-AAG-02). */
    val GLANCE_ICON_INACTIVE_BG: Int = 0x1FFFFFFF

    /** GLANCE_NIGHT_TEXT: the row's single dim accent color at night, text and icons alike (F-SSV-10, P3.6). */
    val GLANCE_NIGHT_TEXT: Int = 0xFFB05A5A.toInt()

    /** GLANCE_NIGHT_PILL_BG: the pill's body at night, so the row does not light the room (F-SSV-10). */
    val GLANCE_NIGHT_PILL_BG: Int = 0xCC000000.toInt()

    /**
     * GLANCE_INACTIVE_STATES: the states an HA tile draws in its inactive
     * color (F-AAG-02). Everything else - `on`, `open`, `unlocked`, a sensor's
     * number - is active (ADR 0032 item 3).
     */
    val GLANCE_INACTIVE_STATES: Set<String> = setOf(
        "off",
        "unknown",
        "unavailable",
        "locked",
        "closed",
        "not_home",
        "idle",
        "standby",
        "disconnected",
        "disarmed",
    )
}
