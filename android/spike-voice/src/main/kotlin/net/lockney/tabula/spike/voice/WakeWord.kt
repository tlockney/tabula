package net.lockney.tabula.spike.voice

import java.io.Closeable
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter

/** The logcat tag every spike line carries, so a run is one `logcat -s`. */
const val SPIKE_TAG = "TabulaVoiceSpike"

/**
 * Runs a streaming wake-word model (microWakeWord's int8 TFLite form) on the
 * device.
 *
 * The model keeps its own state in variable tensors, so one [WakeWord] runs a
 * continuous stream: each [probability] call feeds the next slice of features
 * and the recursion inside the model advances by itself. The output is the
 * model's quantised probability, 0-255.
 *
 * The model file is never committed; tools/device/check_p70.sh fetches it into
 * build/ and pushes it, and the ADR records its source, sha256 and license.
 */
class WakeWord(model: File, threads: Int = 1) : Closeable {
    private val interpreter: Interpreter
    private val input: ByteBuffer
    private val output: ByteBuffer
    private val rows: Int
    private val features: Int

    val describe: String

    init {
        require(model.isFile) { "no model at $model" }
        interpreter = Interpreter(model, Interpreter.Options().apply { numThreads = threads })
        interpreter.allocateTensors()
        val inTensor = interpreter.getInputTensor(0)
        require(inTensor.dataType() == DataType.INT8) { "model input is ${inTensor.dataType()}" }
        val shape = inTensor.shape()
        require(shape.size == 3 && shape[0] == 1) { "model input shape is ${shape.toList()}" }
        rows = shape[1]
        features = shape[2]
        input = ByteBuffer.allocateDirect(inTensor.numBytes()).order(ByteOrder.nativeOrder())
        output = ByteBuffer.allocateDirect(interpreter.getOutputTensor(0).numBytes())
            .order(ByteOrder.nativeOrder())
        describe = "rows=$rows features=$features input_bytes=${inTensor.numBytes()} " +
            "output_type=${interpreter.getOutputTensor(0).dataType()} threads=$threads"
    }

    /** Feature rows the model expects per inference (30 ms of audio at v2). */
    fun rowsPerInference(): Int = rows

    /** Feature bins per row. */
    fun binsPerRow(): Int = features

    /** Feeds one inference's worth of features; returns the probability, 0-255. */
    fun probability(features: ByteArray): Int {
        require(features.size == rows * this.features) {
            "expected ${rows * this.features} feature bytes, got ${features.size}"
        }
        input.rewind()
        input.put(features)
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
