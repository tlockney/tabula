package net.lockney.tabula.ha

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.lockney.tabula.core.tlp.Tlp

/**
 * The HA frontend's V1 external-auth bridge (packet P2.2, ADR 0001 item 3).
 *
 * The wire contract is fixed by the frontend (`external_auth.ts`, in
 * home-assistant/frontend, Apache-2.0): a page loaded with `external_auth=1`
 * calls `window.externalApp.getExternalAuth('{"callback": …, "force"?}')
 * and expects the app to evaluate `externalAuthSetToken(true, {"access_token":
 * …, "expires_in": …})`. Revocation uses `revokeExternalAuth` and the
 * `externalAuthRevokeToken` callback the same way; the external bus carries
 * `{id, type}` messages, and the frontend waits for a `config/get` result.
 *
 * The reply construction follows the HA Companion Android app's
 * FrontendJsBridge.kt (Apache-2.0,
 * https://github.com/home-assistant/android): the callback name is validated
 * against the fixed constant before anything is evaluated, and the answer
 * carries only `access_token` and `expires_in` — never the refresh token.
 *
 * Answers must be evaluated asynchronously (the real caller uses
 * WebView.evaluateJavascript, which is). The frontend registers the pending
 * bus reply *after* externalBus returns, so a synchronous config/get answer
 * would be dropped ("unknown msg ID").
 *
 * V1 exposes this interface to every frame of every page the WebView loads,
 * so every call is gated on [fromHaOrigin]: the caller passes whether the
 * WebView's current main-frame URL is on the HA origin (ADR 0001 item 3), and
 * a foreign page gets no answer at all. "Log out" in the frontend is
 * acknowledged without revoking anything (ADR 0001: a wall tablet loses its
 * credential only when HA revokes it).
 */
class ExternalAuthBridge(
    private val access: suspend (force: Boolean) -> TokenRefresher.Access,
    private val externalConfig: JsonObject,
) {
    /**
     * `getExternalAuth(payload)`. Returns the JavaScript to evaluate in the
     * WebView, or null when the request must be ignored (bad payload, wrong
     * callback name, or a page off the HA origin).
     */
    suspend fun getExternalAuth(payload: String, fromHaOrigin: Boolean): String? {
        if (!fromHaOrigin) return null
        val request = parseAuthPayload(payload) ?: return null
        if (request.callback != CALLBACK_SET_TOKEN) return null
        val token = access(request.force)
        val body = buildJsonObject {
            put("access_token", token.token)
            put("expires_in", token.remainingSeconds)
        }
        return "$CALLBACK_SET_TOKEN(true, $body)"
    }

    /**
     * `revokeExternalAuth(payload)`: acknowledged, never honored. The tablet
     * stays paired; HA is the only side that can revoke (plan 5.2).
     */
    fun revokeExternalAuth(payload: String, fromHaOrigin: Boolean): String? {
        if (!fromHaOrigin) return null
        val request = parseAuthPayload(payload) ?: return null
        if (request.callback != CALLBACK_REVOKE_TOKEN) return null
        return "$CALLBACK_REVOKE_TOKEN(true)"
    }

    /**
     * `externalBus(message)`. P2.2 answers `config/get` (the frontend waits
     * for it, ADR 0001 item 3) and ignores the rest: `theme-update`,
     * `connection-status` and friends are the frontend notifying the host,
     * and Tabula has no feature behind them yet.
     */
    fun externalBus(message: String, fromHaOrigin: Boolean): String? {
        if (!fromHaOrigin) return null
        val json = runCatching { Tlp.json.parseToJsonElement(message).jsonObject }.getOrNull() ?: return null
        val id = json["id"]?.jsonPrimitive?.intOrNull ?: return null
        val type = json["type"]?.jsonPrimitive?.contentOrNull ?: return null
        if (type != CONFIG_GET) return null
        val reply = buildJsonObject {
            put("id", id)
            put("type", "result")
            put("success", true)
            put("result", externalConfig)
        }
        return "$EXTERNAL_BUS_REPLY($reply)"
    }

    private data class AuthPayload(val callback: String, val force: Boolean)

    private fun parseAuthPayload(payload: String): AuthPayload? {
        val json = runCatching { Tlp.json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return null
        val callback = json["callback"]?.jsonPrimitive?.contentOrNull ?: return null
        val force = json["force"]?.jsonPrimitive?.booleanOrNull ?: false
        return AuthPayload(callback, force)
    }

    companion object {
        /** The JavaScript interface name; part of the frontend contract. */
        const val JS_INTERFACE = "externalApp"

        /** Fixed by the frontend; never interpolate an unvalidated callback name. */
        const val CALLBACK_SET_TOKEN = "externalAuthSetToken"

        /** Fixed by the frontend; the reply target of revokeExternalAuth. */
        const val CALLBACK_REVOKE_TOKEN = "externalAuthRevokeToken"

        /** The frontend's global function the app calls to deliver bus replies. */
        const val EXTERNAL_BUS_REPLY = "externalBus"

        /** The only bus message P2.2 answers. */
        const val CONFIG_GET = "config/get"
    }
}
