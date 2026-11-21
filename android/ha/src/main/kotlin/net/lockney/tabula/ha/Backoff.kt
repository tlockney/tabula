package net.lockney.tabula.ha

import kotlin.math.min
import kotlin.random.Random
import net.lockney.tabula.core.Constants

/**
 * Equal-jitter exponential reconnect backoff (docs/PROTOCOL.md section 7,
 * values from ADR 0002): attempt *n* (from 0) waits a uniformly random time in
 * [d/2, d] with d = min(BACKOFF_MAX, BACKOFF_BASE * 2^n).
 *
 * [markStable] is called once a connection has stayed registered for
 * BACKOFF_RESET_AFTER, so the next failure starts over from BACKOFF_BASE. The
 * flag survives until the connection it belongs to ends (the client cancels the
 * stable timer when the connection drops before the window completes).
 */
class Backoff(private val random: Random = Random.Default) {
    var attempt: Int = 0
        private set

    @Volatile
    private var stable = false

    fun markStable() {
        stable = true
    }

    /** Next reconnect delay in milliseconds; consumes the stable flag when set. */
    fun nextDelayMs(): Long {
        if (stable) {
            attempt = 0
            stable = false
        }
        val seconds = min(Constants.BACKOFF_MAX_S, Constants.BACKOFF_BASE_S * 2.0.pow(attempt))
        attempt += 1
        val half = seconds * 500.0 // half of the delay, in ms
        return (half + random.nextDouble() * half).toLong()
    }
}

private fun Double.pow(n: Int): Double {
    var result = 1.0
    repeat(n) { result *= this }
    return result
}
