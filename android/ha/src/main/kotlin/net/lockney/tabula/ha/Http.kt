package net.lockney.tabula.ha

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

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

/** [HttpExchange] over OkHttp; blocking calls run on the IO dispatcher. */
class OkHttpExchange(private val client: OkHttpClient = OkHttpClient()) : HttpExchange {
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
        client.newCall(request).execute().use { response ->
            HttpExchange.Result(response.code, response.body?.string() ?: "")
        }
    }

    override suspend fun get(url: String, headers: Map<String, String>): HttpExchange.Result =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .apply { headers.forEach { (name, value) -> header(name, value) } }
                .build()
            client.newCall(request).execute().use { response ->
                HttpExchange.Result(response.code, response.body?.string() ?: "")
            }
        }
}
