package net.lockney.tabula

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.lockney.tabula.core.screen.BrightnessCurve
import net.lockney.tabula.core.screen.ScreenController
import net.lockney.tabula.core.settings.Screen
import net.lockney.tabula.core.settings.Settings
import net.lockney.tabula.core.tlp.ErrorCode
import net.lockney.tabula.core.tlp.StateDelta
import net.lockney.tabula.ha.CommandHandler

private class FakePanel(var adminActive: Boolean = true, var level: Int? = 70) : Panel {
    val writes = mutableListOf<Int>()
    var on = true
    var keepOn: Boolean? = null
    var powerOffs = 0
    var powerOns = 0

    override fun setLevel(percent: Int) {
        writes += percent
        level = percent
    }

    override fun readLevel(): Int? = level

    override val isOn: Boolean
        get() = on

    override val canPowerOff: Boolean
        get() = adminActive

    override fun powerOff() {
        powerOffs++
        on = false
    }

    override fun powerOn() {
        powerOns++
        on = true
    }

    override fun setKeepOn(keep: Boolean) {
        keepOn = keep
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ScreenManagerTest {
    private val scope = TestScope()
    private val panel = FakePanel()
    private val published = mutableListOf<StateDelta>()
    private val persisted = mutableListOf<Screen>()
    private val curve = BrightnessCurve.DEFAULT
    private val manager = ScreenManager(
        controller = ScreenController(),
        panel = panel,
        persist = { persisted += it },
        scope = scope,
        now = { scope.testScheduler.currentTime },
    ).also { it.listener = { delta -> published += delta } }

    private fun settings(brightness: Int = 60, adaptive: Boolean = false, keepOn: Boolean = true) =
        Settings(screen = Screen(brightness = brightness, adaptiveBrightness = adaptive, keepScreenOn = keepOn))

    @Test
    fun `before any settings the panel is left alone and its real level is reported`() {
        manager.onLux(500.0)
        assertTrue(panel.writes.isEmpty())
        assertEquals(70, manager.current().brightness)
        assertEquals(true, manager.current().screenOn)
    }

    @Test
    fun `settings apply the base level and keep-on`() {
        manager.applySettings(settings(brightness = 45, keepOn = false))
        assertEquals(listOf(45), panel.writes)
        assertEquals(false, panel.keepOn)
        assertEquals(StateDelta(brightness = 45, adaptiveBrightness = false, keepScreenOn = false), published.last())
    }

    @Test
    fun `a manual brightness command turns adaptive off, applies at once and is kept`() {
        manager.applySettings(settings(adaptive = true))
        manager.onLux(1000.0)
        scope.advanceUntilIdle()
        published.clear()

        val reply = manager.command(on = null, brightness = 30)
        assertEquals(CommandHandler.Reply.Ok(), reply)
        assertEquals(30, panel.writes.last())
        assertEquals(listOf(StateDelta(brightness = 30, adaptiveBrightness = false)), published)
        assertEquals(listOf(Screen(brightness = 30, adaptiveBrightness = false, keepScreenOn = true)), persisted)

        // later light changes leave the panel at the manual level
        manager.onLux(5.0)
        scope.advanceUntilIdle()
        assertEquals(30, panel.writes.last())
    }

    @Test
    fun `adaptive changes fade to the curve level`() {
        manager.applySettings(settings(brightness = 60, adaptive = true))
        panel.writes.clear()
        manager.onLux(10000.0) // first reading: curve says 100
        scope.runCurrent()
        assertTrue(panel.writes.size in 1..15, "the fade has started but not finished: ${panel.writes}")
        scope.advanceUntilIdle()
        assertEquals(100, panel.writes.last())
        assertTrue(panel.writes.size > 1, "faded, not jumped: ${panel.writes}")
        panel.writes.zipWithNext().forEach { (a, b) -> assertTrue(b >= a) }
    }

    @Test
    fun `a manual level interrupts a running fade and wins`() {
        manager.applySettings(settings(brightness = 60, adaptive = true))
        manager.onLux(10000.0)
        scope.advanceTimeBy(100)
        manager.command(on = null, brightness = 20)
        scope.advanceUntilIdle()
        assertEquals(20, panel.writes.last(), "the fade's tail must not overwrite the manual level: ${panel.writes}")
    }

    @Test
    fun `a reading held by the minimum interval is followed later without a new reading`() {
        manager.applySettings(settings(adaptive = true))
        manager.onLux(10.0)
        scope.advanceTimeBy(3000)
        manager.onLux(1000.0) // applied
        scope.advanceTimeBy(500)
        manager.onLux(10.0) // inside the interval: held, and the sensor goes quiet
        scope.advanceUntilIdle()
        assertEquals(curve.levelFor(10.0), panel.writes.last())
    }

    @Test
    fun `panel off without device admin is refused and changes nothing`() {
        panel.adminActive = false
        manager.applySettings(settings(brightness = 50))
        val reply = manager.command(on = false, brightness = 10)
        assertEquals(ErrorCode.FAILED, (reply as? CommandHandler.Reply.Error)?.code)
        assertEquals(0, panel.powerOffs)
        assertEquals(50, panel.writes.last())
    }

    @Test
    fun `panel off and on drive the hardware`() {
        assertEquals(CommandHandler.Reply.Ok(), manager.command(on = false, brightness = null))
        assertEquals(1, panel.powerOffs)
        assertEquals(CommandHandler.Reply.Ok(), manager.command(on = true, brightness = null))
        assertEquals(1, panel.powerOns)
        manager.onPanelPower(true)
        assertEquals(StateDelta(screenOn = true), published.last())
    }

    @Test
    fun `the screen command reaches screen control through TabulaCommands`() {
        val store = TabulaStore(FakeSharedPreferences(), PlainTextCrypto)
        val commands = TabulaCommands(store, onRestartApp = {}, screen = manager)
        val reply = commands.handle("screen", buildJsonObject { put("brightness", 40) })
        assertEquals(CommandHandler.Reply.Ok(), reply)
        assertEquals(40, panel.writes.last())

        val without = TabulaCommands(store, onRestartApp = {})
        val unsupported = without.handle("screen", buildJsonObject { put("on", true) })
        assertEquals(ErrorCode.UNSUPPORTED_COMMAND, (unsupported as CommandHandler.Reply.Error).code)
    }

    @Test
    fun `set_settings hands the document to screen control`() {
        val store = TabulaStore(FakeSharedPreferences(), PlainTextCrypto)
        val commands = TabulaCommands(store, onRestartApp = {}, screen = manager)
        val reply = commands.handle(
            "set_settings",
            buildJsonObject {
                put("revision", 3)
                put("schema_version", 1)
                put(
                    "settings",
                    buildJsonObject {
                        put("screen", buildJsonObject { put("brightness", 35) })
                    },
                )
            },
        )
        assertTrue(reply is CommandHandler.Reply.Ok)
        assertEquals(35, panel.writes.last())
    }

    @Test
    fun `the screensaver holds its level and the decided level returns after`() {
        manager.applySettings(settings(brightness = 60))
        manager.setOverride(20)
        assertEquals(20, panel.level)

        // HA moves the brightness while the screensaver shows: reported, not shown
        manager.command(on = null, brightness = 80)
        assertEquals(20, panel.level)
        assertEquals(80, manager.current().brightness)

        manager.setOverride(null)
        assertEquals(80, panel.level)
        assertEquals(listOf(60, 20, 80), panel.writes)
        assertTrue(persisted.none { it.brightness == 20 }, "the screensaver level is never stored")
    }

    @Test
    fun `before any settings the override returns the panel's own level`() {
        manager.setOverride(0)
        assertEquals(70, manager.current().brightness, "the level from before is reported")
        manager.setOverride(null)
        assertEquals(listOf(0, 70), panel.writes)
    }

    @Test
    fun `an adaptive fade does not run under the override`() {
        manager.applySettings(settings(adaptive = true))
        manager.onLux(10_000.0)
        scope.advanceUntilIdle()
        manager.setOverride(5)
        val before = panel.writes.size
        scope.advanceTimeBy(5_000)
        manager.onLux(1.0)
        scope.advanceUntilIdle()
        assertEquals(before, panel.writes.size, "nothing reached the panel")
        manager.setOverride(null)
        assertEquals(curve.levelFor(1.0), panel.level)
    }
}
