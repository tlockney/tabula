package net.lockney.tabula.ha

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * The media fetch (packet P2.5, PR review): no credential, redirects
 * followed but never https-to-http, hop-limited, size-capped, cancellable,
 * and never leaving a partial file behind.
 */
class MediaFetcherTest {
    private val server = TestHttpServer()
    private val dir = kotlin.io.path.createTempDirectory("p25-fetch").toFile()
    private val fetcher = MediaFetcher()

    private fun file(): File = File.createTempFile("tabula-media", null, dir)

    @AfterTest
    fun tearDown() {
        server.close()
        dir.deleteRecursively()
    }

    @Test
    fun `fetches a body to the file`() = runBlocking {
        server.route("/a.mp3", TestHttpServer.Reply.ok("ID3 audio bytes"))
        val file = file()
        fetcher.fetch("${server.url}/a.mp3", file)
        assertEquals("ID3 audio bytes", file.readText())
    }

    @Test
    fun `follows a redirect to the final body`() = runBlocking {
        server.route("/hop", TestHttpServer.Reply.redirect("/a.mp3"))
        server.route("/a.mp3", TestHttpServer.Reply.ok("audio"))
        val file = file()
        fetcher.fetch("${server.url}/hop", file)
        assertEquals("audio", file.readText())
    }

    @Test
    fun `an https origin refuses a cleartext hop, but upgrades and same-scheme hops pass`() {
        val https = "https://example.com/a".toHttpUrl()
        assertNull(fetcher.nextHop(https, "http://cdn.example.com/b"), "https-to-http is refused")
        assertEquals("https://example.com/b", fetcher.nextHop(https, "/b").toString())
        assertEquals(
            "https://secure.example.com/c",
            fetcher.nextHop(https, "https://secure.example.com/c").toString(),
        )
        assertNull(fetcher.nextHop(https, null), "a redirect without a Location goes nowhere")

        val http = "http://example.com/a".toHttpUrl()
        assertEquals("http://cdn.example.com/b", fetcher.nextHop(http, "http://cdn.example.com/b").toString())
        assertEquals(
            "https://secure.example.com/c",
            fetcher.nextHop(http, "https://secure.example.com/c").toString(),
        )
    }

    @Test
    fun `a redirect chain stops at MEDIA_MAX_REDIRECTS hops and leaves no file`() = runBlocking {
        for (i in 1..6) server.route("/hop$i", TestHttpServer.Reply.redirect("/hop${i + 1}"))
        server.route("/hop7", TestHttpServer.Reply.ok("end"))
        val file = file()
        val error = assertFailsWith<HttpException> { fetcher.fetch("${server.url}/hop1", file) }
        assertTrue(error.message!!.contains("redirects"), error.message)
        assertTrue(!file.exists(), "no partial file is left behind")
    }

    @Test
    fun `a body over the cap fails with 413 and leaves no file`() = runBlocking {
        server.route("/big", TestHttpServer.Reply.ok("x".repeat(4096)))
        val capped = MediaFetcher(maxBytes = 64)
        val file = file()
        val error = assertFailsWith<HttpException> { capped.fetch("${server.url}/big", file) }
        assertEquals(413, error.status)
        assertTrue(!file.exists(), "no partial file is left behind")
    }

    @Test
    fun `cancelling the fetch cancels the stalled download and leaves no file`() = runBlocking {
        server.route("/stream", TestHttpServer.Reply.stalling(firstBytes = 512))
        val file = file()
        val job = launch(Dispatchers.IO) { fetcher.fetch("${server.url}/stream", file) }
        withTimeout(10_000) {
            // the download runs until the stall; the file is growing, then stops
            while (file.length() < 512) delay(10)
        }
        job.cancelAndJoin() // must return despite the stalled socket (review must-fix 2)
        assertTrue(!file.exists(), "a cancelled fetch deletes its partial file")
    }

    @Test
    fun `an http error leaves no file`() = runBlocking {
        val file = file()
        val error = assertFailsWith<HttpException> { fetcher.fetch("${server.url}/missing", file) }
        assertEquals(404, error.status)
        assertTrue(!file.exists())
    }

    @Test
    fun `the request carries no authorization header`() = runBlocking {
        server.route("/a.mp3", TestHttpServer.Reply.ok("audio"))
        val file = file()
        fetcher.fetch("${server.url}/a.mp3", file)
        val request = server.requests.first()
        assertTrue("Authorization" !in request.keys, "no credential is attached (ADR 0001 item 5): $request")
    }
}

/**
 * A minimal HTTP server for the fetch tests: fixed [routes], one thread per
 * connection, and a stall mode that mimics an endless stream.
 */
class TestHttpServer : AutoCloseable {
    class Reply(
        val status: Int,
        val headers: Map<String, String> = emptyMap(),
        val body: ByteArray = ByteArray(0),
        /** Write the first [firstBytes] of [body], then hold the connection [stallMs]. */
        val firstBytes: Int = -1,
        val stallMs: Long = 0,
    ) {
        companion object {
            fun ok(body: String) = Reply(status = 200, body = body.toByteArray())

            fun redirect(location: String) =
                Reply(status = 302, headers = mapOf("Location" to location))

            /** An endless stream: a large body that stalls after [firstBytes]. */
            fun stalling(firstBytes: Int) =
                Reply(status = 200, body = ByteArray(1024 * 1024), firstBytes = firstBytes, stallMs = 120_000)
        }
    }

    private val routes = mutableMapOf<String, Reply>()
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    private val acceptor = thread(isDaemon = true, name = "test-http-acceptor") {
        while (!server.isClosed) {
            val socket = try {
                server.accept()
            } catch (e: Exception) {
                return@thread
            }
            thread(isDaemon = true) { serve(socket) }
        }
    }

    /** Every request this server saw: method, path and headers. */
    val requests: MutableList<Map<String, String>> = CopyOnWriteArrayList()

    val url: String
        get() = "http://127.0.0.1:${server.localPort}"

    fun route(path: String, reply: Reply) {
        routes[path] = reply
    }

    private fun serve(socket: Socket) {
        socket.use { s ->
            val reader = s.getInputStream().bufferedReader()
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            val path = parts.getOrNull(1) ?: return
            val headers = mutableMapOf<String, String>()
            while (true) {
                val line = reader.readLine() ?: return
                if (line.isEmpty()) break
                headers[line.substringBefore(":")] = line.substringAfter(": ", "")
            }
            requests += (mapOf("method" to parts.first(), "path" to path) + headers)
            val reply = routes[path] ?: Reply(404)
            val head = buildString {
                append("HTTP/1.1 ").append(reply.status).append(" ").append(statusText(reply.status)).append("\r\n")
                reply.headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
                append("Content-Length: ").append(reply.body.size).append("\r\n")
                append("Connection: close\r\n\r\n")
            }
            val out = s.getOutputStream()
            out.write(head.toByteArray())
            if (reply.firstBytes >= 0) {
                out.write(reply.body, 0, reply.firstBytes)
                out.flush()
                Thread.sleep(reply.stallMs)
                out.write(reply.body, reply.firstBytes, reply.body.size - reply.firstBytes)
            } else {
                out.write(reply.body)
            }
            out.flush()
        }
    }

    private fun statusText(status: Int): String = when (status) {
        200 -> "OK"
        302 -> "Found"
        404 -> "Not Found"
        else -> "Status"
    }

    override fun close() {
        runCatching { server.close() }
    }
}
