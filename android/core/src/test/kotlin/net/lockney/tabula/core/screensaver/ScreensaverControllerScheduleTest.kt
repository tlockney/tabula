package net.lockney.tabula.core.screensaver

import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.lockney.tabula.core.screensaver.ScreensaverController.Transition.Started
import net.lockney.tabula.core.screensaver.ScreensaverController.Transition.Stopped
import net.lockney.tabula.core.settings.Schedule
import net.lockney.tabula.core.settings.ScheduleSlots
import net.lockney.tabula.core.settings.ScheduleSlotsMode
import net.lockney.tabula.core.settings.Screensaver
import net.lockney.tabula.core.tlp.ScreensaverMode
import net.lockney.tabula.core.tlp.ScreensaverStartTrigger
import net.lockney.tabula.core.tlp.ScreensaverStopTrigger
import net.lockney.tabula.core.settings.ScreensaverMode as SettingsMode

/**
 * The schedule's share of the lifecycle (F-SSV-18, packet P3.12): the slot
 * governs the mode used next time, crossings restart a running screensaver,
 * edits apply live, and the slot's overrides resolve against the global
 * configuration. Times in ms, UTC.
 */
class ScreensaverControllerScheduleTest {
    private val zone = ZoneId.of("UTC")
    private val t0 = 1_800_000_000_000L // 2027-01-15T08:00:00Z

    private fun controller(mode: SettingsMode = SettingsMode.Clock) =
        ScreensaverController(zone = zone).also {
            it.applySettings(
                Screensaver(idleTimeout = 60, mode = mode, brightness = 20),
                t0,
                Schedule(),
            )
        }

    private fun schedule(vararg slots: ScheduleSlots, enabled: Boolean = true) =
        Schedule(enabled = enabled, slots = slots.toList())

    private fun slot(
        start: String,
        mode: ScheduleSlotsMode = ScheduleSlotsMode.Black,
        brightness: Int? = null,
        powerOffDelay: Int? = null,
        presenceDismiss: Boolean? = null,
        widgets: Boolean? = null,
        atAGlance: Boolean? = null,
    ) = ScheduleSlots(
        start = start,
        mode = mode,
        brightness = brightness,
        powerOffDelay = powerOffDelay,
        presenceDismiss = presenceDismiss,
        widgets = widgets,
        atAGlance = atAGlance,
    )

    @Test
    fun `the active slot's mode is the mode used next time`() {
        val c = controller()
        c.applySettings(Screensaver(idleTimeout = 60, brightness = 20), t0, schedule(slot("06:00")))
        assertEquals(ScreensaverMode.BLACK, c.current(t0).screensaverMode, "08:00 is inside the 06:00 slot")
        val out = c.onTick(t0 + 60_000)
        assertEquals(listOf(Started(ScreensaverMode.BLACK, ScreensaverStartTrigger.IDLE)), out.transitions, "the idle timer still starts it")
        assertEquals(ScreensaverMode.BLACK, c.shownMode)
    }

    @Test
    fun `crossing into a slot with a different mode restarts a running screensaver`() {
        val c = controller()
        c.applySettings(
            Screensaver(idleTimeout = 60, brightness = 20),
            t0,
            schedule(slot("06:00", ScheduleSlotsMode.Clock), slot("08:05", ScheduleSlotsMode.Black)),
        )
        c.onTick(t0 + 60_000) // idles into the clock, inside the 06:00 slot
        val out = c.onTick(t0 + 5 * 60_000) // 08:05: the black slot starts
        assertEquals(
            listOf(
                Stopped(ScreensaverMode.CLOCK, ScreensaverStopTrigger.SCHEDULE),
                Started(ScreensaverMode.BLACK, ScreensaverStartTrigger.SCHEDULE),
            ),
            out.transitions,
        )
        assertEquals(ScreensaverMode.BLACK, c.shownMode)
        assertEquals(ScreensaverMode.BLACK, out.state?.screensaverMode)
    }

    @Test
    fun `crossing into the same mode keeps the run`() {
        val c = controller()
        c.applySettings(
            Screensaver(idleTimeout = 60, brightness = 20),
            t0,
            schedule(slot("06:00", ScheduleSlotsMode.Clock), slot("08:05", ScheduleSlotsMode.Clock)),
        )
        c.onTick(t0 + 60_000)
        val out = c.onTick(t0 + 5 * 60_000)
        assertTrue(out.transitions.isEmpty(), "the mode it should show did not change")
        assertEquals(ScreensaverMode.CLOCK, c.shownMode)
    }

    @Test
    fun `an unbuilt slot mode keeps the stand-in running, and a later built mode restarts it`() {
        val c = controller()
        c.applySettings(
            Screensaver(idleTimeout = 60, brightness = 20),
            t0,
            schedule(slot("06:00", ScheduleSlotsMode.Clock), slot("08:05", ScheduleSlotsMode.Weather)),
        )
        c.onTick(t0 + 60_000) // idles into the clock, inside the 06:00 slot
        val out = c.onTick(t0 + 5 * 60_000) // 08:05: the weather slot starts, weather is not built yet
        assertTrue(out.transitions.isEmpty(), "the stand-in for weather is the clock, which is already showing")
        assertEquals(ScreensaverMode.CLOCK, c.shownMode)
        assertEquals(ScreensaverMode.WEATHER, out.state?.screensaverMode, "the reported mode stays the slot's")

        c.applySettings(
            Screensaver(idleTimeout = 60, brightness = 20),
            t0 + 6 * 60_000,
            schedule(
                slot("06:00", ScheduleSlotsMode.Clock),
                slot("08:05", ScheduleSlotsMode.Weather),
                slot("08:10", ScheduleSlotsMode.Black),
            ),
        )
        val built = c.onTick(t0 + 10 * 60_000) // 08:10: a built mode the screen does not show
        assertEquals(
            listOf(
                Stopped(ScreensaverMode.CLOCK, ScreensaverStopTrigger.SCHEDULE),
                // the event carries the mode actually shown (ADR 0013 item 3)
                Started(ScreensaverMode.BLACK, ScreensaverStartTrigger.SCHEDULE),
            ),
            built.transitions,
        )
    }

    @Test
    fun `a slot boundary landing on the idle deadline starts the new slot's mode`() {
        val c = controller()
        c.applySettings(
            Screensaver(idleTimeout = 60, brightness = 20),
            t0,
            schedule(slot("06:00", ScheduleSlotsMode.Clock), slot("08:01", ScheduleSlotsMode.Black)),
        )
        val out = c.onTick(t0 + 60_000) // 08:01: the boundary and the deadline together
        assertEquals(
            listOf(Started(ScreensaverMode.BLACK, ScreensaverStartTrigger.IDLE)),
            out.transitions,
            "the crossing applied first, so the idle start uses the new slot's mode",
        )
    }

    @Test
    fun `a live edit restarts a running screensaver into the new slot's mode`() {
        val c = controller()
        c.applySettings(Screensaver(idleTimeout = 60, brightness = 20), t0, schedule(slot("06:00")))
        c.onTick(t0 + 60_000)
        val edited = c.applySettings(
            Screensaver(idleTimeout = 60, brightness = 20),
            t0 + 70_000,
            schedule(slot("06:00", ScheduleSlotsMode.Clock)),
        )
        assertEquals(
            listOf(
                Stopped(ScreensaverMode.BLACK, ScreensaverStopTrigger.SCHEDULE),
                Started(ScreensaverMode.CLOCK, ScreensaverStartTrigger.SCHEDULE),
            ),
            edited.transitions,
            "edits apply live, including to a running screensaver (F-SSV-18)",
        )
    }

    @Test
    fun `a live edit of only the slot's brightness keeps the run and applies the level`() {
        val c = controller()
        c.applySettings(
            Screensaver(idleTimeout = 60, brightness = 20),
            t0,
            schedule(slot("06:00", ScheduleSlotsMode.Clock, brightness = 5)),
        )
        c.onTick(t0 + 60_000)
        assertEquals(5, c.brightnessWhileShown)
        val edited = c.applySettings(
            Screensaver(idleTimeout = 60, brightness = 20),
            t0 + 70_000,
            schedule(slot("06:00", ScheduleSlotsMode.Clock, brightness = 50)),
        )
        assertTrue(edited.transitions.isEmpty(), "the mode it should show did not change")
        assertEquals(50, c.brightnessWhileShown)
    }

    @Test
    fun `the slot's fixed level wins while it shows, and a slot that inherits follows the global switch`() {
        val c = controller()
        c.applySettings(
            Screensaver(idleTimeout = 60, brightness = 20, brightnessEnabled = false),
            t0,
            schedule(slot("06:00", ScheduleSlotsMode.Clock, brightness = 5)),
        )
        c.onTick(t0 + 60_000)
        assertEquals(5, c.brightnessWhileShown, "the slot's fixed level, not the global switch (F-SSV-18)")
        c.applySettings(
            Screensaver(idleTimeout = 60, brightness = 20, brightnessEnabled = false),
            t0 + 70_000,
            schedule(slot("06:00", ScheduleSlotsMode.Clock)),
        )
        assertNull(c.brightnessWhileShown, "inherit: brightness_enabled off keeps the panel's level")
        c.applySettings(
            Screensaver(idleTimeout = 60, brightness = 20, brightnessEnabled = true),
            t0 + 80_000,
            schedule(slot("06:00", ScheduleSlotsMode.Clock)),
        )
        assertEquals(20, c.brightnessWhileShown, "inherit: the separate level applies")
    }

    @Test
    fun `black and dim keep their own levels even under a slot`() {
        val c = controller()
        c.applySettings(
            Screensaver(idleTimeout = 60, brightness = 20, dimBrightness = 10),
            t0,
            schedule(slot("06:00", ScheduleSlotsMode.Clock, brightness = 50)),
        )
        c.onCommand(active = true, requested = ScreensaverMode.BLACK, nowMs = t0 + 1000)
        assertEquals(0, c.brightnessWhileShown)
        c.onCommand(active = true, requested = ScreensaverMode.DIM, nowMs = t0 + 2000)
        assertEquals(10, c.brightnessWhileShown, "dim holds its own level (F-SSV-07), not the slot's")
    }

    @Test
    fun `crossing into a slot with a different power-off delay restarts the countdown state`() {
        val c = controller()
        c.applySettings(
            Screensaver(idleTimeout = 60, brightness = 20),
            t0,
            schedule(
                slot("06:00", ScheduleSlotsMode.Clock),
                slot("08:05", ScheduleSlotsMode.Clock, powerOffDelay = 10),
                slot("08:10", ScheduleSlotsMode.Clock, powerOffDelay = 10),
            ),
        )
        assertNull(c.powerOff, "nothing while no delay is in effect")
        c.onTick(t0 + 60_000)
        assertNull(c.powerOff, "the run's slot has no delay")
        c.onTick(t0 + 5 * 60_000) // 08:05: a delay comes into effect
        assertEquals(ScreensaverController.PowerOff(10, t0 + 5 * 60_000), c.powerOff)
        c.onTick(t0 + 10 * 60_000) // 08:10: same delay, a new slot
        assertEquals(
            ScreensaverController.PowerOff(10, t0 + 5 * 60_000),
            c.powerOff,
            "the countdown restarts only when the delay changes (F-SSV-18)",
        )
        c.onActivity(t0 + 11 * 60_000)
        assertNull(c.powerOff, "dismissed: the countdown ends with the run")
    }

    @Test
    fun `the slot's presence dismissal flag is carried for the presence source`() {
        val c = controller()
        c.applySettings(
            Screensaver(idleTimeout = 60, brightness = 20),
            t0,
            schedule(
                slot("06:00", ScheduleSlotsMode.Clock, presenceDismiss = false),
                slot("20:00", ScheduleSlotsMode.Clock, presenceDismiss = true),
            ),
        )
        assertEquals(false, c.presenceDismiss)
        c.onTick(t0 + 12 * 3_600_000) // 20:00
        assertEquals(true, c.presenceDismiss)
        c.applySettings(Screensaver(idleTimeout = 60, brightness = 20), t0 + 13 * 3_600_000, schedule())
        assertNull(c.presenceDismiss, "no slot: inherit, and the source itself is still out of scope (F-SSV-24)")
    }

    @Test
    fun `turning the schedule off reverts to the global mode`() {
        val c = controller()
        c.applySettings(Screensaver(idleTimeout = 60, brightness = 20), t0, schedule(slot("06:00")))
        assertEquals(ScreensaverMode.BLACK, c.current(t0).screensaverMode)
        val off = c.applySettings(Screensaver(idleTimeout = 60, brightness = 20), t0, schedule(slot("06:00"), enabled = false))
        assertEquals(ScreensaverMode.CLOCK, off.state?.screensaverMode, "the switch governs the whole schedule")
        assertTrue(off.transitions.isEmpty(), "nothing was running")
    }

    @Test
    fun `the next wake is the idle deadline or the next slot start, whichever comes first`() {
        val c = controller()
        c.applySettings(
            Screensaver(idleTimeout = 120, brightness = 20),
            t0,
            schedule(slot("06:00", ScheduleSlotsMode.Clock), slot("08:01", ScheduleSlotsMode.Black)),
        )
        assertEquals(t0 + 60_000, c.nextEventMs(t0), "the boundary (08:01) comes before the deadline (08:02)")
        c.onTick(t0 + 60_000) // crosses into the black slot; the mode used next time changes
        assertEquals(ScreensaverMode.BLACK, c.current(t0 + 60_000).screensaverMode)
        assertEquals(t0 + 120_000, c.nextEventMs(t0 + 60_000), "the deadline comes next")
        c.onTick(t0 + 120_000) // the idle start, in the slot's mode
        assertEquals(ScreensaverMode.BLACK, c.shownMode)
        // while showing, only the boundary remains: tomorrow's first start
        assertEquals(
            "2027-01-16T06:00:00Z",
            c.nextEventMs(t0 + 120_000)?.let { java.time.Instant.ofEpochMilli(it).toString() },
        )
        // with no schedule, the deadline alone
        val plain = controller()
        plain.applySettings(Screensaver(idleTimeout = 120, brightness = 20), t0, schedule())
        assertEquals(t0 + 120_000, plain.nextEventMs(t0))
    }

    @Test
    fun `a paused timer still crosses slots, so the mode is right when it resumes`() {
        val c = controller()
        c.applySettings(
            Screensaver(idleTimeout = 60, brightness = 20),
            t0,
            schedule(slot("06:00", ScheduleSlotsMode.Clock), slot("08:05", ScheduleSlotsMode.Black)),
        )
        c.setPaused(true, t0)
        val crossed = c.onTick(t0 + 5 * 60_000)
        assertTrue(crossed.transitions.isEmpty(), "nothing runs while paused")
        assertEquals(ScreensaverMode.BLACK, crossed.state?.screensaverMode, "the mode used next time follows the clock")
        c.setPaused(false, t0 + 6 * 60_000)
        c.onTick(t0 + 7 * 60_000)
        assertEquals(ScreensaverMode.BLACK, c.shownMode)
    }
}
