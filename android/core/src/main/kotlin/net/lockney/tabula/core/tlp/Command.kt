@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package net.lockney.tabula.core.tlp

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Command frames the device receives on its tabula/subscribe subscription
 * (docs/PROTOCOL.md 4.5). They arrive as HA events: {"id": n, "type": "event",
 * "event": {command_id, command, args}}. The envelope is shared; args is kept
 * as a raw JsonObject and decoded on demand into the typed args class, so an
 * unknown command or an unknown enum value surfaces as a decode failure the
 * command layer turns into the right result code (unsupported_command or
 * invalid_args, PROTOCOL 4.8).
 */

/** Command names on the wire (docs/PROTOCOL.md 4.5). */
object Commands {
    const val SET_SETTINGS = "set_settings"
    const val SCREEN = "screen"
    const val NOTIFY = "notify"
    const val PLAY_MEDIA = "play_media"
    const val MEDIA_CONTROL = "media_control"
    const val SCREENSAVER = "screensaver"
    const val NAVIGATE = "navigate"
    const val RELOAD = "reload"
    const val RESTART_APP = "restart_app"
    const val CLEAR_CACHE = "clear_cache"
    const val LOG_SUBSCRIPTION = "log_subscription"
    const val SCREENSHOT = "screenshot"

    val all: Set<String> = setOf(
        SET_SETTINGS, SCREEN, NOTIFY, PLAY_MEDIA, MEDIA_CONTROL, SCREENSAVER,
        NAVIGATE, RELOAD, RESTART_APP, CLEAR_CACHE, LOG_SUBSCRIPTION, SCREENSHOT,
    )
}

@Serializable
data class CommandFrame(
    val id: Int,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) @SerialName("type")
    val type: String = "event",
    val event: CommandEvent,
) {
    init {
        require(id >= 1) { "id must be >= 1" }
        require(type == "event") { "type must be event" }
    }
}

@Serializable
data class CommandEvent(
    @SerialName("command_id") val commandId: String,
    val command: String,
    val args: JsonObject,
) {
    init {
        Wire.requireCommandId(commandId)
    }

    fun setSettingsArgs(json: Json): SetSettingsArgs = json.decodeFromJsonElement(SetSettingsArgs.serializer(), args)

    fun screenArgs(json: Json): ScreenArgs = json.decodeFromJsonElement(ScreenArgs.serializer(), args)

    fun notifyArgs(json: Json): NotifyArgs = json.decodeFromJsonElement(NotifyArgs.serializer(), args)

    fun playMediaArgs(json: Json): PlayMediaArgs = json.decodeFromJsonElement(PlayMediaArgs.serializer(), args)

    fun mediaControlArgs(json: Json): MediaControlArgs =
        json.decodeFromJsonElement(MediaControlArgs.serializer(), args)

    fun screensaverArgs(json: Json): ScreensaverArgs =
        json.decodeFromJsonElement(ScreensaverArgs.serializer(), args)

    fun navigateArgs(json: Json): NavigateArgs = json.decodeFromJsonElement(NavigateArgs.serializer(), args)

    fun emptyArgs(json: Json): EmptyArgs = json.decodeFromJsonElement(EmptyArgs.serializer(), args)

    fun logSubscriptionArgs(json: Json): LogSubscriptionArgs =
        json.decodeFromJsonElement(LogSubscriptionArgs.serializer(), args)

    fun screenshotArgs(json: Json): ScreenshotArgs = json.decodeFromJsonElement(ScreenshotArgs.serializer(), args)
}

/** set_settings: full settings document at a revision (PROTOCOL 4.5, plan 5.6). */
@Serializable
data class SetSettingsArgs(
    val revision: Int,
    @SerialName("schema_version") val schemaVersion: Int,
    val settings: JsonObject,
) {
    init {
        require(revision >= 1) { "set_settings.revision must be >= 1" }
        require(schemaVersion >= 1) { "set_settings.schema_version must be >= 1" }
    }
}

/** screen: panel on/off and manual brightness; at least one argument. */
@Serializable
data class ScreenArgs(
    val on: Boolean? = null,
    val brightness: Int? = null,
) {
    init {
        require(on != null || brightness != null) { "screen needs on or brightness" }
        brightness?.let { Wire.requireRange("screen.brightness", it, 0, 100) }
    }
}

/** notify: on-screen card (PROTOCOL 4.5; FEATURES F-NTF). */
@Serializable
data class NotifyArgs(
    val message: String,
    val title: String? = null,
    val level: NotificationLevel? = null,
    val duration: Int? = null,
    val icon: String? = null,
    val chime: Boolean? = null,
    val tag: String? = null,
) {
    init {
        Wire.requireLength("notify.message", message, 1, 1000)
        title?.let { Wire.requireLength("notify.title", it, 1, 200) }
        duration?.let { Wire.requireRange("notify.duration", it, 0, 3600) }
        icon?.let { Wire.requirePattern("notify.icon", it, Wire.mdiIcon) }
        tag?.let { Wire.requireLength("notify.tag", it, 1, 64) }
    }
}

/** play_media: an audio URL, absolute or a path resolved against the HA base URL. */
@Serializable
data class PlayMediaArgs(
    val url: String,
    @SerialName("media_type") val mediaType: MediaType,
    val announce: Boolean,
    val volume: Int? = null,
) {
    init {
        Wire.requireLength("play_media.url", url, 1, 4096)
        Wire.requirePattern("play_media.url", url, Wire.playUrl)
        volume?.let { Wire.requireRange("play_media.volume", it, 0, 100) }
    }
}

/** media_control: playback and volume; seek needs position, set_volume needs volume. */
@Serializable
data class MediaControlArgs(
    val action: MediaControlAction,
    val volume: Int? = null,
    val position: Double? = null,
) {
    init {
        volume?.let { Wire.requireRange("media_control.volume", it, 0, 100) }
        position?.let { Wire.requireMin("media_control.position", it, 0.0) }
        if (action == MediaControlAction.SET_VOLUME) require(volume != null) {
            "media_control.set_volume needs volume"
        }
        if (action == MediaControlAction.SEEK) require(position != null) { "media_control.seek needs position" }
    }
}

/** screensaver: start or stop, optionally naming the mode for the next start. */
@Serializable
data class ScreensaverArgs(
    val active: Boolean,
    val mode: ScreensaverMode? = null,
)

/** navigate: an HA frontend path, single leading slash, resolved against the HA base URL. */
@Serializable
data class NavigateArgs(
    val path: String,
) {
    init {
        Wire.requireLength("navigate.path", path, 1, 2048)
        Wire.requirePattern("navigate.path", path, Wire.navigatePath)
    }
}

/** reload, restart_app, clear_cache: empty args. */
@Serializable
class EmptyArgs {
    override fun equals(other: Any?): Boolean = other is EmptyArgs

    override fun hashCode(): Int = EmptyArgs::class.hashCode()

    override fun toString(): String = "EmptyArgs()"
}

/** log_subscription: enable or disable log streaming, with a minimum level. */
@Serializable
data class LogSubscriptionArgs(
    val enabled: Boolean,
    val level: LogLevel? = null,
)

/** screenshot: capture and upload; JPEG quality 10-95 (Phase 3). */
@Serializable
data class ScreenshotArgs(
    val quality: Int? = null,
) {
    init {
        quality?.let { Wire.requireRange("screenshot.quality", it, 10, 95) }
    }
}
