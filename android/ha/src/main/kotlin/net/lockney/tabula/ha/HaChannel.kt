package net.lockney.tabula.ha

import java.io.IOException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * HA core WebSocket commands on the device's own connection, next to TLP
 * (ADR 0017: one socket). The TLP client publishes one of these per
 * registered connection and withdraws it when the connection ends; a
 * consumer that holds one past that point gets [IOException]s, never a
 * hang.
 *
 * Messages are plain HA commands without an `id`: the channel allocates it
 * on the shared counter, so TLP frames and these commands keep one
 * increasing sequence (PROTOCOL 4).
 */
interface HaChannel {
    /**
     * Send [message] and return the `result` member of HA's success reply.
     * Throws [HaCommandError] for an error reply, [IOException] when the
     * connection is gone, and a timeout when no reply arrives in time.
     */
    suspend fun call(message: JsonObject): JsonElement?

    /**
     * Send a subscribe-style [message]; [onEvent] receives each event's
     * `event` member, in arrival order, on the connection's read loop (keep it
     * short). The route is in place before the message is sent, so an event
     * that follows the success reply in the same read is never lost.
     */
    suspend fun subscribe(message: JsonObject, onEvent: (JsonObject) -> Unit): HaSubscription
}

/** An open HA subscription on one connection. */
interface HaSubscription {
    val id: Int

    /**
     * Stop routing events and send `unsubscribe_events`. Best effort: on a
     * connection that already ended there is nothing left to unsubscribe.
     */
    suspend fun cancel()
}

/** HA answered a command with `success: false`. */
class HaCommandError(val code: String, detail: String) : IOException("$code: $detail")
