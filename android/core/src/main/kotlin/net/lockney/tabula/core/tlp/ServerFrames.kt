@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package net.lockney.tabula.core.tlp

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * HA result envelopes the device receives (docs/PROTOCOL.md 4): ack,
 * register_result and error_result all ride HA's {"type": "result"} shape but
 * differ in success and body; each frame type is its own class and validates in
 * [init].
 */

/** ack: success with a null result (subscribe, state, result, event, log). */
@Serializable
data class AckFrame(
    val id: Int,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) @SerialName("type")
    val type: String = "result",
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val success: Boolean = true,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val result: JsonElement = JsonNull,
) {
    init {
        require(id >= 1) { "id must be >= 1" }
        require(type == "result") { "type must be result" }
        require(success) { "ack success must be true" }
        require(result is JsonNull) { "ack result must be null" }
    }
}

/** register_result (PROTOCOL 4.2). */
@Serializable
data class RegisterResultFrame(
    val id: Int,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) @SerialName("type")
    val type: String = "result",
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val success: Boolean = true,
    val result: RegisterResultData,
) {
    init {
        require(id >= 1) { "id must be >= 1" }
        require(type == "result") { "type must be result" }
        require(success) { "register_result success must be true" }
    }
}

@Serializable
data class RegisterResultData(
    @SerialName("ha_device_id") val haDeviceId: String,
    @SerialName("protocol_version") val protocolVersion: Int,
    @SerialName("settings_revision") val settingsRevision: Int,
    @SerialName("server_time") val serverTime: String,
    /**
     * The capabilities HA accepted (PROTOCOL 4.2): an open set on the wire, so
     * this decodes as strings. Typing it as [Capability] made any accepted
     * capability newer than this build's vocabulary fail the whole
     * registration - a device that had itself declared it - and the session
     * looped "connection lost" forever. The wire stays open (PROTOCOL 8:
     * receivers tolerate future enum values); callers that need the typed
     * vocabulary map the strings they know.
     */
    val capabilities: List<String>,
) {
    init {
        Wire.requireLength("result.ha_device_id", haDeviceId, 1, 256)
        require(protocolVersion >= 1) { "protocol_version must be >= 1" }
        require(settingsRevision >= 0) { "settings_revision must be >= 0" }
        Wire.requireDateTime("result.server_time", serverTime)
        require(capabilities.distinct().size == capabilities.size) { "capabilities must be unique" }
    }
}

/** error_result (PROTOCOL 4, 6): HA's error envelope. Tabula codes or HA's own. */
@Serializable
data class ErrorResultFrame(
    val id: Int,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) @SerialName("type")
    val type: String = "result",
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val success: Boolean = false,
    val error: HaError,
) {
    init {
        require(id >= 1) { "id must be >= 1" }
        require(type == "result") { "type must be result" }
        require(!success) { "error_result success must be false" }
    }
}

@Serializable
data class HaError(
    val code: String,
    val message: String,
    @SerialName("translation_key") val translationKey: String? = null,
    @SerialName("translation_domain") val translationDomain: String? = null,
    @SerialName("translation_placeholders") val translationPlaceholders: kotlinx.serialization.json.JsonObject? = null,
) {
    init {
        require(code.isNotEmpty()) { "error.code must not be empty" }
        require(message.isNotEmpty()) { "error.message must not be empty" }
    }
}
