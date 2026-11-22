package net.lockney.tabula.ha

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.lockney.tabula.core.tlp.CommandEvent
import net.lockney.tabula.core.tlp.ErrorCode
import okhttp3.OkHttpClient

/**
 * The screenshot flow (P3.13, PROTOCOL 5) over real sockets against the
 * Python test server: the device captures, uploads naming the still-pending
 * command, and only then ACKs. Skips like [PythonServerTest].
 */
class PythonServerScreenshotTest {
    private val client = OkHttpClient()

    @Test
    fun `uploads the screenshot before answering its command`() = withPythonServer { url ->
        runBlocking {
            withTimeout(60_000) {
                val http = OkHttpExchange(client)
                val deviceId = "0f0e0d0c-0000-4000-8000-000000000313"
                val refresh = http.pair(url, deviceId)
                val session = register(url, deviceId, refresh, http, client, listOf("screenshot")) { tokens ->
                    UploadingCommands(FakeCommands(), http, { url }, { tokens.access(url) { refresh } })
                }

                val shotFrame = http.command(url, deviceId, "screenshot", """{"quality":80}""")
                assertEquals(true, shotFrame.ok)

                // the server saw the upload land while the command was still open
                val screenshot = http.deviceReport(url, deviceId)["screenshot"]!!.jsonObject
                assertEquals(true, screenshot["uploaded"]!!.jsonPrimitive.content.toBoolean())
                assertEquals(shotFrame.commandId, screenshot["command_id"]!!.jsonPrimitive.content)
                assertEquals(
                    true,
                    screenshot["before_result"]!!.jsonPrimitive.content.toBoolean(),
                    "the upload must precede the command's result (PROTOCOL 5)",
                )

                assertEquals("[]", http.reportedProblems(url))
                session.job.cancelAndJoin()
            }
        }
    }

    /**
     * [FakeCommands], plus the screenshot upload over the real HTTP client:
     * the JPEG names the still-pending command and precedes the ACK, so the
     * server's `before_result` marker proves the ordering.
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
