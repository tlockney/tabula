package net.lockney.tabula.core.intercom

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import net.lockney.tabula.core.voice.WakeWordGate

/**
 * The gate and the arbiter together, in the order the review of #171 found the
 * microphone left open (finding 2): a call takes the capture, a playback inside
 * the call makes the gate report a stop and then a start it cannot satisfy, the
 * call ends and the capture comes back, and the gate's next stop must still
 * close it.
 */
class WakeWordGateArbiterTest {
    private class Monitor : MicArbiter.Listener {
        var opens = 0
        var closes = 0

        override fun startListening(): Boolean {
            opens++
            return true
        }

        override fun stopListening() {
            closes++
        }
    }

    private val monitor = Monitor()
    private val arbiter = MicArbiter(monitor)
    private val gate = WakeWordGate(
        object : WakeWordGate.Listener {
            override fun startListening(): Boolean = arbiter.onWakeWordWanted(true)

            override fun stopListening() {
                arbiter.onWakeWordWanted(false)
            }
        },
    )

    @Test
    fun `a call that takes the microphone and gives it back cannot leave it open`() {
        gate.update(enabled = true, permitted = true, playing = false)
        assertTrue(arbiter.listening, "the detector holds the microphone")

        // a call that captures takes it
        arbiter.onCallHoldsMic(true)
        assertFalse(arbiter.listening)

        // a playback inside the call: the gate reports a stop, then a start the
        // arbiter cannot satisfy while the call holds the microphone
        gate.onPlaying(true)
        gate.onPlaying(false)
        assertFalse(arbiter.listening, "the call still holds it")

        // the call ends and the capture comes back
        arbiter.onCallHoldsMic(false)
        assertTrue(arbiter.listening)

        // the switch goes off: the detector must stop, even though the gate's
        // last start was refused while the call held the microphone
        gate.onSetting(false)
        assertFalse(arbiter.listening, "the microphone stays closed with the feature off")
        assertTrue(monitor.closes >= 2)
    }
}
