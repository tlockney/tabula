package net.lockney.tabula.core.intercom

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The microphone hand-off between the wake-word detector and a call (packet
 * P9.3 item 8, ADR 0030 item 7): a call that captures takes the microphone, the
 * detector gets it back afterwards, and a call that does not capture never
 * takes it.
 */
class MicArbiterTest {
    private var opens = 0
    private var closes = 0
    private var openResult = true

    private val arbiter = MicArbiter(
        object : MicArbiter.Listener {
            override fun startListening(): Boolean {
                opens++
                return openResult
            }

            override fun stopListening() {
                closes++
            }
        },
    )

    @Test
    fun `a wanted detector opens the microphone once`() {
        assertTrue(arbiter.onWakeWordWanted(true))
        assertEquals(1, opens)
        assertTrue(arbiter.listening)
        // asking again changes nothing
        assertTrue(arbiter.onWakeWordWanted(true))
        assertEquals(1, opens)
    }

    @Test
    fun `a call that captures closes the detector and the call ending reopens it`() {
        arbiter.onWakeWordWanted(true)
        arbiter.onCallHoldsMic(true)
        assertFalse(arbiter.listening)
        assertEquals(1, closes)

        arbiter.onCallHoldsMic(false)
        assertTrue(arbiter.listening)
        assertEquals(2, opens)
        assertEquals(1, closes)
    }

    @Test
    fun `the detector stays closed while the call holds the microphone`() {
        arbiter.onWakeWordWanted(true)
        arbiter.onCallHoldsMic(true)
        // a playback pause and resume during the call changes nothing
        assertFalse(arbiter.onWakeWordWanted(false))
        assertFalse(arbiter.onWakeWordWanted(true))
        assertEquals(1, closes)
        assertEquals(1, opens)
    }

    @Test
    fun `a call with the detector switched off opens nothing afterwards`() {
        arbiter.onWakeWordWanted(false)
        arbiter.onCallHoldsMic(true)
        arbiter.onCallHoldsMic(false)
        assertEquals(0, opens)
        assertFalse(arbiter.listening)
    }

    @Test
    fun `a failed open is retried on the next ask`() {
        openResult = false
        assertFalse(arbiter.onWakeWordWanted(true))
        assertFalse(arbiter.listening)
        openResult = true
        assertTrue(arbiter.onWakeWordWanted(true))
        assertTrue(arbiter.listening)
    }

    @Test
    fun `update applies both inputs at once`() {
        arbiter.update(wanted = true, heldByCall = true)
        assertEquals(0, opens)
        assertEquals(0, closes)
        arbiter.update(wanted = true, heldByCall = false)
        assertTrue(arbiter.listening)
        assertEquals(1, opens)
    }
}
