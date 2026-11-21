package net.lockney.tabula

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.lockney.tabula.core.settings.Settings
import net.lockney.tabula.core.tlp.ErrorCode
import net.lockney.tabula.core.tlp.ScreensaverMode
import net.lockney.tabula.ha.CommandHandler

/**
 * The set_settings revision floor (PROTOCOL 4.5): only a revision *lower*
 * than one already applied on the connection is stale. An equal revision is
 * accepted and ACKed - the integration can resend the same revision after
 * two quick saves schedule two pushes that both read the latest document
 * (PR review item 1).
 *
 * And the P2.2 dashboard commands: reload and clear_cache reach the attached
 * dashboard and fail cleanly when none is showing (PROTOCOL 6), and navigate
 * resolves its path against the HA URL, refusing anything that would leave
 * the origin (PROTOCOL 4.5, ADR 0001 item 3).
 */
class TabulaCommandsTest {
    private fun setSettingsArgs(revision: Int) = buildJsonObject {
        put("revision", revision)
        put("schema_version", 1)
        put("settings", buildJsonObject {})
    }

    private fun store() = TabulaStore(FakeSharedPreferences(), PlainTextCrypto)

    /** A dashboard that records what it was asked to do. */
    private class FakeDashboard(
        var page: String? = "/",
    ) : DashboardBus.Handle {
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
    }

    private fun commands(dashboard: DashboardBus): TabulaCommands =
        TabulaCommands(store(), onRestartApp = {}, dashboard = dashboard)

    private fun busWith(handle: DashboardBus.Handle? = null): DashboardBus {
        val bus = DashboardBus()
        if (handle != null) bus.attach(handle)
        return bus
    }

    // --- set_settings -----------------------------------------------------------

    @Test
    fun `an equal revision is accepted and ACKed`() {
        val store = store()
        val commands = TabulaCommands(store, onRestartApp = {}, dashboard = DashboardBus())
        val first = commands.handle("set_settings", setSettingsArgs(5))
        val resent = commands.handle("set_settings", setSettingsArgs(5))

        assertTrue(first is CommandHandler.Reply.Ok)
        assertTrue(resent is CommandHandler.Reply.Ok)
        assertEquals(5, resent.data?.get("revision")?.toString()?.toInt())
        assertEquals(5, store.settingsRevision)
    }

    @Test
    fun `only a strictly lower revision is stale`() {
        val store = store()
        val commands = TabulaCommands(store, onRestartApp = {}, dashboard = DashboardBus())
        assertTrue(commands.handle("set_settings", setSettingsArgs(7)) is CommandHandler.Reply.Ok)

        val stale = commands.handle("set_settings", setSettingsArgs(6))
        assertTrue(stale is CommandHandler.Reply.Error)
        assertEquals(ErrorCode.STALE_REVISION, stale.code)
        // the floor did not move, and the stale push did not apply anything
        assertEquals(7, store.settingsRevision)

        // the rejected revision floor does not lock out the current one
        assertTrue(commands.handle("set_settings", setSettingsArgs(7)) is CommandHandler.Reply.Ok)
    }

    @Test
    fun `the revision floor resets on a new connection`() {
        val commands = commands(DashboardBus())
        assertTrue(commands.handle("set_settings", setSettingsArgs(9)) is CommandHandler.Reply.Ok)
        assertTrue(commands.handle("set_settings", setSettingsArgs(8)) is CommandHandler.Reply.Error)

        commands.onNewConnection()

        // the same revision floor is gone: an earlier revision is accepted again
        assertTrue(commands.handle("set_settings", setSettingsArgs(8)) is CommandHandler.Reply.Ok)
    }

    // --- reload / clear_cache ------------------------------------------------------

    @Test
    fun `reload reaches the dashboard`() {
        val dashboard = FakeDashboard()
        val reply = commands(busWith(dashboard)).handle("reload", buildJsonObject {})
        assertTrue(reply is CommandHandler.Reply.Ok)
        assertEquals(1, dashboard.reloads)
    }

    @Test
    fun `reload fails when no dashboard is showing`() {
        val reply = commands(busWith()).handle("reload", buildJsonObject {})
        assertTrue(reply is CommandHandler.Reply.Error)
        assertEquals(ErrorCode.FAILED, reply.code)
    }

    @Test
    fun `clear_cache reaches the dashboard`() {
        val dashboard = FakeDashboard()
        val reply = commands(busWith(dashboard)).handle("clear_cache", buildJsonObject {})
        assertTrue(reply is CommandHandler.Reply.Ok)
        assertEquals(1, dashboard.clears)
    }

    @Test
    fun `clear_cache fails when no dashboard is showing`() {
        val reply = commands(busWith()).handle("clear_cache", buildJsonObject {})
        assertTrue(reply is CommandHandler.Reply.Error)
        assertEquals(ErrorCode.FAILED, reply.code)
    }

    // --- navigate ------------------------------------------------------------------

    private fun navigateArgs(path: String) = buildJsonObject { put("path", path) }

    @Test
    fun `navigate loads a resolved frontend path`() {
        val dashboard = FakeDashboard()
        val store = store().apply { haUrl = "http://ha:8123" }
        val commands = TabulaCommands(store, onRestartApp = {}, dashboard = busWith(dashboard))
        val reply = commands.handle("navigate", navigateArgs("/lovelace/1"))
        assertTrue(reply is CommandHandler.Reply.Ok)
        assertEquals(listOf("http://ha:8123/lovelace/1"), dashboard.loaded)
    }

    @Test
    fun `navigate refuses a path that leaves the HA origin`() {
        val dashboard = FakeDashboard()
        val store = store().apply { haUrl = "http://ha:8123" }
        val commands = TabulaCommands(store, onRestartApp = {}, dashboard = busWith(dashboard))
        val reply = commands.handle("navigate", navigateArgs("//example.com/x"))
        assertTrue(reply is CommandHandler.Reply.Error)
        assertEquals(ErrorCode.INVALID_ARGS, reply.code)
        assertTrue(dashboard.loaded.isEmpty())
    }

    @Test
    fun `navigate fails when no dashboard is showing`() {
        val store = store().apply { haUrl = "http://ha:8123" }
        val commands = TabulaCommands(store, onRestartApp = {}, dashboard = busWith())
        val reply = commands.handle("navigate", navigateArgs("/lovelace/1"))
        assertTrue(reply is CommandHandler.Reply.Error)
        assertEquals(ErrorCode.FAILED, reply.code)
    }

    private class FakeScreensaver : ScreensaverControl {
        val commands = mutableListOf<Pair<Boolean, ScreensaverMode?>>()
        val settings = mutableListOf<Settings>()

        override fun command(active: Boolean, mode: ScreensaverMode?): CommandHandler.Reply {
            commands += active to mode
            return CommandHandler.Reply.Ok()
        }

        override fun applySettings(settings: Settings) {
            this.settings += settings
        }
    }

    @Test
    fun `the screensaver command and settings reach the screensaver`() {
        val saver = FakeScreensaver()
        val commands = TabulaCommands(store(), onRestartApp = {}, screensaver = saver)
        val reply = commands.handle(
            "screensaver",
            buildJsonObject {
                put("active", true)
                put("mode", "black")
            },
        )
        assertTrue(reply is CommandHandler.Reply.Ok)
        assertEquals(listOf(true to ScreensaverMode.BLACK), saver.commands)
        commands.handle("set_settings", setSettingsArgs(1))
        assertEquals(1, saver.settings.size)

        val without = TabulaCommands(store(), onRestartApp = {})
        val unsupported = without.handle("screensaver", buildJsonObject { put("active", false) })
        assertEquals(ErrorCode.UNSUPPORTED_COMMAND, (unsupported as CommandHandler.Reply.Error).code)
    }
}
