package net.lockney.tabula

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.lockney.tabula.core.Constants
import net.lockney.tabula.core.log.LogBuffer
import net.lockney.tabula.core.log.LogStreamer
import net.lockney.tabula.core.tlp.LogFrame
import net.lockney.tabula.core.tlp.LogLevel
import net.lockney.tabula.core.tlp.LogLine
import net.lockney.tabula.ha.CommandHandler

/**
 * The device's log subscription (P5.4, PROTOCOL.md 4.5, 4.8): the `log_subscription`
 * command's handler and the frames it produces.
 *
 * The rules live in [LogStreamer] (interval, frame cap, level filter, TTL); this
 * class only gives them a clock to wait on and a channel for the session to
 * drain. Its ticker runs while a subscription is on and stops by itself when the
 * subscription does - by the command, by the TTL, or by the next connection
 * (`TabulaCommands.onNewConnection`), so a reconnect never resumes a stream the
 * old socket had asked for.
 */
class LogStream(
    buffer: LogBuffer = TabulaLog.buffer,
    clockMs: () -> Long = { System.currentTimeMillis() },
) : LogControl {
    /** Owned by the stream, so [close] ends the ticker with the session. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val streamer = LogStreamer(buffer, clockMs)
    private val channel = Channel<(Int) -> LogFrame>(Channel.UNLIMITED)
    private var ticker: Job? = null

    /** Frames for the session to send, each built for the id it goes out with. */
    val frames: ReceiveChannel<(Int) -> LogFrame> = channel

    /** The session ended: the ticker stops and nothing more is queued. */
    fun close() {
        streamer.disable()
        scope.cancel()
    }

    override fun subscribe(enabled: Boolean, level: LogLevel?): CommandHandler.Reply {
        if (!enabled) {
            streamer.disable()
            return CommandHandler.Reply.Ok()
        }
        streamer.enable(level)
        start()
        return CommandHandler.Reply.Ok()
    }

    private fun start() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                delay(Constants.LOG_MIN_INTERVAL_MS)
                streamer.takeBatch()?.let { batch ->
                    val lines = batch.map {
                        LogLine(
                            ts = it.tsIso,
                            level = it.level,
                            tag = it.tag,
                            message = it.message,
                        )
                    }
                    channel.trySend { id -> LogFrame(id = id, lines = lines) }
                }
                // The TTL and the disable command both end here: one more tick
                // to flush nothing, then the loop returns.
                if (!streamer.isEnabled()) break
            }
        }
    }
}

/** The `log_subscription` command, as [TabulaCommands] sees it. */
interface LogControl {
    fun subscribe(enabled: Boolean, level: LogLevel?): CommandHandler.Reply
}
