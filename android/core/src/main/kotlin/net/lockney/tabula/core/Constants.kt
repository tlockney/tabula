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

    /** NOTIFICATION_EVENT_BUFFER: notification events kept while they wait for the session (P2.5). */
    const val NOTIFICATION_EVENT_BUFFER = 16

    /** MEDIA_EVENT_BUFFER: media_finished events kept while they wait for the session (P2.5). */
    const val MEDIA_EVENT_BUFFER = 16

    /** MEDIA_MAX_BYTES: largest audio file the media player downloads before playing it (P2.5). */
    const val MEDIA_MAX_BYTES = 50L * 1024 * 1024

    /** MEDIA_MAX_REDIRECTS: redirect hops the media fetch follows before giving up (P2.5). */
    const val MEDIA_MAX_REDIRECTS = 5

    /** MEDIA_READ_TIMEOUT: s a media download may wait on one socket read (P2.5). */
    const val MEDIA_READ_TIMEOUT_S = 30.0

    /** SCREENSHOT_JPEG_QUALITY: the capture's JPEG quality when the command
     * sends none; inside the schema's 10-95 range (P3.13, 0002-constants.md). */
    const val SCREENSHOT_JPEG_QUALITY = 80

    /** CHIME_VOLUME: the notification chime generator's own scale, at the top (P2.5). */
    const val CHIME_VOLUME = 100

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
}
