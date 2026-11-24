package net.lockney.tabula.core.voice

/**
 * Whether the microphone should be open right now (P7.1, ADR 0029 item 3,
 * ADR 0030 item 7).
 *
 * Three inputs decide it: the `voice.wake_word_enabled` setting, the
 * `RECORD_AUDIO` grant, and whether the tablet is playing its own audio. The
 * Fire HD 10 Plus exposes no usable echo canceller, so detection is
 * half-duplex: while a chime, a TTS reply or a media stream is sounding, capture
 * is closed rather than filtered, and it is reopened - with the detector reset -
 * once the sound stops. The microphone never stays open to score the tablet's
 * own speaker.
 *
 * This is plain Kotlin on purpose: the app wires it to [WakeWordMonitor] and to
 * the playback signals, and the transitions are tested on a JVM.
 */
class WakeWordGate(private val listener: Listener) {
    /** What the gate drives; the app's implementation is [WakeWordMonitor]. */
    interface Listener {
        /** Opens the microphone; false when capture could not start. */
        fun startListening(): Boolean

        /** Closes the microphone; no capture happens after this returns. */
        fun stopListening()
    }

    private var enabled = false
    private var permitted = false
    private var playing = false

    /** The decision last reported to the listener; false after a refused open. */
    private var wanted = false

    /**
     * The last capture state the listener reported. It is what the listener
     * said, never a decision of the gate's own: capture has one owner, and
     * another feature (a call that captures) can close and reopen the
     * microphone without the gate hearing about it (review of #171, finding 2).
     */
    var listening: Boolean = false
        private set

    /** The setting, the grant and silence: everything a detection needs. */
    val shouldListen: Boolean get() = enabled && permitted && !playing

    /** The setting changed (a settings push, startup, or the HA switch). */
    fun onSetting(enabled: Boolean) {
        if (this.enabled == enabled) return
        this.enabled = enabled
        reconcile()
    }

    /** The grant was given or withdrawn (Fire OS can withdraw it at boot). */
    fun onPermission(permitted: Boolean) {
        if (this.permitted == permitted) return
        this.permitted = permitted
        reconcile()
    }

    /**
     * The device started or finished playing. Called for every source the
     * tablet can hear itself through: the notification chime, a TTS reply and a
     * media stream.
     */
    fun onPlaying(playing: Boolean) {
        if (this.playing == playing) return
        this.playing = playing
        reconcile()
    }

    /**
     * Applies the three inputs in one step, for callers that have just learnt
     * several at once (startup, or a reconnect).
     */
    fun update(enabled: Boolean = this.enabled, permitted: Boolean = this.permitted, playing: Boolean = this.playing) {
        this.enabled = enabled
        this.permitted = permitted
        this.playing = playing
        reconcile()
    }

    /**
     * Reports the gate's decision whenever that decision changes, rather than
     * acting only on its own cached copy of the capture state. Capture has one
     * owner: a call that captures can close and reopen the microphone without
     * the gate hearing about it, so a cached "we are open" would let the next
     * stop go unreported and leave the detector listening with the feature off
     * (review of #171, finding 2). A stop is reported whenever capture was last
     * asked for, which is what makes that case safe; a refused open is retried
     * at the next change, as before.
     */
    private fun reconcile() {
        val decision = shouldListen
        val retry = decision && !listening
        if (decision == wanted && !retry) return
        wanted = decision
        if (decision) {
            listening = listener.startListening()
            return
        }
        listener.stopListening()
        listening = false
    }
}
