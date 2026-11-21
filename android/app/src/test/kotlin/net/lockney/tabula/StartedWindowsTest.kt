package net.lockney.tabula

import kotlin.test.Test
import kotlin.test.assertEquals
import net.lockney.tabula.core.screensaver.ScreensaverController
import net.lockney.tabula.core.settings.Screensaver

class StartedWindowsTest {
    private val seen = mutableListOf<Boolean>()
    private val windows = StartedWindows { seen += it }

    @Test
    fun `a window opening over another keeps the app in front`() {
        windows.onStart() // the first window
        // a second window comes up first, then the one below it stops: the
        // order Android uses when an activity opens over another (issue #96)
        windows.onStart()
        windows.onStop()
        assertEquals(listOf(true), seen, "one change: the app came to the front and stayed there")
        windows.onStop()
        assertEquals(listOf(true, false), seen)
    }

    @Test
    fun `an unmatched stop never goes below zero`() {
        windows.onStop()
        windows.onStart()
        assertEquals(listOf(true), seen, "a stray stop is ignored, and the start still counts")
    }

    @Test
    fun `the idle timer keeps a deadline through overlapping windows`() {
        // the issue's symptom end to end: with a flag, next_screensaver went
        // to the explicit null and stayed there
        val controller = ScreensaverController()
        var now = 1_000_000L
        controller.applySettings(Screensaver(enabled = true), now)
        val tracked = StartedWindows { inFront -> controller.setPaused(!inFront, now) }
        controller.setPaused(true, now) // nothing in front at boot, as ScreensaverManager starts
        tracked.onStart()
        now += 1_000
        tracked.onStart()
        tracked.onStop()
        assertEquals(true, controller.deadlineMs(now) != null, "the timer runs while a window is in front")
        // and a panel-off pause is not undone by a second window starting
        controller.setPaused(true, now)
        tracked.onStart()
        assertEquals(null, controller.deadlineMs(now), "the panel-off pause still holds")
    }
}
