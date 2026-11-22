package net.lockney.tabula

import java.io.IOException
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import net.lockney.tabula.ha.HttpExchange
import net.lockney.tabula.ha.PairingClient

/**
 * The pairing screen's state (issue #127). With HA unreachable the pair POST
 * fails with an IOException, and the screen used to show the confirm view
 * with an empty code: these tests pin the form-with-the-reason behaviour, and
 * that a confirm view only exists when HA has issued a code.
 */
class PairingScreenTest {
    private val haUrl = "http://homeassistant.local:8123"
    private val deviceId = "0f0e0d0c-0000-4000-8000-000000000001"

    /** 202 http_pair_response (docs/PROTOCOL.md 2.2). */
    private val pairPending = HttpExchange.Result(
        202,
        """{"status":"pending","expires_in":600,"poll_interval":1}""",
    )

    /** 200 http_claim_response. */
    private val claimPaired = HttpExchange.Result(
        200,
        """{"status":"paired","credential":{"type":"refresh_token",""" +
            """"refresh_token":"${"s".repeat(48)}","token_url":"/auth/token"},"ha_device_id":"abc123"}""",
    )

    @BeforeTest
    fun reset() {
        TabulaStatus.phase.value = Phase.UNPAIRED
        TabulaStatus.pairing.value = null
        TabulaStatus.pairingError.value = null
        TabulaStatus.pairingNotice.value = null
    }

    /**
     * A network that is down for the first [failures] pair requests (the
     * socket never opens, so the POST throws as an unreachable server does),
     * then answers and pairs on the first claim poll.
     */
    private inner class FlakyNetwork(private val failures: Int) : HttpExchange {
        var pairRequests = 0
            private set

        override suspend fun post(
            url: String,
            contentType: String,
            body: String,
            headers: Map<String, String>,
        ): HttpExchange.Result {
            if (url.endsWith("/api/tabula/pair")) {
                pairRequests += 1
                if (pairRequests <= failures) throw IOException("network is unreachable")
                return pairPending
            }
            return claimPaired
        }

        override suspend fun postBytes(
            url: String,
            contentType: String,
            body: ByteArray,
            headers: Map<String, String>,
        ): HttpExchange.Result = HttpExchange.Result(404, "")

        override suspend fun get(url: String, headers: Map<String, String>): HttpExchange.Result =
            HttpExchange.Result(404, "")
    }

    /**
     * Run one pairing attempt the way [TabulaService] does, recording the
     * screen's state after every event the client reports.
     */
    private suspend fun attempt(network: HttpExchange): List<PairingScreenState> {
        val seen = mutableListOf<PairingScreenState>()
        PairingScreen.attemptStarted()
        seen.add(PairingScreen.state())
        PairingClient(network).pair(
            haUrl,
            deviceId,
            "Desk",
            "mock",
            onStatus = { status ->
                PairingScreen.codeIssued(status.code, status.deviceId.take(8))
                seen.add(PairingScreen.state())
            },
            onUnreachable = {
                PairingScreen.unreachableNow(haUrl)
                seen.add(PairingScreen.state())
            },
        )
        return seen
    }

    @Test
    fun `a pair POST that fails with an IOException keeps the form up and says why`() = runTest {
        val network = FlakyNetwork(failures = 2)
        val states = attempt(network)

        assertEquals(3, network.pairRequests, "the failed pair requests must have been retried")
        // the form first, then the reason after each failed request, then the code
        assertEquals(PairingScreenState.Form(), states.first())
        assertEquals(
            PairingScreenState.Form(PairingScreen.unreachable(haUrl)),
            states[1],
        )
        assertEquals(
            PairingScreenState.Form(PairingScreen.unreachable(haUrl)),
            states[2],
        )
    }

    @Test
    fun `the confirm screen appears only once HA has issued a code`() = runTest {
        val states = attempt(FlakyNetwork(failures = 1))

        val firstConfirm = states.indexOfFirst { it is PairingScreenState.Confirm }
        assertTrue(firstConfirm > 0, "pairing must reach the confirm screen")
        assertTrue(states.take(firstConfirm).all { it is PairingScreenState.Form })
        val confirm = assertIs<PairingScreenState.Confirm>(states[firstConfirm])
        assertTrue(Regex("^[0-9]{6}$").matches(confirm.code), confirm.code)
        assertEquals(deviceId.take(8), confirm.deviceIdShort)
    }

    @Test
    fun `PAIRING without a code draws the form, not an empty confirm`() {
        TabulaStatus.phase.value = Phase.PAIRING
        TabulaStatus.pairingNotice.value = PairingScreen.unreachable(haUrl)

        val state = PairingScreen.state()

        assertIs<PairingScreenState.Form>(state)
        assertEquals(PairingScreen.unreachable(haUrl), state.message)
    }

    @Test
    fun `PAIRING with a blank code draws the form too`() {
        TabulaStatus.phase.value = Phase.PAIRING
        TabulaStatus.pairing.value = TabulaStatus.PairingView("", deviceId.take(8))

        assertIs<PairingScreenState.Form>(PairingScreen.state())
    }

    @Test
    fun `a code draws the confirm screen`() {
        TabulaStatus.phase.value = Phase.PAIRING
        TabulaStatus.pairing.value = TabulaStatus.PairingView("123456", deviceId.take(8))
        TabulaStatus.pairingNotice.value = "a stale notice is not drawn over a code"

        assertEquals(
            PairingScreenState.Confirm("123456", deviceId.take(8)),
            PairingScreen.state(),
        )
    }

    @Test
    fun `a terminal failure replaces the notice on the form`() {
        TabulaStatus.pairingNotice.value = PairingScreen.unreachable(haUrl)
        PairingScreen.failed("Pairing failed: bad_nonce. Pair again.")

        assertEquals(Phase.UNPAIRED, TabulaStatus.phase.value)
        assertEquals(null, TabulaStatus.pairingNotice.value)
        assertEquals(
            PairingScreenState.Form("Pairing failed: bad_nonce. Pair again."),
            PairingScreen.state(),
        )
    }
}
