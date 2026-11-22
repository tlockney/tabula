package net.lockney.tabula.core.voice

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The half-duplex rule (ADR 0029 item 3, ADR 0030 item 7): detection stops
 * whenever the tablet plays and resumes when it stops, and the settings and the
 * grant keep their vetoes across both.
 */
class WakeWordGateTest {
    private class RecordingListener : WakeWordGate.Listener {
        val calls = mutableListOf<String>()

        /** Openings to refuse before the microphone works (a busy device). */
        var refusals = 0

        override fun startListening(): Boolean {
            calls += "start"
            if (refusals > 0) {
                refusals--
                return false
            }
            return true
        }

        override fun stopListening() {
            calls += "stop"
        }
    }

    private val listener = RecordingListener()
    private val gate = WakeWordGate(listener)

    private fun listening(enabled: Boolean = true, permitted: Boolean = true) {
        gate.update(enabled = enabled, permitted = permitted)
    }

    @Test
    fun `the microphone opens only with the setting and the grant`() {
        gate.onSetting(true)
        assertFalse(gate.listening, "the grant is still missing")
        gate.onPermission(true)
        assertTrue(gate.listening)
        assertEquals(listOf("start"), listener.calls)
    }

    @Test
    fun `a settings push stops capture when the switch goes off`() {
        listening()
        gate.onSetting(false)
        assertFalse(gate.listening)
        assertEquals(listOf("start", "stop"), listener.calls)
    }

    @Test
    fun `a withdrawn grant stops capture`() {
        listening()
        gate.onPermission(false)
        assertFalse(gate.listening)
        assertEquals(listOf("start", "stop"), listener.calls)
    }

    @Test
    fun `playback closes the microphone and the end of it reopens it`() {
        listening()
        gate.onPlaying(true)
        assertFalse(gate.listening, "the tablet must not listen to its own speaker")
        gate.onPlaying(false)
        assertTrue(gate.listening, "capture resumes after the sound")
        // start, stop for the playback, start again: the resume is a fresh
        // opening, which is where the monitor resets the detector
        assertEquals(listOf("start", "stop", "start"), listener.calls)
    }

    @Test
    fun `the setting turned on during playback waits for silence`() {
        gate.onPermission(true)
        gate.onPlaying(true)
        gate.onSetting(true)
        assertFalse(gate.listening)
        assertEquals(emptyList(), listener.calls, "no capture while something plays")
        gate.onPlaying(false)
        assertTrue(gate.listening)
        assertEquals(listOf("start"), listener.calls)
    }

    @Test
    fun `the setting turned off during playback stays off when silence returns`() {
        listening()
        gate.onPlaying(true)
        gate.onSetting(false)
        gate.onPlaying(false)
        assertFalse(gate.listening)
        assertEquals(listOf("start", "stop"), listener.calls)
    }

    @Test
    fun `a rejected opening is retried at the next trigger`() {
        listener.refusals = 1
        listening()
        assertFalse(gate.listening, "the listener said it could not open")
        gate.onPlaying(true)
        gate.onPlaying(false)
        assertTrue(gate.listening)
        assertEquals(listOf("start", "start"), listener.calls)
    }

    @Test
    fun `repeated signals do not churn the microphone`() {
        listening()
        gate.onSetting(true)
        gate.onPermission(true)
        gate.onPlaying(false)
        assertEquals(listOf("start"), listener.calls, "no stop/start for a no-op")
    }
}
