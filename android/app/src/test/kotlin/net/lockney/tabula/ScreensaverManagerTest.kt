package net.lockney.tabula

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import net.lockney.tabula.core.screensaver.ScreensaverController
import net.lockney.tabula.core.settings.Screensaver
import net.lockney.tabula.core.settings.Settings
import net.lockney.tabula.core.tlp.EventName
import net.lockney.tabula.core.tlp.ScreensaverMode
import net.lockney.tabula.core.tlp.StateDelta
import net.lockney.tabula.core.settings.ScreensaverMode as SettingsMode

/** The idle timer against a (virtual) clock, and what each outcome does (packet P2.4). */
@OptIn(ExperimentalCoroutinesApi::class)
class ScreensaverManagerTest {
    private val scope = TestScope()
    private val t0 = 1_800_000_000_000L
    private val shown = mutableListOf<ScreensaverMode?>()
    private val held = mutableListOf<Int?>()
    private val published = mutableListOf<StateDelta>()
    private val manager = ScreensaverManager(
        controller = ScreensaverController(),
        show = { shown += it },
        holdBrightness = { held += it },
        scope = scope,
        now = { t0 + scope.testScheduler.currentTime },
    ).also { it.listener = { delta -> published += delta } }

    private fun settings(timeoutS: Int = 60, mode: SettingsMode = SettingsMode.Clock, enabled: Boolean = true) =
        Settings(screensaver = Screensaver(enabled = enabled, idleTimeout = timeoutS, mode = mode, brightness = 20))

    private fun drainEvents(): List<String> = buildList {
        while (true) {
            val build = manager.events.tryReceive().getOrNull() ?: break
            add(build(1).event.name)
        }
    }

    private fun running() {
        manager.applySettings(settings())
        manager.setForeground(true)
        manager.start()
        scope.runCurrent()
    }

    @AfterTest
    fun tearDown() = manager.stop()

    @Test
    fun `idles into the clock at the timeout and touch dismisses it`() {
        running()
        scope.advanceTimeBy(59_000)
        scope.runCurrent()
        assertTrue(shown.isEmpty())

        scope.advanceTimeBy(1_001)
        scope.runCurrent()
        assertEquals(listOf<ScreensaverMode?>(ScreensaverMode.CLOCK), shown)
        assertEquals(20, held.last(), "the screensaver brightness holds the panel")
        assertEquals(true, published.last { it.screensaverActive != null }.screensaverActive)

        manager.onActivity()
        assertEquals(listOf(ScreensaverMode.CLOCK, null), shown)
        assertNull(held.last(), "the panel is released")
        assertEquals(
            listOf(EventName.SCREENSAVER_STARTED.name, EventName.SCREENSAVER_STOPPED.name),
            drainEvents(),
        )

        // the timer starts over from the touch
        scope.advanceTimeBy(59_000)
        scope.runCurrent()
        assertEquals(2, shown.size)
        scope.advanceTimeBy(1_001)
        scope.runCurrent()
        assertEquals(3, shown.size)
    }

    @Test
    fun `a touch before the timeout moves the deadline`() {
        running()
        scope.advanceTimeBy(50_000)
        manager.onActivity()
        scope.advanceTimeBy(50_000)
        scope.runCurrent()
        assertTrue(shown.isEmpty(), "100 s since start, but only 50 s since the touch")
        scope.advanceTimeBy(10_001)
        scope.runCurrent()
        assertEquals(listOf<ScreensaverMode?>(ScreensaverMode.CLOCK), shown)
    }

    @Test
    fun `no idle start while the panel is off or another app is in front`() {
        running()
        manager.setPanelOn(false)
        scope.advanceTimeBy(600_000)
        scope.runCurrent()
        assertTrue(shown.isEmpty())

        manager.setPanelOn(true)
        manager.setForeground(false)
        scope.advanceTimeBy(600_000)
        scope.runCurrent()
        assertTrue(shown.isEmpty())

        manager.setForeground(true)
        scope.advanceTimeBy(60_001)
        scope.runCurrent()
        assertEquals(1, shown.size)
    }

    @Test
    fun `nothing starts before the app's window is in front`() {
        manager.applySettings(settings())
        manager.start()
        scope.advanceTimeBy(600_000)
        scope.runCurrent()
        assertTrue(shown.isEmpty())
    }

    @Test
    fun `HA starts black and stops it, and black holds the lowest backlight`() {
        running()
        manager.command(active = true, mode = ScreensaverMode.BLACK)
        assertEquals(listOf<ScreensaverMode?>(ScreensaverMode.BLACK), shown)
        assertEquals(0, held.last())
        manager.command(active = false, mode = null)
        assertEquals(listOf(ScreensaverMode.BLACK, null), shown)
        assertNull(held.last())
        assertEquals(2, drainEvents().size)
    }

    @Test
    fun `a new screensaver brightness applies while it shows`() {
        running()
        manager.command(active = true, mode = null)
        manager.applySettings(Settings(screensaver = Screensaver(enabled = true, idleTimeout = 60, brightness = 35)))
        assertEquals(35, held.last())
    }

    @Test
    fun `turning the master switch off dismisses it and no idle start follows`() {
        running()
        manager.command(active = true, mode = null)
        manager.applySettings(settings(enabled = false))
        assertEquals(null, shown.last())
        scope.advanceTimeBy(600_000)
        scope.runCurrent()
        assertEquals(2, shown.size)
    }
}
