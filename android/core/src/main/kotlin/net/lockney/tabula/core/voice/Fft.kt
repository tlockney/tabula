package net.lockney.tabula.core.voice

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * In-place iterative radix-2 complex FFT. Pure Kotlin so the frontend's cost
 * and its output can be checked on a plain JVM.
 *
 * The transform is the standard decimation-in-time Cooley-Tukey butterfly with
 * a bit-reversal permutation and precomputed twiddles; [size] must be a power
 * of two.
 */
class Fft(val size: Int) {
    init {
        require(size > 1 && size and (size - 1) == 0) { "size must be a power of two: $size" }
    }

    private val levels = Integer.numberOfTrailingZeros(size)
    private val reversed = IntArray(size) { Integer.reverse(it) ushr (32 - levels) }
    private val cosTable = DoubleArray(size / 2) { cos(2.0 * PI * it / size) }
    private val sinTable = DoubleArray(size / 2) { sin(2.0 * PI * it / size) }

    /** Transforms [re]/[im] in place; [re] and [im] must both be [size] long. */
    fun transform(re: DoubleArray, im: DoubleArray) {
        require(re.size == size && im.size == size) { "arrays must be $size long" }
        for (i in 0 until size) {
            val j = reversed[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var span = 2
        while (span <= size) {
            val half = span / 2
            val step = size / span
            var start = 0
            while (start < size) {
                for (k in 0 until half) {
                    val twiddle = k * step
                    val wr = cosTable[twiddle]
                    val wi = -sinTable[twiddle]
                    val a = start + k
                    val b = a + half
                    val tr = re[b] * wr - im[b] * wi
                    val ti = re[b] * wi + im[b] * wr
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                }
                start += span
            }
            span *= 2
        }
    }
}
