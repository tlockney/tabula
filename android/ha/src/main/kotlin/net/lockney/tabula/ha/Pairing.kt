package net.lockney.tabula.ha

import java.io.IOException
import java.security.MessageDigest
import kotlin.random.Random
import kotlin.random.asKotlinRandom
import java.security.SecureRandom
import kotlinx.coroutines.delay
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import net.lockney.tabula.core.Constants
import net.lockney.tabula.core.tlp.Credential
import net.lockney.tabula.core.tlp.HttpClaimRequest
import net.lockney.tabula.core.tlp.HttpErrorName
import net.lockney.tabula.core.tlp.HttpPairRequest
import net.lockney.tabula.core.tlp.HttpPairResponse
import net.lockney.tabula.core.tlp.Tlp

/**
 * The device side of TLP pairing (docs/PROTOCOL.md section 2): one attempt is a
 * code shown on screen plus a hidden nonce; the claim poll releases the
 * credential exactly once.
 *
 * - A retry of POST /api/tabula/pair reuses the same [Attempt] (same nonce
 *   hash), which the server treats as a retry (PROTOCOL 2.2).
 * - `rate_limited` and `too_many_pending` are retried after the server's
 *   retry_after; `already_pending` (a different attempt is pending for this
 *   device) waits out the entry and starts over with a fresh nonce.
 * - `expired` (404) from claim is terminal for the attempt: the caller starts
 *   pairing again (PROTOCOL 2.5). `bad_nonce` (403) is terminal outright:
 *   something else on the network has interfered.
 */
class PairingClient(
    private val http: HttpExchange,
    private val random: Random = SecureRandom().asKotlinRandom(),
) {

    /** One pairing attempt: the code the user reads, and the nonce only the device sends. */
    class Attempt(val code: String, val nonce: String, val nonceHash: String)

    fun newAttempt(): Attempt {
        val code = buildString {
            repeat(Constants.PAIR_CODE_DIGITS) { append('0' + random.nextInt(10)) }
        }
        val bytes = ByteArray(Constants.NONCE_BYTES).also { random.nextBytes(it) }
        val nonce = base64Url(bytes)
        val hash = MessageDigest.getInstance("SHA-256").digest(nonce.toByteArray(Charsets.US_ASCII))
        return Attempt(code, nonce, hash.joinToString("") { "%02x".format(it) })
    }

    /** POST /api/tabula/pair. Returns the pending response; retries wait server-side. */
    internal suspend fun start(
        haUrl: String,
        deviceId: String,
        attempt: Attempt,
        name: String?,
        model: String?,
    ): HttpPairResponse {
        val request = HttpPairRequest(
            deviceId = deviceId,
            code = attempt.code,
            nonceHash = attempt.nonceHash,
            name = name,
            model = model,
            protocolVersion = Constants.PROTOCOL_VERSION,
        )
        val result = http.post(
            "$haUrl/api/tabula/pair",
            "application/json",
            Tlp.encodeFrame("http_pair_request", request),
        )
        when (result.status) {
            202 -> return Tlp.json.decodeFromString(
                HttpPairResponse.serializer(),
                result.body,
            )
            400 -> throw PairingException(httpError(result).first, httpErrorMessage(result))
            409, 429, 503 -> throw PairingException(
                httpError(result).first,
                "pairing rejected",
                result.bodyJson()
                    ?.get("retry_after")?.toString()?.toIntOrNull(),
            )
            else -> throw IOException("pair: unexpected HTTP ${result.status}")
        }
    }

    /** One POST /api/tabula/pair/claim poll (docs/PROTOCOL.md 2.5). */
    internal suspend fun claim(haUrl: String, deviceId: String, attempt: Attempt): ClaimOutcome {
        val request = HttpClaimRequest(deviceId = deviceId, nonce = attempt.nonce)
        val result = http.post(
            "$haUrl/api/tabula/pair/claim",
            "application/json",
            Tlp.encodeFrame("http_claim_request", request),
        )
        return when (result.status) {
            202 -> ClaimOutcome.Pending
            200 -> {
                val response = Tlp.json.decodeFromString(
                    net.lockney.tabula.core.tlp.HttpClaimResponse.serializer(),
                    result.body,
                )
                ClaimOutcome.Paired(response.credential, response.haDeviceId)
            }
            403 -> throw PairingException(httpError(result).first, "another pairing attempt took over this one")
            404 -> ClaimOutcome.Expired
            400 -> throw PairingException(httpError(result).first, "claim request rejected")
            else -> throw IOException("claim: unexpected HTTP ${result.status}")
        }
    }

    /**
     * The full pairing flow: post the attempt, poll claim every poll_interval
     * seconds, and restart with a fresh attempt when the pending entry expires.
     * Emits [PairingStatus] for the screen; returns when paired. Cancellation
     * (the user leaves pairing) propagates normally.
     */
    suspend fun pair(
        haUrl: String,
        deviceId: String,
        name: String?,
        model: String?,
        onStatus: suspend (PairingStatus) -> Unit = {},
    ): Paired {
        while (true) {
            val attempt = newAttempt()
            val pending = try {
                startWithRetries(haUrl, deviceId, attempt, name, model)
            } catch (e: PairingException) {
                // A different attempt is still pending for this device_id; it was
                // waited out above, so try again with a fresh nonce (PROTOCOL 2.2).
                if (e.name != HttpErrorName.ALREADY_PENDING) throw e
                continue
            }
            onStatus(PairingStatus(attempt.code, deviceId, pending.expiresIn))
            val pollMs = (pending.pollInterval * 1000.0).toLong()
            while (true) {
                delay(pollMs)
                when (val outcome = claimWithRetry(haUrl, deviceId, attempt)) {
                    is ClaimOutcome.Paired -> return Paired(outcome.credential, outcome.haDeviceId)
                    is ClaimOutcome.Expired -> break // start pairing again (PROTOCOL 2.5)
                    ClaimOutcome.Pending -> onStatus(
                        PairingStatus(attempt.code, deviceId, pending.expiresIn),
                    )
                }
            }
        }
    }

    /** start(), retrying rate-limit / cap responses after their retry_after. */
    private suspend fun startWithRetries(
        haUrl: String,
        deviceId: String,
        attempt: Attempt,
        name: String?,
        model: String?,
    ): HttpPairResponse {
        while (true) {
            try {
                return start(haUrl, deviceId, attempt, name, model)
            } catch (e: PairingException) {
                when (e.name) {
                    HttpErrorName.RATE_LIMITED, HttpErrorName.TOO_MANY_PENDING -> {
                        delay((e.retryAfter ?: 1) * 1000L)
                    }
                    // A different attempt is pending for this device_id; the nonce
                    // in it is not ours, so it can only be waited out (PROTOCOL 2.2).
                    HttpErrorName.ALREADY_PENDING -> {
                        delay((e.retryAfter ?: 1) * 1000L)
                        throw e // pair() catches it and restarts with a fresh nonce
                    }
                    else -> throw e
                }
            } catch (e: IOException) {
                delay((Constants.CLAIM_POLL_INTERVAL_S * 1000).toLong())
            }
        }
    }

    private suspend fun claimWithRetry(haUrl: String, deviceId: String, attempt: Attempt): ClaimOutcome {
        try {
            return claim(haUrl, deviceId, attempt)
        } catch (e: PairingException) {
            throw e // terminal; never retried (PROTOCOL 2.5)
        } catch (e: IOException) {
            // A transport hiccup must not kill the poll loop; retry at the default rate.
            delay((Constants.CLAIM_POLL_INTERVAL_S * 1000).toLong())
            return claim(haUrl, deviceId, attempt)
        }
    }

    private fun httpError(result: HttpExchange.Result): Pair<HttpErrorName, String> {
        val raw = result.bodyJson()?.get("error")?.jsonPrimitiveOrNull()?.content
        val name = HttpErrorName.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) }
            ?: throw IOException("HTTP ${result.status}: ${result.body.take(200)}")
        return name to httpErrorMessage(result)
    }

    private fun httpErrorMessage(result: HttpExchange.Result): String =
        result.bodyJson()?.get("message")?.jsonPrimitiveOrNull()?.content
            ?: "HTTP ${result.status}"
}

sealed interface ClaimOutcome {
    data object Pending : ClaimOutcome
    data object Expired : ClaimOutcome
    data class Paired(val credential: Credential, val haDeviceId: String) : ClaimOutcome
}

data class Paired(val credential: Credential, val haDeviceId: String)

/** What the pairing screen shows: the code to type in HA and the pending TTL. */
data class PairingStatus(val code: String, val deviceId: String, val expiresIn: Int)

/** A terminal pairing failure the caller surfaces instead of retrying. */
class PairingException(
    val name: HttpErrorName,
    message: String,
    val retryAfter: Int? = null,
) : IOException(message)

private fun kotlinx.serialization.json.JsonElement?.jsonPrimitiveOrNull(): kotlinx.serialization.json.JsonPrimitive? =
    this as? kotlinx.serialization.json.JsonPrimitive

/** base64url without padding (RFC 4648 section 5), for the pairing nonce. */
internal fun base64Url(bytes: ByteArray): String {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    val out = StringBuilder(((bytes.size + 2) / 3) * 4)
    var i = 0
    while (i + 2 < bytes.size) {
        val n = (bytes[i].toInt() and 0xff shl 16) or (bytes[i + 1].toInt() and 0xff shl 8) or
            (bytes[i + 2].toInt() and 0xff)
        out.append(alphabet[n ushr 18 and 0x3f])
        out.append(alphabet[n ushr 12 and 0x3f])
        out.append(alphabet[n ushr 6 and 0x3f])
        out.append(alphabet[n and 0x3f])
        i += 3
    }
    val remaining = bytes.size - i
    if (remaining == 1) {
        val n = bytes[i].toInt() and 0xff shl 16
        out.append(alphabet[n ushr 18 and 0x3f])
        out.append(alphabet[n ushr 12 and 0x3f])
    } else if (remaining == 2) {
        val n = (bytes[i].toInt() and 0xff shl 16) or (bytes[i + 1].toInt() and 0xff shl 8)
        out.append(alphabet[n ushr 18 and 0x3f])
        out.append(alphabet[n ushr 12 and 0x3f])
        out.append(alphabet[n ushr 6 and 0x3f])
    }
    return out.toString()
}
