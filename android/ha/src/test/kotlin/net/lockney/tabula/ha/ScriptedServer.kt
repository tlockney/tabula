package net.lockney.tabula.ha

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import net.lockney.tabula.core.tlp.ErrorCode
import net.lockney.tabula.core.tlp.NetworkType
import net.lockney.tabula.core.tlp.RegisterResultData
import net.lockney.tabula.core.tlp.StateDelta

/** A scripted HA side of the WebSocket: hands the client frames and records its own. */
class ScriptedServer : WsTransportFactory {
    val connections = mutableListOf<Conn>()
    val registered = mutableListOf<JsonObject>()
    val states = mutableListOf<JsonObject>()
    val results = mutableListOf<JsonObject>()
    val events = mutableListOf<JsonObject>()
    val pings = mutableListOf<Int>()

    /** Per-frame override for scenario scripting; runs after the default reply. */
    var onFrame: (Conn.(JsonObject) -> Unit)? = null

    /** Frame types the server records but never answers (timeout scenarios). */
    val suppress = mutableSetOf<String>()

    inner class Conn : WsTransport {
        lateinit var listener: WsTransportFactory.Listener
        var closed = false
        val sent = mutableListOf<String>()

        override fun send(text: String): Boolean {
            if (closed) return false
            sent.add(text)
            receive(Json.parseToJsonElement(text).jsonObject)
            return true
        }

        override fun close(code: Int, reason: String) {
            if (!closed) {
                closed = true
                listener.onClosed()
            }
        }

        fun toClient(text: String) = listener.onText(text)

        private fun receive(frame: JsonObject) {
            when (val type = frame.str("type")) {
                "auth" -> if ("auth" !in suppress) {
                    toClient("""{"type":"auth_ok","ha_version":"test"}""")
                }
                "tabula/register" -> {
                    registered.add(frame)
                    if ("tabula/register" !in suppress) {
                        reply(
                            frame.int("id"),
                            buildJsonObject {
                                put("ha_device_id", "hadevice1")
                                put("protocol_version", 1)
                                put("settings_revision", 0)
                                put("server_time", "2026-11-21T12:00:00+00:00")
                                put("capabilities", kotlinx.serialization.json.buildJsonArray {
                                    add(kotlinx.serialization.json.JsonPrimitive("battery"))
                                })
                            },
                        )
                    }
                }
                "tabula/subscribe" -> {
                    if ("tabula/subscribe" !in suppress) reply(frame.int("id"), null)
                }
                "ping" -> {
                    pings.add(frame.int("id"))
                    if ("ping" !in suppress) toClient("""{"id":${frame.int("id")},"type":"pong"}""")
                }
                "tabula/state" -> states.add(frame)
                "tabula/result" -> results.add(frame)
                "tabula/event" -> events.add(frame)
            }
            onFrame?.invoke(this, frame)
        }

        private fun reply(id: Int, result: kotlinx.serialization.json.JsonElement?) {
            toClient(
                buildJsonObject {
                    put("id", id)
                    put("type", "result")
                    put("success", true)
                    if (result != null) put("result", result) else put("result", kotlinx.serialization.json.JsonNull)
                }.toString(),
            )
        }

        fun replyError(id: Int, code: String, message: String) {
            toClient(
                buildJsonObject {
                    put("id", id)
                    put("type", "result")
                    put("success", false)
                    put("error", buildJsonObject {
                        put("code", code)
                        put("message", message)
                    })
                }.toString(),
            )
        }
    }

    override fun open(url: String, listener: WsTransportFactory.Listener): WsTransport {
        val conn = Conn().also { it.listener = listener }
        connections.add(conn)
        listener.onOpen()
        listener.onText("""{"type":"auth_required","ha_version":"test"}""")
        return conn
    }

    fun sendCommand(conn: Conn, subscribeId: Int, commandId: String, command: String, args: String) {
        conn.toClient(
            """{"id":$subscribeId,"type":"event","event":{"command_id":"$commandId","command":"$command","args":$args}}""",
        )
    }
}

class FakeState(
    initial: StateDelta = StateDelta(
        batteryLevel = 80,
        charging = true,
        network = NetworkType.WIFI,
        wifiRssi = -50,
        appVersion = "0.1.0",
    ),
) : StateSource {
    var currentDelta = initial
    private val channel = Channel<StateDelta>(Channel.UNLIMITED)

    override fun current(): StateDelta = currentDelta

    override val changes: kotlinx.coroutines.channels.ReceiveChannel<StateDelta> = channel

    fun change(delta: StateDelta) {
        channel.trySend(delta)
    }
}

class FakeCommands : CommandHandler {
    var connections = 0
    val handled = mutableListOf<Pair<String, JsonObject>>()

    override fun onNewConnection() {
        connections++
    }

    override fun handle(command: String, args: JsonObject): CommandHandler.Reply = when (command) {
        "set_settings" -> CommandHandler.Reply.Ok(
            buildJsonObject { put("revision", args["revision"]!!.jsonPrimitive.intOrNull) },
        )
        "reload", "clear_cache", "restart_app" -> CommandHandler.Reply.Ok()
        else -> CommandHandler.Reply.Error(ErrorCode.UNSUPPORTED_COMMAND, "not declared: $command")
    }
        .also { handled.add(command to args) }
}

class RecordingEvents : TlpClient.Events {
    val registered = mutableListOf<RegisterResultData>()
    var disconnected = 0
    var revoked = 0
    val rejected = mutableListOf<Pair<String, String>>()

    override fun onRegistered(result: RegisterResultData) {
        registered.add(result)
    }

    override fun onDisconnected() {
        disconnected++
    }

    override fun onRevoked() {
        revoked++
    }

    override fun onRejected(code: String, message: String) {
        rejected.add(code to message)
    }
}

internal fun JsonObject.str(name: String): String? = this[name]?.jsonPrimitive?.content

internal fun JsonObject.int(name: String): Int = this[name]!!.jsonPrimitive.intOrNull
    ?: error("no int field $name in $this")
