package net.lockney.tabula.core.screen

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt
import net.lockney.tabula.core.Constants

/** One curve handle: at [lux], the panel sits at [percent]. */
data class CurvePoint(val lux: Double, val percent: Int)

/**
 * Maps room light to panel brightness (FEATURES F-SCR-05, F-SCR-06).
 *
 * - The light axis is logarithmic: the curve is interpolated over log10(lux).
 * - Between points it is a monotone cubic (Fritsch-Carlson), so it is smooth
 *   and never overshoots. A brighter room never gives a dimmer screen. An
 *   inverted curve (the dark-end point above the bright-end point) is
 *   monotone the other way.
 * - Below the first point or above the last, the end level holds.
 *
 * Points must have strictly increasing lux above zero, and percents that are
 * all non-decreasing or all non-increasing.
 */
class BrightnessCurve(points: List<CurvePoint>) {
    private val xs: DoubleArray
    private val ys: DoubleArray
    private val slopes: DoubleArray

    init {
        require(points.size >= 2) { "a brightness curve needs at least two points" }
        require(points.all { it.lux > 0.0 && it.lux.isFinite() }) { "curve lux values must be positive" }
        require(points.zipWithNext().all { (a, b) -> b.lux > a.lux }) { "curve lux values must increase" }
        require(points.all { it.percent in 0..100 }) { "curve percents must be 0..100" }
        val rising = points.zipWithNext().all { (a, b) -> b.percent >= a.percent }
        val falling = points.zipWithNext().all { (a, b) -> b.percent <= a.percent }
        require(rising || falling) { "a brightness curve must be monotonic" }

        xs = DoubleArray(points.size) { log10(points[it].lux) }
        ys = DoubleArray(points.size) { points[it].percent.toDouble() }
        slopes = monotoneSlopes(xs, ys)
    }

    /** Panel level, percent, for a reading of [lux]. A negative or NaN reading counts as darkness. */
    fun levelFor(lux: Double): Int {
        val x = if (lux.isNaN() || lux <= 0.0) xs.first() else log10(lux)
        if (x <= xs.first()) return ys.first().roundToInt()
        if (x >= xs.last()) return ys.last().roundToInt()
        var i = 0
        while (x > xs[i + 1]) i++
        val h = xs[i + 1] - xs[i]
        val t = (x - xs[i]) / h
        val t2 = t * t
        val t3 = t2 * t
        val value = (2 * t3 - 3 * t2 + 1) * ys[i] +
            (t3 - 2 * t2 + t) * h * slopes[i] +
            (-2 * t3 + 3 * t2) * ys[i + 1] +
            (t3 - t2) * h * slopes[i + 1]
        return value.roundToInt().coerceIn(0, 100)
    }

    companion object {
        /** The default curve (ADR 0002: BRIGHTNESS_CURVE). */
        val DEFAULT = BrightnessCurve(Constants.BRIGHTNESS_CURVE.map { (lux, pct) -> CurvePoint(lux, pct) })

        /** Fritsch-Carlson tangents: a cubic Hermite with these stays monotone between points. */
        private fun monotoneSlopes(x: DoubleArray, y: DoubleArray): DoubleArray {
            val n = x.size
            val secants = DoubleArray(n - 1) { (y[it + 1] - y[it]) / (x[it + 1] - x[it]) }
            val m = DoubleArray(n)
            m[0] = secants[0]
            m[n - 1] = secants[n - 2]
            for (i in 1 until n - 1) {
                m[i] = if (secants[i - 1] * secants[i] <= 0.0) 0.0 else (secants[i - 1] + secants[i]) / 2
            }
            for (i in 0 until n - 1) {
                if (secants[i] == 0.0) {
                    m[i] = 0.0
                    m[i + 1] = 0.0
                    continue
                }
                val a = m[i] / secants[i]
                val b = m[i + 1] / secants[i]
                val s = a * a + b * b
                if (s > 9.0) {
                    val tau = 3.0 / sqrt(s)
                    m[i] = tau * a * secants[i]
                    m[i + 1] = tau * b * secants[i]
                }
            }
            // a flat neighbour must not leave a non-zero tangent behind
            for (i in 0 until n) if (abs(m[i]) < 1e-12) m[i] = 0.0
            return m
        }
    }
}
