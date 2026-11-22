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
 * The card chime (F-NTF-09): a short synthesized beep on the media stream
 * (ADR 0023), the stream TTS uses, so the tablet's silent mode and
 * notification volume do not mute it. Its loudness is `audio.chime_volume`,
 * a share of the media volume ([volume], read at each chime). The generator
 * is kept between chimes and rebuilt only when the level changes; 0 plays
 * nothing. The sound library (F-NTF-10) is deferred.
 */
class NotificationChime(
    private val volume: () -> Int,
    private val newTone: (stream: Int, volume: Int) -> Tone = { stream, level -> AndroidTone(stream, level) },
) {
    /** One synthesized beep source; [AndroidTone] on the device. */
    interface Tone {
        fun beep()

        fun release()
    }

    private var tone: Tone? = null
    private var toneVolume = -1

    @Synchronized
    fun play() {
        val level = volume().coerceIn(0, 100)
        if (level == 0) return
        runCatching {
            if (tone == null || toneVolume != level) {
                tone?.release()
                tone = newTone(STREAM, level)
                toneVolume = level
            }
            tone?.beep()
        }
    }

    @Synchronized
    fun release() {
        runCatching { tone?.release() }
        tone = null
        toneVolume = -1
    }

    companion object {
        /** The media stream (ADR 0023): the ringer mode does not mute it. */
        const val STREAM = AudioManager.STREAM_MUSIC
    }
}

/** A [ToneGenerator] beep at [volume] percent of [stream]'s level. */
private class AndroidTone(stream: Int, volume: Int) : NotificationChime.Tone {
    private val generator = ToneGenerator(stream, volume)

    override fun beep() {
        generator.startTone(ToneGenerator.TONE_PROP_BEEP)
    }

    override fun release() {
        generator.release()
    }
}
