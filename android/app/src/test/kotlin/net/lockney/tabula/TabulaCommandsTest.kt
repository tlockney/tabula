package net.lockney.tabula

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.lockney.tabula.core.settings.Settings
import net.lockney.tabula.core.tlp.CommandEvent
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
 * The P2.2 dashboard commands: reload and clear_cache reach the attached
 * dashboard and fail cleanly when none is showing (PROTOCOL 6), and navigate
 * resolves its path against the HA URL, refusing anything that would leave
 * the origin (PROTOCOL 4.5, ADR 0001 item 3).
 *
 * And the P3.13 screenshot dispatch: the command reaches the capture flow
 * with its quality hint and command id, and is unsupported when the device
 * did not declare the capability.
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

        var startLoads = 0

        override fun loadStart() {
            startLoads++
        }

        override fun currentPage(): String? = page

        override suspend fun capture(quality: Int?): ByteArray? = byteArrayOf(1, 2, 3)
    }

    private fun commands(dashboard: DashboardBus): TabulaCommands =
        TabulaCommands(store(), onRestartApp = {}, dashboard = dashboard)

    private fun busWith(handle: DashboardBus.Handle? = null): DashboardBus {
        val bus = DashboardBus()
        if (handle != null) bus.attach(handle)
        return bus
    }

    /** Every dispatch goes through CommandEvent (PROTOCOL 4.5). */
    private suspend fun TabulaCommands.run(command: String, args: kotlinx.serialization.json.JsonObject): CommandHandler.Reply =
        handle(CommandEvent("cmd-0001", command, args))

    private class FakeScreenshot : ScreenshotControl {
        val captured = mutableListOf<Pair<Int?, String>>()

        override suspend fun capture(quality: Int?, commandId: String): CommandHandler.Reply {
            captured += quality to commandId
            return CommandHandler.Reply.Ok()
        }
    }

    // --- set_settings -----------------------------------------------------------

    @Test
    fun `a name typed at pairing survives a set_settings with no identity name`() = runTest {
        // issue #100: set_settings replaces the whole document, and HA keeps the
        // pairing name on its device record rather than in identity.name
        val store = store()
        store.pairingName = "Office Kiosk"
        store.settings = store.settings.copy(identity = store.settings.identity.copy(name = "Office Kiosk"))
        val commands = TabulaCommands(store, onRestartApp = {}, dashboard = busWith())

        assertTrue(commands.run("set_settings", setSettingsArgs(1)) is CommandHandler.Reply.Ok)

        assertEquals(null, store.settings.identity.name)
        assertEquals("Office Kiosk", store.nameForPairing("Fire Tablet 78b2"))
    }

    @Test
    fun `a name set in HA's settings wins over the pairing name`() = runTest {
        val store = store()
        store.pairingName = "Office Kiosk"
        val commands = TabulaCommands(store, onRestartApp = {}, dashboard = busWith())
        val args = buildJsonObject {
            put("revision", 1)
            put("schema_version", 1)
            put("settings", buildJsonObject { put("identity", buildJsonObject { put("name", "Hall Kiosk") }) })
        }

        assertTrue(commands.run("set_settings", args) is CommandHandler.Reply.Ok)

        assertEquals("Hall Kiosk", store.nameForPairing("Fire Tablet 78b2"))
    }

    @Test
    fun `with no names stored the default is used`() {
        assertEquals("Fire Tablet 78b2", store().nameForPairing("Fire Tablet 78b2"))
    }

    @Test
    fun `an equal revision is accepted and ACKed`() = runTest {
        val store = store()
        val commands = TabulaCommands(store, onRestartApp = {}, dashboard = DashboardBus())
        val first = commands.run("set_settings", setSettingsArgs(5))
        val resent = commands.run("set_settings", setSettingsArgs(5))

        assertTrue(first is CommandHandler.Reply.Ok)
        assertTrue(resent is CommandHandler.Reply.Ok)
        assertEquals(5, resent.data?.get("revision")?.toString()?.toInt())
        assertEquals(5, store.settingsRevision)
    }

    @Test
    fun `only a strictly lower revision is stale`() = runTest {
        val store = store()
        val commands = TabulaCommands(store, onRestartApp = {}, dashboard = DashboardBus())
        assertTrue(commands.run("set_settings", setSettingsArgs(7)) is CommandHandler.Reply.Ok)

        val stale = commands.run("set_settings", setSettingsArgs(6))
        assertTrue(stale is CommandHandler.Reply.Error)
        assertEquals(ErrorCode.STALE_REVISION, stale.code)
        // the floor did not move, and the stale push did not apply anything
        assertEquals(7, store.settingsRevision)

        // the rejected revision floor does not lock out the current one
        assertTrue(commands.run("set_settings", setSettingsArgs(7)) is CommandHandler.Reply.Ok)
    }

    @Test
    fun `the revision floor resets on a new connection`() = runTest {
        val commands = commands(DashboardBus())
        assertTrue(commands.run("set_settings", setSettingsArgs(9)) is CommandHandler.Reply.Ok)
        assertTrue(commands.run("set_settings", setSettingsArgs(8)) is CommandHandler.Reply.Error)

        commands.onNewConnection()

        // the same revision floor is gone: an earlier revision is accepted again
        assertTrue(commands.run("set_settings", setSettingsArgs(8)) is CommandHandler.Reply.Ok)
    }

    // --- reload / clear_cache ------------------------------------------------------

    @Test
    fun `reload reaches the dashboard`() = runTest {
        val dashboard = FakeDashboard()
        val reply = commands(busWith(dashboard)).run("reload", buildJsonObject {})
        assertTrue(reply is CommandHandler.Reply.Ok)
        assertEquals(1, dashboard.reloads)
    }

    @Test
    fun `reload fails when no dashboard is showing`() = runTest {
        val reply = commands(busWith()).run("reload", buildJsonObject {})
        assertTrue(reply is CommandHandler.Reply.Error)
        assertEquals(ErrorCode.FAILED, reply.code)
    }

    @Test
    fun `clear_cache reaches the dashboard`() = runTest {
        val dashboard = FakeDashboard()
        val reply = commands(busWith(dashboard)).run("clear_cache", buildJsonObject {})
        assertTrue(reply is CommandHandler.Reply.Ok)
        assertEquals(1, dashboard.clears)
    }

    @Test
    fun `clear_cache fails when no dashboard is showing`() = runTest {
        val reply = commands(busWith()).run("clear_cache", buildJsonObject {})
        assertTrue(reply is CommandHandler.Reply.Error)
        assertEquals(ErrorCode.FAILED, reply.code)
    }

    // --- navigate ------------------------------------------------------------------

    private fun navigateArgs(path: String) = buildJsonObject { put("path", path) }

    @Test
    fun `navigate loads a resolved frontend path`() = runTest {
        val dashboard = FakeDashboard()
        val store = store().apply { haUrl = "http://ha:8123" }
        val commands = TabulaCommands(store, onRestartApp = {}, dashboard = busWith(dashboard))
        val reply = commands.run("navigate", navigateArgs("/lovelace/1"))
        assertTrue(reply is CommandHandler.Reply.Ok)
        assertEquals(listOf("http://ha:8123/lovelace/1"), dashboard.loaded)
    }

    @Test
    fun `navigate refuses a path that leaves the HA origin`() = runTest {
        val dashboard = FakeDashboard()
        val store = store().apply { haUrl = "http://ha:8123" }
        val commands = TabulaCommands(store, onRestartApp = {}, dashboard = busWith(dashboard))
        val reply = commands.run("navigate", navigateArgs("//example.com/x"))
        assertTrue(reply is CommandHandler.Reply.Error)
        assertEquals(ErrorCode.INVALID_ARGS, reply.code)
        assertTrue(dashboard.loaded.isEmpty())
    }

    @Test
    fun `navigate fails when no dashboard is showing`() = runTest {
        val store = store().apply { haUrl = "http://ha:8123" }
        val commands = TabulaCommands(store, onRestartApp = {}, dashboard = busWith())
        val reply = commands.run("navigate", navigateArgs("/lovelace/1"))
        assertTrue(reply is CommandHandler.Reply.Error)
        assertEquals(ErrorCode.FAILED, reply.code)
    }

    // --- go_home (P3.15) -----------------------------------------------------------

    @Test
    fun `go_home asks the dashboard for its start target`() = runTest {
        val dashboard = FakeDashboard()
        val reply = commands(busWith(dashboard)).run("go_home", buildJsonObject {})
        assertTrue(reply is CommandHandler.Reply.Ok)
        assertEquals(1, dashboard.startLoads)
        assertTrue(dashboard.loaded.isEmpty(), "the bus's loadStart carries it, not load")
    }

    @Test
    fun `go_home fails when no dashboard is showing`() = runTest {
        val reply = commands(busWith()).run("go_home", buildJsonObject {})
        assertTrue(reply is CommandHandler.Reply.Error)
        assertEquals(ErrorCode.FAILED, reply.code)
    }

    private class FakeStartPage : StartPageControl {
        val settings = mutableListOf<Settings>()

        override fun applySettings(settings: Settings) {
            this.settings += settings
        }
    }

    @Test
    fun `set_settings reaches the start page manager too`() = runTest {
        val start = FakeStartPage()
        val commands = TabulaCommands(store(), onRestartApp = {}, startPage = start)
        assertTrue(commands.run("set_settings", setSettingsArgs(1)) is CommandHandler.Reply.Ok)
        assertEquals(1, start.settings.size)
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
    fun `the screensaver command and settings reach the screensaver`() = runTest {
        val saver = FakeScreensaver()
        val commands = TabulaCommands(store(), onRestartApp = {}, screensaver = saver)
        val reply = commands.run(
            "screensaver",
            buildJsonObject {
                put("active", true)
                put("mode", "black")
            },
        )
        assertTrue(reply is CommandHandler.Reply.Ok)
        assertEquals(listOf<Pair<Boolean, ScreensaverMode?>>(true to ScreensaverMode.BLACK), saver.commands)
        commands.run("set_settings", setSettingsArgs(1))
        assertEquals(1, saver.settings.size)

        val without = TabulaCommands(store(), onRestartApp = {})
        val unsupported = without.run("screensaver", buildJsonObject { put("active", false) })
        assertEquals(ErrorCode.UNSUPPORTED_COMMAND, (unsupported as CommandHandler.Reply.Error).code)
    }

    // --- notify / play_media / media_control (P2.5) ------------------------------

    private class FakeNotifications : NotificationControl {
        val shown = mutableListOf<net.lockney.tabula.core.tlp.NotifyArgs>()
        val settings = mutableListOf<Settings>()

        override fun show(args: net.lockney.tabula.core.tlp.NotifyArgs): CommandHandler.Reply {
            shown += args
            return CommandHandler.Reply.Ok()
        }

        override fun applySettings(settings: Settings) {
            this.settings += settings
        }
    }

    private class FakeAudio : AudioControl {
        val played = mutableListOf<Triple<String, Boolean, Int?>>()
        val controls = mutableListOf<Triple<String, Int?, Double?>>()
        val settings = mutableListOf<Settings>()

        override fun play(url: String, mediaType: net.lockney.tabula.core.tlp.MediaType, announce: Boolean, volume: Int?): CommandHandler.Reply {
            played += Triple(url, announce, volume)
            return CommandHandler.Reply.Ok()
        }

        override fun control(
            action: net.lockney.tabula.core.tlp.MediaControlAction,
            volume: Int?,
            position: Double?,
        ): CommandHandler.Reply {
            controls += Triple(action.name, volume, position)
            return CommandHandler.Reply.Ok()
        }

        override fun applySettings(settings: Settings) {
            this.settings += settings
        }
    }

    @Test
    fun `notify, play_media and media_control reach the managers`() = runTest {
        val notes = FakeNotifications()
        val audio = FakeAudio()
        val commands = TabulaCommands(store(), onRestartApp = {}, notifications = notes, audio = audio)

        val notify = commands.run(
            "notify",
            buildJsonObject {
                put("message", "dryer done")
                put("title", "Laundry")
                put("level", "success")
                put("chime", false)
                put("tag", "dryer")
            },
        )
        assertTrue(notify is CommandHandler.Reply.Ok)
        assertEquals(1, notes.shown.size)
        assertEquals("dryer done", notes.shown.single().message)
        assertEquals(net.lockney.tabula.core.tlp.NotificationLevel.SUCCESS, notes.shown.single().level)

        val play = commands.run(
            "play_media",
            buildJsonObject {
                put("url", "/api/tts_proxy/a.mp3")
                put("media_type", "audio")
                put("announce", true)
                put("volume", 70)
            },
        )
        assertTrue(play is CommandHandler.Reply.Ok)
        assertEquals(listOf(Triple<String, Boolean, Int?>("/api/tts_proxy/a.mp3", true, 70)), audio.played)

        val control = commands.run(
            "media_control",
            buildJsonObject {
                put("action", "set_volume")
                put("volume", 30)
            },
        )
        assertTrue(control is CommandHandler.Reply.Ok)
        assertEquals(listOf(Triple<String, Int?, Double?>("SET_VOLUME", 30, null)), audio.controls)

        commands.run("set_settings", setSettingsArgs(1))
        assertEquals(1, notes.settings.size)
        assertEquals(1, audio.settings.size)
    }

    @Test
    fun `notify, play_media and media_control are unsupported when not declared`() = runTest {
        val commands = TabulaCommands(store(), onRestartApp = {})
        assertEquals(
            ErrorCode.UNSUPPORTED_COMMAND,
            (commands.run("notify", buildJsonObject { put("message", "x") }) as CommandHandler.Reply.Error).code,
        )
        assertEquals(
            ErrorCode.UNSUPPORTED_COMMAND,
            (commands.run(
                "play_media",
                buildJsonObject { put("url", "/a.mp3"); put("media_type", "audio"); put("announce", false) },
            ) as CommandHandler.Reply.Error).code,
        )
        assertEquals(
            ErrorCode.UNSUPPORTED_COMMAND,
            (commands.run("media_control", buildJsonObject { put("action", "pause") }) as CommandHandler.Reply.Error).code,
        )
    }

    // --- screenshot (P3.13) --------------------------------------------------------

    @Test
    fun `screenshot reaches the capture flow with quality and command id`() = runTest {
        val shot = FakeScreenshot()
        val commands = TabulaCommands(store(), onRestartApp = {}, screenshot = shot)
        val reply = commands.run("screenshot", buildJsonObject { put("quality", 60) })
        assertTrue(reply is CommandHandler.Reply.Ok)
        assertEquals(listOf<Pair<Int?, String>>(60 to "cmd-0001"), shot.captured)

        val withoutQuality = commands.run("screenshot", buildJsonObject {})
        assertTrue(withoutQuality is CommandHandler.Reply.Ok)
        assertEquals(listOf<Pair<Int?, String>>(60 to "cmd-0001", null to "cmd-0001"), shot.captured)
    }

    @Test
    fun `screenshot is unsupported when not declared`() = runTest {
        val commands = TabulaCommands(store(), onRestartApp = {})
        val reply = commands.run("screenshot", buildJsonObject {})
        assertTrue(reply is CommandHandler.Reply.Error)
        assertEquals(ErrorCode.UNSUPPORTED_COMMAND, reply.code)
    }
}
