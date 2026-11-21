package net.lockney.tabula.core.screen

import kotlin.math.abs
import net.lockney.tabula.core.Constants

/**
 * The illuminance state key's rate limit (F-HA-06, packet P3.16, ADR 0020):
 * a real change goes out at once; small oscillation does not.
 *
 * Every reading is offered to [reading]; the return value is the lux to
 * report, or null when this reading does not change what HA sees. The rule
 * (constants in ADR 0002, "Ambient light sensor reporting"):
 *
 * - The first reading goes out at once.
 * - A change of at least `ILLUMINANCE_CHANGE_THRESHOLD` relative to the last
 *   value sent goes out at once, whatever the interval. From total dark
 *   (last value 0 lx) any positive reading is a step, since the threshold
 *   is relative to zero.
 * - A smaller change goes out only after `ILLUMINANCE_MIN_INTERVAL` since
 *   the last send, so a drifting room reports at a bounded rate and a steady
 *   room (identical readings) reports nothing. Light sensors report on
 *   change, so a sub-threshold change reaches HA only when the next reading
 *   arrives past the interval: a room that drifts and then holds steady
 *   stays at HA's older value, an error bounded by the threshold (ADR 0002).
 * - Readings are sanitized, never rejected with an exception: non-finite
 *   and negative readings are dropped (the last held value stands) and
 *   anything above the schema's 100000 lx bound is clamped to it. A sensor
 *   glitch must not crash the sensor thread or poison registration
 *   (review, PR #108).
 * - [value] always holds the latest reading, even one that was throttled:
 *   registration sends the current value, and if the sensor stops reporting
 *   while the panel is off (F-SCR-14) the last reading is held - never
 *   cleared, never zero.
 *
 * The existing `STATE_MIN_INTERVAL` coalescing still applies to whatever the
 * caller sends on top of this (PROTOCOL 4.4).
 *
 * Thread-safe: the light sensor's listener calls in from its own thread.
 */
class IlluminanceFilter(
    private val threshold: Double = Constants.ILLUMINANCE_CHANGE_THRESHOLD,
    private val minIntervalMs: Long = Constants.ILLUMINANCE_MIN_INTERVAL_MS,
) {
    private var latest: Double? = null
    private var sent: Double? = null
    private var sentAt: Long = Long.MIN_VALUE

    /** The latest reading, held even when it was not sent; null before the first one. */
    @get:Synchronized
    val value: Double?
        get() = latest

    /**
     * Offer a reading. Returns the value HA should see (and the caller
     * reports as `illuminance`), or null when nothing goes out or the
     * reading was a glitch (non-finite or negative; the last held value
     * stands). A reading above the schema's 100000 lx bound is clamped.
     */
    @Synchronized
    fun reading(lux: Double, nowMs: Long): Double? {
        // sanitize rather than throw (review, PR #108): this runs inside
        // onSensorChanged, and the held value feeds registration
        if (lux.isNaN() || lux.isInfinite() || lux < 0.0) return null
        val reading = lux.coerceAtMost(100_000.0)
        latest = reading
        val last = sent
        if (last == null || abs(reading - last) >= threshold * last) {
            sent = reading
            sentAt = nowMs
            return reading
        }
        if (reading != last && nowMs - sentAt >= minIntervalMs) {
            sent = reading
            sentAt = nowMs
            return reading
        }
        return null
    }
}
