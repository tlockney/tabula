package net.lockney.tabula.core.log

import java.time.Instant
import net.lockney.tabula.core.Constants
import net.lockney.tabula.core.tlp.LogLevel

/** One line in the device's log ring, as the app and the dashboard console write it. */
data class LogEntry(
    /** Monotonic position in the ring's writer, so a tail knows what it has sent. */
    val seq: Long,
    /** Milliseconds since the epoch, for `LogLine.ts`. */
    val tsMs: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
) {
    /** The line as a TLP `LogLine` payload (PROTOCOL.md 4.8). */
    fun toWire(): Map<String, Any> = mapOf(
        "ts" to Instant.ofEpochMilli(tsMs).toString(),
        "level" to level.name.lowercase(),
        "tag" to tag,
        "message" to message,
    )
}

/**
 * The device's bounded log ring (P5.4, F-MGT-06): [Constants.LOG_BUFFER_LINES]
 * lines, oldest dropped first, fed by the app's own logging and by the
 * dashboard's console and read by the log subscription.
 *
 * Pure Kotlin on purpose: no Android API, so the ring, the bound, the order and
 * the redaction rule are JVM-tested. Redaction happens here, at the only entry
 * point, so the ring cannot hold a credential (PROTOCOL.md 4.8).
 *
 * Thread-safe: logging happens on whatever thread raised the line (the WebView's
 * console callback, the service's coroutines), while the subscription reads.
 */
class LogBuffer(
    private val capacity: Int = Constants.LOG_BUFFER_LINES,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private val entries = ArrayDeque<LogEntry>(capacity)
    private var nextSeq = 1L

    init {
        require(capacity > 0) { "a log ring needs at least one line" }
    }

    /** Append one line; returns what was stored, after redaction and truncation. */
    fun add(level: LogLevel, tag: String, message: String): LogEntry {
        val entry = synchronized(this) {
            LogEntry(
                seq = nextSeq++,
                tsMs = clockMs(),
                level = level,
                tag = tag.take(TAG_MAX).ifEmpty { "app" },
                message = prepare(message),
            ).also {
                if (entries.size == capacity) entries.removeFirst()
                entries.addLast(it)
            }
        }
        return entry
    }

    /** The ring, oldest first: what the panel shows for an offline device. */
    fun snapshot(): List<LogEntry> = synchronized(this) { entries.toList() }

    /** Lines written after [seq], oldest first; the caller's cursor is its own. */
    fun after(seq: Long): List<LogEntry> = synchronized(this) { entries.filter { it.seq > seq } }

    /** The newest line's sequence number, or 0 while the ring is empty. */
    fun lastSeq(): Long = synchronized(this) { entries.lastOrNull()?.seq ?: 0L }

    fun size(): Int = synchronized(this) { entries.size }

    private fun prepare(message: String): String {
        val redacted = Redaction.apply(message).take(MESSAGE_MAX)
        // A LogLine's message is 1-4000 chars: a blank one is not sent, and an
        // empty line is still worth seeing in the tail.
        return redacted.ifBlank { "(empty)" }
    }

    companion object {
        /** `LogLine.tag` bound (schema/tlp-v1.schema.json). */
        const val TAG_MAX = 64

        /** `LogLine.message` bound (schema/tlp-v1.schema.json). */
        const val MESSAGE_MAX = 4000
    }
}
