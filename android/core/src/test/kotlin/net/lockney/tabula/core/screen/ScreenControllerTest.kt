package net.lockney.tabula.core.screen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.lockney.tabula.core.settings.Screen
import net.lockney.tabula.core.tlp.StateDelta

/** Brightness ownership (plan 5.5, ADR 0010 item 5, ADR 0011) and smoothing (F-SCR-08). */
class ScreenControllerTest {
    private val curve = BrightnessCurve.DEFAULT
    private fun controller() = ScreenController(curve, threshold = 3, minIntervalMs = 2000)

    @Test
    fun `startup applies the stored base level and reports every key`() {
        val c = controller()
        val d = c.applySettings(Screen(brightness = 60, adaptiveBrightness = false, keepScreenOn = true), 0)
        assertEquals(60, d.level)
        assertFalse(d.fade)
        assertEquals(StateDelta(brightness = 60, adaptiveBrightness = false, keepScreenOn = true), d.state)
        assertEquals(StateDelta(brightness = 60, adaptiveBrightness = false, keepScreenOn = true), c.current())
    }

    @Test
    fun `with adaptive off the base level holds whatever the light does`() {
        val c = controller()
        c.applySettings(Screen(brightness = 60, adaptiveBrightness = false), 0)
        assertNull(c.onLux(5000.0, 10_000).level)
        assertNull(c.onLux(0.5, 20_000).level)
        assertEquals(60, c.current().brightness)
    }

    @Test
    fun `with adaptive on the curve owns the panel`() {
        val c = controller()
        c.applySettings(Screen(brightness = 60, adaptiveBrightness = true), 0)
        val d = c.onLux(100.0, 10)
        assertEquals(curve.levelFor(100.0), d.level)
        assertEquals(StateDelta(brightness = curve.levelFor(100.0)), d.state)
        assertEquals(curve.levelFor(100.0), c.current().brightness)
    }

    @Test
    fun `a manual level turns adaptive off and reports both keys in one delta`() {
        val c = controller()
        c.applySettings(Screen(brightness = 60, adaptiveBrightness = true, keepScreenOn = true), 0)
        c.onLux(1000.0, 10)

        val d = c.onManualBrightness(25)
        assertEquals(25, d.level)
        assertFalse(d.fade, "a manual level is applied at once")
        assertEquals(StateDelta(brightness = 25, adaptiveBrightness = false), d.state)
        assertEquals(Screen(brightness = 25, adaptiveBrightness = false, keepScreenOn = true), d.settings)

        // the curve no longer moves the panel
        assertNull(c.onLux(10000.0, 60_000).level)
        assertEquals(StateDelta(brightness = 25, adaptiveBrightness = false, keepScreenOn = true), c.current())
    }

    @Test
    fun `changes within the threshold are ignored`() {
        val c = controller()
        c.applySettings(Screen(adaptiveBrightness = true), 0)
        val first = c.onLux(100.0, 0).level!!
        // pick a reading whose level is within 3 points of the first
        val near = generateSequence(100.0) { it * 1.01 }.take(500).first { curve.levelFor(it) in first + 1..first + 3 }
        assertNull(c.onLux(near, 10_000).level)
        assertEquals(first, c.current().brightness)
    }

    @Test
    fun `slow drift accumulates against the last applied level`() {
        val c = controller()
        c.applySettings(Screen(adaptiveBrightness = true), 0)
        val first = c.onLux(100.0, 0).level!!
        var lux = 100.0
        var now = 0L
        var applied: Int? = null
        while (applied == null && lux < 10000.0) {
            lux *= 1.02 // each step moves the curve by less than the threshold
            now += 5000
            applied = c.onLux(lux, now).level
        }
        assertTrue(applied != null && applied > first + 3, "drift was never followed")
    }

    @Test
    fun `a change inside the minimum interval waits and asks for a recheck`() {
        val c = controller()
        c.applySettings(Screen(adaptiveBrightness = true), 0)
        c.onLux(10.0, 0)
        c.onLux(1000.0, 3000) // applied at t=3000

        val early = c.onLux(10.0, 3500)
        assertNull(early.level)
        assertEquals(1500L, early.retryInMs)

        // the room stays dark, so the sensor reports nothing new; the recheck follows it
        val later = c.recheck(5000)
        assertEquals(curve.levelFor(10.0), later.level)
        assertTrue(later.fade)
    }

    @Test
    fun `adaptive changes fade, the first reading applies at once`() {
        val c = controller()
        c.applySettings(Screen(brightness = 60, adaptiveBrightness = false), 0)
        val on = c.applySettings(Screen(brightness = 60, adaptiveBrightness = true), 100)
        assertNull(on.level, "no reading yet: the panel holds")
        assertEquals(StateDelta(adaptiveBrightness = true), on.state)

        val first = c.onLux(1.0, 200) // first reading, even inside the interval
        assertEquals(curve.levelFor(1.0), first.level)
        assertTrue(first.fade)
    }

    @Test
    fun `turning adaptive on with a known reading moves to the curve`() {
        val c = controller()
        c.applySettings(Screen(brightness = 60, adaptiveBrightness = false), 0)
        c.onLux(1.0, 0)
        val d = c.applySettings(Screen(brightness = 60, adaptiveBrightness = true), 1000)
        assertEquals(curve.levelFor(1.0), d.level)
        assertTrue(d.fade)
        assertEquals(StateDelta(brightness = curve.levelFor(1.0), adaptiveBrightness = true), d.state)
    }

    @Test
    fun `an unrelated settings push does not move an adaptive panel`() {
        val c = controller()
        c.applySettings(Screen(adaptiveBrightness = true, keepScreenOn = true), 0)
        val level = c.onLux(1000.0, 0).level
        val d = c.applySettings(Screen(adaptiveBrightness = true, keepScreenOn = false), 500)
        assertNull(d.level)
        assertEquals(StateDelta(keepScreenOn = false), d.state)
        assertEquals(level, c.current().brightness)
    }

    @Test
    fun `turning adaptive off returns to the base level`() {
        val c = controller()
        c.applySettings(Screen(brightness = 60, adaptiveBrightness = true), 0)
        c.onLux(1.0, 0)
        val d = c.applySettings(Screen(brightness = 60, adaptiveBrightness = false), 5000)
        assertEquals(60, d.level)
        assertFalse(d.fade)
        assertEquals(StateDelta(brightness = 60, adaptiveBrightness = false), d.state)
    }

    @Test
    fun `fade steps end at the target and move one way`() {
        val up = ScreenController.fadeSteps(20, 80, durationMs = 800, frameMs = 50)
        assertEquals(16, up.size)
        assertEquals(80, up.last())
        up.zipWithNext().forEach { (a, b) -> assertTrue(b >= a) }

        val small = ScreenController.fadeSteps(40, 37, durationMs = 800, frameMs = 50)
        assertEquals(listOf(39, 38, 37), small)

        assertTrue(ScreenController.fadeSteps(50, 50).isEmpty())
    }

    @Test
    fun `percent maps to the system scale without switching the backlight off`() {
        assertEquals(1, ScreenController.toSystemLevel(0))
        assertEquals(255, ScreenController.toSystemLevel(100))
        assertEquals(153, ScreenController.toSystemLevel(60))
        assertEquals(60, ScreenController.fromSystemLevel(153))
        (0..100).forEach { p ->
            val back = ScreenController.fromSystemLevel(ScreenController.toSystemLevel(p))
            assertTrue(back == p || (p == 0 && back == 0), "round trip of $p gave $back")
        }
    }

    @Test
    fun `nothing is configured until settings or a manual level arrive`() {
        val c = controller()
        assertFalse(c.configured)
        c.onLux(100.0, 0)
        assertFalse(c.configured)
        c.onManualBrightness(30)
        assertTrue(c.configured)
    }
}
