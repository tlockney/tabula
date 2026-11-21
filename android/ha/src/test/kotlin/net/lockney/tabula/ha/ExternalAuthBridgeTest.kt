package net.lockney.tabula.ha

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The external-auth bridge contract (packet P2.2, ADR 0001 item 3): callback
 * names fixed by the frontend, only the HA origin is answered, only
 * access_token and expires_in cross the bridge, revocation is acknowledged
 * but never honored, and config/get gets a result envelope.
 */
class ExternalAuthBridgeTest {
    private var forced = 0
    private var plain = 0

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
        val js = bridge().getExternalAuth(
            """{"callback":"externalAuthSetToken"}""",
            fromHaOrigin = true,
        )
        assertEquals("""externalAuthSetToken(true, {"access_token":"cached-token","expires_in":1700})""", js)
        assertEquals(1, plain)
        assertEquals(0, forced)
    }

    @Test
    fun `force is forwarded so a stale token is refreshed`() = runTest {
        val js = bridge().getExternalAuth(
            """{"callback":"externalAuthSetToken","force":true}""",
            fromHaOrigin = true,
        )
        assertTrue("forced-token" in js!!)
        assertEquals(1, forced)
    }

    @Test
    fun `a foreign origin gets no answer at all`() = runTest {
        val b = bridge()
        assertNull(b.getExternalAuth("""{"callback":"externalAuthSetToken"}""", fromHaOrigin = false))
        assertNull(b.revokeExternalAuth("""{"callback":"externalAuthRevokeToken"}""", fromHaOrigin = false))
        assertNull(b.externalBus("""{"id":1,"type":"config/get"}""", fromHaOrigin = false))
        // and no token was minted for the foreign page
        assertEquals(0, plain + forced)
    }

    @Test
    fun `a callback name other than the frontend constant is refused`() = runTest {
        val b = bridge()
        // an injected page must not turn the bridge into an arbitrary JS call
        assertNull(b.getExternalAuth("""{"callback":"alert"}""", fromHaOrigin = true))
        assertNull(b.getExternalAuth("""{"callback":"stealTokens"}""", fromHaOrigin = true))
        assertNull(b.revokeExternalAuth("""{"callback":"somethingElse"}""", fromHaOrigin = true))
        assertNull(b.getExternalAuth("not json", fromHaOrigin = true))
        assertNull(b.getExternalAuth("""{}""", fromHaOrigin = true))
        assertEquals(0, plain + forced)
    }

    @Test
    fun `revokeExternalAuth is acknowledged but never honored`() {
        val js = bridge().revokeExternalAuth(
            """{"callback":"externalAuthRevokeToken"}""",
            fromHaOrigin = true,
        )
        assertEquals("externalAuthRevokeToken(true)", js)
    }

    @Test
    fun `config get is answered with a result envelope`() {
        val js = bridge().externalBus(
            """{"id":4,"type":"config/get"}""",
            fromHaOrigin = true,
        )
        assertEquals(
            """externalBus({"id":4,"type":"result","success":true,"result":{"hasSettingsScreen":false,"appVersion":"test"}})""",
            js,
        )
    }

    @Test
    fun `bus messages tabula has no answer for are ignored`() {
        val b = bridge()
        assertNull(b.externalBus("""{"type":"theme-update"}""", fromHaOrigin = true))
        assertNull(b.externalBus("""{"type":"connection-status","payload":{"event":"connected"}}""", fromHaOrigin = true))
        assertNull(b.externalBus("""{"id":9,"type":"haptic"}""", fromHaOrigin = true))
        assertNull(b.externalBus("not json", fromHaOrigin = true))
    }

    @Test
    fun `the reply only ever names the fixed constants`() = runTest {
        val b = bridge()
        assertTrue(b.getExternalAuth("""{"callback":"externalAuthSetToken"}""", true)!!
            .startsWith(ExternalAuthBridge.CALLBACK_SET_TOKEN))
        assertTrue(b.revokeExternalAuth("""{"callback":"externalAuthRevokeToken"}""", true)!!
            .startsWith(ExternalAuthBridge.CALLBACK_REVOKE_TOKEN))
    }
}
