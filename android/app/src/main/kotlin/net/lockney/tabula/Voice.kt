package net.lockney.tabula

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.lockney.tabula.core.voice.WakeWordDetector
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter

/**
 * Wake-word listening inside the foreground service (P7.1, ADR 0030).
 *
 * Off unless `voice.wake_word_enabled` is on, and dependent on RECORD_AUDIO
 * being granted: the app asks once when the setting is first turned on, and a
 * device that says no keeps working, with the capability undeclared and
 * [WakeWordMonitor.onListening] false, so HA can show that it is not listening.
 *
 * The audio path is the spike's (P7.0, ADR 0029): 16 kHz mono 16-bit from
 * VOICE_RECOGNITION, no echo canceller, no noise suppressor, the same 100 ms
 * read size. The model and the feature frontend are the core module's
 * (net.lockney.tabula.core.voice), which is what the unit tests exercise.
 */
class WakeWordMonitor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val onDetection: (WakeWordDetector.Detection) -> Unit,
    private val onListening: (Boolean) -> Unit,
    private val log: (String) -> Unit = { Log.i(TAG, it) },
) {
    private var job: Job? = null
    private var record: AudioRecord? = null

    val running: Boolean get() = job != null

    /** True when the app holds the microphone permission the detector needs. */
    fun permitted(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Opens the microphone and starts scoring. Does nothing when already
     * running or when the permission is missing; a failed open reports false
     * rather than throwing, so a tablet whose microphone is busy keeps its
     * other functions.
     */
    fun start() {
        if (job != null) return
        if (!permitted()) {
            log("wake word: RECORD_AUDIO not granted; not listening")
            onListening(false)
            return
        }
        job = scope.launch(Dispatchers.Default) { listen() }
    }

    fun stop() {
        job?.cancel()
        job = null
        record?.let { runCatching { it.stop() } }
        record?.release()
        record = null
        onListening(false)
    }

    private suspend fun listen() {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            log("wake word: getMinBufferSize=$minBuffer")
            onListening(false)
            job = null
            return
        }
        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer * 4,
            )
        } catch (e: Exception) {
            log("wake word: AudioRecord failed: $e")
            onListening(false)
            job = null
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            log("wake word: AudioRecord state=${rec.state}")
            rec.release()
            onListening(false)
            job = null
            return
        }
        val detector = try {
            WakeWordDetector(
                scorer = openModel(),
                onDetection = onDetection,
            )
        } catch (e: Exception) {
            log("wake word: model did not load: $e")
            rec.release()
            onListening(false)
            job = null
            return
        }
        record = rec
        try {
            rec.startRecording()
            onListening(true)
            log("wake word: listening at ${rec.sampleRate} Hz")
            val chunk = ShortArray(CHUNK_SAMPLES)
            while (currentCoroutineContext().isActive) {
                val read = rec.read(chunk, 0, chunk.size)
                if (read <= 0) {
                    if (read == AudioRecord.ERROR_INVALID_OPERATION ||
                        read == AudioRecord.ERROR_BAD_VALUE
                    ) {
                        log("wake word: read failed ($read); stopping")
                        break
                    }
                    continue
                }
                detector.feed(chunk, read)
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
            detector.close()
            record = null
            job = null
            onListening(false)
        }
    }

    /** Opens the pinned model from the app's assets. */
    private fun openModel(): WakeWordDetector.Scorer {
        val bytes = context.assets.open(MODEL_ASSET).use { it.readBytes() }
        val buffer = ByteBuffer.allocateDirect(bytes.size)
        buffer.put(bytes)
        buffer.rewind()
        return TfliteScorer(buffer)
    }

    companion object {
        private const val TAG = "TabulaWakeWord"

        /** The pinned microWakeWord v2 model, committed as an asset (ADR 0030). */
        const val MODEL_ASSET = "okay_nabu.tflite"

        /** Its sha256, asserted by the unit test that guards the asset. */
        const val MODEL_SHA256 =
            "0689abe1912a95a3318a0d8cb2e67bad0cbcfe3e24dd6e050c75debddfb6f891"

        const val SAMPLE_RATE = 16000

        /** 100 ms per read: ten feature rows, the chunk the spike measured. */
        const val CHUNK_SAMPLES = 1600
    }
}

/** The int8 streaming model behind [WakeWordDetector.Scorer]. */
class TfliteScorer(flatModel: ByteBuffer, threads: Int = 1) : WakeWordDetector.Scorer {
    private val interpreter = Interpreter(flatModel, Interpreter.Options().apply { numThreads = threads })
    private val rows: Int
    private val bins: Int
    private val input: ByteBuffer
    private val output: ByteBuffer

    init {
        interpreter.allocateTensors()
        val tensor = interpreter.getInputTensor(0)
        require(tensor.dataType() == DataType.INT8) { "model input is ${tensor.dataType()}" }
        val shape = tensor.shape()
        require(shape.size == 3 && shape[0] == 1) { "model input shape is ${shape.toList()}" }
        rows = shape[1]
        bins = shape[2]
        input = ByteBuffer.allocateDirect(tensor.numBytes()).order(ByteOrder.nativeOrder())
        output = ByteBuffer.allocateDirect(interpreter.getOutputTensor(0).numBytes())
            .order(ByteOrder.nativeOrder())
    }

    override fun rows(): Int = rows

    override fun bins(): Int = bins

    override fun score(window: ByteArray): Int {
        require(window.size == rows * bins) {
            "expected ${rows * bins} feature bytes, got ${window.size}"
        }
        input.rewind()
        input.put(window)
        input.rewind()
        output.rewind()
        interpreter.run(input, output)
        output.rewind()
        return output.get().toInt() and 0xff
    }

    override fun close() {
        interpreter.close()
    }
}

/** RFC 3339 UTC to the second, the form state.last_wake_word takes (PROTOCOL 4.4). */
fun wakeWordTimestamp(clock: Instant = Instant.now()): String =
    clock.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString()
