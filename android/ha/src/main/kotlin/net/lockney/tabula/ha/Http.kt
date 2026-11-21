package net.lockney.tabula.ha

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Minimal HTTP POST abstraction the pairing client and the token refresher need
 * (docs/PROTOCOL.md sections 2-3). JVM tests swap in fakes; the OkHttp
 * implementation here is used both by the app and by the Python-test-server
 * integration test.
 */
interface HttpExchange {
    suspend fun post(url: String, contentType: String, body: String, headers: Map<String, String> = emptyMap()): Result

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): Result

    data class Result(val status: Int, val body: String) {
        fun bodyJson(): kotlinx.serialization.json.JsonObject? =
            runCatching { kotlinx.serialization.json.Json.parseToJsonElement(body) }
                .getOrNull()
                ?.let { it as? kotlinx.serialization.json.JsonObject }
    }
}

class HttpException(val status: Int, message: String) : IOException(message)

/**
 * [HttpExchange] over OkHttp; blocking calls run on the IO dispatcher.
 *
 * Redirects are never followed: these requests carry the pairing nonce or the
 * refresh token (or an authenticated cookie), and a 307/308 would re-send the
 * body to wherever Location points, even another host. HA never redirects
 * the TLP endpoints, so a 3xx means a misconfigured proxy or something worse,
 * and is treated as an IO error ([HttpException]).
 */
class OkHttpExchange(private val client: OkHttpClient = noRedirectClient()) : HttpExchange {
    override suspend fun post(
        url: String,
        contentType: String,
        body: String,
        headers: Map<String, String>,
    ): HttpExchange.Result = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody(contentType.toMediaType()))
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()
        client.newCall(request).execute().use(::toResult)
    }

    override suspend fun get(url: String, headers: Map<String, String>): HttpExchange.Result =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .build()
            client.newCall(request).execute().use(::toResult)
        }

    private fun toResult(response: Response): HttpExchange.Result {
        if (response.code in 300..399) {
            throw HttpException(response.code, "redirect to ${response.header("Location")} not followed")
        }
        return HttpExchange.Result(response.code, response.body?.string() ?: "")
    }

    companion object {
        /** A client that never follows a redirect, for credential-bearing calls. */
        fun noRedirectClient(): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }
}
