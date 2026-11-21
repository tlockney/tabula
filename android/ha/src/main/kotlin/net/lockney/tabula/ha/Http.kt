package net.lockney.tabula.ha

import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import net.lockney.tabula.core.Constants
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
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

    /**
     * A binary POST: the screenshot upload (PROTOCOL 5), which carries JPEG
     * bytes and the device's bearer token in the headers.
     */
    suspend fun postBytes(url: String, contentType: String, body: ByteArray, headers: Map<String, String> = emptyMap()): Result

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
 * Media audio download (packet P2.5, PROTOCOL 4.5): fetch [url] into [file]
 * with **no** Authorization header - HA's TTS proxy and signed media URLs
 * need none (ADR 0001 item 5) - and the file is deleted if anything fails,
 * including cancellation, so no partial file is ever left behind.
 *
 * Unlike the credential-bearing requests (P2.1), redirects are followed: a
 * media URL carries no secret, and common CDN and podcast URLs answer with a
 * 30x. A downgrade from https to http is still refused (the encrypted origin
 * must not be traded for a cleartext hop), and chains stop at
 * [Constants.MEDIA_MAX_REDIRECTS] hops.
 *
 * The copy runs on the IO dispatcher but never blocks a cancellation: the
 * OkHttp call is cancelled through [suspendCancellableCoroutine], which also
 * unblocks the read, and the loop checks the coroutine between chunks. A
 * body larger than [maxBytes] - an endless stream, which could never be a
 * sensible announcement - fails with HTTP 413 and leaves no file.
 */
class MediaFetcher(
    private val client: OkHttpClient = OkHttpExchange.noRedirectClient(
        // the download's read timeout is an ADR 0002 constant, not the
        // library's default: a stalled server must not hold the load slot
        (Constants.MEDIA_READ_TIMEOUT_S * 1000).toLong(),
    ),
    private val maxBytes: Long = Constants.MEDIA_MAX_BYTES,
    private val maxRedirects: Int = Constants.MEDIA_MAX_REDIRECTS,
) {
    /**
     * The next request for one redirect hop, or null when [location] does not
     * resolve, or [from] was https and the target would be http (a cleartext
     * downgrade is refused even though no credential rides along).
     */
    fun nextHop(from: HttpUrl, location: String?): HttpUrl? {
        val target = location?.let { runCatching { from.resolve(it) }.getOrNull() } ?: return null
        if (from.isHttps && !target.isHttps) return null
        return target
    }

    suspend fun fetch(url: String, file: File) {
        try {
            var request = Request.Builder().url(url).build()
            var hops = 0
            while (true) {
                val response = awaitCall(request)
                try {
                    val code = response.code
                    if (code in 300..399) {
                        val next = nextHop(
                            request.url,
                            response.header("Location"),
                        ) ?: throw HttpException(
                            code,
                            "media redirect to ${response.header("Location")} not followed" +
                                " (missing, unresolvable, https-to-http or more than $maxRedirects hops)",
                        )
                        hops += 1
                        if (hops > maxRedirects) {
                            throw HttpException(code, "media redirects exceeded $maxRedirects hops")
                        }
                        request = Request.Builder().url(next).build()
                        continue
                    }
                    if (!response.isSuccessful) {
                        throw HttpException(code, "media fetch failed: HTTP $code")
                    }
                    val body = response.body ?: throw IOException("media response has no body")
                    withContext(Dispatchers.IO) { copyCapped(body.byteStream(), file) }
                    return
                } finally {
                    response.close()
                }
            }
        } catch (e: Exception) {
            file.delete() // no partial file survives a failed, capped or cancelled fetch
            throw e
        }
    }

    /**
     * Await one HTTP response; cancelling the coroutine cancels the call, which
     * unblocks the read. A response that arrives after the cancellation is
     * closed by the resume handler rather than resumed into a cancelled
     * coroutine (which would throw) or leaked.
     *
     * Internal (not private) so the fetch tests can drive the late-response
     * race directly (like TlpClient's Connection for its concurrency test);
     * OkHttp itself closes a cancelled call's response before delivery, so
     * the race cannot be reached through a real [client.newCall].
     */
    internal suspend fun awaitCall(request: Request): Response =
        suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response) { response.close() }
                }
            })
        }

    /**
     * Copy [input] to [file] in chunks, refusing to outlive the coroutine
     * (each chunk checks for cancellation) and refusing to exceed [maxBytes]
     * (an endless stream fills storage otherwise).
     */
    private suspend fun copyCapped(input: InputStream, file: File) {
        file.outputStream().use { out ->
            val buffer = ByteArray(COPY_CHUNK_BYTES)
            var total = 0L
            while (true) {
                if (!coroutineContext.isActive) throw CancellationException("media fetch cancelled")
                val read = try {
                    input.read(buffer)
                } catch (e: IOException) {
                    // a cancelled call closes the socket under the read; report
                    // that as the cancellation it is, not as a download failure
                    if (!coroutineContext.isActive) throw CancellationException("media fetch cancelled")
                    throw e
                }
                if (read == -1) break
                total += read
                if (total > maxBytes) {
                    throw HttpException(413, "media exceeds MEDIA_MAX_BYTES (${maxBytes} bytes)")
                }
                out.write(buffer, 0, read)
            }
        }
    }

    private companion object {
        const val COPY_CHUNK_BYTES = 64 * 1024
    }
}

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

    override suspend fun postBytes(
        url: String,
        contentType: String,
        body: ByteArray,
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
        /**
         * A client that never follows a redirect, for credential-bearing calls.
         * [readTimeoutMs] sets an explicit socket read timeout where a caller
         * needs one (the media download, ADR 0002 MEDIA_READ_TIMEOUT).
         */
        fun noRedirectClient(readTimeoutMs: Long? = null): OkHttpClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .apply { readTimeoutMs?.let { readTimeout(it, java.util.concurrent.TimeUnit.MILLISECONDS) } }
            .build()
    }
}
