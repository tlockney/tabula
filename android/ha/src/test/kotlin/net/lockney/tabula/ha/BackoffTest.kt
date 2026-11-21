package net.lockney.tabula.ha

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import net.lockney.tabula.core.Constants

class BackoffTest {
    @Test
    fun `delays stay within equal jitter and double per attempt until the cap`() {
        val backoff = Backoff(Random(7))
        val delays = List(8) { backoff.nextDelayMs() }
        // attempt n: d = min(BACKOFF_MAX, BACKOFF_BASE * 2^n) seconds; delay in [d/2, d] ms
        for ((n, delay) in delays.withIndex()) {
            val d = minOf(Constants.BACKOFF_MAX_S, Constants.BACKOFF_BASE_S * (1 shl n)) * 1000
            val half = (d / 2).toLong()
            val full = d.toLong()
            assertTrue(delay >= half && delay <= full, "attempt $n delay $delay outside [$half, $full]")
        }
        // from attempt 6 on, the window is capped at BACKOFF_MAX
        val d6 = minOf(Constants.BACKOFF_MAX_S, Constants.BACKOFF_BASE_S * (1 shl 6)) * 1000
        assertTrue(delays[6] >= (d6 / 2).toLong() && delays[6] <= d6.toLong())
        assertTrue(delays[7] >= (d6 / 2).toLong() && delays[7] <= d6.toLong())
    }

    @Test
    fun `a stable connection resets the attempt counter`() {
        val backoff = Backoff(Random(7))
        repeat(4) { backoff.nextDelayMs() } // attempt is now 4: window [8s, 16s]
        backoff.markStable()
        val delay = backoff.nextDelayMs()
        assertTrue(delay in 500..1000, "expected a reset to BACKOFF_BASE, got $delay")
    }

    @Test
    fun `without the stable flag the window keeps growing`() {
        val backoff = Backoff(Random(7))
        repeat(5) { backoff.nextDelayMs() }
        val delay = backoff.nextDelayMs()
        assertTrue(delay >= 16_000, "attempt 5 draws from [16s, 32s], got $delay")
    }
}
