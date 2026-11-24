package net.lockney.tabula.core.intercom

/**
 * The short tone an auto-answer plays before its microphone opens (P9.5,
 * ADR 0028 guard 1: a tone announces an unanswered microphone).
 *
 * The tone is best effort. A device whose speaker is busy still answers - the
 * tone is a courtesy, not a precondition - so [play] must not throw and must
 * not block: the machine calls it on its own monitor.
 */
fun interface CallTone {
    /** Starts the tone and returns at once. Nothing here captures audio. */
    fun play()

    companion object {
        /** A caller with no tone, and the JVM tests' starting point. */
        val None: CallTone = CallTone { }
    }
}
