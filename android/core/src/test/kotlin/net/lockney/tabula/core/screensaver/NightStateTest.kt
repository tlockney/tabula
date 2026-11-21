package net.lockney.tabula.core.screensaver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The pre-committed night-mode flag contract (ADR 0002, "Settings schema
 * v2"): the never-night default reads false, and a P3.4-style implementation
 * is expressible against the interface alone (plain JVM, no sensor).
 */
class NightStateTest {
    @Test
    fun `the default never reports night`() {
        assertFalse(NightState.NEVER.isNight)
    }

    @Test
    fun `an implementation can report night`() {
        val night = object : NightState {
            override val isNight = true
        }
        assertTrue(night.isNight)
    }

    @Test
    fun `a consumer switches colors on the flag`() {
        // The shape P3.6/P3.11 consume: pick the color from the flag, with the
        // default instance standing in until P3.4 provides the real one.
        fun accentColor(night: NightState): Int =
            if (night.isNight) 0xFF808080.toInt() else 0xFFEEEEEE.toInt()
        assertEquals(0xFFEEEEEE.toInt(), accentColor(NightState.NEVER))
    }
}
