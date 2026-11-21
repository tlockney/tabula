package net.lockney.tabula.ha

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * OkHttp must not follow redirects on credential-bearing requests (PR review
 * item 3): a 307/308 re-sends the request body - the refresh token on
 * /auth/token, the nonce on the pairing endpoints - to wherever Location
 * points, even another host. HA never redirects these endpoints, so a 3xx is
 * treated as an IO error.
 */
class NoRedirectsTest {
    @Test
    fun `an http redirect is not followed and fails the request`() {
        var stolen = false
        val target = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/steal") {
                stolen = true
                it.sendResponseHeaders(200, -1)
            }
            start()
        }
        val redirecting = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/auth/token") {
                it.responseHeaders.add(
                    "Location",
                    "http://127.0.0.1:${target.address.port}/steal",
                )
                it.sendResponseHeaders(307, -1)
            }
            start()
        }
        try {
            val error = runCatching {
                runBlocking {
                    OkHttpExchange().post(
                        "http://127.0.0.1:${redirecting.address.port}/auth/token",
                        "application/json",
                        """{"refresh_token":"secret"}""",
                    )
                }
            }.exceptionOrNull()

            // the redirect is an error, and the body never reached the target host
            assertTrue(error is HttpException, "expected HttpException, got $error")
            assertEquals(307, error.status)
            Thread.sleep(200) // allow a wrongly-followed redirect to land
            assertTrue(!stolen, "the request body was re-sent to the redirect target")
        } finally {
            target.stop(0)
            redirecting.stop(0)
        }
    }

    @Test
    fun `a redirecting websocket handshake fails without following it`() {
        var stolen = false
        val target = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/ws") {
                stolen = true
                it.sendResponseHeaders(200, -1)
            }
            start()
        }
        val redirecting = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/api/tabula/ws") { exchange ->
                exchange.responseHeaders.add(
                    "Location",
                    "http://127.0.0.1:${target.address.port}/ws",
                )
                exchange.sendResponseHeaders(301, -1)
            }
            start()
        }
        try {
            val failure = CountDownLatch(1)
            var failureCount = 0
            val transport = OkHttpWsTransportFactory().open(
                "ws://127.0.0.1:${redirecting.address.port}/api/tabula/ws",
                object : WsTransportFactory.Listener {
                    override fun onOpen() {}

                    override fun onText(text: String) {}

                    override fun onClosed() {}

                    override fun onFailure(throwable: Throwable?) {
                        failureCount++
                        failure.countDown()
                    }
                },
            )
            try {
                assertTrue(failure.await(10, TimeUnit.SECONDS), "the handshake neither failed nor opened")
                assertEquals(1, failureCount)
                Thread.sleep(200)
                assertTrue(!stolen, "the handshake followed the redirect to the target host")
            } finally {
                transport.close()
            }
        } finally {
            target.stop(0)
            redirecting.stop(0)
        }
    }
}
