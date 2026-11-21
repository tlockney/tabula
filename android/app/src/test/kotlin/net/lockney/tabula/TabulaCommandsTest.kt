package net.lockney.tabula

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.lockney.tabula.core.tlp.ErrorCode
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
}
