package net.lockney.tabula.core.log

import net.lockney.tabula.core.Constants
import net.lockney.tabula.core.tlp.LogLevel

/**
 * The device side of `log_subscription` (PROTOCOL.md 4.5, 4.8; P5.4).
 *
 * While enabled, the streamer answers "what goes in the next frame, and is one
 * due?" from the ring: at most [Constants.LOG_MAX_LINES] lines, at most one
 * batch per [Constants.LOG_MIN_INTERVAL], only when there is something new, and
 * only lines at or above the subscription's minimum level. It also owns the
 * subscription's own expiry: with no refresh for [Constants.LOG_SUBSCRIPTION_TTL]
 * the device stops by itself, so a viewer that vanished cannot leave a device
 * streaming forever.
 *
 * A subscription survives nothing: [disable] on disconnect, and [enable] starts
 * from the ring's current end rather than replaying it, because the panel shows
 * the buffered lines from HA's own ring and a replay would show them twice.
 *
 * Pure Kotlin with an injected clock, so the interval, the cap, the level filter
 * and the TTL are JVM-tested without waiting.
 */
class LogStreamer(
    private val buffer: LogBuffer,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {
    private var enabled = false
    private var minLevel: LogLevel? = null
    private var cursor = 0L
    private var lastBatchMs = 0L
    private var refreshedMs = 0L

    /** True while a subscription is on and has not expired. */
    @Synchronized
    fun isEnabled(nowMs: Long = clockMs()): Boolean = enabled && !expired(nowMs)

    /** The subscription's minimum level, or null for everything. */
    @Synchronized
    fun minimumLevel(): LogLevel? = minLevel

    /**
     * Start or refresh the subscription.
     *
     * A repeat `enabled: true` is the integration's TTL refresh: it keeps the
     * cursor, so a refresh never repeats lines, and takes a new `level` when one
     * is sent. A fresh enable starts at the ring's end.
     */
    @Synchronized
    fun enable(level: LogLevel?, nowMs: Long = clockMs()) {
        if (!enabled) {
            cursor = buffer.lastSeq()
            lastBatchMs = nowMs - Constants.LOG_MIN_INTERVAL_MS
        }
        enabled = true
        minLevel = level
        refreshedMs = nowMs
    }

    /** Stop streaming; the cursor goes with it. */
    @Synchronized
    fun disable() {
        enabled = false
        minLevel = null
    }

    /**
     * The next batch, or null when nothing is due: disabled, expired, inside the
     * interval, or no line at or above the minimum level is new.
     */
    @Synchronized
    fun takeBatch(nowMs: Long = clockMs()): List<LogEntry>? {
        if (!enabled) return null
        if (expired(nowMs)) {
            // The TTL is the device's own stop (PROTOCOL.md 4.8): nothing more
            // is sent, and the next log_subscription starts over.
            disable()
            return null
        }
        if (nowMs - lastBatchMs < Constants.LOG_MIN_INTERVAL_MS) return null
        val floor = minLevel
        val fresh = buffer.after(cursor)
        val matching = fresh.filter { floor == null || it.level.rank >= floor.rank }
        val batch = matching.take(Constants.LOG_MAX_LINES)
        // The cursor follows what was read: a debug line below the floor is
        // skipped for good, and a backlog drains over the next few intervals
        // rather than being dropped.
        cursor = if (batch.isNotEmpty()) batch.last().seq else fresh.lastOrNull()?.seq ?: cursor
        if (batch.isEmpty()) return null
        lastBatchMs = nowMs
        return batch
    }

    private fun expired(nowMs: Long): Boolean =
        enabled && nowMs - refreshedMs >= Constants.LOG_SUBSCRIPTION_TTL_MS
}

/** Level ordering for a minimum-level filter; the wire's names, weakest first. */
val LogLevel.rank: Int
    get() = when (this) {
        LogLevel.DEBUG -> 0
        LogLevel.INFO -> 1
        LogLevel.WARNING -> 2
        LogLevel.ERROR -> 3
    }
