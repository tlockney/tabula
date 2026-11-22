package net.lockney.tabula.spike.intercom

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * The spike's audio probe: a 1 kHz tone played out of the device's speaker, and
 * a recorder on the other device that hears its own speaker through the
 * air (packet questions 3 and 7).
 *
 * The recorder measures the tone's onset in its own elapsedRealtime, so a
 * mouth-to-ear figure can be computed from the two devices' clocks once each is
 * anchored to the relay's clock (question 7's clock sync). It is deliberately
 * NOT the WebRTC capture path: recording what the speaker actually emits is
 * what a person at that end hears.
 */
object AudioProbe {
    private const val TAG = "TabulaIntercomSpike"
    const val RATE = 16000

    /** Plays a 1 kHz tone at [amplitude] (0..1) for [seconds]. Returns its start instant. */
    fun playTone(amplitude: Double, seconds: Double): Long {
        val frames = (RATE * seconds).toInt()
        val pcm = ShortArray(frames)
        for (i in 0 until frames) {
            pcm[i] = (sin(2.0 * PI * 1000.0 * i / RATE) * Short.MAX_VALUE * amplitude).toInt().toShort()
        }
        val minBuf = AudioTrack.getMinBufferSize(
            RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        // Voice-communication usage, not media: during a call the device is in
        // MODE_IN_COMMUNICATION, where the media stream is not what reaches the
        // speaker (a plain STREAM_MUSIC tone is nearly inaudible there). This is
        // the stream the call itself plays on, so the tone is as loud as the far
        // end would be.
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(maxOf(minBuf * 2, frames * 2))
            .build()
        val start = SystemClock.elapsedRealtime()
        try {
            track.play()
            track.write(pcm, 0, frames)
        } catch (e: Exception) {
            Log.w(TAG, "tone playback failed: ${e.message}")
        } finally {
            try {
                track.stop()
            } catch (_: Exception) {
            }
            track.release()
        }
        Log.i(TAG, "spike: tone-start elapsedMs=$start amplitude=$amplitude durationMs=${(seconds * 1000).toInt()}")
        return start
    }

    /**
     * Records [seconds] of 16 kHz mono into [file] (WAV) and logs the first tone
     * onset it hears, in elapsedRealtime. Runs on its own thread.
     *
     * Returns the recorder, or null when the microphone cannot be opened (the
     * listen-only case, or RECORD_AUDIO revoked: packet question 5).
     */
    fun startRecorder(file: File, seconds: Int): Recorder? {
        val minBuf = AudioRecord.getMinBufferSize(
            RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuf <= 0) {
            Log.w(TAG, "spike: recorder unavailable (no buffer size)")
            return null
        }
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuf * 4,
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "spike: recorder refused by the OS: ${e.message}")
            return null
        } catch (e: Exception) {
            Log.w(TAG, "spike: recorder could not be created: ${e.message}")
            return null
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.w(TAG, "spike: recorder not initialised (state=${record.state})")
            record.release()
            return null
        }
        val recorder = Recorder(record, file, seconds)
        recorder.start()
        Log.i(TAG, "spike: recording ${file.absolutePath} for ${seconds}s")
        return recorder
    }

    /** One recording run: PCM to a WAV file, plus the tone onset it detects. */
    class Recorder(
        private val record: AudioRecord,
        private val file: File,
        private val seconds: Int,
    ) {
        private val thread = Thread({ run() }, "spike-recorder")

        @Volatile
        var onsetElapsedMs: Long = -1

        /** Starts the recording thread; [startRecorder] returns after this. */
        fun start() {
            thread.start()
        }

        private fun run() {
            val frame = 320 // 20 ms at 16 kHz
            val buf = ShortArray(frame)
            val pcm = ArrayList<Short>(RATE * seconds)
            var startedAt = 0L
            var quietFrames = 0
            var onset = -1L
            var onsetRms = 0.0
            try {
                record.startRecording()
                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    Log.w(TAG, "spike: recorder did not start; the microphone is likely held by another client")
                }
                startedAt = SystemClock.elapsedRealtime()
                while (SystemClock.elapsedRealtime() - startedAt < seconds * 1000L) {
                    val n = record.read(buf, 0, frame)
                    if (n <= 0) break
                    var sum = 0.0
                    for (i in 0 until n) {
                        sum += buf[i].toDouble() * buf[i]
                        pcm.add(buf[i])
                    }
                    val rms = Math.sqrt(sum / n)
                    // Onset: a frame at least 4x the recent quiet level, above the noise floor.
                    if (onset < 0) {
                        if (rms < 220.0) {
                            quietFrames++
                        } else if (quietFrames >= 5 && rms > 900.0) {
                            onset = SystemClock.elapsedRealtime() - (n.toLong() * 1000 / RATE)
                            onsetRms = rms
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "spike: recorder stopped: ${e.message}")
            } finally {
                try {
                    record.stop()
                } catch (_: Exception) {
                }
                record.release()
                writeWav(pcm)
                onsetElapsedMs = onset
                if (onset > 0) {
                    Log.i(TAG, "spike: tone-onset elapsedMs=$onset rms=${onsetRms.toInt()} file=${file.absolutePath}")
                } else {
                    Log.i(TAG, "spike: tone-onset none (no tone above the noise floor) file=${file.absolutePath}")
                }
                Log.i(TAG, "spike: recorded samples=${pcm.size} seconds=${pcm.size / RATE}")
            }
        }

        private fun writeWav(pcm: List<Short>) {
            try {
                file.parentFile?.mkdirs()
                FileOutputStream(file).use { out ->
                    val dataLen = pcm.size * 2
                    out.write(wavHeader(dataLen))
                    val bytes = ByteArray(dataLen)
                    for (i in pcm.indices) {
                        val v = pcm[i].toInt()
                        bytes[i * 2] = (v and 0xff).toByte()
                        bytes[i * 2 + 1] = ((v shr 8) and 0xff).toByte()
                    }
                    out.write(bytes)
                }
            } catch (e: Exception) {
                Log.w(TAG, "spike: could not write ${file.absolutePath}: ${e.message}")
            }
        }

        /** A 44-byte canonical WAV header; written as bytes so values above 127 survive. */
        private fun wavHeader(dataLen: Int): ByteArray {
            val channels = 1
            val byteRate = RATE * channels * 2
            val header = ByteArray(44)
            fun put(offset: Int, text: String) {
                for (i in text.indices) header[offset + i] = text[i].code.toByte()
            }

            fun le32(offset: Int, v: Int) {
                header[offset] = (v and 0xff).toByte()
                header[offset + 1] = ((v shr 8) and 0xff).toByte()
                header[offset + 2] = ((v shr 16) and 0xff).toByte()
                header[offset + 3] = ((v shr 24) and 0xff).toByte()
            }

            fun le16(offset: Int, v: Int) {
                header[offset] = (v and 0xff).toByte()
                header[offset + 1] = ((v shr 8) and 0xff).toByte()
            }

            put(0, "RIFF")
            le32(4, 36 + dataLen)
            put(8, "WAVEfmt ")
            le32(16, 16)
            le16(20, 1) // PCM
            le16(22, channels)
            le32(24, RATE)
            le32(28, byteRate)
            le16(32, channels * 2)
            le16(34, 16)
            put(36, "data")
            le32(40, dataLen)
            return header
        }
    }

}
