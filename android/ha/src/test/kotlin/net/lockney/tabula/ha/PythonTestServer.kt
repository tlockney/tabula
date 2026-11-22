package net.lockney.tabula.ha

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import net.lockney.tabula.core.tlp.CommandResultFrame
import net.lockney.tabula.core.tlp.RegisterResultData
import net.lockney.tabula.core.tlp.Tlp
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue

/**
 * `uv run python -m mockdevice serve`, started from the repo checkout: the
 * in-process Python test server the socket-level JVM tests drive (P2.1's
 * pattern; shared by [PythonServerTest] and [EntityDataServerTest]).
 */
internal class TestServerProcess(root: File) {
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

/** The repo checkout above the working directory, or null (the socket tests then skip). */
internal fun findRepoRoot(): File? {
    var dir: File? = File(".").absoluteFile.parentFile ?: File(".")
    while (dir != null) {
        if (File(dir, "pyproject.toml").isFile && File(dir, "mockdevice").isDirectory) return dir
        dir = dir.parentFile
    }
    return null
}

internal fun uvAvailable(): Boolean =
    runCatching { ProcessBuilder("uv", "--version").start().waitFor(5, TimeUnit.SECONDS) }.getOrDefault(false)

/**
 * Runs [scenario] against a fresh Python test server, or skips the test when
 * the checkout or uv is missing. Each feature's socket test is its own class
 * built on this and [pairAndRegister], so parallel packets add files instead
 * of editing one shared scenario.
 */
internal fun withPythonServer(scenario: (url: String) -> Unit) {
    val root = findRepoRoot()
    assumeTrue("repo checkout not found from ${File(".").absolutePath}", root != null)
    assumeTrue("uv is not available", uvAvailable())
    val server = TestServerProcess(root!!)
    try {
        server.awaitListening()
        scenario(server.url)
    } finally {
        server.close()
    }
}

/** POST a `/test/<name>` hook and require 200. */
internal suspend fun HttpExchange.testHook(url: String, name: String, body: String) {
    val reply = post("$url/test/$name", "application/json", body)
    assertEquals(200, reply.status, "$name: ${reply.body}")
}

/** The server's `/test/report` entry for one device. */
internal suspend fun HttpExchange.deviceReport(url: String, deviceId: String): JsonObject {
    val report = get("$url/test/report")
    assertEquals(200, report.status)
    return Json.parseToJsonElement(report.body).jsonObject["devices"]!!.jsonObject[deviceId]!!.jsonObject
}

/** The server's schema problems with anything clients sent; "[]" when clean. */
internal suspend fun HttpExchange.reportedProblems(url: String): String =
    Json.parseToJsonElement(get("$url/test/report").body).jsonObject["problems"].toString()

/** Pair over HTTP (confirming through the test hook) and return the refresh token. */
internal suspend fun HttpExchange.pair(url: String, deviceId: String): String {
    val pairing = PairingClient(this)
    val attempt = pairing.newAttempt()
    pairing.start(url, deviceId, attempt, "JUnit", "jvm")
    testHook(url, "confirm", """{"device_id":"$deviceId","code":"${attempt.code}"}""")
    return (pairing.claim(url, deviceId, attempt) as ClaimOutcome.Paired).credential.refreshToken
}

/** A registered TLP session; cancel [job] to end it. */
internal class RegisteredSession(val client: TlpClient, val job: Job, val tokens: TokenRefresher)

/**
 * Start the real TLP client for an already-paired device and wait until the
 * server has registered it. [commands] receives the session's token source
 * for handlers that call back over HTTP (screenshot upload).
 */
internal suspend fun CoroutineScope.register(
    url: String,
    deviceId: String,
    refreshToken: String,
    http: HttpExchange,
    okHttp: OkHttpClient,
    capabilities: List<String>,
    commands: (TokenRefresher) -> CommandHandler = { FakeCommands() },
): RegisteredSession {
    val tokens = TokenRefresher(http)
    val registered = CompletableDeferred<Unit>()
    val client = TlpClient(
        TlpClient.Config(
            haUrl = url,
            deviceId = deviceId,
            appVersion = "0.1.0-test",
            manufacturer = "Tabula",
            model = "jvm-test",
            osVersion = "jvm",
            capabilities = capabilities,
            settingsRevision = { 0 },
            refreshToken = { refreshToken },
            state = FakeState(),
            commands = commands(tokens),
        ),
        tokens,
        OkHttpWsTransportFactory(okHttp),
    )
    val events = object : TlpClient.Events {
        override fun onRegistered(result: RegisterResultData) {
            registered.complete(Unit)
        }

        override fun onDisconnected() = Unit

        override fun onRevoked() = Unit

        override fun onRejected(code: String, message: String) = Unit
    }
    val job = launch { client.run(events) }
    withTimeout(20_000) { registered.await() }
    return RegisteredSession(client, job, tokens)
}

/** Send a command through the server's hook and decode the device's result frame. */
internal suspend fun HttpExchange.command(url: String, deviceId: String, command: String, args: String): CommandResultFrame {
    val reply = post(
        "$url/test/command",
        "application/json",
        """{"device_id":"$deviceId","command":"$command","args":$args}""",
    )
    assertEquals(200, reply.status, "$command: ${reply.body}")
    return Tlp.strict.decodeFromString(
        CommandResultFrame.serializer(),
        Json.parseToJsonElement(reply.body).jsonObject["result"].toString(),
    )
}
