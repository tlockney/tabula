package net.lockney.tabula.core.tlp

import kotlinx.serialization.json.Json
import net.lockney.tabula.core.Constants

/**
 * The TLP codec. Frames are typed data classes that validate in [init], so a
 * value that exists is a valid one; encode writes with the same strictness
 * (PROTOCOL 4.0: strict senders, tolerant receivers).
 *
 * - [strict] rejects unknown object members, matching schema additionalProperties
 *   and tools/validate_frames.py; the fixture tests decode through it.
 * - [json] ignores unknown members, which is how a receiver treats frames from
 *   a peer that speaks a newer version (PROTOCOL 4.0).
 */
object Tlp {
    val strict: Json = Json { ignoreUnknownKeys = false }

    val json: Json = Json { ignoreUnknownKeys = true }

    /** Frame types the schema defines (x-tabula-frames). */
    val frameTypes: Set<String> = setOf(
        "register", "subscribe", "state_delta", "command_result", "event", "log", "ping", "pong",
        "register_result", "ack", "error_result",
        "command_set_settings", "command_screen", "command_notify", "command_play_media",
        "command_media_control", "command_screensaver", "command_navigate", "command_go_home",
        "command_reload",
        "command_restart_app", "command_clear_cache", "command_log_subscription", "command_screenshot",
        "command_voice_start", "command_voice_stop",
        "command_intercom_ring", "command_intercom_signal", "command_intercom_end",
        "http_pair_request", "http_pair_response", "http_claim_request", "http_claim_pending",
        "http_claim_response", "http_error", "http_screenshot_response",
    )

    /** Decode a frame of the given schema name (an x-tabula-frames entry), strictly. */
    fun decodeFrame(frame: String, text: String): Any = when (frame) {
        "register" -> strict.decodeFromString(RegisterFrame.serializer(), text)
        "subscribe" -> strict.decodeFromString(SubscribeFrame.serializer(), text)
        "state_delta" -> strict.decodeFromString(StateFrame.serializer(), text)
        "command_result" -> strict.decodeFromString(CommandResultFrame.serializer(), text)
        "event" -> strict.decodeFromString(EventFrame.serializer(), text).also { it.eventData(strict) }
        "log" -> strict.decodeFromString(LogFrame.serializer(), text)
        "ping" -> strict.decodeFromString(PingFrame.serializer(), text)
        "pong" -> strict.decodeFromString(PongFrame.serializer(), text)
        "register_result" -> strict.decodeFromString(RegisterResultFrame.serializer(), text)
        "ack" -> strict.decodeFromString(AckFrame.serializer(), text)
        "error_result" -> strict.decodeFromString(ErrorResultFrame.serializer(), text)
        "command_set_settings" -> commandFrame(text, Commands.SET_SETTINGS) { it.setSettingsArgs(strict) }
        "command_screen" -> commandFrame(text, Commands.SCREEN) { it.screenArgs(strict) }
        "command_notify" -> commandFrame(text, Commands.NOTIFY) { it.notifyArgs(strict) }
        "command_play_media" -> commandFrame(text, Commands.PLAY_MEDIA) { it.playMediaArgs(strict) }
        "command_media_control" -> commandFrame(text, Commands.MEDIA_CONTROL) { it.mediaControlArgs(strict) }
        "command_screensaver" -> commandFrame(text, Commands.SCREENSAVER) { it.screensaverArgs(strict) }
        "command_navigate" -> commandFrame(text, Commands.NAVIGATE) { it.navigateArgs(strict) }
        "command_go_home" -> commandFrame(text, Commands.GO_HOME) { it.emptyArgs(strict) }
        "command_reload" -> commandFrame(text, Commands.RELOAD) { it.emptyArgs(strict) }
        "command_restart_app" -> commandFrame(text, Commands.RESTART_APP) { it.emptyArgs(strict) }
        "command_clear_cache" -> commandFrame(text, Commands.CLEAR_CACHE) { it.emptyArgs(strict) }
        "command_log_subscription" -> commandFrame(text, Commands.LOG_SUBSCRIPTION) { it.logSubscriptionArgs(strict) }
        "command_screenshot" -> commandFrame(text, Commands.SCREENSHOT) { it.screenshotArgs(strict) }
        "command_voice_start" -> commandFrame(text, Commands.VOICE_START) { it.voiceStartArgs(strict) }
        "command_voice_stop" -> commandFrame(text, Commands.VOICE_STOP) { it.voiceStopArgs(strict) }
        "command_intercom_ring" -> commandFrame(text, Commands.INTERCOM_RING) { it.intercomRingArgs(strict) }
        "command_intercom_signal" ->
            commandFrame(text, Commands.INTERCOM_SIGNAL) { it.intercomSignalArgs(strict) }
        "command_intercom_end" -> commandFrame(text, Commands.INTERCOM_END) { it.intercomEndArgs(strict) }
        "http_pair_request" -> strict.decodeFromString(HttpPairRequest.serializer(), text)
        "http_pair_response" -> strict.decodeFromString(HttpPairResponse.serializer(), text)
        "http_claim_request" -> strict.decodeFromString(HttpClaimRequest.serializer(), text)
        "http_claim_pending" -> strict.decodeFromString(HttpClaimPending.serializer(), text)
        "http_claim_response" -> strict.decodeFromString(HttpClaimResponse.serializer(), text)
        "http_error" -> strict.decodeFromString(HttpError.serializer(), text)
        "http_screenshot_response" -> strict.decodeFromString(HttpScreenshotResponse.serializer(), text)
        else -> throw IllegalArgumentException("unknown frame type: $frame")
    }

    private inline fun commandFrame(
        text: String,
        name: String,
        decodeArgs: (CommandEvent) -> Any,
    ): CommandFrame {
        val frame = strict.decodeFromString(CommandFrame.serializer(), text)
        require(frame.event.command == name) { "frame is a ${frame.event.command} command, expected $name" }
        decodeArgs(frame.event)
        return frame
    }

    /**
     * Encode a frame by its schema name, enforcing FRAME_MAX_BYTES (PROTOCOL 4).
     * @throws IllegalArgumentException when the encoded frame is too large or [frame] is unknown.
     */
    fun encodeFrame(frame: String, value: Any): String {
        val text = when (frame) {
            "register" -> strict.encodeToString(RegisterFrame.serializer(), value as RegisterFrame)
            "subscribe" -> strict.encodeToString(SubscribeFrame.serializer(), value as SubscribeFrame)
            "state_delta" -> strict.encodeToString(StateFrame.serializer(), value as StateFrame)
            "command_result" -> strict.encodeToString(CommandResultFrame.serializer(), value as CommandResultFrame)
            "event" -> strict.encodeToString(EventFrame.serializer(), value as EventFrame)
            "log" -> strict.encodeToString(LogFrame.serializer(), value as LogFrame)
            "ping" -> strict.encodeToString(PingFrame.serializer(), value as PingFrame)
            "pong" -> strict.encodeToString(PongFrame.serializer(), value as PongFrame)
            "register_result" -> strict.encodeToString(RegisterResultFrame.serializer(), value as RegisterResultFrame)
            "ack" -> strict.encodeToString(AckFrame.serializer(), value as AckFrame)
            "error_result" -> strict.encodeToString(ErrorResultFrame.serializer(), value as ErrorResultFrame)
            "http_pair_request" -> strict.encodeToString(HttpPairRequest.serializer(), value as HttpPairRequest)
            "http_pair_response" -> strict.encodeToString(HttpPairResponse.serializer(), value as HttpPairResponse)
            "http_claim_request" -> strict.encodeToString(HttpClaimRequest.serializer(), value as HttpClaimRequest)
            "http_claim_pending" -> strict.encodeToString(HttpClaimPending.serializer(), value as HttpClaimPending)
            "http_claim_response" -> strict.encodeToString(HttpClaimResponse.serializer(), value as HttpClaimResponse)
            "http_error" -> strict.encodeToString(HttpError.serializer(), value as HttpError)
            "http_screenshot_response" ->
                strict.encodeToString(HttpScreenshotResponse.serializer(), value as HttpScreenshotResponse)
            else -> throw IllegalArgumentException("unknown frame type: $frame")
        }
        require(text.toByteArray(Charsets.UTF_8).size <= Constants.FRAME_MAX_BYTES) {
            "frame exceeds FRAME_MAX_BYTES (${Constants.FRAME_MAX_BYTES} bytes)"
        }
        return text
    }
}
