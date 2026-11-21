package net.lockney.tabula.core.screensaver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.lockney.tabula.core.screensaver.ScreensaverController.Transition.Started
import net.lockney.tabula.core.screensaver.ScreensaverController.Transition.Stopped
import net.lockney.tabula.core.settings.Screensaver
import net.lockney.tabula.core.tlp.ScreensaverMode
import net.lockney.tabula.core.tlp.ScreensaverStartTrigger
import net.lockney.tabula.core.tlp.ScreensaverStopTrigger
import net.lockney.tabula.core.tlp.StateDelta
import net.lockney.tabula.core.tlp.Tlp
import net.lockney.tabula.core.tlp.StateFrame
import net.lockney.tabula.core.settings.ScreensaverMode as SettingsMode

/** The idle timer and lifecycle (F-SSV-01 to F-SSV-05, F-SSV-26). Times in ms. */
class ScreensaverControllerTest {
    private val t0 = 1_800_000_000_000L // 2027-01-15T08:00:00Z, a whole second

    private fun controller(timeoutS: Int = 60, mode: SettingsMode = SettingsMode.Clock, enabled: Boolean = true) =
        ScreensaverController().also {
            it.applySettings(Screensaver(enabled = enabled, idleTimeout = timeoutS, mode = mode, brightness = 20), t0)
        }

    @Test
    fun `the idle timer starts the configured mode after the timeout`() {
        val c = controller(timeoutS = 60)
        assertTrue(c.onTick(t0 + 59_999).transitions.isEmpty())
        val out = c.onTick(t0 + 60_000)
        assertEquals(listOf(Started(ScreensaverMode.CLOCK, ScreensaverStartTrigger.IDLE)), out.transitions)
        assertEquals(true, out.state?.screensaverActive)
        assertEquals(StateDelta.NO_NEXT_SCREENSAVER, out.state?.nextScreensaver, "null while active")
        assertEquals(ScreensaverMode.CLOCK, c.shownMode)
    }

    @Test
    fun `touch restarts the timer and dismisses a running screensaver`() {
        val c = controller(timeoutS = 60)
        c.onActivity(t0 + 50_000)
        assertTrue(c.onTick(t0 + 60_000).transitions.isEmpty(), "the touch moved the deadline")
        assertEquals(t0 + 110_000, c.deadlineMs(t0 + 60_000))

        c.onTick(t0 + 110_000)
        val out = c.onActivity(t0 + 120_000)
        assertEquals(listOf(Stopped(ScreensaverMode.CLOCK, ScreensaverStopTrigger.TOUCH)), out.transitions)
        assertEquals(false, out.state?.screensaverActive)
        assertEquals("2027-01-15T08:03:00Z", out.state?.nextScreensaver)
        assertNull(c.shownMode)
    }

    @Test
    fun `commands start and stop it, optionally in another mode`() {
        val c = controller()
        val start = c.onCommand(active = true, requested = ScreensaverMode.BLACK, nowMs = t0 + 1000)
        assertEquals(listOf(Started(ScreensaverMode.BLACK, ScreensaverStartTrigger.COMMAND)), start.transitions)
        assertEquals(0, c.brightnessWhileShown, "black uses the lowest backlight")

        val switch = c.onCommand(active = true, requested = ScreensaverMode.CLOCK, nowMs = t0 + 2000)
        assertEquals(
            listOf(
                Stopped(ScreensaverMode.BLACK, ScreensaverStopTrigger.COMMAND),
                Started(ScreensaverMode.CLOCK, ScreensaverStartTrigger.COMMAND),
            ),
            switch.transitions,
        )
        assertEquals(20, c.brightnessWhileShown)

        assertTrue(c.onCommand(active = true, requested = null, nowMs = t0 + 3000).transitions.isEmpty(), "already showing")
        val stop = c.onCommand(active = false, requested = null, nowMs = t0 + 4000)
        assertEquals(listOf(Stopped(ScreensaverMode.CLOCK, ScreensaverStopTrigger.COMMAND)), stop.transitions)
        assertTrue(c.onCommand(active = false, requested = null, nowMs = t0 + 5000).transitions.isEmpty())
    }

    @Test
    fun `modes not built yet show the clock but the configured mode is reported`() {
        // WEATHER is still unbuilt; PHOTOS joined the built modes in P3.8
        val c = controller(mode = SettingsMode.Weather)
        val out = c.onTick(t0 + 60_000)
        assertEquals(listOf(Started(ScreensaverMode.CLOCK, ScreensaverStartTrigger.IDLE)), out.transitions)
        assertEquals(ScreensaverMode.WEATHER, c.current(t0 + 60_000).screensaverMode)
    }

    @Test
    fun `turning the master switch off dismisses it and stops the timer`() {
        val c = controller()
        c.onTick(t0 + 60_000)
        val out = c.applySettings(Screensaver(enabled = false), t0 + 61_000)
        assertEquals(listOf(Stopped(ScreensaverMode.CLOCK, ScreensaverStopTrigger.COMMAND)), out.transitions)
        assertNull(c.deadlineMs(t0 + 61_000))
        assertTrue(c.onTick(t0 + 999_000).transitions.isEmpty())
        assertEquals(StateDelta.NO_NEXT_SCREENSAVER, c.current(t0 + 999_000).nextScreensaver)

        // an explicit start from HA still works with the switch off
        val manual = c.onCommand(active = true, requested = null, nowMs = t0 + 1_000_000)
        assertEquals(1, manual.transitions.size)
    }

    @Test
    fun `turning it back on starts the timer from that moment`() {
        val c = controller(enabled = false)
        c.applySettings(Screensaver(enabled = true, idleTimeout = 60), t0 + 500_000)
        assertEquals(t0 + 560_000, c.deadlineMs(t0 + 500_000))
    }

    @Test
    fun `the timer pauses while the panel is off and starts over after`() {
        val c = controller(timeoutS = 60)
        c.setPaused(true, t0 + 30_000)
        assertNull(c.deadlineMs(t0 + 90_000))
        assertTrue(c.onTick(t0 + 500_000).transitions.isEmpty())
        // two pauses nest: panel off while another app is in front
        c.setPaused(true, t0 + 500_000)
        c.setPaused(false, t0 + 510_000)
        assertNull(c.deadlineMs(t0 + 510_000))
        val resumed = c.setPaused(false, t0 + 600_000)
        assertEquals(t0 + 660_000, c.deadlineMs(t0 + 600_000))
        assertEquals("2027-01-15T08:11:00Z", resumed.state?.nextScreensaver)
    }

    @Test
    fun `a settings change reports mode, brightness and the new deadline`() {
        val c = controller(timeoutS = 60)
        val out = c.applySettings(
            Screensaver(enabled = true, idleTimeout = 120, mode = SettingsMode.Black, brightness = 5),
            t0 + 10_000,
        )
        assertTrue(out.transitions.isEmpty())
        assertEquals(ScreensaverMode.BLACK, out.state?.screensaverMode)
        assertEquals(5, out.state?.screensaverBrightness)
        assertEquals("2027-01-15T08:02:00Z", out.state?.nextScreensaver, "120 s from the last activity at t0")
    }

    @Test
    fun `the explicit null encodes as JSON null and merges over a time`() {
        val frame = Tlp.encodeFrame("state_delta", StateFrame(id = 7, state = StateDelta(nextScreensaver = StateDelta.NO_NEXT_SCREENSAVER)))
        assertEquals("""{"id":7,"type":"tabula/state","state":{"next_screensaver":null}}""", frame)
        val withTime = Tlp.encodeFrame("state_delta", StateFrame(id = 8, state = StateDelta(nextScreensaver = "2027-01-15T08:01:00Z")))
        assertTrue(withTime.contains(""""next_screensaver":"2027-01-15T08:01:00Z""""))
    }

    @Test
    fun `a settings push that changes nothing here reports nothing`() {
        val c = controller(timeoutS = 60)
        val again = c.applySettings(Screensaver(enabled = true, idleTimeout = 60, mode = SettingsMode.Clock, brightness = 20), t0)
        assertTrue(again.transitions.isEmpty())
        assertTrue(again.state == null || !again.state!!.isNotEmpty, "no unchanged keys: ${again.state}")
    }
}
