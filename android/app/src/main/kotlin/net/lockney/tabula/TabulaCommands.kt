package net.lockney.tabula

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.lockney.tabula.core.settings.SettingsCodec
import net.lockney.tabula.core.tlp.CommandEvent
import net.lockney.tabula.core.tlp.Commands
import net.lockney.tabula.core.tlp.ErrorCode
import net.lockney.tabula.core.tlp.LogSubscriptionArgs
import net.lockney.tabula.core.tlp.MediaControlArgs
import net.lockney.tabula.core.tlp.NotifyArgs
import net.lockney.tabula.core.tlp.PlayMediaArgs
import net.lockney.tabula.core.tlp.ScreenArgs
import net.lockney.tabula.core.tlp.ScreenshotArgs
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
 * check allows, go_home (P3.15) loads the start target through the same
 * bus, screensaver (P2.4) goes to [screensaver], notify (P2.5) to
 * [notifications], play_media and media_control (P2.5) to [audio], and
 * screenshot (P3.13) to [screenshot], which captures, uploads and only then
 * answers, and log_subscription (P5.4) to [logs], which starts or stops the
 * device's log stream.
 */
class TabulaCommands(
    private val store: TabulaStore,
    private val onRestartApp: () -> Unit,
    private val dashboard: DashboardBus = DashboardBus(),
    private val screen: ScreenControl? = null,
    private val screensaver: ScreensaverControl? = null,
    private val notifications: NotificationControl? = null,
    private val audio: AudioControl? = null,
    private val screenshot: ScreenshotControl? = null,
    private val startPage: StartPageControl? = null,
    private val voice: VoiceControl? = null,
    private val logs: LogControl? = null,
    private val intercom: IntercomControl? = null,
) : CommandHandler {
    /** Highest revision applied on this connection (PROTOCOL 4.5). */
    private var connectionRevision = 0

    override fun onNewConnection() {
        connectionRevision = 0
        // A log subscription survives nothing (P5.4, PROTOCOL.md 4.8): a new
        // connection starts with the stream off, and the panel asks again.
        logs?.subscribe(enabled = false, level = null)
    }

    override suspend fun handle(event: CommandEvent): CommandHandler.Reply = when (event.command) {
        Commands.SET_SETTINGS -> setSettings(event.args)
        Commands.RESTART_APP -> {
            onRestartApp()
            CommandHandler.Reply.Ok()
        }
        Commands.SCREEN -> screen?.let { control ->
            val parsed = Tlp.json.decodeFromJsonElement(ScreenArgs.serializer(), event.args)
            control.command(parsed.on, parsed.brightness)
        } ?: undeclared(event.command)
        Commands.SCREENSAVER -> screensaver?.let { control ->
            val parsed = Tlp.json.decodeFromJsonElement(ScreensaverArgs.serializer(), event.args)
            control.command(parsed.active, parsed.mode)
        } ?: undeclared(event.command)
        Commands.NOTIFY -> notifications?.let { control ->
            control.show(Tlp.json.decodeFromJsonElement(NotifyArgs.serializer(), event.args))
        } ?: undeclared(event.command)
        Commands.PLAY_MEDIA -> audio?.let { control ->
            val parsed = Tlp.json.decodeFromJsonElement(PlayMediaArgs.serializer(), event.args)
            control.play(parsed.url, parsed.mediaType, parsed.announce, parsed.volume)
        } ?: undeclared(event.command)
        Commands.MEDIA_CONTROL -> audio?.let { control ->
            val parsed = Tlp.json.decodeFromJsonElement(MediaControlArgs.serializer(), event.args)
            control.control(parsed.action, parsed.volume, parsed.position)
        } ?: undeclared(event.command)
        Commands.SCREENSHOT -> screenshot?.let { control ->
            val parsed = Tlp.json.decodeFromJsonElement(ScreenshotArgs.serializer(), event.args)
            control.capture(parsed.quality, event.commandId)
        } ?: undeclared(event.command)
        Commands.LOG_SUBSCRIPTION -> logs?.let { control ->
            val parsed = Tlp.json.decodeFromJsonElement(LogSubscriptionArgs.serializer(), event.args)
            control.subscribe(parsed.enabled, parsed.level)
        } ?: undeclared(event.command)
        Commands.INTERCOM_START -> intercom?.let { control ->
            control.start(event.intercomStartArgs(Tlp.json))
        } ?: undeclared(event.command)
        Commands.INTERCOM_RING -> intercom?.let { control ->
            control.ring(event.intercomRingArgs(Tlp.json))
        } ?: undeclared(event.command)
        Commands.INTERCOM_SIGNAL -> intercom?.let { control ->
            control.signal(event.intercomSignalArgs(Tlp.json))
        } ?: undeclared(event.command)
        Commands.INTERCOM_END -> intercom?.let { control ->
            control.end(event.intercomEndArgs(Tlp.json))
        } ?: undeclared(event.command)
        Commands.RELOAD -> dashboardAction("reload the dashboard") { dashboard.reload() }
        Commands.CLEAR_CACHE -> dashboardAction("clear the cache") { dashboard.clearCache() }
        Commands.NAVIGATE -> navigate(event.args)
        Commands.GO_HOME -> dashboardAction("go home") { dashboard.loadStart() }
        else -> undeclared(event.command)
    }

    private fun undeclared(command: String): CommandHandler.Reply =
        CommandHandler.Reply.Error(ErrorCode.UNSUPPORTED_COMMAND, "not declared: $command")

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
        startPage?.applySettings(settings)
        voice?.applySettings(settings)
        // the activity re-applies the clock settings while a face shows, so
        // every write reloads the background image (F-SSV-09, P3.3)
        TabulaStatus.settingsRevision.value = parsed.revision
        return CommandHandler.Reply.Ok(buildJsonObject { put("revision", parsed.revision) })
    }
}

/**
 * The screenshot flow (P3.13, PROTOCOL 5): capture the screen and upload the
 * JPEG *before* the command's result is sent, so HA stores the image for a
 * command that is still pending. With the screen off nothing is captured or
 * uploaded and the answer is still Ok - HA then shows its placeholder
 * (F-MGT-07). No dashboard, or an upload HA refuses, fails the command.
 */
/**
 * Wake-word listening follows the settings push (P7.1, ADR 0030): the monitor
 * starts on enable, stops on disable, and a device without RECORD_AUDIO keeps
 * running with the capability undeclared.
 */
fun interface VoiceControl {
    fun applySettings(settings: net.lockney.tabula.core.settings.Settings)
}

interface ScreenshotControl {
    suspend fun capture(quality: Int?, commandId: String): CommandHandler.Reply
}
