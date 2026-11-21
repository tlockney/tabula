@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package net.lockney.tabula.core.tlp

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * HTTP pairing and claim bodies plus the error envelope
 * (docs/PROTOCOL.md 2, 5, 6). The device only encodes pair/claim requests and
 * decodes the responses; the screenshot response arrives in Phase 3.
 */

/** POST /api/tabula/pair request body. */
@Serializable
data class HttpPairRequest(
    @SerialName("device_id") val deviceId: String,
    val code: String,
    @SerialName("nonce_hash") val nonceHash: String,
    val name: String? = null,
    val model: String? = null,
    @SerialName("protocol_version") val protocolVersion: Int,
) {
    init {
        Wire.requireDeviceId(deviceId)
        Wire.requirePattern("pair.code", code, Wire.pairingCode)
        Wire.requirePattern("pair.nonce_hash", nonceHash, Wire.nonceHash)
        name?.let { Wire.requireLength("pair.name", it, 1, 64) }
        model?.let { Wire.requireLength("pair.model", it, 1, 64) }
        require(protocolVersion >= 1) { "protocol_version must be >= 1" }
    }
}

/** 202 response from /api/tabula/pair. */
@Serializable
data class HttpPairResponse(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val status: String = "pending",
    @SerialName("expires_in") val expiresIn: Int,
    @SerialName("poll_interval") val pollInterval: Int,
) {
    init {
        require(status == "pending") { "pair status must be pending" }
        require(expiresIn >= 1) { "expires_in must be >= 1" }
        require(pollInterval >= 1) { "poll_interval must be >= 1" }
    }
}

/** POST /api/tabula/pair/claim request body, polled every poll_interval seconds. */
@Serializable
data class HttpClaimRequest(
    @SerialName("device_id") val deviceId: String,
    val nonce: String,
) {
    init {
        Wire.requireDeviceId(deviceId)
        Wire.requirePattern("claim.nonce", nonce, Wire.nonce)
    }
}

/** 202 claim response while the user has not confirmed. */
@Serializable
data class HttpClaimPending(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val status: String = "pending",
) {
    init {
        require(status == "pending") { "claim status must be pending" }
    }
}

/** 200 claim response, returned once (PROTOCOL 2.5). */
@Serializable
data class HttpClaimResponse(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val status: String = "paired",
    val credential: Credential,
    @SerialName("ha_device_id") val haDeviceId: String,
) {
    init {
        require(status == "paired") { "claim status must be paired" }
        Wire.requireLength("ha_device_id", haDeviceId, 1, 256)
    }
}

@Serializable
data class Credential(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val type: String = "refresh_token",
    @SerialName("refresh_token") val refreshToken: String,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("token_url") val tokenUrl: String = "/auth/token",
) {
    init {
        require(type == "refresh_token") { "credential type must be refresh_token" }
        require(refreshToken.length >= 32) { "refresh_token must be at least 32 characters" }
        require(tokenUrl == "/auth/token") { "token_url must be /auth/token" }
    }
}

/** Error body the HTTP views return (PROTOCOL 2, 5). */
@Serializable
data class HttpError(
    val error: HttpErrorName,
    val message: String? = null,
    @SerialName("retry_after") val retryAfter: Int? = null,
) {
    init {
        message?.let { Wire.requireLength("http_error.message", it, 1, 500) }
        retryAfter?.let { require(it >= 1) { "retry_after must be >= 1" } }
    }
}

/** 200 response from POST /api/tabula/screenshot. */
@Serializable
data class HttpScreenshotResponse(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    val status: String = "stored",
    val bytes: Int,
) {
    init {
        require(status == "stored") { "screenshot status must be stored" }
        require(bytes >= 1) { "bytes must be >= 1" }
    }
}
