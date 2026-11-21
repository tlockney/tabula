package net.lockney.tabula.core.screen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IlluminanceFilterTest {
    private val threshold = 0.20
    private val minInterval = 30_000L

    private val filter = IlluminanceFilter(threshold, minInterval)

    @Test
    fun `the first reading goes out at once`() {
        assertEquals(128.5, filter.reading(128.5, 0))
        assertEquals(128.5, filter.value)
    }

    @Test
    fun `a step change goes out at once whatever the interval`() {
        filter.reading(100.0, 0)
        // +25 %, past the 20 % threshold: no wait, no throttle
        assertEquals(125.0, filter.reading(125.0, 10))
        // an order of magnitude: immediately too
        assertEquals(1200.0, filter.reading(1200.0, 20))
    }

    @Test
    fun `from total dark any positive reading is a step`() {
        filter.reading(0.0, 0)
        assertEquals(0.1, filter.reading(0.1, 1))
    }

    @Test
    fun `sub-threshold oscillation is throttled to the interval`() {
        filter.reading(100.0, 0)
        // +-2 % swing around 100 lx: every one of these is sub-threshold
        assertNull(filter.reading(102.0, 1000))
        assertNull(filter.reading(98.0, 2000))
        assertNull(filter.reading(101.0, 10_000))
        // before the interval nothing goes out, identical values included
        assertNull(filter.reading(100.0, 20_000))
        // at the interval the change in progress reaches HA
        assertEquals(102.0, filter.reading(102.0, 31_000))
        // and the clock starts over: nothing until the next interval
        assertNull(filter.reading(103.0, 32_000))
        assertEquals(103.0, filter.reading(103.0, 62_000))
    }

    @Test
    fun `a steady room produces no updates`() {
        filter.reading(100.0, 0)
        repeat(10) { i -> assertNull(filter.reading(100.0, minInterval * (i + 1))) }
    }

    @Test
    fun `the throttled latest reading is held for registration`() {
        assertNull(filter.value)
        filter.reading(100.0, 0)
        // 1 % drift: throttled, but [value] reports the current room
        assertNull(filter.reading(101.0, 1000))
        assertEquals(101.0, filter.value)
        // the sensor stops reporting (panel off, F-SCR-14): the last value stays
        assertEquals(101.0, filter.value)
    }

    @Test
    fun `a reading above the schema bound is clamped to 100000`() {
        // some Android light sensors report past 100000 lx in direct sun; the
        // clamped value is what HA sees (review, PR #108)
        assertEquals(100_000.0, filter.reading(150_000.0, 0))
        assertEquals(100_000.0, filter.value)
        assertNull(filter.reading(100_000.0, 1000)) // identical after the clamp
    }

    @Test
    fun `negative and non-finite readings are dropped, not thrown`() {
        filter.reading(100.0, 0)
        // a sensor glitch inside onSensorChanged must neither crash the app
        // nor move the held value (review, PR #108)
        assertNull(filter.reading(-0.5, 10))
        assertNull(filter.reading(Double.NaN, 20))
        assertNull(filter.reading(Double.POSITIVE_INFINITY, 30))
        assertNull(filter.reading(Double.NEGATIVE_INFINITY, 40))
        assertEquals(100.0, filter.value)
    }
}
