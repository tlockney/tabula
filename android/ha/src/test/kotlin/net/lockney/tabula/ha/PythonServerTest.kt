package net.lockney.tabula.ha

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient

/**
 * Runs the real client (OkHttp sockets) against mockdevice's in-process Python
 * test server, end to end: pair, confirm over HTTP, claim, token refresh, WS
 * registration, and a set_settings command with its ACK. The server validates
 * every frame the client sends against the TLP schema, so "problems" empty
 * means the client's wire format is clean.
 *
 * Feature flows over the same server live in their own classes
 * (PythonServer*Test, EntityDataServerTest) built on the helpers in
 * PythonTestServer.kt. Skips when the repo checkout or the uv-managed Python
 * environment cannot be found (the state-machine unit tests cover the client
 * on their own).
 */
class PythonServerTest {
    private val client = OkHttpClient()

    @Test
    fun `pairs, registers and answers a command against the Python test server`() = withPythonServer { url ->
        runBlocking {
            withTimeout(60_000) {
                val http = OkHttpExchange(client)

                // pair: post the attempt, confirm the code as the HA user would, claim
                val pairing = PairingClient(http)
                val deviceId = "0f0e0d0c-0000-4000-8000-000000000002"
                val attempt = pairing.newAttempt()
                val pending = pairing.start(url, deviceId, attempt, "JUnit", "jvm")
                assertEquals(600, pending.expiresIn)
                http.testHook(url, "confirm", """{"device_id":"$deviceId","code":"${attempt.code}"}""")
                val paired = pairing.claim(url, deviceId, attempt) as ClaimOutcome.Paired
                assertTrue(paired.credential.refreshToken.length >= 32)

                // the claim is one-shot: a second claim is terminal (PROTOCOL 2.5)
                val again = runCatching { pairing.claim(url, deviceId, attempt) }
                assertTrue(again.getOrNull() == ClaimOutcome.Expired, "second claim must be expired")

                // access token, then a full WS session
                val session = register(url, deviceId, paired.credential.refreshToken, http, client, listOf("battery"))

                // a command sent by the server must come back as a schema-valid ACK
                val frame = http.command(
                    url,
                    deviceId,
                    "set_settings",
                    """{"revision":1,"schema_version":1,"settings":{}}""",
                )
                assertEquals(true, frame.ok)
                assertEquals(1, frame.data!!["revision"]!!.jsonPrimitive.content.toInt())

                assertEquals("[]", http.reportedProblems(url))
                session.job.cancelAndJoin()
            }
        }
    }
}
