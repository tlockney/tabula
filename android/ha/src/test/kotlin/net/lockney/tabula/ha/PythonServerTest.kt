package net.lockney.tabula.ha

import java.io.File
import java.util.concurrent.TimeUnit
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
import net.lockney.tabula.core.tlp.CommandEvent
import net.lockney.tabula.core.tlp.CommandResultFrame
import net.lockney.tabula.core.tlp.ErrorCode
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
        assumeTrue(
            "uv is not available",
            runCatching { ProcessBuilder("uv", "--version").start().waitFor(5, TimeUnit.SECONDS) }
                .getOrDefault(false),
        )
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
            val uploading = UploadingCommands(commands, http, { url }, { tokens.access(url) { paired.credential.refreshToken } })
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
                    commands = uploading,
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

            // the screenshot flow (P3.13, PROTOCOL 5): the device captures,
            // uploads naming the still-pending command, and only then ACKs
            val shotCommand = http.post(
                "$url/test/command",
                "application/json",
                """{"device_id":"$deviceId","command":"screenshot",""" +
                    """"args":{"quality":80}}""",
            )
            assertEquals(200, shotCommand.status, "screenshot command: ${shotCommand.body}")
            val shotFrame = Tlp.strict.decodeFromString(
                CommandResultFrame.serializer(),
                Json.parseToJsonElement(shotCommand.body).jsonObject["result"].toString(),
            )
            assertEquals(true, shotFrame.ok)

            // the server saw the upload land while the command was still open
            val afterShot = http.get("$url/test/report")
            assertEquals(200, afterShot.status)
            val report = Json.parseToJsonElement(afterShot.body).jsonObject
            val screenshot = report["devices"]!!
                .jsonObject[deviceId]!!.jsonObject["screenshot"]!!.jsonObject
            assertEquals(true, screenshot["uploaded"]!!.jsonPrimitive.content.toBoolean())
            assertEquals(shotFrame.commandId, screenshot["command_id"]!!.jsonPrimitive.content)
            assertEquals(
                true,
                screenshot["before_result"]!!.jsonPrimitive.content.toBoolean(),
                "the upload must precede the command's result (PROTOCOL 5)",
            )

            // the server saw no schema problems with anything the client sent
            val problems = report["problems"].toString()
            assertEquals("[]", problems)

            job.cancelAndJoin()
        }
    }

    // --- server process -------------------------------------------------------

    /** `uv run python -m mockdevice serve`, started from the repo checkout. */
    private class TestServerProcess(root: File) {
        val process: Process = ProcessBuilder("uv", "run", "python", "-m", "mockdevice", "serve")
            .directory(root)
            .apply {
                environment()["UV_CONFIG_FILE"] = File(root, "tools/uv.toml").absolutePath
                // mirror the Makefile's isolation (docs/DEVELOPMENT.md)
                environment().remove("UV_EXCLUDE_NEWER")
                environment().remove("UV_RESOLUTION")
                environment().remove("UV_PRERELEASE")
                environment().remove("UV_FORK_STRATEGY")
            }
            .start()

        var url: String = ""
            private set

        fun awaitListening(timeoutMs: Long = 30_000) {
            val deadline = System.currentTimeMillis() + timeoutMs
            val lines = sequence {
                while (true) yield(process.inputStream.bufferedReader().readLine() ?: return@sequence)
            }
            for (line in lines) {
                assertTrue(System.currentTimeMillis() < deadline, "test server did not start; last: $line")
                if (line.startsWith("LISTENING ")) {
                    url = line.substringAfter("LISTENING ")
                    return
                }
            }
            error("test server output ended before LISTENING")
        }

        fun close() {
            process.destroy()
            process.waitFor(5, TimeUnit.SECONDS)
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun findRepoRoot(): File? {
        var dir: File? = File(".").absoluteFile.parentFile ?: File(".")
        while (dir != null) {
            if (File(dir, "pyproject.toml").isFile && File(dir, "mockdevice").isDirectory) return dir
            dir = dir.parentFile
        }
        return null
    }

    /**
     * [FakeCommands], plus the P3.13 screenshot flow over the real HTTP
     * client: the JPEG upload names the still-pending command and precedes
     * the ACK, so the server's `before_result` marker proves the ordering
     * (PROTOCOL 5).
     */
    private class UploadingCommands(
        private val inner: CommandHandler,
        private val http: HttpExchange,
        private val url: () -> String,
        private val token: suspend () -> String,
    ) : CommandHandler {
        override fun onNewConnection() = inner.onNewConnection()

        override suspend fun handle(event: CommandEvent): CommandHandler.Reply =
            if (event.command == "screenshot") {
                val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + "jpeg-bytes".toByteArray()
                val result = http.postBytes(
                    "${url()}/api/tabula/screenshot",
                    "image/jpeg",
                    jpeg,
                    mapOf(
                        "Authorization" to "Bearer ${token()}",
                        "X-Tabula-Command-Id" to event.commandId,
                    ),
                )
                if (result.status == 200) {
                    CommandHandler.Reply.Ok()
                } else {
                    CommandHandler.Reply.Error(ErrorCode.FAILED, "screenshot upload rejected: HTTP ${result.status}")
                }
            } else {
                inner.handle(event)
            }
    }
}
