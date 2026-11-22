package net.lockney.tabula.core.dashboard

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.lockney.tabula.core.settings.Dashboard

/** The return-to-start countdown (F-DSH-15, ADR 0021). Times in ms. */
class StartPageControllerTest {
    private val t0 = 1_800_000_000_000L // 2027-01-15T08:00:00Z

    private fun controller(startPath: String = "", returnAfterS: Int = 0) =
        StartPageController().also {
            it.applySettings(Dashboard(startPath = startPath, returnHomeAfter = returnAfterS), t0)
        }

    @Test
    fun `zero never returns, and no target is configured`() {
        val c = controller()
        assertNull(c.deadlineMs(t0))
        assertFalse(c.onTick(t0 + 100_000, currentPage = "/somewhere/else"))
        assertNull(c.targetPath, "an unset start path is no idle target (ADR 0021 item 7)")
    }

    @Test
    fun `an unset or root target never loops the return`() {
        // The frontend redirects "/" client-side to its default panel, so the
        // page after a fallback load reports e.g. "/lovelace/0", never "/"
        // (review round 3): with no configured target the idle return stays
        // inert instead of reloading the dashboard once per delay period
        // forever. "/" set explicitly is the same case (the schema accepts it).
        val c = controller(startPath = "", returnAfterS = 60)
        assertNull(c.deadlineMs(t0))
        assertFalse(c.onTick(t0 + 60_001, currentPage = "/lovelace/0"), "no target: no return")
        c.applySettings(Dashboard(startPath = "/", returnHomeAfter = 60), t0)
        assertNull(c.deadlineMs(t0))
        assertFalse(c.onTick(t0 + 60_001, currentPage = "/"), "the dashboard cannot rest on /")
        assertFalse(
            c.onTick(t0 + 60_001, currentPage = "/lovelace/0"),
            "no reload churn after the frontend's redirect",
        )
    }

    @Test
    fun `the countdown returns to the start target when it passes`() {
        val c = controller(startPath = "/lovelace/kitchen", returnAfterS = 60)
        assertEquals(t0 + 60_000, c.deadlineMs(t0))
        assertFalse(c.onTick(t0 + 59_999, currentPage = "/lovelace/0"))
        assertTrue(c.onTick(t0 + 60_000, currentPage = "/lovelace/0"), "returns to the start target")
    }

    @Test
    fun `a touch resets the countdown`() {
        val c = controller(startPath = "/lovelace/kitchen", returnAfterS = 60)
        c.onActivity(t0 + 50_000)
        assertFalse(c.onTick(t0 + 60_000, currentPage = "/lovelace/0"), "the touch moved the deadline")
        assertTrue(c.onTick(t0 + 110_000, currentPage = "/lovelace/0"))
    }

    @Test
    fun `a page change resets the countdown, including an HA navigation`() {
        val c = controller(startPath = "/lovelace/kitchen", returnAfterS = 60)
        c.onPageChanged(t0 + 50_000)
        assertFalse(c.onTick(t0 + 60_000, currentPage = "/lovelace/0"), "the page change moved the deadline")
        assertTrue(c.onTick(t0 + 110_000, currentPage = "/lovelace/0"))
    }

    @Test
    fun `no navigation when the dashboard is already on the start target`() {
        val c = controller(startPath = "/lovelace/kitchen#popup", returnAfterS = 60)
        assertEquals("/lovelace/kitchen", c.targetPath, "a fragment target compares by path")
        assertFalse(c.onTick(t0 + 60_000, currentPage = "/lovelace/kitchen"))
        // and it starts over: the next return is a full delay away
        assertFalse(c.onTick(t0 + 60_001, currentPage = "/lovelace/kitchen"))
    }

    @Test
    fun `the countdown keeps running behind a screensaver`() {
        // The controller has no screensaver input at all (F-DSH-15, F-OPT):
        // nothing a screensaver does pauses it. The next page change or touch
        // is the only reset, and an idle covered panel still returns.
        val c = controller(startPath = "/lovelace/kitchen", returnAfterS = 60)
        assertTrue(c.onTick(t0 + 60_000, currentPage = "/lovelace/0"))
    }

    @Test
    fun `a hold pauses the countdown and resuming starts it over`() {
        val c = controller(startPath = "/lovelace/kitchen", returnAfterS = 60)
        c.setHeld(true, t0)
        assertNull(c.deadlineMs(t0))
        assertFalse(c.onTick(t0 + 120_000, currentPage = "/lovelace/0"), "held: no return")
        c.setHeld(false, t0 + 120_000)
        assertEquals(t0 + 180_000, c.deadlineMs(t0 + 120_000), "resumed: a full delay from the release")
    }

    @Test
    fun `a changed delay restarts the countdown and a changed path does not navigate`() {
        val c = controller(startPath = "/lovelace/kitchen", returnAfterS = 60)
        c.onActivity(t0 + 50_000)
        c.applySettings(Dashboard(startPath = "/lovelace/hall", returnHomeAfter = 30), t0 + 60_000)
        assertEquals("/lovelace/hall", c.targetPath)
        assertEquals(t0 + 90_000, c.deadlineMs(t0 + 60_000), "the new delay counts from its own apply")
        c.applySettings(Dashboard(startPath = "/lovelace/hall", returnHomeAfter = 30), t0 + 70_000)
        assertEquals(
            t0 + 90_000,
            c.deadlineMs(t0 + 70_000),
            "an unchanged delay leaves the countdown running",
        )
    }
}
