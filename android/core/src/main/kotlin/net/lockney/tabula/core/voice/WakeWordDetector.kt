package net.lockney.tabula.core.voice

import java.io.Closeable

/**
 * The streaming wake-word detector (P7.1, ADR 0030).
 *
 * Audio goes in as 16 kHz mono PCM; the [FeatureFrontend] turns it into 40-bin
 * int8 rows, the rows slide through a window of whatever size the model reads,
 * and a [Scorer] (the pinned TFLite model on the device, a stub in tests) gives
 * one probability per window. A probability at or above [cutoff] is a
 * detection, unless the last one was less than [cooldownFrames] frames ago: the
 * model's own recursion keeps a phrase above the cutoff for a few windows, and
 * the app must not report the same phrase four times.
 *
 * Everything here is plain Kotlin on purpose. The interpreter lives behind
 * [Scorer], so this class is tested on a JVM with recorded PCM and a fixed
 * score sequence, without a model file or an Android device.
 */
class WakeWordDetector(
    private val scorer: Scorer,
    private val phrase: String = WAKE_WORD_PHRASE,
    private val cutoff: Int = DETECTION_CUTOFF,
    private val cooldownFrames: Int = COOLDOWN_FRAMES,
    private val onDetection: (Detection) -> Unit,
) : Closeable {
    /** One accepted detection: what was heard and how sure the model was. */
    data class Detection(val phrase: String, val probability: Int)

    /** The model, reduced to what the detector needs. */
    interface Scorer : Closeable {
        /** Feature rows per inference (3 for the v2 models, i.e. 30 ms). */
        fun rows(): Int

        /** Feature bins per row (40 for the v2 models). */
        fun bins(): Int

        /** Feeds one window of [rows] x [bins] int8 features; returns 0-255. */
        fun score(window: ByteArray): Int
    }

    private val frontend = FeatureFrontend()
    private val rows = scorer.rows()
    private val bins = scorer.bins()
    private val window = ArrayDeque<ByteArray>(rows + 1)
    private val flat = ByteArray(rows * bins)

    /** Windows scored since construction (diagnostics and the cost table). */
    var inferences: Long = 0
        private set

    /** Detections accepted since construction. */
    var detections: Long = 0
        private set

    /**
     * Feeds [count] samples of a 16 kHz mono buffer.
     *
     * [onDetection] runs on the calling thread, once per accepted detection, so
     * the caller decides how to leave the audio callback (the app posts to its
     * own dispatcher). Nothing here allocates beyond the window copy.
     */
    fun feed(pcm: ShortArray, count: Int = pcm.size) {
        require(count <= pcm.size) { "count $count exceeds buffer ${pcm.size}" }
        frontend.feed(pcm, count) { row -> accumulate(row) }
    }

    /** Forgets the audio and the window, as if the microphone had just opened. */
    fun reset() {
        frontend.reset()
        window.clear()
        lastDetectionAt = -1
    }

    override fun close() {
        scorer.close()
    }

    private fun accumulate(row: ByteArray) {
        window.addLast(row.copyOf())
        if (window.size < rows) return
        scoreWindow()
        // Consume the window rather than sliding it by a row: the model reads
        // three rows per inference and fires only on that phase (the desk
        // harness scores a positive clip at 1.0 with this stride and at 0.02
        // with overlapping windows), and it is also what the spike measured
        // 6.0% of a core for (ADR 0029).
        window.clear()
    }

    private fun scoreWindow() {
        var offset = 0
        for (row in window) {
            System.arraycopy(row, 0, flat, offset, bins)
            offset += bins
        }
        val probability = scorer.score(flat)
        inferences++
        if (probability < cutoff) return
        // One phrase is one detection: the recursion stays above the cutoff for
        // a few windows, and the cooldown swallows them.
        val last = lastDetectionAt
        if (last >= 0 && inferences - last <= cooldownFrames) return
        lastDetectionAt = inferences
        detections++
        onDetection(Detection(phrase, probability))
    }

    private var lastDetectionAt: Long = -1

    companion object {
        /** The phrase the pinned model was trained on (ADR 0002). */
        const val WAKE_WORD_PHRASE = "okay_nabu"

        /** The model's probability at or above which a window counts, 0-255. */
        const val DETECTION_CUTOFF = 200

        /**
         * Inferences to ignore after a detection: the detector infers once per
         * 30 ms window, so 133 windows is the 4 s of ADR 0002.
         */
        const val COOLDOWN_FRAMES = 133
    }
}
