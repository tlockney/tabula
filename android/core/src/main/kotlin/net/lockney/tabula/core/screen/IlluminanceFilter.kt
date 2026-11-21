package net.lockney.tabula.core.screen

import kotlin.math.abs
import net.lockney.tabula.core.Constants

/**
 * The illuminance state key's rate limit (F-HA-06, packet P3.16, ADR 0019):
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
 *   room (identical readings) reports nothing.
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
     * reports as `illuminance`), or null when nothing goes out.
     */
    @Synchronized
    fun reading(lux: Double, nowMs: Long): Double? {
        require(lux >= 0.0) { "illuminance must be >= 0, is $lux" }
        latest = lux
        val last = sent
        if (last == null || abs(lux - last) >= threshold * last) {
            sent = lux
            sentAt = nowMs
            return lux
        }
        if (lux != last && nowMs - sentAt >= minIntervalMs) {
            sent = lux
            sentAt = nowMs
            return lux
        }
        return null
    }
}
