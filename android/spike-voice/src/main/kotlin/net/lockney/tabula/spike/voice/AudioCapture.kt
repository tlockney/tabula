package net.lockney.tabula.spike.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * One AudioRecord at 16 kHz, 16-bit, mono, optionally with the platform's echo
 * canceller and noise suppressor attached to its session.
 *
 * Everything the platform decides for itself — the sample rate, channel count
 * and format it actually opened with — is readable through [actualSampleRate]
 * and friends after [open], because a 16 kHz request is only a request.
 */
class Capture(
    private val source: Int,
    private val sampleRate: Int = 16000,
    private val channelConfig: Int = AudioFormat.CHANNEL_IN_MONO,
    private val audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT,
    private val echoCancel: Boolean = false,
    private val noiseSuppress: Boolean = false,
) {
    var record: AudioRecord? = null
        private set
    var openError: String? = null
        private set

    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null

    val actualSampleRate: Int get() = record?.sampleRate ?: -1
    val actualChannels: Int get() = record?.channelCount ?: -1
    val actualFormat: Int get() = record?.audioFormat ?: -1
    val sessionId: Int get() = record?.audioSessionId ?: -1
    val echoCancellerEnabled: Boolean get() = aec?.enabled ?: false
    val noiseSuppressorEnabled: Boolean get() = ns?.enabled ?: false

    @Suppress("DEPRECATION")
    fun open(): Boolean {
        val minBuffer = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (minBuffer <= 0) {
            openError = "getMinBufferSize=$minBuffer"
            return false
        }
        val rec = try {
            AudioRecord(source, sampleRate, channelConfig, audioFormat, minBuffer * 4)
        } catch (e: Exception) {
            openError = e.toString()
            return false
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            openError = "state=${rec.state}"
            rec.release()
            return false
        }
        record = rec
        if (echoCancel) {
            aec = try {
                AcousticEchoCanceler.create(rec.audioSessionId)?.also { it.enabled = true }
            } catch (e: Exception) {
                Log.w(SPIKE_TAG, "SPIKE aec create failed: $e")
                null
            }
        }
        if (noiseSuppress) {
            ns = try {
                NoiseSuppressor.create(rec.audioSessionId)?.also { it.enabled = true }
            } catch (e: Exception) {
                Log.w(SPIKE_TAG, "SPIKE ns create failed: $e")
                null
            }
        }
        return true
    }

    fun start() {
        record?.startRecording()
    }

    fun read(buffer: ShortArray): Int = record?.read(buffer, 0, buffer.size) ?: -1

    fun close() {
        try {
            aec?.release()
        } catch (_: Exception) {
        }
        try {
            ns?.release()
        } catch (_: Exception) {
        }
        try {
            record?.stop()
        } catch (_: Exception) {
        }
        try {
            record?.release()
        } catch (_: Exception) {
        }
        aec = null
        ns = null
        record = null
    }

    fun describe(): String =
        "source=$source sr=$actualSampleRate ch=$actualChannels fmt=$actualFormat " +
            "session=$sessionId aec=$echoCancellerEnabled ns=$noiseSuppressorEnabled"
}

/** Collects [capacitySamples] of 16-bit mono audio on its own thread. */
class Recorder(private val capture: Capture, capacitySamples: Int) {
    val pcm = ShortArray(capacitySamples)

    @Volatile
    var written = 0
        private set

    @Volatile
    private var running = false
    private var thread: Thread? = null

    fun start() {
        capture.start()
        running = true
        thread = Thread({
            val buffer = ShortArray(1600)
            while (running && written < pcm.size) {
                val n = capture.read(buffer)
                if (n > 0) {
                    val room = pcm.size - written
                    val copy = minOf(n, room)
                    System.arraycopy(buffer, 0, pcm, written, copy)
                    written += copy
                }
            }
        }, "spike-recorder")
        thread?.start()
    }

    fun stop() {
        running = false
        thread?.join(2000)
        thread = null
    }
}

/**
 * Plays a generated clip on the media stream, so the spike can measure what the
 * microphone hears while the device talks — the echo-cancellation test, and the
 * "wake word over the device's own playback" case.
 */
class ClipPlayer(private val sampleRate: Int = 16000) {
    private var track: AudioTrack? = null
    private var thread: Thread? = null

    fun play(pcm: ShortArray) {
        val bufferBytes = pcm.size * 2
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(bufferBytes)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track = t
        t.write(pcm, 0, pcm.size)
        t.play()
        thread = Thread({
            val started = System.nanoTime()
            val durationMs = pcm.size * 1000L / sampleRate
            while ((System.nanoTime() - started) / 1_000_000 < durationMs) Thread.sleep(20)
            try {
                t.stop()
            } catch (_: Exception) {
            }
            t.release()
        }, "spike-player")
        thread?.start()
    }

    fun stop() {
        thread?.join(5000)
        thread = null
        track = null
    }

    companion object {
        /**
         * A deterministic 3 s speech-band clip: three tones around the speech
         * range, amplitude modulated at 4 Hz so it has the syllabic rhythm of
         * speech. Its exact content does not matter for an echo measurement;
         * what matters is that the device produces real acoustic output that
         * the microphone can hear.
         */
        fun speechBandClip(seconds: Int = 3, sampleRate: Int = 16000): ShortArray {
            val total = seconds * sampleRate
            val out = ShortArray(total)
            val tones = doubleArrayOf(520.0, 1040.0, 2080.0)
            val random = Random(7)
            for (i in 0 until total) {
                val t = i.toDouble() / sampleRate
                val envelope = 0.5 + 0.5 * sin(2.0 * PI * 4.0 * t)
                var sample = 0.0
                for ((index, hz) in tones.withIndex()) {
                    sample += sin(2.0 * PI * hz * t + index) / tones.size
                }
                // a little broadband content, as a voice has
                sample += (random.nextDouble() - 0.5) * 0.15
                out[i] = (sample * envelope * 12000.0).toInt().coerceIn(-32768, 32767).toShort()
            }
            return out
        }
    }
}
