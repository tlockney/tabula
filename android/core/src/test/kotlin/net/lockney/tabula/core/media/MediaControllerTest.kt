package net.lockney.tabula.core.media

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.lockney.tabula.core.settings.Audio
import net.lockney.tabula.core.tlp.MediaControlAction
import net.lockney.tabula.core.tlp.MediaFinishedReason
import net.lockney.tabula.core.tlp.MediaState

/** The media state machine (packet P2.5): play, controls, terminal states (F-MED, F-TTS). */
class MediaControllerTest {
    private val controller = MediaController()

    init {
        controller.current() // registration reported the initial state; deltas follow
    }

    private fun ok(action: MediaControlAction, volume: Int? = null, position: Double? = null): MediaController.Outcome {
        val result = controller.control(action, volume, position)
        return assertIs<MediaController.ControlResult.Ok>(result).outcome
    }

    private fun failed(action: MediaControlAction, volume: Int? = null, position: Double? = null): String {
        val result = controller.control(action, volume, position)
        return assertIs<MediaController.ControlResult.Failed>(result).message
    }

    @Test
    fun `registration state and the settings level`() {
        val state = controller.current()
        assertEquals(50, state.volume, "ADR 0002: media volume defaults to 50")
        assertEquals(false, state.muted)
        assertEquals(MediaState.IDLE, state.mediaState)

        val outcome = controller.applySettings(Audio(mediaVolume = 80))
        assertEquals(listOf(MediaController.Effect.SetVolume(80)), outcome.effects)
        assertEquals(80, outcome.state?.volume)
    }

    @Test
    fun `play loads, prepared starts, ended finishes`() {
        val play = controller.play("http://ha:8123/api/tts_proxy/a.mp3", perCallVolume = null)
        assertEquals(listOf(MediaController.Effect.Prepare("http://ha:8123/api/tts_proxy/a.mp3", null)), play.effects)
        assertNull(play.state, "loading is still idle; no key changed")

        val prepared = controller.onPrepared()
        assertEquals(listOf(MediaController.Effect.Start), prepared.effects)
        assertEquals(MediaState.PLAYING, prepared.state?.mediaState)

        val ended = controller.onEnded()
        val finished = assertIs<MediaController.Transition.Finished>(ended.transitions.single())
        assertEquals(MediaFinishedReason.ENDED, finished.reason)
        assertEquals(MediaState.IDLE, ended.state?.mediaState)
    }

    @Test
    fun `a per-call volume writes the channel level`() {
        controller.current() // registration reported the defaults
        val outcome = controller.play("http://ha:8123/a.mp3", perCallVolume = 70)
        assertEquals(MediaController.Effect.SetVolume(70), outcome.effects.first())
        assertEquals(70, outcome.state?.volume)
        assertEquals(70, controller.current().volume)
    }

    @Test
    fun `a new play stops the current one - latest wins (F-TTS-06)`() {
        controller.play("http://ha:8123/a.mp3", null)
        controller.onPrepared()
        val outcome = controller.play("http://ha:8123/b.mp3", null)
        val finished = assertIs<MediaController.Transition.Finished>(outcome.transitions.single())
        assertEquals(MediaFinishedReason.STOPPED, finished.reason)
        assertEquals(
            listOf(
                MediaController.Effect.Stop,
                MediaController.Effect.Prepare("http://ha:8123/b.mp3", null),
            ),
            outcome.effects,
        )
        assertEquals(MediaState.IDLE, outcome.state?.mediaState)
    }

    @Test
    fun `pause, resume and seek a playing stream`() {
        controller.play("/a.mp3", null)
        controller.onPrepared()

        assertEquals(MediaState.PAUSED, ok(MediaControlAction.PAUSE).state?.mediaState)
        assertEquals(listOf(MediaController.Effect.Seek(3.5)), ok(MediaControlAction.SEEK, position = 3.5).effects)
        assertEquals(MediaState.PLAYING, ok(MediaControlAction.RESUME).state?.mediaState)
    }

    @Test
    fun `pause, resume and seek fail with nothing to act on`() {
        assertTrue(failed(MediaControlAction.PAUSE).isNotEmpty())
        assertTrue(failed(MediaControlAction.RESUME).isNotEmpty())
        assertTrue(failed(MediaControlAction.SEEK, position = 3.0).isNotEmpty())
    }

    @Test
    fun `stop ends playback, and stopping idle is a no-op`() {
        controller.play("/a.mp3", null)
        controller.onPrepared()
        val outcome = ok(MediaControlAction.STOP)
        assertEquals(
            MediaFinishedReason.STOPPED,
            assertIs<MediaController.Transition.Finished>(outcome.transitions.single()).reason,
        )
        assertEquals(MediaState.IDLE, outcome.state?.mediaState)

        val idle = ok(MediaControlAction.STOP)
        assertTrue(idle.effects.isEmpty(), "nothing to stop is not an error (F-TTS-06)")
    }

    @Test
    fun `next and previous fail - there is no queue (F-MED-01)`() {
        assertTrue(failed(MediaControlAction.NEXT).isNotEmpty())
        assertTrue(failed(MediaControlAction.PREVIOUS).isNotEmpty())
    }

    @Test
    fun `set volume, mute and unmute`() {
        assertEquals(30, ok(MediaControlAction.SET_VOLUME, volume = 30).state?.volume)
        assertEquals(true, ok(MediaControlAction.MUTE).state?.muted)
        assertEquals(false, ok(MediaControlAction.UNMUTE).state?.muted)
    }

    @Test
    fun `a fetch or decode error is terminal (F-TTS-10)`() {
        controller.current() // registration reported the idle state
        controller.play("/missing.mp3", null)
        val failed = controller.onError()
        val finished = assertIs<MediaController.Transition.Finished>(failed.transitions.single())
        assertEquals(MediaFinishedReason.ERROR, finished.reason)
        assertEquals(MediaState.IDLE, controller.current().mediaState)
    }

    @Test
    fun `state deltas carry only the keys that changed (PROTOCOL 4-4)`() {
        controller.current() // everything reported
        val outcome = ok(MediaControlAction.MUTE)
        assertEquals(setOf(true), outcome.state?.let { delta -> setOf(delta.muted) })
        assertEquals(null, outcome.state?.volume)
        assertEquals(null, outcome.state?.mediaState)
    }
}
