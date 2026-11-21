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

    /** TOKEN_REFRESH_MARGIN: s of access-token life left before the device refreshes. */
    const val TOKEN_REFRESH_MARGIN_S = 300.0

    /** REGISTER_TIMEOUT: s to wait for each HA reply during the session handshake. */
    const val REGISTER_TIMEOUT_S = 10.0

    /** APP_PING_INTERVAL: s between the device's own HA ping commands. */
    const val APP_PING_INTERVAL_S = 30.0

    /** PONG_TIMEOUT: s to wait for the pong before treating the connection as dead. */
    const val PONG_TIMEOUT_S = 10.0

    /** STATE_MIN_INTERVAL: minimum gap between two tabula/state frames, milliseconds. */
    const val STATE_MIN_INTERVAL_MS = 250L

    /** BACKOFF_BASE: s; the first reconnect retry delay (before equal jitter). */
    const val BACKOFF_BASE_S = 1.0

    /** BACKOFF_MAX: s; the reconnect delay cap (before equal jitter). */
    const val BACKOFF_MAX_S = 60.0

    /** BACKOFF_RESET_AFTER: s a connection must stay registered for the attempt counter to reset. */
    const val BACKOFF_RESET_AFTER_S = 60.0
}
