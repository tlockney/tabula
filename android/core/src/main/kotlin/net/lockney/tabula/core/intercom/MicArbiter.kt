package net.lockney.tabula.core.intercom

/**
 * The microphone's single owner (packet P9.3 item 8, ADR 0029 item 3, ADR 0030
 * item 7).
 *
 * Two features want the microphone: the wake-word detector and a call that
 * captures. The call always wins, because it is a person's conversation and the
 * detector is a convenience: when a call that uses the microphone starts, the
 * detector's capture is closed rather than shared - Fire OS gives no exclusive
 * handle and two `AudioRecord`s on the same source is not a thing the spike
 * measured. When the call ends the detector re-acquires the microphone if it
 * was wanted, which is the setting still being on, the grant still being held
 * and nothing playing.
 *
 * A call that does not capture (listen-only, or a broadcast being played) does
 * not take the microphone away. Plain Kotlin so the hand-off is unit-tested on
 * a JVM, not only on a tablet.
 */
class MicArbiter(private val listener: Listener) {
    /** What the arbiter drives; the app's implementation is [WakeWordMonitor]. */
    interface Listener {
        /** Opens the microphone; false when capture could not start. */
        fun startListening(): Boolean

        /** Closes the microphone; no capture happens after this returns. */
        fun stopListening()
    }

    private var wanted = false
    private var heldByCall = false

    /** True while the arbiter has the detector's capture open. */
    var listening: Boolean = false
        private set

    /** The detector may listen only while nothing else needs the microphone. */
    val shouldListen: Boolean get() = wanted && !heldByCall

    /**
     * The wake-word gate's decision (setting on, granted, not playing). Returns
     * the resulting capture state so a gate can record what actually happened.
     */
    fun onWakeWordWanted(wanted: Boolean): Boolean {
        this.wanted = wanted
        reconcile()
        return listening
    }

    /** A call that captures started or ended. */
    fun onCallHoldsMic(held: Boolean) {
        if (heldByCall == held) return
        heldByCall = held
        reconcile()
    }

    /** Applies both inputs in one step (startup, or a reconnect). */
    fun update(wanted: Boolean = this.wanted, heldByCall: Boolean = this.heldByCall) {
        this.wanted = wanted
        this.heldByCall = heldByCall
        reconcile()
    }

    private fun reconcile() {
        if (shouldListen) {
            if (!listening) listening = listener.startListening()
            return
        }
        if (listening) {
            listening = false
            listener.stopListening()
        }
    }
}
