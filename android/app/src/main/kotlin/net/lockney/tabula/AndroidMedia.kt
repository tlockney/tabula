package net.lockney.tabula

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import java.io.File
import kotlin.math.roundToInt

/**
 * The Android side of notifications and audio (packet P2.5): the music
 * stream as the media channel (F-TTS-07), [MediaPlayer] on a downloaded file
 * as the player, and a synthesized notification chime (F-NTF-09) on the
 * notification stream. Everything behind the interfaces [MediaManager]
 * consumes, so its logic is unit-tested on a plain JVM.
 */

/** The media channel (PROTOCOL 4.4 `volume`/`muted`) on the OS music stream. */
class AndroidAudioVolume(context: Context) : AudioVolume {
    private val manager = context.getSystemService(AudioManager::class.java)

    override fun setVolume(percent: Int) {
        val max = manager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: return
        manager.setStreamVolume(AudioManager.STREAM_MUSIC, (percent * max + 50) / 100, 0)
    }

    override fun setMuted(muted: Boolean) {
        manager?.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (muted) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
            0,
        )
    }
}

/** [MediaPlayerHandle] over [MediaPlayer]; one player per prepared file. */
object AndroidPlayer : MediaPlayerHandle {
    override fun prepare(file: File, onEnded: () -> Unit, onError: (String) -> Unit): MediaPlayerHandle.Prepared {
        val mediaPlayer = MediaPlayer()
        mediaPlayer.setOnCompletionListener { onEnded() }
        mediaPlayer.setOnErrorListener { _, what, extra ->
            onError("media player error $what/$extra")
            true // handled: the manager reports it as media_finished, not to the logcat error queue
        }
        mediaPlayer.setDataSource(file.absolutePath)
        mediaPlayer.prepare() // a local file: fast, and a decode failure throws (F-TTS-10)
        return object : MediaPlayerHandle.Prepared {
            override fun start() {
                if (mediaPlayer.isPlaying.not()) runCatching { mediaPlayer.start() }
            }

            override fun pause() {
                runCatching { mediaPlayer.pause() }
            }

            override fun resume() {
                if (mediaPlayer.isPlaying.not()) runCatching { mediaPlayer.start() }
            }

            override fun seekTo(seconds: Double) {
                runCatching { mediaPlayer.seekTo((seconds * 1000).roundToInt()) }
            }

            override fun stop() {
                runCatching { mediaPlayer.stop() }
                mediaPlayer.release()
            }
        }
    }
}

/**
 * The card chime (F-NTF-09): a short synthesized beep on the notification
 * stream, so it follows the OS notification volume. The sound library and a
 * per-device notification level (F-NTF-10) have no settings-schema fields or
 * ADR 0002 defaults yet and are deferred; the OS stream level is the volume.
 */
class NotificationChime {
    private var tone: ToneGenerator? = null

    fun play() {
        runCatching {
            (tone ?: ToneGenerator(AudioManager.STREAM_NOTIFICATION, 100).also { tone = it })
                .startTone(ToneGenerator.TONE_PROP_BEEP)
        }
    }

    fun release() {
        runCatching { tone?.release() }
        tone = null
    }
}
