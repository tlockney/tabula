package net.lockney.tabula.ha

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.lockney.tabula.core.Constants
import net.lockney.tabula.core.tlp.Capability
import net.lockney.tabula.core.tlp.CommandResultFrame
import net.lockney.tabula.core.tlp.ErrorCode
import net.lockney.tabula.core.tlp.NetworkType
import net.lockney.tabula.core.tlp.RegisterFrame
import net.lockney.tabula.core.tlp.StateDelta
import net.lockney.tabula.core.tlp.StateFrame
import net.lockney.tabula.core.tlp.Tlp

/**
 * The client's loops (ping every APP_PING_INTERVAL, reconnect backoff) suspend
 * forever, so virtual time can never run "until idle": these tests step time
 * explicitly with runCurrent and advanceTimeBy instead.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TlpClientTest {
    private val deviceId = "0f0e0d0c-0000-4000-8000-000000000001"
    private val refreshToken = "r".repeat(48)
    private val pingMs = (Constants.APP_PING_INTERVAL_S * 1000).toLong()
    private val pongMs = (Constants.PONG_TIMEOUT_S * 1000).toLong()
    private val registerMs = (Constants.REGISTER_TIMEOUT_S * 1000).toLong()

    private fun granted(token: String) =
        HttpExchange.Result(200, """{"access_token":"$token","expires_in":1800,"token_type":"Bearer"}""")

    private fun client(
        state: StateSource,
        server: ScriptedServer,
        tokens: TokenRefresher,
        commands: CommandHandler = FakeCommands(),
    ) = TlpClient(
        TlpClient.Config(
            haUrl = "http://ha:8123",
            deviceId = deviceId,
            appVersion = "0.1.0",
            manufacturer = "Tabula",
            model = "test",
            osVersion = "test",
            capabilities = listOf("battery"),
            settingsRevision = { 0 },
            refreshToken = { refreshToken },
            state = state,
            commands = commands,
        ),
        tokens,
        server,
        Backoff(Random(3)),
    )

    @Test
    fun `connects, authenticates, registers with versions and state, subscribes`() = runTest {
        val state = FakeState(
            StateDelta(
                batteryLevel = 80,
                charging = true,
                network = NetworkType.WIFI,
                wifiRssi = -50,
                appVersion = "0.1.0",
            ),
        )
        val commands = FakeCommands()
        val server = ScriptedServer()
        val events = RecordingEvents()
        val job = launch { client(state, server, tokens(granted("access-1")), commands).run(events) }
        runCurrent()
        assertEquals(1, events.registered.size)
        assertEquals(listOf(Capability.BATTERY), events.registered.single().capabilities)

        val register = Tlp.strict.decodeFromString(
            RegisterFrame.serializer(),
            server.registered.single().toString(),
        )
        assertEquals(deviceId, register.deviceId)
        assertEquals(Constants.PROTOCOL_VERSION, register.protocolVersion)
        assertEquals(1, register.schemaVersion)
        assertEquals("0.1.0", register.appVersion)
        assertEquals(listOf("battery"), register.capabilities)
        assertEquals(0, register.settingsRevision)
        assertEquals(80, register.state.batteryLevel)
        assertEquals(true, register.state.charging)
        assertEquals(NetworkType.WIFI, register.state.network)
        assertEquals("0.1.0", register.state.appVersion)

        val auth = Json.parseToJsonElement(server.connections.single().sent.first()).jsonObject
        assertEquals("auth", auth.str("type"))
        assertEquals("access-1", auth.str("access_token"))
        assertEquals(1, commands.connections)
        job.cancel()
    }

    private fun tokens(result: HttpExchange.Result) = TokenRefresher(FakeHttp { _, _ -> result }.exchange)

    private fun tokensCounting(): Pair<TokenRefresher, () -> Int> {
        var calls = 0
        val refresher = TokenRefresher(FakeHttp { _, _ -> granted("access-${++calls}") }.exchange)
        return refresher to { calls }
    }

    @Test
    fun `sends HA's ping every interval and stays connected on pong`() = runTest {
        val server = ScriptedServer()
        val events = RecordingEvents()
        val job = launch { client(FakeState(), server, tokens(granted("access-1"))).run(events) }
        runCurrent()
        assertEquals(0, server.pings.size)
        advanceTimeBy(pingMs + 1)
        runCurrent()
        assertEquals(1, server.pings.size)
        advanceTimeBy(pingMs)
        runCurrent()
        assertEquals(2, server.pings.size)
        assertEquals(0, events.disconnected)
        assertTrue(!server.connections.single().closed)
        job.cancel()
    }

    @Test
    fun `a missing pong closes the connection and reconnects with backoff`() = runTest {
        val server = ScriptedServer()
        server.suppress.add("ping") // never answer pings
        val events = RecordingEvents()
        // same seed as the client's backoff, so it predicts the reconnect delay
        val oracle = Backoff(Random(3))
        val job = launch { client(FakeState(), server, tokens(granted("access-1"))).run(events) }
        runCurrent()
        assertEquals(1, server.connections.size)
        // APP_PING_INTERVAL + PONG_TIMEOUT of silence ends the connection (PROTOCOL 4)
        advanceTimeBy(pingMs + pongMs + 1)
        runCurrent()
        assertTrue(server.connections.first().closed)
        assertEquals(1, events.disconnected)
        // the client reconnects after the attempt-0 backoff delay
        advanceTimeBy(oracle.nextDelayMs() + 1)
        runCurrent()
        assertEquals(2, server.connections.size)
        job.cancel()
    }

    @Test
    fun `commands are answered with exactly one result`() = runTest {
        val server = ScriptedServer()
        val commands = FakeCommands()
        val events = RecordingEvents()
        val job = launch { client(FakeState(), server, tokens(granted("access-1")), commands).run(events) }
        runCurrent()
        val conn = server.connections.single()
        val subscribeId = server.registered.single().int("id") + 1

        server.sendCommand(
            conn, subscribeId, "cmd-00001", "set_settings",
            """{"revision":7,"schema_version":1,"settings":{}}""",
        )
        runCurrent()
        assertEquals(1, server.results.size)
        val result = Tlp.strict.decodeFromString(
            CommandResultFrame.serializer(),
            server.results.single().toString(),
        )
        assertEquals("cmd-00001", result.commandId)
        assertEquals(true, result.ok)
        assertEquals(7, result.data!!["revision"]!!.jsonPrimitive.content.toInt())
        assertEquals(listOf("set_settings"), commands.handled.map { it.first })

        // an unknown command NACKs unsupported_command and keeps the connection;
        // a known command with schema-invalid args NACKs invalid_args
        server.sendCommand(conn, subscribeId, "cmd-00002", "the_future", """{}""")
        server.sendCommand(conn, subscribeId, "cmd-00003", "screen", """{}""")
        runCurrent()
        assertEquals(3, server.results.size)
        assertEquals(1, events.registered.size) // still connected
        val nack1 = Tlp.strict.decodeFromString(CommandResultFrame.serializer(), server.results[1].toString())
        val nack2 = Tlp.strict.decodeFromString(CommandResultFrame.serializer(), server.results[2].toString())
        assertEquals(false, nack1.ok)
        assertEquals(ErrorCode.UNSUPPORTED_COMMAND, nack1.error!!.code)
        assertEquals(false, nack2.ok)
        assertEquals(ErrorCode.INVALID_ARGS, nack2.error!!.code)
        job.cancel()
    }

    @Test
    fun `state changes coalesce to at most one frame per interval`() = runTest {
        val state = FakeState()
        val server = ScriptedServer()
        val events = RecordingEvents()
        val job = launch { client(state, server, tokens(granted("access-1"))).run(events) }
        runCurrent()
        assertEquals(0, server.states.size)

        state.change(StateDelta(batteryLevel = 50))
        runCurrent() // the collector picks it up and enters the interval wait
        state.change(StateDelta(volume = 30))
        advanceTimeBy(Constants.STATE_MIN_INTERVAL_MS + 1)
        runCurrent() // both changes leave in one merged frame
        assertEquals(1, server.states.size)
        val frame = Tlp.strict.decodeFromString(StateFrame.serializer(), server.states.single().toString())
        assertEquals(50, frame.state.batteryLevel)
        assertEquals(30, frame.state.volume)

        state.change(StateDelta(charging = true))
        advanceTimeBy(Constants.STATE_MIN_INTERVAL_MS + 1)
        runCurrent()
        assertEquals(2, server.states.size) // the next change waits out the interval again
        job.cancel()
    }

    @Test
    fun `auth invalid forces a token refresh and reconnects`() = runTest {
        val server = ScriptedServer()
        val (tokens, calls) = tokensCounting()
        val events = RecordingEvents()
        server.suppress.add("auth")
        server.onFrame = { frame ->
            if (frame.str("type") == "auth") {
                if (server.connections.indexOf(this) == 0) {
                    toClient("""{"type":"auth_invalid","message":"stale"}""")
                } else {
                    toClient("""{"type":"auth_ok","ha_version":"test"}""")
                }
            }
        }
        val job = launch { client(FakeState(), server, tokens).run(events) }
        advanceTimeBy((Constants.BACKOFF_BASE_S * 1000).toLong() + 1)
        runCurrent()
        assertEquals(2, server.connections.size)
        assertEquals(2, calls())
        val auths = server.connections.map { Json.parseToJsonElement(it.sent.first()).jsonObject }
        assertEquals("access-1", auths[0].str("access_token"))
        assertEquals("access-2", auths[1].str("access_token"))
        job.cancel()
    }

    @Test
    fun `a revoked credential stops the client instead of retrying`() = runTest {
        val server = ScriptedServer()
        val tokens = TokenRefresher(
            FakeHttp { _, _ -> HttpExchange.Result(400, """{"error":"invalid_grant"}""") }.exchange,
        )
        val events = RecordingEvents()
        val job = launch { client(FakeState(), server, tokens).run(events) }
        runCurrent()
        assertEquals(0, server.connections.size)
        assertEquals(1, events.revoked)
        assertTrue(job.isCompleted)
    }

    @Test
    fun `register timeout closes the socket and reconnects`() = runTest {
        val server = ScriptedServer()
        server.suppress.add("tabula/register") // never answer register
        val events = RecordingEvents()
        val oracle = Backoff(Random(3))
        val job = launch { client(FakeState(), server, tokens(granted("access-1"))).run(events) }
        runCurrent()
        assertEquals(1, server.connections.size)
        // no register_result within REGISTER_TIMEOUT: the handshake times out,
        // the socket closes and the client backs off (PROTOCOL 4.1, 7)
        advanceTimeBy(registerMs + 1)
        runCurrent()
        assertTrue(server.connections.first().closed)
        assertEquals(1, events.disconnected)
        advanceTimeBy(oracle.nextDelayMs() + 1)
        runCurrent()
        assertEquals(2, server.connections.size)
        job.cancel()
    }

    @Test
    fun `unknown device is final`() = runTest {
        val server = ScriptedServer()
        server.suppress.add("tabula/register")
        server.onFrame = { frame ->
            if (frame.str("type") == "tabula/register") {
                replyError(frame.int("id"), "unknown_device", "not this device")
            }
        }
        val events = RecordingEvents()
        val job = launch { client(FakeState(), server, tokens(granted("access-1"))).run(events) }
        runCurrent()
        assertEquals(1, server.connections.size)
        assertEquals(listOf("unknown_device" to "not this device"), events.rejected)
        assertTrue(job.isCompleted)
    }

    @Test
    fun `superseded closes the connection and a new one takes over`() = runTest {
        val server = ScriptedServer()
        val events = RecordingEvents()
        val job = launch { client(FakeState(), server, tokens(granted("access-1"))).run(events) }
        runCurrent()
        val conn = server.connections.first()
        // the server answers the next ping with a superseded error (PROTOCOL 4.9)
        server.suppress.add("ping")
        server.onFrame = { frame ->
            if (frame.str("type") == "ping") {
                replyError(frame.int("id"), "superseded", "a newer connection replaced this one")
            }
        }
        advanceTimeBy(pingMs + 1)
        runCurrent()
        assertTrue(conn.closed, "a superseded reply must close the socket")
        assertEquals(1, events.disconnected)
        val oracle = Backoff(Random(3))
        advanceTimeBy(oracle.nextDelayMs() + 1)
        runCurrent()
        assertEquals(2, server.connections.size)
        job.cancel()
    }
}
