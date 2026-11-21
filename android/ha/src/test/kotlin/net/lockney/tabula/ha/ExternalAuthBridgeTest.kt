package net.lockney.tabula.ha

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The external-auth bridge contract (packet P2.2, ADR 0001 item 3): callback
 * names fixed by the frontend, only the HA origin is answered, only
 * access_token and expires_in cross the bridge, revocation is acknowledged
 * but never honored, and config/get gets a result envelope.
 *
 * The origin is checked twice: once when the call arrives, and once when the
 * answer is delivered — a main-frame navigation during the (suspending) token
 * fetch must not receive the answer, because the page that took over could
 * define the callback itself.
 */
class ExternalAuthBridgeTest {
    private var forced = 0
    private var plain = 0

    private val origin = URI("http://ha:8123")
    private val onOrigin = "http://ha:8123/lovelace/0"
    private val offOrigin = "http://evil.example/x"

    private fun bridge() = ExternalAuthBridge(
        access = { force ->
            if (force) {
                forced++
                TokenRefresher.Access("forced-token", 100)
            } else {
                plain++
                TokenRefresher.Access("cached-token", 1700)
            }
        },
        externalConfig = buildJsonObject {
            put("hasSettingsScreen", false)
            put("appVersion", "test")
        },
    )

    @Test
    fun `answers getExternalAuth with token and remaining life`() = runTest {
        val answer = bridge().getExternalAuth(
            """{"callback":"externalAuthSetToken"}""",
            origin,
            onOrigin,
        )
        assertEquals("""externalAuthSetToken(true, {"access_token":"cached-token","expires_in":1700})""", answer!!.script)
        assertEquals(1, plain)
        assertEquals(0, forced)
    }

    @Test
    fun `force is forwarded so a stale token is refreshed`() = runTest {
        val answer = bridge().getExternalAuth(
            """{"callback":"externalAuthSetToken","force":true}""",
            origin,
            onOrigin,
        )
        assertTrue("forced-token" in answer!!.script)
        assertEquals(1, forced)
    }

    @Test
    fun `a foreign origin gets no answer at all`() = runTest {
        val b = bridge()
        assertNull(b.getExternalAuth("""{"callback":"externalAuthSetToken"}""", origin, offOrigin))
        assertNull(b.revokeExternalAuth("""{"callback":"externalAuthRevokeToken"}""", origin, offOrigin))
        assertNull(b.externalBus("""{"id":1,"type":"config/get"}""", origin, offOrigin))
        // and no token was minted for the foreign page
        assertEquals(0, plain + forced)
    }

    @Test
    fun `a callback name other than the frontend constant is refused`() = runTest {
        val b = bridge()
        // an injected page must not turn the bridge into an arbitrary JS call
        assertNull(b.getExternalAuth("""{"callback":"alert"}""", origin, onOrigin))
        assertNull(b.getExternalAuth("""{"callback":"stealTokens"}""", origin, onOrigin))
        assertNull(b.revokeExternalAuth("""{"callback":"somethingElse"}""", origin, onOrigin))
        assertNull(b.getExternalAuth("not json", origin, onOrigin))
        assertNull(b.getExternalAuth("{}", origin, onOrigin))
        assertEquals(0, plain + forced)
    }

    @Test
    fun `revokeExternalAuth is acknowledged but never honored`() {
        val answer = bridge().revokeExternalAuth(
            """{"callback":"externalAuthRevokeToken"}""",
            origin,
            onOrigin,
        )
        assertEquals("externalAuthRevokeToken(true)", answer!!.script)
    }

    @Test
    fun `config get is answered with a result envelope`() {
        val answer = bridge().externalBus(
            """{"id":4,"type":"config/get"}""",
            origin,
            onOrigin,
        )
        assertEquals(
            """externalBus({"id":4,"type":"result","success":true,"result":{"hasSettingsScreen":false,"appVersion":"test"}})""",
            answer!!.script,
        )
    }

    @Test
    fun `bus messages tabula has no answer for are ignored`() {
        val b = bridge()
        assertNull(b.externalBus("""{"type":"theme-update"}""", origin, onOrigin))
        assertNull(b.externalBus("""{"type":"connection-status","payload":{"event":"connected"}}""", origin, onOrigin))
        assertNull(b.externalBus("""{"id":9,"type":"haptic"}""", origin, onOrigin))
        assertNull(b.externalBus("not json", origin, onOrigin))
    }

    @Test
    fun `the reply only ever names the fixed constants`() = runTest {
        val b = bridge()
        assertTrue(b.getExternalAuth("""{"callback":"externalAuthSetToken"}""", origin, onOrigin)!!
            .script.startsWith(ExternalAuthBridge.CALLBACK_SET_TOKEN))
        assertTrue(b.revokeExternalAuth("""{"callback":"externalAuthRevokeToken"}""", origin, onOrigin)!!
            .script.startsWith(ExternalAuthBridge.CALLBACK_REVOKE_TOKEN))
    }

    @Test
    fun `a navigation during the token fetch drops the answer`() = runTest {
        // the fetch suspends the way a real network refresh does
        val fetch = CompletableDeferred<Unit>()
        val b = ExternalAuthBridge(
            access = {
                fetch.await()
                TokenRefresher.Access("cached-token", 1700)
            },
            externalConfig = buildJsonObject {
                put("hasSettingsScreen", false)
                put("appVersion", "test")
            },
        )
        val pending = async { b.getExternalAuth("""{"callback":"externalAuthSetToken"}""", origin, onOrigin) }
        yield()
        assertFalse(pending.isCompleted) // the fetch is still running

        // ...and while it runs, the main frame navigates off the origin
        fetch.complete(Unit)
        val answer = pending.await()!!
        assertTrue(answer.script.startsWith(ExternalAuthBridge.CALLBACK_SET_TOKEN))
        // the answer is not delivered to the page that took over
        assertFalse(answer.deliverTo(offOrigin))
        assertFalse(answer.deliverTo(null)) // no page at all
        assertFalse(answer.deliverTo("http://ha:8124/")) // the wrong port is a foreign origin
        // a page still on the origin receives it, wherever it went
        assertTrue(answer.deliverTo("http://ha:8123/profile"))
        assertTrue(answer.deliverTo(onOrigin))
    }

    @Test
    fun `an answer is delivered only to the origin it was built for`() = runTest {
        val b = bridge()
        val answer = b.getExternalAuth("""{"callback":"externalAuthSetToken"}""", origin, onOrigin)!!
        assertFalse(answer.deliverTo(offOrigin))
        assertTrue(answer.deliverTo("http://ha:8123/"))
    }
}
