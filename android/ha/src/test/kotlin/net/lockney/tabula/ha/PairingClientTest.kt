package net.lockney.tabula.ha

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import net.lockney.tabula.core.tlp.HttpPairRequest
import net.lockney.tabula.core.tlp.HttpErrorName
import net.lockney.tabula.core.tlp.Tlp

/** Shared fake HTTP layer: scripted responses and a record of every request. */
class FakeHttp(private val script: (url: String, body: String) -> HttpExchange.Result) {
    val requests = mutableListOf<Pair<String, String>>()

    val exchange = object : HttpExchange {
        override suspend fun post(
            url: String,
            contentType: String,
            body: String,
            headers: Map<String, String>,
        ): HttpExchange.Result {
            requests.add(url to body)
            return script(url, body)
        }

        override suspend fun get(url: String, headers: Map<String, String>): HttpExchange.Result =
            HttpExchange.Result(404, "")
    }
}

class PairingClientTest {
    private val deviceId = "0f0e0d0c-0000-4000-8000-000000000001"

    /** 202 http_pair_response (docs/PROTOCOL.md 2.2). */
    private fun pairPending(pollInterval: Int = 3) = HttpExchange.Result(
        202,
        """{"status":"pending","expires_in":600,"poll_interval":$pollInterval}""",
    )

    /** 202 http_claim_pending. */
    private val claimPending = HttpExchange.Result(202, """{"status":"pending"}""")

    private fun claimPaired(token: String) = HttpExchange.Result(
        200,
        """{"status":"paired","credential":{"type":"refresh_token","refresh_token":"$token",""" +
            """"token_url":"/auth/token"},"ha_device_id":"abc123"}""",
    )

    @Test
    fun `attempt has a six digit code and a 43 char base64url nonce`() {
        val client = PairingClient(FakeHttp { _, _ -> pairPending() }.exchange, Random(1))
        val attempt = client.newAttempt()
        assertTrue(Regex("^[0-9]{6}$").matches(attempt.code), attempt.code)
        assertTrue(Regex("^[A-Za-z0-9_-]{43}$").matches(attempt.nonce), attempt.nonce)
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(attempt.nonce.toByteArray(Charsets.US_ASCII))
            .joinToString("") { "%02x".format(it) }
        assertEquals(digest, attempt.nonceHash)
    }

    @Test
    fun `base64url matches known vectors`() {
        assertEquals("", base64Url(ByteArray(0)))
        assertEquals("aGVsbG8", base64Url("hello".toByteArray()))
        assertEquals("A".repeat(43), base64Url(ByteArray(32)))
        assertEquals("_-8", base64Url(byteArrayOf(0xff.toByte(), 0xef.toByte())))
    }

    @Test
    fun `pair request body is a schema-valid http_pair_request`() = runTest {
        val http = FakeHttp { _, _ -> pairPending() }
        val client = PairingClient(http.exchange, Random(1))
        val attempt = client.newAttempt()
        client.start("http://ha:8123", deviceId, attempt, "Desk", "mock")
        val body = http.requests.single().second
        val frame = Tlp.strict.decodeFromString(HttpPairRequest.serializer(), body)
        assertEquals(deviceId, frame.deviceId)
        assertEquals(attempt.code, frame.code)
        assertEquals(attempt.nonceHash, frame.nonceHash)
        assertEquals(1, frame.protocolVersion)
        assertEquals("Desk", frame.name)
    }

    @Test
    fun `polling returns the credential once confirmed`() = runTest {
        val responses = ArrayDeque(listOf(pairPending(), claimPending, claimPaired("t".repeat(48))))
        val http = FakeHttp { _, _ -> responses.removeFirst() }
        val client = PairingClient(http.exchange, Random(1))
        val statuses = mutableListOf<PairingStatus>()
        val paired = client.pair("http://ha:8123", deviceId, "Desk", "mock") { statuses.add(it) }
        assertEquals("t".repeat(48), paired.credential.refreshToken)
        assertEquals("abc123", paired.haDeviceId)
        // one pair request + two claim polls, in order
        assertEquals(
            listOf("/api/tabula/pair", "/api/tabula/pair/claim", "/api/tabula/pair/claim"),
            http.requests.map { it.first.substringAfter("ha:8123") },
        )
        assertTrue(statuses.all { Regex("^[0-9]{6}$").matches(it.code) })
    }

    @Test
    fun `expired claim restarts pairing with a fresh nonce`() = runTest {
        val responses = ArrayDeque(
            listOf(
                pairPending(pollInterval = 1),
                HttpExchange.Result(404, """{"error":"expired","message":"no pending pairing"}"""),
                pairPending(pollInterval = 1),
                claimPaired("u".repeat(48)),
            ),
        )
        val http = FakeHttp { _, _ -> responses.removeFirst() }
        val client = PairingClient(http.exchange, Random(1))
        val paired = client.pair("http://ha:8123", deviceId, null, null)
        assertEquals("u".repeat(48), paired.credential.refreshToken)
        val hashes = http.requests
            .filter { it.first.endsWith("/pair") }
            .map { Tlp.json.decodeFromString(HttpPairRequest.serializer(), it.second).nonceHash }
        assertEquals(2, hashes.size)
        assertTrue(hashes[0] != hashes[1], "the restarted attempt must use a fresh nonce")
    }

    @Test
    fun `bad nonce is terminal`() = runTest {
        val responses = ArrayDeque(
            listOf(
                pairPending(pollInterval = 1),
                HttpExchange.Result(403, """{"error":"bad_nonce","message":"no match"}"""),
            ),
        )
        val http = FakeHttp { _, _ -> responses.removeFirst() }
        val client = PairingClient(http.exchange, Random(1))
        val error = assertFailsWith<PairingException> {
            client.pair("http://ha:8123", deviceId, null, null)
        }
        assertEquals(HttpErrorName.BAD_NONCE, error.name)
    }

    @Test
    fun `rate limited pair retries after retry_after`() = runTest {
        val responses = ArrayDeque(
            listOf(
                HttpExchange.Result(429, """{"error":"rate_limited","retry_after":2}"""),
                pairPending(pollInterval = 1),
                claimPending,
                claimPaired("v".repeat(48)),
            ),
        )
        val http = FakeHttp { _, _ -> responses.removeFirst() }
        val client = PairingClient(http.exchange, Random(1))
        val paired = client.pair("http://ha:8123", deviceId, null, null)
        assertEquals("v".repeat(48), paired.credential.refreshToken)
        assertEquals(2, http.requests.count { it.first.endsWith("/pair") })
    }

    @Test
    fun `already pending with a foreign nonce restarts after waiting it out`() = runTest {
        val responses = ArrayDeque(
            listOf(
                HttpExchange.Result(409, """{"error":"already_pending","retry_after":1}"""),
                pairPending(pollInterval = 1),
                claimPending,
                claimPaired("w".repeat(48)),
            ),
        )
        val http = FakeHttp { _, _ -> responses.removeFirst() }
        val client = PairingClient(http.exchange, Random(1))
        val paired = client.pair("http://ha:8123", deviceId, null, null)
        assertEquals("w".repeat(48), paired.credential.refreshToken)
    }

    @Test
    fun `consecutive transport failures during claim polling are retried, not fatal`() = runTest {
        // an HA restart mid-poll: several claim polls fail in a row (the old
        // code retried exactly once and the second failure escaped pair(),
        // which crashed the app - PR #47 second review, blocker 1)
        var claims = 0
        val http = FakeHttp { url, _ ->
            if (url.endsWith("/claim")) {
                claims += 1
                if (claims <= 3) throw java.io.IOException("HA is restarting")
                claimPaired("z".repeat(48))
            } else {
                pairPending(pollInterval = 1)
            }
        }
        val client = PairingClient(http.exchange, Random(1))
        val paired = client.pair("http://ha:8123", deviceId, null, null)
        assertEquals("z".repeat(48), paired.credential.refreshToken)
        assertTrue(claims >= 4, "the failures must have been retried, not fatal")
    }

    @Test
    fun `an unreachable server expires the attempt after the window and pairing starts over`() = runTest {
        // the whole pending window passes with every claim failing: the
        // attempt expires (as a server-side 404 would) and pairing restarts
        // with a fresh nonce, which then succeeds
        var attempt = 0
        val http = FakeHttp { url, _ ->
            if (url.endsWith("/pair")) {
                attempt += 1
                HttpExchange.Result(
                    202,
                    """{"status":"pending","expires_in":2,"poll_interval":1}""",
                )
            } else {
                if (attempt == 1) throw java.io.IOException("network down")
                claimPaired("q".repeat(48))
            }
        }
        val client = PairingClient(http.exchange, Random(1))
        val paired = client.pair("http://ha:8123", deviceId, null, null)
        assertEquals("q".repeat(48), paired.credential.refreshToken)
        assertEquals(2, attempt, "the expired attempt must have started a fresh one")
    }
}
