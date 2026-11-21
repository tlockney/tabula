package net.lockney.tabula.core.screen

import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BrightnessCurveTest {
    private val curve = BrightnessCurve.DEFAULT

    /** Lux values from 0.01 to 100000, ten per decade, log-spaced. */
    private val sweep = (-20..50).map { 10.0.pow(it / 10.0) }

    @Test
    fun `the default curve passes through its points`() {
        assertEquals(5, curve.levelFor(1.0))
        assertEquals(15, curve.levelFor(10.0))
        assertEquals(40, curve.levelFor(100.0))
        assertEquals(75, curve.levelFor(1000.0))
        assertEquals(100, curve.levelFor(10000.0))
    }

    @Test
    fun `readings beyond the end points hold the end levels`() {
        assertEquals(5, curve.levelFor(0.1))
        assertEquals(5, curve.levelFor(0.0))
        assertEquals(5, curve.levelFor(-3.0))
        assertEquals(5, curve.levelFor(Double.NaN))
        assertEquals(100, curve.levelFor(120000.0))
    }

    @Test
    fun `a brighter room never gives a dimmer screen`() {
        val levels = sweep.map(curve::levelFor)
        levels.zipWithNext().forEach { (a, b) -> assertTrue(b >= a, "curve fell from $a to $b: $levels") }
    }

    @Test
    fun `the light axis is logarithmic`() {
        // the midpoint of a decade in log space sits between the decade's levels;
        // a linear axis would put 31.6 lx very close to the 10 lx level
        val mid = curve.levelFor(10.0.pow(1.5))
        assertTrue(mid in 20..35, "31.6 lx gave $mid")
    }

    @Test
    fun `the interpolation never overshoots its neighbours`() {
        // a sharp step: a plain cubic spline would overshoot past 90 or under 10
        val step = BrightnessCurve(
            listOf(CurvePoint(1.0, 10), CurvePoint(10.0, 10), CurvePoint(100.0, 90), CurvePoint(1000.0, 90)),
        )
        sweep.forEach { lux ->
            val level = step.levelFor(lux)
            assertTrue(level in 10..90, "$lux lx gave $level")
        }
        assertEquals(10, step.levelFor(5.0), "flat segment stays flat")
        assertEquals(90, step.levelFor(500.0), "flat segment stays flat")
    }

    @Test
    fun `an inverted curve is monotone the other way`() {
        val inverted = BrightnessCurve(listOf(CurvePoint(1.0, 80), CurvePoint(100.0, 30), CurvePoint(10000.0, 10)))
        val levels = sweep.map(inverted::levelFor)
        levels.zipWithNext().forEach { (a, b) -> assertTrue(b <= a, "inverted curve rose from $a to $b") }
        assertEquals(80, inverted.levelFor(0.5))
        assertEquals(10, inverted.levelFor(50000.0))
    }

    @Test
    fun `invalid curves are rejected`() {
        assertFailsWith<IllegalArgumentException> { BrightnessCurve(listOf(CurvePoint(1.0, 5))) }
        assertFailsWith<IllegalArgumentException> {
            BrightnessCurve(listOf(CurvePoint(10.0, 5), CurvePoint(1.0, 50)))
        }
        assertFailsWith<IllegalArgumentException> {
            BrightnessCurve(listOf(CurvePoint(0.0, 5), CurvePoint(1.0, 50)))
        }
        assertFailsWith<IllegalArgumentException> {
            BrightnessCurve(listOf(CurvePoint(1.0, 5), CurvePoint(10.0, 50), CurvePoint(100.0, 20)))
        }
        assertFailsWith<IllegalArgumentException> {
            BrightnessCurve(listOf(CurvePoint(1.0, 5), CurvePoint(10.0, 101)))
        }
    }
}
