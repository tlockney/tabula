package net.lockney.tabula.ha

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Connection.pending is touched from three threads with no common lock: the
 * send path under sendMutex, the read loop (completePending), and the OkHttp
 * listener threads (failPending, Transport.kt). It is a ConcurrentHashMap
 * with remove-then-complete operations; as a plain HashMap this same storm
 * threw ConcurrentModificationException inside the OkHttp callback — an
 * uncaught exception that killed the process (PR #47 second review, blocker 2).
 *
 * Real threads and real timeouts, not virtual time: the point is the race.
 */
class PendingConcurrencyTest {
    @Test
    fun `requests survive a concurrent completePending and failPending storm`() = runBlocking {
        val conn = TlpClient.Connection()
        conn.transport = object : WsTransport {
            override fun send(text: String) = true

            override fun close(code: Int, reason: String) {}
        }
        val unexpected = CopyOnWriteArrayList<Throwable>()

        // 24 senders issuing requests, each ending in complete / fail / its
        // own timeout — whichever gets there first
        val requesters = (1..24).map { n ->
            launch(Dispatchers.Default) {
                repeat(20) {
                    try {
                        val id = conn.allocId()
                        conn.request(id, { i -> "frame-$i" }, 250)
                    } catch (e: TimeoutCancellationException) {
                        // its reply and its failure both raced past the timeout
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: IOException) {
                        // failPending got this request in flight: the expected outcome
                    } catch (e: Throwable) {
                        unexpected.add(e) // e.g. ConcurrentModificationException
                    }
                }
            }
        }

        // the storm: a listener thread failing everything in flight, and a
        // read loop completing whatever ids it happens to see
        val failer = launch(Dispatchers.Default) {
            repeat(2_000) { conn.failPending(IOException("storm")) }
        }
        val completer = launch(Dispatchers.Default) {
            repeat(2_000) {
                conn.completePending(Random.nextInt(1, 481), buildJsonObject { put("ok", 1) })
            }
        }

        failer.join()
        completer.join()
        requesters.forEach { it.join() }

        assertTrue(unexpected.isEmpty(), "unexpected exceptions: $unexpected")

        // the map is consistent afterwards: a fresh request completes normally
        val id = conn.allocId()
        val answer = launch(Dispatchers.Default) {
            delay(50)
            conn.completePending(id, buildJsonObject { put("alive", 1) })
        }
        assertEquals(1, conn.request(id, { "ping" }, 2_000)["alive"]!!.jsonPrimitive.int)
        answer.join()
    }
}
