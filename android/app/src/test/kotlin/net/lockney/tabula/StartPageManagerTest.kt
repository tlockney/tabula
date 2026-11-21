package net.lockney.tabula

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import net.lockney.tabula.core.dashboard.StartPageController
import net.lockney.tabula.core.settings.Dashboard
import net.lockney.tabula.core.settings.Settings

/**
 * The return-to-start timer against a (virtual) clock, and what it does when
 * it fires (packet P3.15, F-DSH-15): it loads the start target through the
 * dashboard bus, resets on touch and page change, and does nothing while the
 * dashboard is already on the target.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StartPageManagerTest {
    private val scope = TestScope()
    private val t0 = 1_800_000_000_000L

    private class FakeDashboard(var page: String? = "/lovelace/0") : DashboardBus.Handle {
        var startLoads = 0

        override fun reload() {}

        override fun clearCache() {}

        override fun load(url: String) {}

        override fun loadStart() {
            startLoads++
        }

        override fun currentPage(): String? = page

        override suspend fun capture(quality: Int?): ByteArray? = null
    }

    private val handle = FakeDashboard()
    private val bus = DashboardBus().also { it.attach(handle) }
    private val manager = StartPageManager(
        controller = StartPageController(),
        dashboard = bus,
        scope = scope,
        now = { t0 + scope.testScheduler.currentTime },
    )

    private fun settings(startPath: String = "/lovelace/kitchen", returnAfterS: Int = 60) =
        Settings(dashboard = Dashboard(startPath = startPath, returnHomeAfter = returnAfterS))

    private fun running(startPath: String = "/lovelace/kitchen", returnAfterS: Int = 60) {
        manager.applySettings(settings(startPath, returnAfterS))
        manager.start()
        scope.runCurrent()
    }

    @AfterTest
    fun tearDown() = manager.stop()

    @Test
    fun `returns to the start target after the delay`() {
        running()
        scope.advanceTimeBy(59_000)
        scope.runCurrent()
        assertEquals(0, handle.startLoads)

        scope.advanceTimeBy(1_001)
        scope.runCurrent()
        assertEquals(1, handle.startLoads)

        // the countdown starts over: one return per delay, not one per tick
        scope.advanceTimeBy(1)
        scope.runCurrent()
        assertEquals(1, handle.startLoads)
    }

    @Test
    fun `nothing returns while the delay is never`() {
        running(returnAfterS = 0)
        scope.advanceTimeBy(120_000)
        scope.runCurrent()
        assertEquals(0, handle.startLoads)
    }

    @Test
    fun `a touch resets the countdown`() {
        running()
        scope.advanceTimeBy(50_000)
        scope.runCurrent()
        manager.onActivity()
        scope.advanceTimeBy(10_000)
        scope.runCurrent()
        assertEquals(0, handle.startLoads, "the touch moved the deadline")
        scope.advanceTimeBy(50_000)
        scope.runCurrent()
        assertEquals(1, handle.startLoads)
    }

    @Test
    fun `a page change resets the countdown, and no dashboard means no return`() {
        running()
        scope.advanceTimeBy(50_000)
        scope.runCurrent()
        manager.onPageChanged()
        scope.advanceTimeBy(10_000)
        scope.runCurrent()
        assertEquals(0, handle.startLoads, "the page change moved the deadline")

        handle.page = "/lovelace/kitchen"
        scope.advanceTimeBy(50_000)
        scope.runCurrent()
        assertEquals(0, handle.startLoads, "already on the start target: no navigation")

        bus.detach(handle)
        scope.advanceTimeBy(120_000)
        scope.runCurrent()
        assertEquals(0, handle.startLoads, "no dashboard showing: nothing to load")
        assertFalse(bus.loadStart())
    }

    @Test
    fun `a client-side move away from the target still returns at the deadline`() {
        // Launch landed on the target (a full load); the user then routed
        // inside the frontend - a history change, no full load. The dashboard
        // reports the new page and a page change fires for it, the way the
        // WebView's visited-history callback now does (review round 2,
        // blocker 2): the countdown resets, and the deadline still returns
        // because the page reported is no longer the target.
        handle.page = "/lovelace/kitchen"
        running()
        scope.advanceTimeBy(30_000)
        scope.runCurrent()
        handle.page = "/lovelace/other"
        manager.onPageChanged()
        scope.advanceTimeBy(30_000)
        scope.runCurrent()
        assertEquals(0, handle.startLoads, "the client-side move reset the countdown")
        scope.advanceTimeBy(30_001)
        scope.runCurrent()
        assertEquals(1, handle.startLoads)
    }

    @Test
    fun `no configured start target: the idle return stays inert`() {
        // Delay only, no start path (review round 3): nothing to return to,
        // and the frontend's "/" redirect means a "/" target could never
        // compare equal - so the countdown does not run at all.
        running(startPath = "", returnAfterS = 60)
        handle.page = "/lovelace/0"
        scope.advanceTimeBy(120_000)
        scope.runCurrent()
        assertEquals(0, handle.startLoads, "no target: no return, no reload loop")
        manager.applySettings(settings(startPath = "/", returnAfterS = 60))
        scope.advanceTimeBy(120_000)
        scope.runCurrent()
        assertEquals(0, handle.startLoads, "the root path is no idle target either")
    }

    @Test
    fun `a settings push restarts a changed countdown and a later push keeps it`() {
        running()
        scope.advanceTimeBy(30_000)
        scope.runCurrent()
        manager.applySettings(settings(returnAfterS = 90))
        scope.advanceTimeBy(60_000)
        scope.runCurrent()
        assertEquals(0, handle.startLoads, "the new delay counts from its own apply")
        scope.advanceTimeBy(30_001)
        scope.runCurrent()
        assertEquals(1, handle.startLoads)
    }

    @Test
    fun `a changed start path alone does not navigate`() {
        running()
        manager.applySettings(settings(startPath = "/lovelace/hall", returnAfterS = 60))
        scope.runCurrent()
        assertEquals(0, handle.startLoads, "changing the setting does not move the page (F-DSH-02)")
        assertTrue(bus.currentPage() == "/lovelace/0")
    }
}
