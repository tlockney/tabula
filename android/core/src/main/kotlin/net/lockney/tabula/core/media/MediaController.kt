package net.lockney.tabula.core.media

import net.lockney.tabula.core.settings.Audio
import net.lockney.tabula.core.tlp.MediaControlAction
import net.lockney.tabula.core.tlp.MediaFinishedReason
import net.lockney.tabula.core.tlp.MediaState
import net.lockney.tabula.core.tlp.StateDelta

/**
 * The media player's state machine (FEATURES F-MED-01, F-TTS-01, F-TTS-02,
 * F-TTS-06, F-TTS-07, F-TTS-10), free of Android so it is tested on a plain
 * JVM. The app feeds it `play_media` and `media_control` commands, the
 * player's prepared/ended/error callbacks and settings, and carries out each
 * [Effect] on the real player and volume hardware, each [Transition] as a
 * `media_finished` event and each state delta as `tabula/state`.
 *
 * - Latest wins: a new `play_media` stops the current playback, which ends
 *   with reason "stopped" (F-TTS-06).
 * - `next` and `previous` fail: there is no queue (F-MED-01, PROTOCOL 4.5).
 * - `seek` works on a prepared stream (local playback can always seek) and
 *   fails with nothing to seek.
 * - `stop` with nothing playing is a no-op, not an error (F-TTS-06).
 * - Volume is Tabula's media channel (PROTOCOL 4.4): `set_volume`, `mute`,
 *   `unmute` and a per-call play volume all write it, and HA's settings
 *   document owns its level (`audio.media_volume`, plan 5.6).
 * - Every playback reaches a terminal state (F-TTS-10): the app reports
 *   [onEnded] or [onError], never neither.
 *
 * Thread-safe.
 */
class MediaController {
    /** What the app must do to the real player and volume hardware. */
    sealed interface Effect {
        /** Release the current playback and any load still in flight. */
        data object Stop : Effect

        /** Fetch [url] and prepare it; [volume] applies to this playback only. */
        data class Prepare(val url: String, val volume: Int?) : Effect

        data object Start : Effect

        data object Pause : Effect

        data object Resume : Effect

        data class Seek(val positionS: Double) : Effect

        /** Write the media channel's level to the OS (settings or set_volume). */
        data class SetVolume(val volume: Int) : Effect

        /** Mute or unmute the media channel. */
        data class SetMuted(val muted: Boolean) : Effect
    }

    /** media_finished (PROTOCOL 4.7), for the app to send as an event. */
    sealed interface Transition {
        data class Finished(val reason: MediaFinishedReason) : Transition
    }

    data class Outcome(
        val effects: List<Effect> = emptyList(),
        val transitions: List<Transition> = emptyList(),
        val state: StateDelta? = null,
    )

    /** A `media_control` either applies or fails (PROTOCOL 6, F-MED-01). */
    sealed interface ControlResult {
        data class Ok(val outcome: Outcome) : ControlResult

        data class Failed(val message: String) : ControlResult
    }

    private enum class Phase { IDLE, LOADING, PLAYING, PAUSED }

    private var phase = Phase.IDLE
    private var volume = Audio().mediaVolume
    private var muted = false
    private var reportedVolume: Int? = null
    private var reportedMuted: Boolean? = null
    private var reportedState: MediaState? = null

    /** Full media state for registration (PROTOCOL 4.2). */
    @Synchronized
    fun current(): StateDelta =
        StateDelta(volume = volume, muted = muted, mediaState = state())
            .also { reportedVolume = it.volume; reportedMuted = it.muted; reportedState = it.mediaState }

    /** A settings document was applied: HA owns the channel level (plan 5.6). */
    @Synchronized
    fun applySettings(settings: Audio): Outcome {
        val old = volume
        volume = settings.mediaVolume
        return Outcome(
            effects = if (volume != old) listOf(Effect.SetVolume(volume)) else emptyList(),
            state = delta(),
        )
    }

    /** The `play_media` command (PROTOCOL 4.5). [perCallVolume] is the one-off level. */
    @Synchronized
    fun play(url: String, perCallVolume: Int?): Outcome {
        val transitions = if (phase != Phase.IDLE) {
            // latest wins: the playback being cut off ends with "stopped" (F-TTS-06)
            listOf(Transition.Finished(MediaFinishedReason.STOPPED))
        } else {
            emptyList()
        }
        phase = Phase.LOADING
        val effects = buildList {
            if (transitions.isNotEmpty()) add(Effect.Stop)
            perCallVolume?.let { volume = it; add(Effect.SetVolume(it)) }
            add(Effect.Prepare(url, perCallVolume))
        }
        return Outcome(effects, transitions, delta())
    }

    /** The player decoded the audio; the app starts it now. */
    @Synchronized
    fun onPrepared(): Outcome {
        phase = Phase.PLAYING
        return Outcome(effects = listOf(Effect.Start), state = delta())
    }

    /** The audio played to its end (F-TTS-10: a terminal state). */
    @Synchronized
    fun onEnded(): Outcome {
        phase = Phase.IDLE
        return Outcome(transitions = listOf(Transition.Finished(MediaFinishedReason.ENDED)), state = delta())
    }

    /** The fetch or the decoder failed (F-TTS-10: a terminal state). */
    @Synchronized
    fun onError(): Outcome {
        phase = Phase.IDLE
        return Outcome(transitions = listOf(Transition.Finished(MediaFinishedReason.ERROR)), state = delta())
    }

    /** The `media_control` command (PROTOCOL 4.5). */
    @Synchronized
    fun control(action: MediaControlAction, volume: Int?, position: Double?): ControlResult = when (action) {
        MediaControlAction.PAUSE ->
            if (phase == Phase.PLAYING) {
                phase = Phase.PAUSED
                ok(listOf(Effect.Pause))
            } else {
                ControlResult.Failed("nothing is playing")
            }

        MediaControlAction.RESUME ->
            if (phase == Phase.PAUSED) {
                phase = Phase.PLAYING
                ok(listOf(Effect.Resume))
            } else {
                ControlResult.Failed("nothing is paused")
            }

        MediaControlAction.STOP -> when (phase) {
            Phase.IDLE -> ControlResult.Ok(Outcome()) // nothing to stop is not an error (F-TTS-06)
            else -> {
                phase = Phase.IDLE
                ControlResult.Ok(
                    Outcome(
                        effects = listOf(Effect.Stop),
                        transitions = listOf(Transition.Finished(MediaFinishedReason.STOPPED)),
                        state = delta(),
                    ),
                )
            }
        }

        MediaControlAction.SEEK ->
            // the codec guarantees position; local playback can always seek
            if (phase == Phase.PLAYING || phase == Phase.PAUSED) {
                ok(listOf(Effect.Seek(requireNotNull(position) { "media_control.seek needs position" })))
            } else {
                ControlResult.Failed("nothing to seek")
            }

        MediaControlAction.SET_VOLUME -> {
            this.volume = requireNotNull(volume) { "media_control.set_volume needs volume" }
            ok(listOf(Effect.SetVolume(this.volume)))
        }

        MediaControlAction.MUTE -> {
            muted = true
            ok(listOf(Effect.SetMuted(true)))
        }

        MediaControlAction.UNMUTE -> {
            muted = false
            ok(listOf(Effect.SetMuted(false)))
        }

        // no queue (F-MED-01, PROTOCOL 4.5)
        MediaControlAction.NEXT, MediaControlAction.PREVIOUS ->
            ControlResult.Failed("no queue: this player plays single items")
    }

    private fun ok(effects: List<Effect>): ControlResult = ControlResult.Ok(
        Outcome(effects = effects, state = delta()),
    )

    /**
     * Only the state keys that changed since they were last reported
     * (PROTOCOL 4.4), or null. A key is reported only after [current] has
     * sent it once with registration: before that, a delta would repeat what
     * the register frame already carries.
     */
    private fun delta(): StateDelta? {
        val delta = StateDelta(
            volume = volume.takeIf { reportedVolume != null && it != reportedVolume }?.also { reportedVolume = it },
            muted = muted.takeIf { reportedMuted != null && it != reportedMuted }?.also { reportedMuted = it },
            mediaState = state().takeIf { reportedState != null && it != reportedState }?.also { reportedState = it },
        )
        return delta.takeIf { it.isNotEmpty }
    }

    private fun state(): MediaState = when (phase) {
        Phase.IDLE, Phase.LOADING -> MediaState.IDLE
        Phase.PLAYING -> MediaState.PLAYING
        Phase.PAUSED -> MediaState.PAUSED
    }
}
