@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package net.lockney.tabula.core.tlp

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Frames the device sends on the HA WebSocket (docs/PROTOCOL.md 4.1-4.8).
 * Each class validates in [init], so constructing or decoding one enforces the
 * schema bounds; `type` is always written even though it has a default.
 */

/** tabula/register (PROTOCOL 4.2): identity, versions, capabilities, full state. */
@Serializable
data class RegisterFrame(
    val id: Int,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) @SerialName("type")
    val type: String = "tabula/register",
    @SerialName("device_id") val deviceId: String,
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("app_version") val appVersion: String,
    val manufacturer: String,
    val model: String,
    @SerialName("os_version") val osVersion: String,
    val capabilities: List<String>,
    @SerialName("settings_revision") val settingsRevision: Int,
    val state: StateDelta,
) {
    init {
        require(id >= 1) { "id must be >= 1" }
        require(type == "tabula/register") { "type must be tabula/register" }
        Wire.requireDeviceId(deviceId)
        require(protocolVersion >= 1) { "protocol_version must be >= 1" }
        require(schemaVersion >= 1) { "schema_version must be >= 1" }
        Wire.requireLength("app_version", appVersion, 1, 64)
        Wire.requireLength("manufacturer", manufacturer, 1, 64)
        Wire.requireLength("model", model, 1, 64)
        Wire.requireLength("os_version", osVersion, 1, 64)
        require(capabilities.size <= 64) { "at most 64 capabilities" }
        require(capabilities.distinct().size == capabilities.size) { "capabilities must be unique" }
        capabilities.forEach { Wire.requirePattern("capability", it, Wire.capability) }
        require(settingsRevision >= 0) { "settings_revision must be >= 0" }
        require(state.isNotEmpty) { "register.state must carry at least one key" }
    }
}

/** tabula/subscribe (PROTOCOL 4.3): opens the command channel. */
@Serializable
data class SubscribeFrame(
    val id: Int,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) @SerialName("type")
    val type: String = "tabula/subscribe",
) {
    init {
        require(id >= 1) { "id must be >= 1" }
        require(type == "tabula/subscribe") { "type must be tabula/subscribe" }
    }
}

/** tabula/state (PROTOCOL 4.4): a state delta, at least one key. */
@Serializable
data class StateFrame(
    val id: Int,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) @SerialName("type")
    val type: String = "tabula/state",
    val state: StateDelta,
) {
    init {
        require(id >= 1) { "id must be >= 1" }
        require(type == "tabula/state") { "type must be tabula/state" }
        require(state.isNotEmpty) { "state must carry at least one key" }
    }
}

/** tabula/result (PROTOCOL 4.6): exactly one reply per command. */
@Serializable
data class CommandResultFrame(
    val id: Int,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) @SerialName("type")
    val type: String = "tabula/result",
    @SerialName("command_id") val commandId: String,
    val ok: Boolean,
    val error: TlpError? = null,
    val data: kotlinx.serialization.json.JsonObject? = null,
) {
    init {
        require(id >= 1) { "id must be >= 1" }
        require(type == "tabula/result") { "type must be tabula/result" }
        Wire.requireCommandId(commandId)
        if (ok) {
            require(error == null) { "ok=true must not carry error" }
        } else {
            require(error != null) { "ok=false requires error" }
        }
    }

    companion object {
        fun ack(id: Int, commandId: String, data: kotlinx.serialization.json.JsonObject? = null) =
            CommandResultFrame(id = id, commandId = commandId, ok = true, data = data)

        fun nack(id: Int, commandId: String, error: TlpError) =
            CommandResultFrame(id = id, commandId = commandId, ok = false, error = error)
    }
}

/** Result error body (PROTOCOL 4.6, $defs "error"). */
@Serializable
data class TlpError(
    val code: ErrorCode,
    val message: String,
) {
    init {
        Wire.requireLength("error.message", message, 1, 500)
    }
}

/** tabula/log (PROTOCOL 4.8): a batch of 1..LOG_MAX_LINES lines. */
@Serializable
data class LogFrame(
    val id: Int,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) @SerialName("type")
    val type: String = "tabula/log",
    val lines: List<LogLine>,
) {
    init {
        require(id >= 1) { "id must be >= 1" }
        require(type == "tabula/log") { "type must be tabula/log" }
        require(lines.size in 1..net.lockney.tabula.core.Constants.LOG_MAX_LINES) {
            "log frames carry 1-${net.lockney.tabula.core.Constants.LOG_MAX_LINES} lines"
        }
    }
}

@Serializable
data class LogLine(
    val ts: String,
    val level: LogLevel,
    val tag: String,
    val message: String,
) {
    init {
        Wire.requireDateTime("log_line.ts", ts)
        Wire.requireLength("log_line.tag", tag, 1, 64)
        Wire.requireLength("log_line.message", message, 1, 4000)
    }
}

/** HA core keepalive the device sends (PROTOCOL 4). */
@Serializable
data class PingFrame(
    val id: Int,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) @SerialName("type")
    val type: String = "ping",
) {
    init {
        require(id >= 1) { "id must be >= 1" }
        require(type == "ping") { "type must be ping" }
    }
}

/** HA core's reply to ping. */
@Serializable
data class PongFrame(
    val id: Int,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) @SerialName("type")
    val type: String = "pong",
) {
    init {
        require(id >= 1) { "id must be >= 1" }
        require(type == "pong") { "type must be pong" }
    }
}
