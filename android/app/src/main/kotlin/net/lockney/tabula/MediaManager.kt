package net.lockney.tabula

import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import net.lockney.tabula.core.Constants
import net.lockney.tabula.core.media.MediaController
import net.lockney.tabula.core.settings.Settings
import net.lockney.tabula.core.tlp.EventFrame
import net.lockney.tabula.core.tlp.MediaControlAction
import net.lockney.tabula.core.tlp.MediaType
import net.lockney.tabula.core.tlp.StateDelta
import net.lockney.tabula.ha.CommandHandler
import net.lockney.tabula.ha.DashboardLinks

/** What [TabulaCommands] needs from the media player. */
interface AudioControl {
    /** The `play_media` command (PROTOCOL 4.5). */
    fun play(url: String, mediaType: MediaType, announce: Boolean, volume: Int?): CommandHandler.Reply

    /** The `media_control` command (PROTOCOL 4.5). */
    fun control(action: MediaControlAction, volume: Int?, position: Double?): CommandHandler.Reply

    /** A settings document was applied (startup or set_settings). */
    fun applySettings(settings: Settings)
}

/**
 * The device's media channel, behind an interface so [MediaManager] runs on a
 * plain JVM in tests. The Android implementation writes the music stream.
 */
interface AudioVolume {
    fun setVolume(percent: Int)

    fun setMuted(muted: Boolean)
}

/**
 * A media player the manager can drive; [prepare] blocks until the audio can
 * be played and returns a [Prepared] playback. The Android implementation
 * wraps [android.media.MediaPlayer] on a local file.
 */
interface MediaPlayerHandle {
    fun prepare(file: File, onEnded: () -> Unit, onError: (String) -> Unit): Prepared

    interface Prepared {
        fun start()

        fun pause()

        fun resume()

        fun seekTo(seconds: Double)

        /** Stop and release the player; no callback fires after this. */
        fun stop()
    }
}

/**
 * The media player (packet P2.5): carries out [MediaController]'s decisions -
 * fetching the audio, driving the player, writing the volume - and reports
 * each outcome as state and `media_finished` events (F-MED-01, F-TTS-01).
 *
 * The fetch never carries the device credential (PROTOCOL 4.5, ADR 0001
 * item 5): HA-hosted media is signed or unauthenticated, so the request goes
 * out with no Authorization header. Redirects are followed - this request
 * carries no secret, so the P2.1 no-redirect rule does not apply - with a
 * https-to-http downgrade and long chains refused, and the downloaded file
 * is deleted at every terminal state, on replacement, and by the startup
 * sweep ([sweepStale]), so a wall tablet never accumulates audio.
 *
 * Loads are generation-counted: a `play_media` that arrives while an earlier
 * one is still loading cancels it, and the older load's late callbacks
 * (decoded after the stop) are dropped, so a replaced playback can never
 * start over the newer one.
 */
class MediaManager(
    private val controller: MediaController,
    private val haUrl: () -> String?,
    private val download: suspend (String) -> File,
    private val player: MediaPlayerHandle,
    private val audio: AudioVolume,
    private val scope: CoroutineScope,
) : AudioControl {
    /** Receives every media state change; the session sets it while connected. */
    @Volatile
    var listener: ((StateDelta) -> Unit)? = null

    /**
     * media_finished events for the TLP session, bounded while they wait
     * (Constants.MEDIA_EVENT_BUFFER, ADR 0002).
     */
    private val eventQueue =
        Channel<(Int) -> EventFrame>(Constants.MEDIA_EVENT_BUFFER, BufferOverflow.DROP_OLDEST)
    val events: ReceiveChannel<(Int) -> EventFrame> get() = eventQueue

    private val generation = AtomicInteger(0)
    private var loadJob: Job? = null
    private var current: MediaPlayerHandle.Prepared? = null

    /** The file [current] plays; deleted when the playback reaches a terminal state. */
    private var currentFile: File? = null

    /** Full media state for registration (PROTOCOL 4.2). */
    fun current(): StateDelta = controller.current()

    override fun applySettings(settings: Settings) {
        carryOut(controller.applySettings(settings.audio))
    }

    override fun play(url: String, mediaType: MediaType, announce: Boolean, volume: Int?): CommandHandler.Reply {
        // a `/` path resolves against the HA base URL; an absolute URL plays as-is
        val origin = haUrl()
            ?.let { runCatching { java.net.URI(it) }.getOrNull()?.takeIf { uri -> uri.isAbsolute } }
            ?: return CommandHandler.Reply.Error(
                net.lockney.tabula.core.tlp.ErrorCode.FAILED,
                "no absolute HA URL stored; cannot resolve the media URL",
            )
        val resolved = DashboardLinks.resolvePlayUrl(url, origin)
            ?: return CommandHandler.Reply.Error(
                net.lockney.tabula.core.tlp.ErrorCode.INVALID_ARGS,
                "media URL '$url' is neither absolute http(s) without userinfo nor a / path",
            )
        carryOut(controller.play(resolved, volume))
        return CommandHandler.Reply.Ok()
    }

    override fun control(action: MediaControlAction, volume: Int?, position: Double?): CommandHandler.Reply {
        val result = controller.control(action, volume, position)
        return when (result) {
            is MediaController.ControlResult.Ok -> {
                carryOut(result.outcome)
                CommandHandler.Reply.Ok()
            }
            is MediaController.ControlResult.Failed ->
                CommandHandler.Reply.Error(net.lockney.tabula.core.tlp.ErrorCode.FAILED, result.message)
        }
    }

    /**
     * Release the player, cancel any load in flight, and drop its file. Takes
     * the same monitor as [carryOut] and [finishLoad], so a close racing a
     * load's completion can neither stop a player the manager still uses nor
     * leave a stopped one in `current`.
     */
    @Synchronized
    fun close() {
        generation.incrementAndGet()
        loadJob?.cancel()
        current?.stop()
        current = null
        currentFile?.delete()
        currentFile = null
    }

    @Synchronized
    private fun carryOut(outcome: MediaController.Outcome) {
        for (effect in outcome.effects) {
            when (effect) {
                is MediaController.Effect.Stop -> {
                    generation.incrementAndGet()
                    loadJob?.cancel()
                    current?.stop()
                    current = null
                    releaseFile() // the replaced playback's audio is nobody's now
                }
                is MediaController.Effect.Prepare -> {
                    val gen = generation.incrementAndGet()
                    loadJob = scope.launch { load(gen, effect.url) }
                }
                is MediaController.Effect.Start -> current?.start()
                is MediaController.Effect.Pause -> current?.pause()
                is MediaController.Effect.Resume -> current?.resume()
                is MediaController.Effect.Seek -> current?.seekTo(effect.positionS)
                is MediaController.Effect.SetVolume -> audio.setVolume(effect.volume)
                is MediaController.Effect.SetMuted -> audio.setMuted(effect.muted)
            }
        }
        for (transition in outcome.transitions) {
            val build: (Int) -> EventFrame = when (transition) {
                is MediaController.Transition.Finished ->
                    { id -> EventFrame.mediaFinished(id, transition.reason) }
            }
            eventQueue.trySend(build)
        }
        outcome.state?.takeIf { it.isNotEmpty }?.let { delta -> listener?.invoke(delta) }
    }

    /**
     * Fetch the audio and prepare it. [download] runs its own IO dispatching
     * (MediaFetcher in the app). The generation check before and after each
     * slow step drops the load when a newer command replaced it, and the
     * player it prepared is released - and its file deleted - rather than
     * left running.
     */
    private suspend fun load(gen: Int, url: String) {
        try {
            val file = download(url)
            if (generation.get() != gen) {
                file.delete() // replaced while it downloaded; nobody will play it
                return
            }
            finishLoad(gen, file)
        } catch (e: CancellationException) {
            throw e // the stop that cancelled this load already reported the transition
        } catch (e: Exception) {
            if (generation.get() == gen) {
                carryOut(controller.onError()) // the fetch failed: a terminal state (F-TTS-10)
            }
        }
    }

    @Synchronized
    private fun finishLoad(gen: Int, file: File) {
        if (generation.get() != gen) {
            file.delete()
            return
        }
        val prepared = try {
            player.prepare(file, onEnded = { onPlaybackEnded(gen) }, onError = { message -> onPlaybackError(gen, message) })
        } catch (e: Exception) {
            file.delete()
            carryOut(controller.onError()) // the audio could not be decoded (F-TTS-10)
            return
        }
        if (generation.get() != gen) {
            prepared.stop() // replaced while it decoded; the newer load owns the speaker
            file.delete()
            return
        }
        current = prepared
        currentFile = file
        carryOut(controller.onPrepared())
    }

    private fun onPlaybackEnded(gen: Int) {
        if (generation.get() == gen) {
            carryOut(controller.onEnded())
            releaseFile() // the playback is over; its audio is nobody's now
        }
    }

    private fun onPlaybackError(gen: Int, message: String) {
        if (generation.get() == gen) {
            carryOut(controller.onError())
            releaseFile()
        }
    }

    /** Delete the current playback's file (F-TTS-10: a terminal state releases it). */
    @Synchronized
    private fun releaseFile() {
        currentFile?.delete()
        currentFile = null
    }

    companion object {
        private const val FILE_PREFIX = "tabula-media"

        /**
         * Delete leftover media files at service start: a file survives a
         * crash or a process kill only when its playback never reached a
         * terminal state (F-TTS-10), so at most one per abrupt death.
         */
        fun sweepStale(dir: File) {
            dir.listFiles { file -> file.isFile && file.name.startsWith(FILE_PREFIX) }
                ?.forEach { it.delete() }
        }
    }
}
