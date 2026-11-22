package net.lockney.tabula

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import net.lockney.tabula.ha.CommandHandler
import net.lockney.tabula.ha.HttpExchange

/**
 * The screenshot flow (P3.13, PROTOCOL.md 5 and 6): the upload must name the
 * pending command and carry the device token, the answer only follows the
 * upload's verdict, the screen-off case captures nothing and still ACKs (HA
 * shows its placeholder, F-MGT-07), and a missing dashboard, a missing HA
 * URL or an upload HA refuses fails the command with the generic `failed`
 * code.
 */
class ScreenshotFlowTest {
    /** An HTTP client that records the one POST it is asked to make. */
    private class FakeHttp(
        var status: Int = 200,
        var body: String = "",
        var fail: Boolean = false,
    ) : HttpExchange {
        data class Post(val url: String, val contentType: String, val body: ByteArray, val headers: Map<String, String>)

        val posts = mutableListOf<Post>()

        override suspend fun post(url: String, contentType: String, body: String, headers: Map<String, String>): HttpExchange.Result =
            HttpExchange.Result(status, this.body)

        override suspend fun postBytes(
            url: String,
            contentType: String,
            body: ByteArray,
            headers: Map<String, String>,
        ): HttpExchange.Result {
            posts += Post(url, contentType, body.copyOf(), headers)
            if (fail) throw java.io.IOException("connection reset")
            return HttpExchange.Result(status, this.body)
        }

        override suspend fun get(url: String, headers: Map<String, String>): HttpExchange.Result =
            HttpExchange.Result(status, this.body)
    }

    private class CapturingHandle(var jpeg: ByteArray?) : DashboardBus.Handle {
        var captures = 0
        val loaded = mutableListOf<String>()

        override fun reload() {}

        override fun clearCache() {}

        override fun load(url: String) {
            loaded.add(url)
        }

        override fun loadStart() {}

        override fun currentPage(): String? = "/"

        override suspend fun capture(quality: Int?): ByteArray? {
            captures++
            return jpeg
        }
    }

    private fun flow(
        http: HttpExchange,
        bus: DashboardBus,
        screenOn: Boolean = true,
        url: String? = "http://ha:8123",
    ) = ScreenshotFlow(
        bus = bus,
        http = http,
        accessToken = { "test-access-token" },
        haUrl = { url },
        screenOn = { screenOn },
    )

    private suspend fun run(flow: ScreenshotFlow, quality: Int? = null): CommandHandler.Reply =
        flow.capture(quality, "cmd-9")

    @Test
    fun `a capture is uploaded naming the pending command, then ACKed`() = runTest {
        val http = FakeHttp()
        val handle = CapturingHandle(byteArrayOf(1, 2, 3))
        val bus = DashboardBus().apply { attach(handle) }
        val reply = run(flow(http, bus), quality = 60)
        assertTrue(reply is CommandHandler.Reply.Ok)

        val (url, contentType, body, headers) = http.posts.single()
        assertEquals("http://ha:8123/api/tabula/screenshot", url)
        assertEquals("image/jpeg", contentType)
        assertTrue(body.contentEquals(byteArrayOf(1, 2, 3)))
        assertEquals("Bearer test-access-token", headers["Authorization"])
        assertEquals("cmd-9", headers["X-Tabula-Command-Id"])
    }

    @Test
    fun `the screen off case captures nothing and still ACKs`() = runTest {
        val http = FakeHttp()
        val handle = CapturingHandle(byteArrayOf(1))
        val bus = DashboardBus().apply { attach(handle) }
        val reply = run(flow(http, bus, screenOn = false))
        assertTrue(reply is CommandHandler.Reply.Ok)
        assertEquals(0, handle.captures)
        assertTrue(http.posts.isEmpty())
    }

    @Test
    fun `no dashboard showing fails the command`() = runTest {
        val http = FakeHttp()
        val reply = run(flow(http, DashboardBus()))
        assertTrue(reply is CommandHandler.Reply.Error)
        assertEquals(net.lockney.tabula.core.tlp.ErrorCode.FAILED, reply.code)
        assertTrue(http.posts.isEmpty())
    }

    @Test
    fun `a missing HA URL fails the command without capturing`() = runTest {
        val http = FakeHttp()
        val handle = CapturingHandle(byteArrayOf(1))
        val bus = DashboardBus().apply { attach(handle) }
        val reply = run(flow(http, bus, url = null))
        assertTrue(reply is CommandHandler.Reply.Error)
        assertEquals(net.lockney.tabula.core.tlp.ErrorCode.FAILED, reply.code)
        assertEquals(0, handle.captures)
        assertTrue(http.posts.isEmpty())
    }

    @Test
    fun `an upload HA refuses fails the command`() = runTest {
        val http = FakeHttp(status = 409, body = """{"error":"no_pending_screenshot"}""")
        val bus = DashboardBus().apply { attach(CapturingHandle(byteArrayOf(1))) }
        val reply = run(flow(http, bus))
        assertTrue(reply is CommandHandler.Reply.Error)
        assertEquals(net.lockney.tabula.core.tlp.ErrorCode.FAILED, reply.code)
        assertEquals(1, http.posts.size)
    }

    @Test
    fun `a broken upload fails the command`() = runTest {
        val http = FakeHttp(fail = true)
        val bus = DashboardBus().apply { attach(CapturingHandle(byteArrayOf(1))) }
        val reply = run(flow(http, bus))
        assertTrue(reply is CommandHandler.Reply.Error)
        assertEquals(net.lockney.tabula.core.tlp.ErrorCode.FAILED, reply.code)
    }

    @Test
    fun `a connection closing mid-upload propagates cancellation, not a failed reply`() = runTest {
        // postBytes suspends forever: the upload is in flight when the
        // connection dies and the command's scope is cancelled (review).
        val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
        val http = object : HttpExchange {
            override suspend fun post(url: String, contentType: String, body: String, headers: Map<String, String>) =
                HttpExchange.Result(200, "")

            override suspend fun postBytes(
                url: String,
                contentType: String,
                body: ByteArray,
                headers: Map<String, String>,
            ): HttpExchange.Result {
                entered.complete(Unit)
                kotlinx.coroutines.awaitCancellation()
            }

            override suspend fun get(url: String, headers: Map<String, String>) = HttpExchange.Result(200, "")
        }
        val bus = DashboardBus().apply { attach(CapturingHandle(byteArrayOf(1))) }
        val outcome = kotlinx.coroutines.CompletableDeferred<CommandHandler.Reply>()
        val job = launch { outcome.complete(flow(http, bus).capture(60, "cmd-9")) }
        entered.await()

        var completion: Throwable? = null
        job.invokeOnCompletion { completion = it }
        job.cancelAndJoin()

        // The cancellation propagated: no reply was produced (a swallowed
        // cancellation would have completed the outcome with FAILED), and the
        // command's coroutine ended cancelled, not failed.
        assertFalse(outcome.isCompleted)
        assertTrue(completion is kotlinx.coroutines.CancellationException, "job ended by: $completion")
    }
}
