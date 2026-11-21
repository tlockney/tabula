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
}
