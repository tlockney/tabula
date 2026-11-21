package net.lockney.tabula.core.screensaver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The pre-committed night-mode flag contract (ADR 0002, "Settings schema
 * v2"): the never-night default reads false and never fires a listener, and
 * a P3.4-style implementation is expressible against the interface alone
 * (plain JVM, no sensor): listeners see every transition, and removal stops
 * delivery.
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
            override fun addListener(listener: NightState.Listener): () -> Unit = {}
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

    @Test
    fun `listeners observe every transition`() {
        var night = false
        val listeners = mutableListOf<NightState.Listener>()
        val state = object : NightState {
            override val isNight get() = night
            override fun addListener(listener: NightState.Listener): () -> Unit {
                listeners += listener
                return { listeners -= listener }
            }
        }
        fun setNight(value: Boolean) {
            night = value
            listeners.forEach { it.onNightChanged(value) }
        }

        val seen = mutableListOf<Boolean>()
        val remove = state.addListener(NightState.Listener { seen += it })
        setNight(true)
        setNight(false)
        remove()
        setNight(true)
        assertEquals(listOf(true, false), seen)
    }

    @Test
    fun `the never-night default has no transitions to observe`() {
        val seen = mutableListOf<Boolean>()
        val remove = NightState.NEVER.addListener(NightState.Listener { seen += it })
        remove()
        assertEquals(emptyList<Boolean>(), seen)
    }
}
