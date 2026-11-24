package net.lockney.tabula

import android.util.Log
import android.webkit.ConsoleMessage
import net.lockney.tabula.core.log.LogBuffer
import net.lockney.tabula.core.tlp.LogLevel

/**
 * The app's one logging sink (P5.4, F-MGT-06).
 *
 * Every line the app and its modules write goes to two places: `android.util.Log`,
 * so a developer with a cable still sees it, and the device's bounded [buffer],
 * which is what the log subscription streams and what the Tabularium shows. The
 * sweep is the point: `android.util.Log` is called here and nowhere else in the
 * app, so the buffer cannot miss a line an engineer would have seen in logcat.
 *
 * `:core` keeps no logging: the ring and its rules live there, the platform sink
 * lives here.
 *
 * The platform call is best-effort. A JVM unit test runs against a stubbed
 * `android.util.Log` that throws on every call, and a log line is never worth
 * failing that test over; on a device the call does not throw.
 */
object TabulaLog {
    /** The device's ring, shared by the app's logging and the dashboard console. */
    val buffer = LogBuffer()

    fun d(tag: String, message: String) = write(LogLevel.DEBUG, tag, message)

    fun i(tag: String, message: String) = write(LogLevel.INFO, tag, message)

    fun w(tag: String, message: String) = write(LogLevel.WARNING, tag, message)

    fun e(tag: String, message: String) = write(LogLevel.ERROR, tag, message)

    /** [d]/[i]/[w]/[e] with a cause appended: a failure reads as one line in the tail. */
    fun w(tag: String, message: String, error: Throwable?) = w(tag, withCause(message, error))

    fun e(tag: String, message: String, error: Throwable?) = e(tag, withCause(message, error))

    private fun withCause(message: String, error: Throwable?): String =
        if (error == null) message else "$message: ${error::class.simpleName}: ${error.message}"

    /**
     * The dashboard WebView's console message, tagged `console` so the panel can
     * tell it apart from the app's own lines. The level maps from Chromium's:
     * `log`/`debug`/`verbose` are debug, `warn` is a warning, `error` is an
     * error, and `tip` (Chromium's own hint) is informational. The source line is
     * appended because it is the half of a console message that locates a bug;
     * the packet's URL rule leaves redaction to the ring, which strips the
     * query string a page URL may carry.
     */
    fun console(level: ConsoleMessage.MessageLevel, message: String, source: String?, line: Int) {
        val where = source?.takeIf { it.isNotEmpty() }?.let { "$it:$line" }
        write(consoleLevel(level), CONSOLE_TAG, if (where == null) message else "$message ($where)")
    }

    private fun write(level: LogLevel, tag: String, message: String) {
        buffer.add(level, tag, message)
        runCatching { Log.println(priority(level), tag.take(LogBuffer.TAG_MAX), message) }
    }

    private fun consoleLevel(level: ConsoleMessage.MessageLevel): LogLevel = when (level) {
        ConsoleMessage.MessageLevel.ERROR -> LogLevel.ERROR
        ConsoleMessage.MessageLevel.WARNING -> LogLevel.WARNING
        ConsoleMessage.MessageLevel.LOG,
        ConsoleMessage.MessageLevel.DEBUG,
        ConsoleMessage.MessageLevel.TIP,
        -> LogLevel.DEBUG
    }

    private fun priority(level: LogLevel): Int = when (level) {
        LogLevel.DEBUG -> Log.DEBUG
        LogLevel.INFO -> Log.INFO
        LogLevel.WARNING -> Log.WARN
        LogLevel.ERROR -> Log.ERROR
    }

    /** The tag console lines carry (the panel styles it differently). */
    const val CONSOLE_TAG = "console"
}
