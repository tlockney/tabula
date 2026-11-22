package net.lockney.tabula.spike.voice

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Level arithmetic for the spike's measurements, kept free of the Android
 * framework so it can be unit tested on a plain JVM.
 *
 * All levels are dBFS: a full-scale 16-bit sample (32767) is 0 dB, and a
 * silent buffer floors at -120 dB rather than -infinity, so a table stays
 * printable.
 */
object Levels {
    const val FULL_SCALE = 32768.0
    const val FLOOR_DB = -120.0

    fun rms(pcm: ShortArray, count: Int = pcm.size): Double {
        if (count <= 0) return 0.0
        var sum = 0.0
        for (i in 0 until count) {
            val v = pcm[i].toDouble()
            sum += v * v
        }
        return sqrt(sum / count)
    }

    fun peak(pcm: ShortArray, count: Int = pcm.size): Int {
        var peak = 0
        for (i in 0 until count) {
            val v = kotlin.math.abs(pcm[i].toInt())
            if (v > peak) peak = v
        }
        return peak
    }

    fun dbfs(amplitude: Double): Double =
        if (amplitude <= 0.0) FLOOR_DB else maxOf(FLOOR_DB, 20.0 * log10(amplitude / FULL_SCALE))

    fun rmsDb(pcm: ShortArray, count: Int = pcm.size): Double = dbfs(rms(pcm, count))

    /** Fraction of samples above a fixed amplitude: a speech-versus-silence check. */
    fun activeFraction(pcm: ShortArray, count: Int = pcm.size, threshold: Int = 500): Double {
        if (count <= 0) return 0.0
        var active = 0
        for (i in 0 until count) if (kotlin.math.abs(pcm[i].toInt()) >= threshold) active++
        return active.toDouble() / count
    }

    /**
     * Echo return loss enhancement: how much a canceller removed from what the
     * microphone heard, in dB. `offDb` is the echo measured with the canceller
     * disabled, `onDb` with it enabled, both over the same playback window.
     * Positive means the canceller helped; negative means it made things worse.
     */
    fun erleDb(offDb: Double, onDb: Double): Double = offDb - onDb
}
