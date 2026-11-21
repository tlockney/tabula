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
 */
class TabulaCommandsTest {
    private fun setSettingsArgs(revision: Int) = buildJsonObject {
        put("revision", revision)
        put("schema_version", 1)
        put("settings", buildJsonObject {})
    }

    private fun store() = TabulaStore(FakeSharedPreferences(), PlainTextCrypto)

    @Test
    fun `an equal revision is accepted and ACKed`() {
        val store = store()
        val commands = TabulaCommands(store, onRestartApp = {})
        val first = commands.handle("set_settings", setSettingsArgs(5))
        val resent = commands.handle("set_settings", setSettingsArgs(5))

        assertTrue(first is CommandHandler.Reply.Ok)
        assertTrue(resent is CommandHandler.Reply.Ok)
        assertEquals(5, (resent as CommandHandler.Reply.Ok).data?.get("revision")?.toString()?.toInt())
        assertEquals(5, store.settingsRevision)
    }

    @Test
    fun `only a strictly lower revision is stale`() {
        val store = store()
        val commands = TabulaCommands(store, onRestartApp = {})
        assertTrue(commands.handle("set_settings", setSettingsArgs(7)) is CommandHandler.Reply.Ok)

        val stale = commands.handle("set_settings", setSettingsArgs(6))
        assertTrue(stale is CommandHandler.Reply.Error)
        assertEquals(ErrorCode.STALE_REVISION, (stale as CommandHandler.Reply.Error).code)
        // the floor did not move, and the stale push did not apply anything
        assertEquals(7, store.settingsRevision)

        // the rejected revision floor does not lock out the current one
        assertTrue(commands.handle("set_settings", setSettingsArgs(7)) is CommandHandler.Reply.Ok)
    }

    @Test
    fun `the revision floor resets on a new connection`() {
        val commands = TabulaCommands(store(), onRestartApp = {})
        assertTrue(commands.handle("set_settings", setSettingsArgs(9)) is CommandHandler.Reply.Ok)
        assertTrue(commands.handle("set_settings", setSettingsArgs(8)) is CommandHandler.Reply.Error)

        commands.onNewConnection()

        // the same revision floor is gone: an earlier revision is accepted again
        assertTrue(commands.handle("set_settings", setSettingsArgs(8)) is CommandHandler.Reply.Ok)
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
