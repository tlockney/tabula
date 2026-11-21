package net.lockney.tabula.ha

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.channels.awaitClose
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * A client-owned WebSocket. All methods must be safe to call from any thread;
 * [send] returns false once the socket is closed, which the client treats as a
 * dead connection.
 */
interface WsTransport {
    fun send(text: String): Boolean

    fun close(code: Int = 1000, reason: String = "")
}

interface WsTransportFactory {
    fun open(url: String, listener: Listener): WsTransport

    interface Listener {
        fun onOpen()

        fun onText(text: String)

        fun onClosed()

        fun onFailure(throwable: Throwable?)
    }
}

/**
 * OkHttp-backed transport, used by the app and by the Python-test-server test.
 * Listener callbacks arrive on OkHttp threads and must not block.
 */
class OkHttpWsTransportFactory(
    client: OkHttpClient? = null,
) : WsTransportFactory {
    // Redirects are never followed: the WS handshake carries the access token
    // in its headers, and a redirect must not re-send them anywhere else.
    private val client = client ?: OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .pingInterval(0, TimeUnit.SECONDS) // HA sends its own pings; the app's keepalive is the HA ping command (PROTOCOL 4)
        .build()

    override fun open(url: String, listener: WsTransportFactory.Listener): WsTransport {
        val request = Request.Builder().url(url).build()
        val socket = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = listener.onOpen()

            override fun onMessage(webSocket: WebSocket, text: String) = listener.onText(text)

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = listener.onClosed()

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                listener.onFailure(t)
        }
        return OkHttpTransport(client.newWebSocket(request, socket))
    }

    private class OkHttpTransport(private val socket: WebSocket) : WsTransport {
        override fun send(text: String): Boolean = socket.send(text)

        override fun close(code: Int, reason: String) {
            socket.close(code, reason)
        }
    }
}
