package net.lockney.tabula

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The dashboard handle bus (packet P2.2): commands act only on the attached
 * handle, a detached handle is never used again, and page-change listeners
 * unregister cleanly.
 */
class DashboardBusTest {
    private class FakeDashboard(var page: String? = "/") : DashboardBus.Handle {
        var reloads = 0
        var clears = 0
        val loaded = mutableListOf<String>()

        override fun reload() {
            reloads++
        }

        override fun clearCache() {
            clears++
        }

        override fun load(url: String) {
            loaded.add(url)
        }

        override fun currentPage(): String? = page

        var captured = 0

        override suspend fun capture(quality: Int?): ByteArray? {
            captured++
            return byteArrayOf(quality?.toByte() ?: 0)
        }
    }

    @Test
    fun `commands reach the attached dashboard`() {
        val bus = DashboardBus()
        val dashboard = FakeDashboard()
        bus.attach(dashboard)

        assertTrue(bus.reload())
        assertTrue(bus.clearCache())
        assertTrue(bus.load("http://ha:8123/config"))
        assertEquals(1, dashboard.reloads)
        assertEquals(1, dashboard.clears)
        assertEquals(listOf("http://ha:8123/config"), dashboard.loaded)
        assertEquals("/", bus.currentPage())
    }

    @Test
    fun `with no dashboard the commands report failure and no page`() {
        val bus = DashboardBus()
        assertFalse(bus.reload())
        assertFalse(bus.clearCache())
        assertFalse(bus.load("http://ha:8123/config"))
        assertNull(bus.currentPage())
    }

    @Test
    fun `capture reaches the attached dashboard and yields nothing without one`() = kotlinx.coroutines.test.runTest {
        val bus = DashboardBus()
        assertNull(bus.capture(quality = 80))

        val dashboard = FakeDashboard()
        bus.attach(dashboard)
        val jpeg = bus.capture(quality = 80)
        assertEquals(1, dashboard.captured)
        assertEquals(80.toByte(), jpeg!!.first())
    }

    @Test
    fun `detaching removes the handle and a stale one cannot re-attach by accident`() {
        val bus = DashboardBus()
        val first = FakeDashboard()
        val second = FakeDashboard()
        bus.attach(first)
        bus.attach(second)
        bus.detach(first) // an older handle detaching must not drop the current one
        assertTrue(bus.reload())
        assertEquals(0, first.reloads)
        assertEquals(1, second.reloads)

        bus.detach(second)
        assertFalse(bus.reload())
    }

    @Test
    fun `page listeners hear changes and unregister cleanly`() {
        val bus = DashboardBus()
        val heard = mutableListOf<String?>()
        val remove = bus.onPageChanged { path: String? -> heard.add(path) }
        bus.pageChanged("/lovelace/1")
        remove()
        bus.pageChanged("/config")
        assertEquals(listOf("/lovelace/1"), heard.filterNotNull())
    }
}
