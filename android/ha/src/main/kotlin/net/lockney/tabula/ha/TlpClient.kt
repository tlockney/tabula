package net.lockney.tabula.ha

import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.lockney.tabula.core.Constants
import net.lockney.tabula.core.settings.SCHEMA_VERSION
import net.lockney.tabula.core.tlp.CommandFrame
import net.lockney.tabula.core.tlp.CommandResultFrame
import net.lockney.tabula.core.tlp.Commands
import net.lockney.tabula.core.tlp.ErrorCode
import net.lockney.tabula.core.tlp.EventFrame
import net.lockney.tabula.core.tlp.PingFrame
import net.lockney.tabula.core.tlp.RegisterFrame
import net.lockney.tabula.core.tlp.RegisterResultData
import net.lockney.tabula.core.tlp.StateDelta
import net.lockney.tabula.core.tlp.StateFrame
import net.lockney.tabula.core.tlp.SubscribeFrame
import net.lockney.tabula.core.tlp.Tlp
import net.lockney.tabula.core.tlp.TlpError

/**
 * The device's TLP session over one HA WebSocket: auth, register with versions
 * and capabilities, subscribe, the command loop with exactly one tabula/result
 * per command, coalesced state pushes, the HA ping heartbeat, and reconnect
 * with equal-jitter backoff (docs/PROTOCOL.md 4, 7; values from ADR 0002).
 *
 * Pure Kotlin, driven through [WsTransportFactory] and [HttpExchange], so the
 * JVM tests run it against fakes and against mockdevice's Python test server.
 * A reconnect repeats the whole session order; registration is idempotent.
 * Commands in flight when a connection drops are lost and never replayed
 * (PROTOCOL 7).
 *
 * [run] returns when the credential is revoked (the device goes back to
 * pairing), when registration is rejected in a way retrying cannot fix, or
 * when the caller cancels it.
 */
class TlpClient(
    private val config: Config,
    private val tokens: TokenRefresher,
    private val transportFactory: WsTransportFactory,
    private val backoff: Backoff = Backoff(),
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    /** Everything the session needs from the device. */
    data class Config(
        val haUrl: String,
        val deviceId: String,
        val appVersion: String,
        val manufacturer: String,
        val model: String,
        val osVersion: String,
        val capabilities: List<String>,
        val settingsRevision: () -> Int,
        val refreshToken: () -> String,
        val state: StateSource,
        val commands: CommandHandler,
        /**
         * Device events to send (PROTOCOL 4.7), each built for the frame id
         * it is sent with. Events raised while no session is registered are
         * dropped when the next one starts: they describe a moment HA did not
         * see, and the registration state already carries the outcome.
         */
        val events: ReceiveChannel<(Int) -> EventFrame>? = null,
    )

    /** Session lifecycle callbacks; called from the client's coroutines. */
    interface Events {
        fun onRegistered(result: RegisterResultData)

        fun onDisconnected()

        /** The credential was revoked; the device returns to pairing (ADR 0001). */
        fun onRevoked()

        /** Registration was refused in a way retrying cannot fix (PROTOCOL 6). */
        fun onRejected(code: String, message: String)
    }

    /** Registration was refused in a way retrying cannot fix (PROTOCOL 6). */
    class ProtocolRejected(val code: String, val detail: String) :
        IOException("registration refused: $code: $detail")

    private val _channel = MutableStateFlow<HaChannel?>(null)

    /**
     * HA core commands on the current registered connection, or null while
     * none is (ADR 0017: the entity data subscription shares this socket).
     * A new value per connection; consumers re-subscribe when it changes.
     */
    val channel: StateFlow<HaChannel?> = _channel.asStateFlow()

    private val registerTimeoutMs = (Constants.REGISTER_TIMEOUT_S * 1000).toLong()
    private val pongTimeoutMs = (Constants.PONG_TIMEOUT_S * 1000).toLong()
    private val pingIntervalMs = (Constants.APP_PING_INTERVAL_S * 1000).toLong()
    private val stableAfterMs = (Constants.BACKOFF_RESET_AFTER_S * 1000).toLong()

    /** Connect and reconnect until revoked, rejected or cancelled. */
    suspend fun run(events: Events) {
        while (true) {
            try {
                session(events)
            } catch (e: CredentialRevoked) {
                events.onRevoked() // final: the caller returns to pairing (ADR 0001)
                return
            } catch (e: ProtocolRejected) {
                events.onRejected(e.code, e.detail) // final; the caller surfaces it
                return
            } catch (e: TimeoutCancellationException) {
                // a handshake or pong timed out; close and back off (PROTOCOL 4.1, 4)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // connection ended; reconnect (PROTOCOL 7)
            }
            events.onDisconnected()
            delay(backoff.nextDelayMs())
        }
    }

    // --- one connection -------------------------------------------------------

    /**
     * One connection: transport, inbound frames, request/response matching by
     * HA message id, and a mutex so concurrent senders keep ids increasing.
     * Request matchers are registered before the frame is sent, so a reply
     * that races the sender is never dropped.
     *
     * [pending] is shared across threads without a common lock: `request`
     * mutates it under [sendMutex], while [completePending] runs from the
     * read loop and [failPending] from the OkHttp listener threads
     * (Transport.kt). It is therefore a [ConcurrentHashMap] with
     * remove-then-complete operations: each pending entry is completed exactly
     * once, by whichever side removed it.
     *
     * Internal (not private) so the concurrency test can drive it directly.
     */
    internal class Connection {
        lateinit var transport: WsTransport
        val initial = CompletableDeferred<Result<Unit>>()
        val inbound = Channel<String>(Channel.UNLIMITED)
        private val pending = ConcurrentHashMap<Int, CompletableDeferred<JsonObject>>()

        /** HA subscription id -> event consumer (HaChannel.subscribe). */
        val eventRoutes = ConcurrentHashMap<Int, (JsonObject) -> Unit>()
        private val sendMutex = Mutex()
        var nextId = 1
        var subscribeId = -1

        val listener = object : WsTransportFactory.Listener {
            override fun onOpen() {
                initial.complete(Result.success(Unit))
            }

            override fun onText(text: String) {
                inbound.trySend(text)
            }

            override fun onClosed() {
                finish(IOException("connection closed"))
            }

            override fun onFailure(throwable: Throwable?) {
                finish(throwable ?: IOException("connection failed"))
            }

            private fun finish(cause: Throwable) {
                if (initial.isActive) initial.complete(Result.failure(cause))
                failPending(cause)
                inbound.close()
            }
        }

        /** Fire-and-forget send; the id is allocated but no reply is awaited. */
        suspend fun send(encode: (Int) -> String): Int = sendMutex.withLock {
            val id = nextId
            nextId += 1
            if (!transport.send(encode(id))) throw IOException("connection closed")
            id
        }

        /** Reserve the next HA message id (must increase within a connection). */
        suspend fun allocId(): Int = sendMutex.withLock {
            val id = nextId
            nextId += 1
            id
        }

        /** Send the frame for [id] and await its reply envelope within [timeoutMs]. */
        suspend fun request(id: Int, encode: (Int) -> String, timeoutMs: Long): JsonObject {
            val deferred = CompletableDeferred<JsonObject>()
            sendMutex.withLock {
                pending[id] = deferred
                try {
                    if (!transport.send(encode(id))) throw IOException("connection closed")
                } catch (e: Exception) {
                    pending.remove(id)
                    throw e
                }
            }
            return try {
                withTimeout(timeoutMs) { deferred.await() }
            } finally {
                pending.remove(id)
            }
        }

        /**
         * Atomically remove the pending entry and complete it: only the side
         * that removed it completes, so a racing [failPending] cannot
         * double-complete, and the read loop's reply can never arrive on a
         * cleared map and vanish.
         */
        fun completePending(id: Int, message: JsonObject) {
            pending.remove(id)?.complete(message)
        }

        /**
         * Fail everything in flight. The iteration is weakly consistent (safe
         * against concurrent [request] mutation), and each entry is removed
         * before being completed, so it is completed exactly once here.
         */
        fun failPending(cause: Throwable) {
            val iterator = pending.entries.iterator()
            while (iterator.hasNext()) {
                iterator.next().value.completeExceptionally(cause)
                iterator.remove()
            }
        }
    }

    private suspend fun session(events: Events) {
        val haUrl = config.haUrl.trimEnd('/')
        val token = tokens.access(haUrl, config.refreshToken)
        val conn = Connection()
        conn.transport = transportFactory.open("$haUrl/api/websocket", conn.listener)
        try {
            withTimeout(registerTimeoutMs) { conn.initial.await().getOrThrow() }
            authenticate(conn, token)
            coroutineScope {
                val readJob = launch { readLoop(conn) }
                val result = register(conn)
                subscribe(conn)
                config.commands.onNewConnection()
                events.onRegistered(result)
                _channel.value = ConnectionChannel(conn)
                val stableJob = launch {
                    delay(stableAfterMs)
                    backoff.markStable()
                }
                val pingJob = launch { pingLoop(conn) }
                val stateJob = launch { stateLoop(conn) }
                val eventJob = launch { eventLoop(conn) }
                readJob.join()
                eventJob.cancelAndJoin()
                stableJob.cancelAndJoin()
                pingJob.cancelAndJoin()
                stateJob.cancelAndJoin()
            }
        } finally {
            if (_channel.value.let { it is ConnectionChannel && it.conn === conn }) _channel.value = null
            conn.eventRoutes.clear()
            // A timed-out handshake closes the socket too (PROTOCOL 4.1).
            conn.transport.close(1000, "session end")
            conn.failPending(IOException("session ended"))
            conn.inbound.close()
        }
    }

    /** HA's WebSocket auth handshake (docs/PROTOCOL.md 4). */
    private suspend fun authenticate(conn: Connection, token: String) {
        val greeting = receiveJson(conn)
        if (greeting.str("type") != "auth_required") {
            throw IOException("unexpected greeting: ${greeting.str("type")}")
        }
        val auth = buildJsonObject {
            put("type", "auth")
            put("access_token", token)
        }.toString()
        conn.send { auth } // no numbered id; the allocation is harmless and keeps ids increasing
        val reply = receiveJson(conn)
        when (reply.str("type")) {
            "auth_ok" -> Unit
            "auth_invalid" -> {
                // The access token went stale (HA restart); force a refresh and
                // let the reconnect loop retry with the new one.
                tokens.forceRefresh(config.haUrl.trimEnd('/'), config.refreshToken)
                throw IOException("auth invalid; refreshed the access token")
            }
            else -> throw IOException("unexpected auth reply: ${reply.str("type")}")
        }
    }

    private suspend fun register(conn: Connection): RegisterResultData {
        val id = conn.allocId()
        val reply = conn.request(
            id,
            { msgId ->
                Tlp.encodeFrame(
                    "register",
                    RegisterFrame(
                        id = msgId,
                        deviceId = config.deviceId,
                        protocolVersion = Constants.PROTOCOL_VERSION,
                        schemaVersion = SCHEMA_VERSION,
                        appVersion = config.appVersion,
                        manufacturer = config.manufacturer,
                        model = config.model,
                        osVersion = config.osVersion,
                        capabilities = config.capabilities,
                        settingsRevision = config.settingsRevision(),
                        state = config.state.current(),
                    ),
                )
            },
            registerTimeoutMs,
        )
        return classifyRegister(reply)
    }

    private fun classifyRegister(reply: JsonObject): RegisterResultData {
        val success = reply["success"]?.jsonPrimitive?.booleanOrNull
            ?: throw IOException("register reply has no success: $reply")
        if (!success) {
            val error = reply["error"]?.jsonObject
            val code = error?.get("code")?.jsonPrimitive?.content ?: "unknown"
            val message = error?.get("message")?.jsonPrimitive?.content ?: ""
            if (code == "unsupported_protocol" || code == "unknown_device") {
                throw ProtocolRejected(code, message)
            }
            throw IOException("register refused: $code: $message")
        }
        val result = reply["result"]?.jsonObject
            ?: throw IOException("register reply has no result: $reply")
        return try {
            Tlp.json.decodeFromString(RegisterResultData.serializer(), result.toString())
        } catch (e: kotlinx.serialization.SerializationException) {
            throw IOException("malformed register_result: ${e.message}")
        }
    }

    private suspend fun subscribe(conn: Connection) {
        // Know the subscription id before the reply: commands can follow the
        // ack in the same read.
        val id = conn.allocId()
        conn.subscribeId = id
        val reply = conn.request(id, { msgId -> Tlp.encodeFrame("subscribe", SubscribeFrame(msgId)) }, registerTimeoutMs)
        val success = reply["success"]?.jsonPrimitive?.booleanOrNull
        if (success != true) throw IOException("subscribe refused: $reply")
    }

    /** Wait for a message the auth handshake exchanges before any reader runs. */
    private suspend fun receiveJson(conn: Connection): JsonObject =
        withTimeout(registerTimeoutMs) {
            val text = conn.inbound.receive()
            when (val element = Json.parseToJsonElement(text)) {
                is JsonObject -> element
                is JsonArray -> element.firstOrNull() as? JsonObject
                else -> null
            } ?: throw IOException("malformed message")
        }

    // --- inbound ----------------------------------------------------------------

    private suspend fun readLoop(conn: Connection) {
        try {
            coroutineScope {
                for (text in conn.inbound) {
                    // HA may coalesce several messages into one JSON array.
                    val element = try {
                        Json.parseToJsonElement(text)
                    } catch (e: IllegalArgumentException) {
                        null
                    } ?: continue
                    val messages = (element as? JsonArray)
                        ?.filterIsInstance<JsonObject>()
                        ?: listOfNotNull(element as? JsonObject)
                    for (message in messages) {
                        when {
                            message.str("type") == "event" &&
                                message["id"]?.jsonPrimitive?.intOrNull == conn.subscribeId ->
                                launch { handleCommand(conn, message) }

                            message.str("type") == "result" || message.str("type") == "pong" ->
                                dispatch(conn, message)

                            message.str("type") == "event" -> routeEvent(conn, message)
                        }
                    }
                }
            }
        } finally {
            conn.failPending(IOException("connection closed"))
        }
    }

    /** An HA core subscription's event (HaChannel.subscribe); unknown ids are dropped. */
    private fun routeEvent(conn: Connection, message: JsonObject) {
        val id = message["id"]?.jsonPrimitive?.intOrNull ?: return
        val route = conn.eventRoutes[id] ?: return
        val event = message["event"] as? JsonObject ?: return
        runCatching { route(event) } // a consumer's bug must not end the TLP session
    }

    /** [HaChannel] over one registered connection (ADR 0017). */
    private inner class ConnectionChannel(val conn: Connection) : HaChannel {
        override suspend fun call(message: JsonObject): JsonElement? {
            val id = conn.allocId()
            val reply = conn.request(id, { msgId -> withId(message, msgId) }, registerTimeoutMs)
            return resultOf(reply)
        }

        override suspend fun subscribe(message: JsonObject, onEvent: (JsonObject) -> Unit): HaSubscription {
            val id = conn.allocId()
            conn.eventRoutes[id] = onEvent
            try {
                resultOf(conn.request(id, { msgId -> withId(message, msgId) }, registerTimeoutMs))
            } catch (e: Exception) {
                conn.eventRoutes.remove(id)
                throw e
            }
            return object : HaSubscription {
                override val id: Int = id

                override suspend fun cancel() {
                    if (conn.eventRoutes.remove(id) == null) return
                    runCatching {
                        call(buildJsonObject {
                            put("type", "unsubscribe_events")
                            put("subscription", id)
                        })
                    }
                }
            }
        }

        private fun withId(message: JsonObject, id: Int): String =
            JsonObject(mapOf("id" to JsonPrimitive(id)) + message.filterKeys { it != "id" }).toString()

        private fun resultOf(reply: JsonObject): JsonElement? {
            if (reply["success"]?.jsonPrimitive?.booleanOrNull == true) return reply["result"]
            val error = reply["error"] as? JsonObject
            throw HaCommandError(
                error?.str("code") ?: "unknown_error",
                (error?.str("message") ?: reply.toString()).take(200),
            )
        }
    }

    private fun dispatch(conn: Connection, message: JsonObject) {
        if (message.str("type") == "result" &&
            message["success"]?.jsonPrimitive?.booleanOrNull == false &&
            message["error"]?.jsonObject?.get("code")?.jsonPrimitive?.content == "superseded"
        ) {
            // A newer registration replaced this connection (PROTOCOL 4.9):
            // close it; the waiting request also fails when the socket dies.
            conn.transport.close(1000, "superseded")
        }
        message["id"]?.jsonPrimitive?.intOrNull?.let { id ->
            conn.completePending(id, message)
        }
    }

    private suspend fun handleCommand(conn: Connection, raw: JsonObject) {
        val commandId = raw["event"]?.jsonObject?.get("command_id")?.jsonPrimitive?.content
        val command = raw["event"]?.jsonObject?.get("command")?.jsonPrimitive?.content
        if (commandId == null || command == null) return // unanswerable without a command_id
        val reply: CommandHandler.Reply = runCatching {
            val frame = Tlp.json.decodeFromString(CommandFrame.serializer(), raw.toString())
            if (frame.event.command !in Commands.all) {
                // A command from a newer server: NACK it, keep the connection (PROTOCOL 4.0).
                CommandHandler.Reply.Error(ErrorCode.UNSUPPORTED_COMMAND, "unknown command $command")
            } else {
                validateArgs(frame.event) // schema-invalid args NACK as invalid_args (mock parity)
                runCatching { config.commands.handle(frame.event) }
                    .getOrElse {
                        // A closing connection cancelled the handler mid-command
                        // (mid-upload for a screenshot): that is not a command
                        // failure, and writing FAILED to the dead socket is
                        // wrong - rethrow so the launch ends cancelled (review).
                        if (it is CancellationException) throw it
                        CommandHandler.Reply.Error(ErrorCode.FAILED, it.message ?: "command failed")
                    }
            }
        }.getOrElse {
            // Same for the decode/validate scope: cancellation must reach the
            // command's launch, not become a reply (review must-fix).
            if (it is CancellationException) throw it
            CommandHandler.Reply.Error(ErrorCode.INVALID_ARGS, (it.message ?: "invalid frame").take(500))
        }
        sendResult(conn, commandId, reply)
    }

    /** Decode the command's typed args with the tolerant codec (PROTOCOL 4.0). */
    private fun validateArgs(event: net.lockney.tabula.core.tlp.CommandEvent) {
        when (event.command) {
            Commands.SET_SETTINGS -> event.setSettingsArgs(Tlp.json)
            Commands.SCREEN -> event.screenArgs(Tlp.json)
            Commands.NOTIFY -> event.notifyArgs(Tlp.json)
            Commands.PLAY_MEDIA -> event.playMediaArgs(Tlp.json)
            Commands.MEDIA_CONTROL -> event.mediaControlArgs(Tlp.json)
            Commands.SCREENSAVER -> event.screensaverArgs(Tlp.json)
            Commands.NAVIGATE -> event.navigateArgs(Tlp.json)
            Commands.RELOAD, Commands.RESTART_APP, Commands.CLEAR_CACHE, Commands.GO_HOME -> event.emptyArgs(Tlp.json)
            Commands.LOG_SUBSCRIPTION -> event.logSubscriptionArgs(Tlp.json)
            Commands.SCREENSHOT -> event.screenshotArgs(Tlp.json)
        }
    }

    private suspend fun sendResult(conn: Connection, commandId: String, reply: CommandHandler.Reply) {
        try {
            conn.send { id ->
                val frame = when (reply) {
                    is CommandHandler.Reply.Ok -> CommandResultFrame.ack(id, commandId, reply.data)
                    is CommandHandler.Reply.Error ->
                        CommandResultFrame.nack(id, commandId, TlpError(reply.code, reply.message))
                }
                Tlp.encodeFrame("command_result", frame)
            }
        } catch (e: IllegalArgumentException) {
            // command_id itself fails the wire pattern; no valid result frame can name it
        }
    }

    // --- outbound loops -----------------------------------------------------------

    /**
     * The device's own keepalive (PROTOCOL 4): HA's library-level pings are
     * invisible to the app, so send HA's ping command every APP_PING_INTERVAL
     * and treat a missing pong after PONG_TIMEOUT as a dead connection.
     *
     * A bare withTimeout inside this child coroutine would surface as a
     * CancellationException and leave the session running, so a missing pong is
     * converted into a connection failure explicitly.
     */
    private suspend fun pingLoop(conn: Connection) {
        while (true) {
            delay(pingIntervalMs)
            val id = conn.allocId()
            val reply = try {
                conn.request(id, { msgId -> Tlp.encodeFrame("ping", PingFrame(msgId)) }, pongTimeoutMs)
            } catch (e: TimeoutCancellationException) {
                throw IOException("no pong within PONG_TIMEOUT", e)
            }
            if (reply.str("type") != "pong") throw IOException("ping answered with $reply")
        }
    }

    /**
     * Send device events in order. Events queued before this loop starts are
     * dropped: they describe moments the register frame's state already
     * covers. An event raised in the short gap between the register snapshot
     * and this loop's start is dropped with them; state still converges
     * through the next delta, but an HA automation keyed on the event misses
     * that one edge (a known gap, ADR 0013).
     */
    private suspend fun eventLoop(conn: Connection) {
        val events = config.events ?: return
        while (events.tryReceive().isSuccess) {
            // drop it
        }
        while (true) {
            val build = events.receive()
            conn.send { id -> Tlp.encodeFrame("event", build(id)) }
        }
    }

    /**
     * Push state deltas, at most one frame per STATE_MIN_INTERVAL (PROTOCOL 4.4).
     * A change that arrives while the interval is waited out merges into the
     * same frame; the source's unlimited channel never drops one.
     */
    private suspend fun stateLoop(conn: Connection) {
        var lastSendMs = clockMs()
        var merged = StateDelta()
        while (true) {
            merged = mergeState(merged, config.state.changes.receive())
            val sinceLast = clockMs() - lastSendMs
            if (sinceLast < Constants.STATE_MIN_INTERVAL_MS) {
                delay(Constants.STATE_MIN_INTERVAL_MS - sinceLast)
                // drain changes that arrived during the wait into this frame
                while (true) {
                    val next = config.state.changes.tryReceive().getOrNull() ?: break
                    merged = mergeState(merged, next)
                }
            }
            val toSend = merged
            merged = StateDelta()
            lastSendMs = clockMs()
            if (toSend.isNotEmpty) {
                conn.send { id -> Tlp.encodeFrame("state_delta", StateFrame(id = id, state = toSend)) }
            }
        }
    }
}

private fun JsonObject.str(name: String): String? = this[name]?.jsonPrimitive?.content
