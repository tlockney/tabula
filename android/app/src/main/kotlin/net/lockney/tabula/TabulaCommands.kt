package net.lockney.tabula

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.lockney.tabula.core.settings.SettingsCodec
import net.lockney.tabula.core.tlp.Commands
import net.lockney.tabula.core.tlp.ErrorCode
import net.lockney.tabula.core.tlp.MediaControlArgs
import net.lockney.tabula.core.tlp.NotifyArgs
import net.lockney.tabula.core.tlp.PlayMediaArgs
import net.lockney.tabula.core.tlp.ScreenArgs
import net.lockney.tabula.core.tlp.ScreensaverArgs
import net.lockney.tabula.core.tlp.SetSettingsArgs
import net.lockney.tabula.core.tlp.Tlp
import net.lockney.tabula.ha.CommandHandler
import net.lockney.tabula.ha.DashboardLinks

/**
 * The commands a P2.2 device runs (PROTOCOL 4.5): set_settings applies
 * HA's document (plan 5.6) and hands it to every manager, screen (P2.3)
 * goes to [screen] when the app declared screen control, restart_app asks
 * the service to restart the app process, reload and clear_cache drive the
 * dashboard WebView through the [DashboardBus] (the command fails when no
 * dashboard is showing), navigate loads an HA frontend path the origin
 * check allows, screensaver (P2.4) goes to [screensaver], notify (P2.5) to
 * [notifications], and play_media and media_control (P2.5) to [audio].
 */
class TabulaCommands(
    private val store: TabulaStore,
    private val onRestartApp: () -> Unit,
    private val dashboard: DashboardBus = DashboardBus(),
    private val screen: ScreenControl? = null,
    private val screensaver: ScreensaverControl? = null,
    private val notifications: NotificationControl? = null,
    private val audio: AudioControl? = null,
) : CommandHandler {
    /** Highest revision applied on this connection (PROTOCOL 4.5). */
    private var connectionRevision = 0

    override fun onNewConnection() {
        connectionRevision = 0
    }

    override fun handle(command: String, args: JsonObject): CommandHandler.Reply = when (command) {
        Commands.SET_SETTINGS -> setSettings(args)
        Commands.RESTART_APP -> {
            onRestartApp()
            CommandHandler.Reply.Ok()
        }
        Commands.SCREEN -> screen?.let { control ->
            val parsed = Tlp.json.decodeFromJsonElement(ScreenArgs.serializer(), args)
            control.command(parsed.on, parsed.brightness)
        } ?: CommandHandler.Reply.Error(ErrorCode.UNSUPPORTED_COMMAND, "not declared: $command")
        Commands.SCREENSAVER -> screensaver?.let { control ->
            val parsed = Tlp.json.decodeFromJsonElement(ScreensaverArgs.serializer(), args)
            control.command(parsed.active, parsed.mode)
        } ?: CommandHandler.Reply.Error(ErrorCode.UNSUPPORTED_COMMAND, "not declared: $command")
        Commands.NOTIFY -> notifications?.let { control ->
            control.show(Tlp.json.decodeFromJsonElement(NotifyArgs.serializer(), args))
        } ?: CommandHandler.Reply.Error(ErrorCode.UNSUPPORTED_COMMAND, "not declared: $command")
        Commands.PLAY_MEDIA -> audio?.let { control ->
            val parsed = Tlp.json.decodeFromJsonElement(PlayMediaArgs.serializer(), args)
            control.play(parsed.url, parsed.mediaType, parsed.announce, parsed.volume)
        } ?: CommandHandler.Reply.Error(ErrorCode.UNSUPPORTED_COMMAND, "not declared: $command")
        Commands.MEDIA_CONTROL -> audio?.let { control ->
            val parsed = Tlp.json.decodeFromJsonElement(MediaControlArgs.serializer(), args)
            control.control(parsed.action, parsed.volume, parsed.position)
        } ?: CommandHandler.Reply.Error(ErrorCode.UNSUPPORTED_COMMAND, "not declared: $command")
        Commands.RELOAD -> dashboardAction("reload the dashboard") { dashboard.reload() }
        Commands.CLEAR_CACHE -> dashboardAction("clear the cache") { dashboard.clearCache() }
        Commands.NAVIGATE -> navigate(args)
        else -> CommandHandler.Reply.Error(ErrorCode.UNSUPPORTED_COMMAND, "not declared: $command")
    }

    /** reload/clear_cache succeed only when a dashboard is showing (PROTOCOL 6). */
    private inline fun dashboardAction(what: String, run: () -> Boolean): CommandHandler.Reply =
        if (run()) CommandHandler.Reply.Ok()
        else CommandHandler.Reply.Error(ErrorCode.FAILED, "no dashboard is showing; cannot $what")

    /** navigate: an HA frontend path, resolved against the HA URL (PROTOCOL 4.5). */
    private fun navigate(args: JsonObject): CommandHandler.Reply {
        val parsed = runCatching {
            Tlp.json.decodeFromJsonElement(net.lockney.tabula.core.tlp.NavigateArgs.serializer(), args)
        }.getOrElse {
            return CommandHandler.Reply.Error(ErrorCode.INVALID_ARGS, "navigate args rejected: ${it.message}")
        }
        val haUrl = store.haUrl
            ?: return CommandHandler.Reply.Error(ErrorCode.FAILED, "no HA URL stored; cannot navigate")
        val origin = runCatching { java.net.URI(haUrl) }.getOrNull()?.takeIf { it.isAbsolute }
            ?: return CommandHandler.Reply.Error(ErrorCode.FAILED, "the stored HA URL is not absolute")
        val url = DashboardLinks.resolveNavigatePath(parsed.path, origin)
            ?: return CommandHandler.Reply.Error(
                // leaving the origin is refused as invalid_args (PROTOCOL 4.5)
                ErrorCode.INVALID_ARGS,
                "path '${parsed.path}' does not stay on the Home Assistant origin",
            )
        return dashboardAction("navigate to $url") { dashboard.load(url) }
    }

    private fun setSettings(args: JsonObject): CommandHandler.Reply {
        val parsed = Tlp.json.decodeFromJsonElement(SetSettingsArgs.serializer(), args)
        if (parsed.revision < connectionRevision) {
            // only a revision lower than one already applied on this connection
            // is stale (PROTOCOL 4.5); an equal revision is accepted and ACKed -
            // the integration can resend the same revision after two quick saves
            return CommandHandler.Reply.Error(
                ErrorCode.STALE_REVISION,
                "revision ${parsed.revision} is lower than $connectionRevision already applied on this connection",
            )
        }
        val settings = runCatching { SettingsCodec.decode(parsed.settings.toString()) }.getOrElse {
            return CommandHandler.Reply.Error(ErrorCode.INVALID_ARGS, "settings document rejected: ${it.message}")
        }
        store.settings = settings
        store.settingsRevision = parsed.revision
        connectionRevision = parsed.revision
        screen?.applySettings(settings)
        screensaver?.applySettings(settings)
        notifications?.applySettings(settings)
        audio?.applySettings(settings)
        return CommandHandler.Reply.Ok(buildJsonObject { put("revision", parsed.revision) })
    }
}
