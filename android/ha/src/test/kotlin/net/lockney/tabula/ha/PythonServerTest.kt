package net.lockney.tabula.ha

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.lockney.tabula.core.tlp.CommandResultFrame
import net.lockney.tabula.core.tlp.Tlp
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue

/**
 * Runs the real client (OkHttp sockets) against mockdevice's in-process Python
 * test server, end to end: pair, confirm over HTTP, claim, token refresh, WS
 * registration, and a set_settings command with its ACK. The server validates
 * every frame the client sends against the TLP schema, so "problems" empty
 * means the client's wire format is clean.
 *
 * Skips when the repo checkout or the uv-managed Python environment cannot be
 * found (the state-machine unit tests cover the client on their own).
 */
class PythonServerTest {
    private val client = OkHttpClient()

    @Test
    fun `pairs, registers and answers a command against the Python test server`() {
        val root = findRepoRoot()
        assumeTrue("repo checkout not found from ${File(".").absolutePath}", root != null)
        assumeTrue("uv is not available", uvAvailable())
        val server = TestServerProcess(root!!)
        try {
            server.awaitListening()
            runServerScenario(server.url)
        } finally {
            server.close()
        }
    }

    private fun runServerScenario(url: String): Unit = runBlocking {
        withTimeout(60_000) {
            val http = OkHttpExchange(client)

            // pair: post the attempt, confirm the code as the HA user would, claim
            val pairing = PairingClient(http)
            val deviceId = "0f0e0d0c-0000-4000-8000-000000000002"
            val attempt = pairing.newAttempt()
            val pending = pairing.start(url, deviceId, attempt, "JUnit", "jvm")
            assertEquals(600, pending.expiresIn)
            val confirm = http.post(
                "$url/test/confirm",
                "application/json",
                """{"device_id":"$deviceId","code":"${attempt.code}"}""",
            )
            assertTrue(confirm.status == 200, "confirm: ${confirm.status} ${confirm.body}")
            val claimed = pairing.claim(url, deviceId, attempt)
            val paired = claimed as ClaimOutcome.Paired
            assertTrue(paired.credential.refreshToken.length >= 32)

            // the claim is one-shot: a second claim is terminal (PROTOCOL 2.5)
            val again = runCatching { pairing.claim(url, deviceId, attempt) }
            assertTrue(again.getOrNull() == ClaimOutcome.Expired, "second claim must be expired")

            // access token, then a full WS session
            val tokens = TokenRefresher(http)
            val state = FakeState()
            val commands = FakeCommands()
            val registered = CompletableDeferred<Unit>()
            val events = object : TlpClient.Events {
                override fun onRegistered(result: net.lockney.tabula.core.tlp.RegisterResultData) {
                    registered.complete(Unit)
                }

                override fun onDisconnected() = Unit

                override fun onRevoked() = Unit

                override fun onRejected(code: String, message: String) = Unit
            }
            val tlpClient = TlpClient(
                TlpClient.Config(
                    haUrl = url,
                    deviceId = deviceId,
                    appVersion = "0.1.0-test",
                    manufacturer = "Tabula",
                    model = "jvm-test",
                    osVersion = "jvm",
                    capabilities = listOf("battery"),
                    settingsRevision = { 0 },
                    refreshToken = { paired.credential.refreshToken },
                    state = state,
                    commands = commands,
                ),
                tokens,
                OkHttpWsTransportFactory(client),
            )
            val job = launch { tlpClient.run(events) }
            withTimeout(20_000) { registered.await() }

            // a command sent by the server must come back as a schema-valid ACK
            val command = http.post(
                "$url/test/command",
                "application/json",
                """{"device_id":"$deviceId","command":"set_settings",""" +
                    """"args":{"revision":1,"schema_version":1,"settings":{}}}""",
            )
            assertEquals(200, command.status, "command: ${command.body}")
            val frame = Tlp.strict.decodeFromString(
                CommandResultFrame.serializer(),
                Json.parseToJsonElement(command.body).jsonObject["result"].toString(),
            )
            assertEquals(true, frame.ok)
            assertEquals(1, frame.data!!["revision"]!!.jsonPrimitive.content.toInt())

            // the server saw no schema problems with anything the client sent
            val report = http.get("$url/test/report")
            assertEquals(200, report.status)
            val problems = Json.parseToJsonElement(report.body).jsonObject["problems"].toString()
            assertEquals("[]", problems)

            job.cancelAndJoin()
        }
    }
}
