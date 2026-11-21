package net.lockney.tabula

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import net.lockney.tabula.core.settings.SettingsCodec
import net.lockney.tabula.core.tlp.Commands
import net.lockney.tabula.core.tlp.ErrorCode
import net.lockney.tabula.core.tlp.ScreenArgs
import net.lockney.tabula.core.tlp.SetSettingsArgs
import net.lockney.tabula.core.tlp.Tlp
import net.lockney.tabula.ha.CommandHandler

/**
 * The commands the device runs (PROTOCOL 4.5):
 * - set_settings applies HA's document (plan 5.6) and hands it to screen control.
 * - screen (P2.3) goes to [screen] when the app declared screen control.
 * - restart_app asks the service to restart the app process.
 * - reload and clear_cache are ACKed as no-ops until the dashboard arrives (P2.2).
 */
class TabulaCommands(
    private val store: TabulaStore,
    private val onRestartApp: () -> Unit,
    private val screen: ScreenControl? = null,
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
        Commands.RELOAD, Commands.CLEAR_CACHE ->
            CommandHandler.Reply.Ok() // no dashboard yet; P2.2 makes these real
        else -> CommandHandler.Reply.Error(ErrorCode.UNSUPPORTED_COMMAND, "not declared: $command")
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
        return CommandHandler.Reply.Ok(buildJsonObject { put("revision", parsed.revision) })
    }
}
