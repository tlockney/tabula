package net.lockney.tabula.ha

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class TokenRefresherTest {
    private var nowMs = 0L

    private fun refresher(script: (String, String) -> HttpExchange.Result): Pair<TokenRefresher, FakeHttp> {
        val http = FakeHttp(script)
        return TokenRefresher(http.exchange, clockMs = { nowMs }) to http
    }

    private fun granted(token: String, expiresIn: Long = 1800) =
        HttpExchange.Result(200, """{"access_token":"$token","expires_in":$expiresIn,"token_type":"Bearer"}""")

    @Test
    fun `refreshes and caches within the margin`() = runTest {
        var calls = 0
        val (tokens, http) = refresher { _, body ->
            assertTrue(body.startsWith("grant_type=refresh_token&refresh_token="))
            calls++
            granted("token-$calls")
        }
        assertEquals("token-1", tokens.access("http://ha:8123") { "refresh-1" })
        assertEquals("token-1", tokens.access("http://ha:8123") { "refresh-1" })
        assertEquals(1, http.requests.size)
        // inside the refresh margin (1800s life, 300s margin): refresh again
        nowMs = (1800 - 300) * 1000
        assertEquals("token-2", tokens.access("http://ha:8123") { "refresh-1" })
        assertEquals(2, http.requests.size)
    }

    @Test
    fun `the token endpoint is called without a client_id`() = runTest {
        val (_, http) = refresher { _, _ -> granted("token-1") }
        val tokens = TokenRefresher(http.exchange, clockMs = { nowMs })
        tokens.access("http://ha:8123") { "refresh-1" }
        assertEquals("http://ha:8123/auth/token", http.requests.single().first)
        assertEquals("grant_type=refresh_token&refresh_token=refresh-1", http.requests.single().second)
    }

    @Test
    fun `invalid_grant is final`() = runTest {
        val (tokens, _) = refresher { _, _ ->
            HttpExchange.Result(400, """{"error":"invalid_grant"}""")
        }
        assertFailsWith<CredentialRevoked> { tokens.access("http://ha:8123") { "refresh-1" } }
    }

    @Test
    fun `a 403 from the token endpoint is final`() = runTest {
        val (tokens, _) = refresher { _, _ -> HttpExchange.Result(403, "forbidden") }
        assertFailsWith<CredentialRevoked> { tokens.access("http://ha:8123") { "refresh-1" } }
    }

    @Test
    fun `other failures are retryable by the caller`() = runTest {
        var calls = 0
        val (tokens, _) = refresher { _, _ ->
            calls++
            if (calls == 1) HttpExchange.Result(500, "boom") else granted("token-2")
        }
        // the caller catches IOException and tries again; the refresher does not loop
        assertFailsWith<java.io.IOException> { tokens.access("http://ha:8123") { "refresh-1" } }
        assertEquals("token-2", tokens.access("http://ha:8123") { "refresh-1" })
    }

    @Test
    fun `forceRefresh drops the cache`() = runTest {
        var calls = 0
        val (tokens, _) = refresher { _, _ ->
            calls++
            granted("token-$calls")
        }
        tokens.access("http://ha:8123") { "refresh-1" }
        assertEquals("token-2", tokens.forceRefresh("http://ha:8123") { "refresh-1" })
        assertEquals(2, calls)
    }
}
