package net.lockney.tabula.core.voice

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * The spike's 16 kHz feature frontend: 30 ms window, 10 ms hop, a 512-point
 * FFT and 40 mel bins between 125 Hz and 7500 Hz, quantised to the int8 range
 * the streaming model's input tensor declares.
 *
 * This is a cost proxy, not a re-implementation of the reference frontend that
 * ships with the model family. The reference adds spectral noise reduction and
 * per-channel amplitude normalisation; those are left out here so this code
 * stays readable and the measured cost is a lower bound for the whole frontend.
 * Detection accuracy is therefore measured on the host, with the reference
 * frontend, over clips recorded on the device (see tools/device/, P7.0
 * question 5). The ADR records the split.
 *
 * [feed] reuses one output array: a callback must copy it before storing it.
 */
class FeatureFrontend(
    private val sampleRate: Int = SAMPLE_RATE,
    private val windowSamples: Int = WINDOW_SAMPLES,
    private val hopSamples: Int = HOP_SAMPLES,
    fftSize: Int = FFT_SIZE,
    bins: Int = BINS,
    private val quantScale: Double = 0.10196078,
    private val quantZeroPoint: Int = -128,
) {
    private val fft = Fft(fftSize)
    private val spectrum = fftSize / 2 + 1
    private val window = DoubleArray(windowSamples) {
        0.5 - 0.5 * cos(2.0 * PI * it / (windowSamples - 1))
    }
    private val filters = melFilterbank(bins, fftSize, sampleRate, LOW_HZ, HIGH_HZ)
    private val re = DoubleArray(fftSize)
    private val im = DoubleArray(fftSize)
    private val power = DoubleArray(spectrum)
    private val energies = DoubleArray(bins)
    private val ring = ShortArray(windowSamples)
    private val frame = ByteArray(bins)
    private var filled = 0

    /** Frames produced since construction. */
    var frames: Long = 0
        private set

    /** Accepts [count] samples and calls [onFrame] once per completed hop. */
    fun feed(pcm: ShortArray, count: Int, onFrame: (ByteArray) -> Unit) {
        var i = 0
        while (i < count) {
            val room = windowSamples - filled
            val n = minOf(room, count - i)
            System.arraycopy(pcm, i, ring, filled, n)
            filled += n
            i += n
            if (filled == windowSamples) {
                computeInto(frame)
                frames++
                onFrame(frame)
                System.arraycopy(ring, hopSamples, ring, 0, windowSamples - hopSamples)
                filled = windowSamples - hopSamples
            }
        }
    }

    /** Drops the buffered audio, as if the microphone had just been opened. */
    fun reset() {
        filled = 0
        java.util.Arrays.fill(ring, 0)
    }

    private fun computeInto(out: ByteArray) {
        for (i in 0 until windowSamples) {
            re[i] = window[i] * ring[i]
            im[i] = 0.0
        }
        for (i in windowSamples until fft.size) {
            re[i] = 0.0
            im[i] = 0.0
        }
        fft.transform(re, im)
        for (k in 0 until spectrum) {
            power[k] = re[k] * re[k] + im[k] * im[k]
        }
        var loudest = Double.NEGATIVE_INFINITY
        for (b in energies.indices) {
            var sum = 0.0
            val filter = filters[b]
            for (k in 0 until spectrum) sum += filter[k] * power[k]
            val db = 10.0 * log10(sum + 1e-10)
            energies[b] = db
            if (db > loudest) loudest = db
        }
        // Per-frame normalisation puts the loudest bin at the top of the
        // model's input range and 20 dB below it at the bottom, then stretches
        // that onto the range the input tensor declares: the reference
        // frontend's raw features divided by 25.6 land between 0 and about 26,
        // and scale 0.10196 with zero point -128 covers exactly 0 to 26. A
        // frontend that emitted 0 to 1 would use ten of the 256 levels.
        for (b in out.indices) {
            val norm = ((energies[b] - loudest + DYNAMIC_RANGE_DB) / DYNAMIC_RANGE_DB)
                .coerceIn(0.0, 1.0) * TOP_VALUE
            val q = (norm / quantScale).roundToInt() + quantZeroPoint
            out[b] = q.coerceIn(-128, 127).toByte()
        }
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val WINDOW_SAMPLES = 480 // 30 ms
        const val HOP_SAMPLES = 160 // 10 ms
        const val FFT_SIZE = 512
        const val BINS = 40
        const val LOW_HZ = 125.0
        const val HIGH_HZ = 7500.0
        const val DYNAMIC_RANGE_DB = 20.0

        /** Top of the input tensor's real range: 255 steps of 0.10196. */
        const val TOP_VALUE = 26.0

        /** Slaney mel scale, as TensorFlow's mel matrix uses below 1 kHz. */
        fun hzToMel(hz: Double): Double =
            if (hz < 1000.0) 3.0 * hz / 200.0 else 15.0 + 27.0 * ln(hz / 1000.0) / ln(6.4)

        fun melToHz(mel: Double): Double =
            if (mel < 15.0) 200.0 * mel / 3.0 else 1000.0 * exp((mel - 15.0) * ln(6.4) / 27.0)

        private fun melFilterbank(
            bins: Int,
            fftSize: Int,
            sampleRate: Int,
            lowHz: Double,
            highHz: Double,
        ): Array<DoubleArray> {
            val spectrum = fftSize / 2 + 1
            val melLow = hzToMel(lowHz)
            val melHigh = hzToMel(highHz)
            val edges = DoubleArray(bins + 2) {
                melToHz(melLow + (melHigh - melLow) * it / (bins + 1))
            }
            return Array(bins) { b ->
                val lo = edges[b]
                val mid = edges[b + 1]
                val hi = edges[b + 2]
                DoubleArray(spectrum) { k ->
                    val f = k.toDouble() * sampleRate / fftSize
                    when {
                        f in lo..mid && mid > lo -> (f - lo) / (mid - lo)
                        f in mid..hi && hi > mid -> (hi - f) / (hi - mid)
                        else -> 0.0
                    }
                }
            }
        }
    }
}
