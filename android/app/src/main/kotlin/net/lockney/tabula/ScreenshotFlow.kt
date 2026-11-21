package net.lockney.tabula

import net.lockney.tabula.core.tlp.ErrorCode
import net.lockney.tabula.ha.CommandHandler
import net.lockney.tabula.ha.HttpExchange

/**
 * The screenshot capture flow (packet P3.13, PROTOCOL.md 5): capture the
 * dashboard's WebView, upload the JPEG to HA naming the command it belongs
 * to, and only then let the command's result go out - HA accepts the upload
 * only while that command is still pending.
 *
 * With the screen off nothing is captured and nothing is uploaded, and the
 * answer is still Ok: HA then shows its placeholder instead of a black frame
 * (F-MGT-07). No dashboard showing, or an upload HA refuses or that fails,
 * fails the command (PROTOCOL.md 6, the generic `failed` code).
 *
 * Pure Kotlin over interfaces (the bus, the HTTP exchange, the token and
 * screen providers), so the flow runs on the JVM against the Python test
 * server; the service supplies the Android pieces.
 */
class ScreenshotFlow(
    private val bus: DashboardBus,
    private val http: HttpExchange,
    private val accessToken: suspend () -> String,
    private val haUrl: () -> String?,
    private val screenOn: () -> Boolean,
) : ScreenshotControl {

    override suspend fun capture(quality: Int?, commandId: String): CommandHandler.Reply {
        if (!screenOn()) return CommandHandler.Reply.Ok()
        val base = haUrl()
            ?: return CommandHandler.Reply.Error(ErrorCode.FAILED, "no HA URL stored; cannot screenshot")
        val jpeg = bus.capture(quality)
            ?: return CommandHandler.Reply.Error(ErrorCode.FAILED, "no dashboard is showing; cannot screenshot")
        return try {
            val result = http.postBytes(
                "$base/api/tabula/screenshot",
                "image/jpeg",
                jpeg,
                mapOf(
                    "Authorization" to "Bearer ${accessToken()}",
                    "X-Tabula-Command-Id" to commandId,
                ),
            )
            if (result.status == 200) {
                CommandHandler.Reply.Ok()
            } else {
                CommandHandler.Reply.Error(
                    ErrorCode.FAILED,
                    "screenshot upload rejected: HTTP ${result.status} ${result.body.take(200)}",
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // A closing connection cancelled the upload: not a command
            // failure. Rethrow so the command's launch ends cancelled instead
            // of writing a result to the dead socket (review must-fix).
            throw e
        } catch (e: Exception) {
            CommandHandler.Reply.Error(ErrorCode.FAILED, "screenshot upload failed: ${e.message}")
        }
    }
}
