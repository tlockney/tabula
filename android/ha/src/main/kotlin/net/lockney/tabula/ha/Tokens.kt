package net.lockney.tabula.ha

import java.io.IOException
import java.net.URLEncoder
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.lockney.tabula.core.Constants

/**
 * Refresh-token exchange at HA's POST /auth/token (docs/PROTOCOL.md 3, ADR 0001
 * item 2): grant_type=refresh_token with no client_id, access tokens cached and
 * refreshed while less than TOKEN_REFRESH_MARGIN remains.
 *
 * `invalid_grant` and 403 are final (the credential was revoked): the caller
 * stops retrying — failures count toward HA's IP ban — and returns to pairing.
 */
class TokenRefresher(
    private val http: HttpExchange,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private data class Cached(val token: String, val expiresAtMs: Long)

    @Volatile
    private var cached: Cached? = null

    /** The current access token, refreshing when it is within the margin of expiry. */
    suspend fun access(haUrl: String, refreshToken: () -> String): String {
        val current = cached
        if (current != null &&
            current.expiresAtMs - clockMs() > Constants.TOKEN_REFRESH_MARGIN_S * 1000
        ) {
            return current.token
        }
        return refresh(haUrl, refreshToken())
    }

    /** Force a fresh access token (used after the server rejects one as stale). */
    suspend fun forceRefresh(haUrl: String, refreshToken: () -> String): String {
        cached = null
        return refresh(haUrl, refreshToken())
    }

    private suspend fun refresh(haUrl: String, refreshToken: String): String {
        val body = "grant_type=refresh_token&refresh_token=" +
            URLEncoder.encode(refreshToken, "UTF-8")
        val result = http.post("$haUrl/auth/token", "application/x-www-form-urlencoded", body)
        when {
            result.status == 200 -> {
                val json = result.bodyJson()?.jsonObject
                    ?: throw IOException("token: malformed response ${result.body.take(200)}")
                val token = json["access_token"]?.jsonPrimitive?.content
                    ?: throw IOException("token: no access_token in response")
                val expiresIn = json["expires_in"]?.jsonPrimitive?.content?.toDoubleOrNull()
                    ?: throw IOException("token: no expires_in in response")
                cached = Cached(token, clockMs() + (expiresIn * 1000).toLong())
                return token
            }
            // Final: the credential was revoked (ADR 0001). Stop retrying.
            result.status == 400 && result.body.contains("invalid_grant") ->
                throw CredentialRevoked("refresh token rejected")
            result.status == 403 -> throw CredentialRevoked("token endpoint refused the credential")
            else -> throw IOException("token: unexpected HTTP ${result.status}")
        }
    }
}

/** The stored credential no longer works; the device returns to pairing. */
class CredentialRevoked(message: String) : IOException(message)
