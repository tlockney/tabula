package net.lockney.tabula.spike.intercom

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The spike's throwaway signaling path (packet question 2): a plain HTTP relay
 * on the LAN, one room per call, offer/answer/ICE as text. Production signaling
 * rides TLP over the device's HA connection (ADR 0025 decision 4); this exists
 * only to place a call without it, and lives in the debug-only spike app.
 *
 * Wire shape, all JSON text:
 *   POST {base}/send?room=R&role=C   body: one message
 *   GET  {base}/poll?room=R&role=C&since=N
 *        -> {"server_ms":..., "seq":..., "messages":[[seq,from,body],...]}
 */
class RelayClient(base: String, private val room: String, private val role: String) {
    private val base = base.trimEnd('/')

    /** Where the relay's clock was when the last poll was answered (question 7's clock sync). */
    var lastServerMs: Long = 0
        private set

    /** The highest relay sequence number this client has consumed. */
    var since: Long = 0
        private set

    private fun url(path: String, extra: String = ""): URL {
        val q = "room=" + enc(room) + "&role=" + enc(role) + extra
        return URL("$base$path?$q")
    }

    /** Sends one message; returns false (and logs) when the relay is unreachable. */
    fun send(body: String): Boolean {
        return try {
            val c = url("/send").openConnection() as HttpURLConnection
            c.requestMethod = "POST"
            c.connectTimeout = 4000
            c.readTimeout = 4000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val ok = c.responseCode == 200
            c.inputStream.use { it.readBytes() }
            c.disconnect()
            ok
        } catch (e: Exception) {
            Log.w(TAG, "relay send failed: ${e.message}")
            false
        }
    }

    /** Polls for the peer's messages; returns (serverMs, bodies, roundTripMs). */
    fun poll(): Triple<Long, List<String>, Long> {
        val started = System.currentTimeMillis()
        return try {
            val c = url("/poll", "&since=$since").openConnection() as HttpURLConnection
            c.connectTimeout = 4000
            c.readTimeout = 4000
            val text = BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8)).use { it.readText() }
            c.disconnect()
            val rtt = System.currentTimeMillis() - started
            val json = JSONObject(text)
            lastServerMs = json.optLong("server_ms", 0)
            since = json.optLong("seq", since)
            val arr: JSONArray = json.optJSONArray("messages") ?: JSONArray()
            val bodies = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) {
                val row = arr.getJSONArray(i)
                bodies.add(row.getString(2))
            }
            Triple(lastServerMs, bodies, rtt)
        } catch (e: Exception) {
            Log.w(TAG, "relay poll failed: ${e.message}")
            Triple(0, emptyList(), 0)
        }
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    companion object {
        const val TAG = "TabulaIntercomSpike"
    }
}
