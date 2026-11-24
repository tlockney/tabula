package net.lockney.tabula.core.intercom

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.lockney.tabula.core.Constants
import net.lockney.tabula.core.tlp.IntercomEndReason
import net.lockney.tabula.core.tlp.IntercomMode
import net.lockney.tabula.core.tlp.IntercomPeer
import net.lockney.tabula.core.tlp.IntercomSignalKind
import net.lockney.tabula.core.tlp.IntercomState

/**
 * The call machine's transitions, deadlines and guards (packet P9.3 item 9),
 * against a fake connection, a virtual clock and a fake set of locks. Nothing
 * here opens a socket, a speaker or a microphone.
 */
class CallMachineTest {
    private val clock = TestClock()
    private val scheduler = ManualScheduler(clock)
    private val peers = FakePeerFactory()
    private val locks = FakeLocks()
    private var canTalk = true

    private val machine = CallMachine(
        peers = peers,
        scheduler = scheduler,
        clockMs = { clock.now },
        canTalk = { canTalk },
        locks = locks,
        newCallId = { CALL_TEST },
        closeExecutor = DIRECT,
    )

    private fun ring(
        callId: String = CALL,
        mode: IntercomMode = IntercomMode.CALL,
        caller: String = "caller-1",
    ) = machine.ring(RingRequest(callId, caller, "Office Kiosk", mode))

    private fun events(): List<EngineEvent> = buildList {
        while (true) add(machine.events.tryReceive().getOrNull() ?: break)
    }

    // --- the states ------------------------------------------------------------

    @Test
    fun `a new engine is idle, holds nothing and starts nothing by itself`() {
        // Guard 1: no call starts because a device is online
        assertEquals(IntercomState.IDLE, machine.state.value)
        assertNull(machine.info.value)
        assertEquals(0, locks.acquired)
        scheduler.advance(600_000)
        assertEquals(IntercomState.IDLE, machine.state.value)
        assertEquals(0, locks.acquired)
        assertTrue(events().isEmpty())
        assertTrue(peers.created.isEmpty())
    }

    @Test
    fun `a ring rings, takes the locks and opens no connection yet`() {
        assertTrue(ring())
        assertEquals(IntercomState.RINGING, machine.state.value)
        assertEquals(CallDirection.INCOMING, machine.info.value?.direction)
        assertEquals("Office Kiosk", machine.info.value?.peerName)
        assertTrue(machine.info.value?.usesMicrophone == true)
        assertEquals(1, locks.acquired)
        assertTrue(peers.created.isEmpty())
        assertTrue(events().isEmpty())
    }

    @Test
    fun `answering opens a two-way connection and reports the answer`() {
        ring()
        assertTrue(machine.answer())
        assertEquals(IntercomState.IN_CALL, machine.state.value)
        assertEquals(PeerDirection.SEND_RECEIVE, peers.last?.direction)
        assertEquals(listOf<EngineEvent>(EngineEvent.Answered(CALL)), events())

        machine.signal(CALL, IntercomSignalKind.OFFER, "v=0 offer")
        assertEquals("v=0 offer", peers.last?.answeredOffer)
        peers.last!!.local(IntercomSignalKind.ANSWER, "v=0 answer")
        assertEquals(
            listOf<EngineEvent>(EngineEvent.Signal(CALL, IntercomSignalKind.ANSWER, "v=0 answer")),
            events(),
        )
    }

    @Test
    fun `an offer that arrives before the answer waits for it`() {
        ring()
        assertTrue(machine.signal(CALL, IntercomSignalKind.OFFER, "v=0 early"))
        assertTrue(peers.created.isEmpty())
        machine.answer()
        assertEquals("v=0 early", peers.last?.answeredOffer)
    }

    @Test
    fun `candidates that arrive while ringing reach the peer on the answer`() {
        // The caller offers at start and trickles candidates at once, long
        // before anyone answers (review of #171, finding 1)
        ring()
        assertTrue(machine.signal(CALL, IntercomSignalKind.CANDIDATE, "candidate:1"))
        assertTrue(machine.signal(CALL, IntercomSignalKind.CANDIDATE, "candidate:2"))
        assertTrue(peers.created.isEmpty())
        machine.answer()
        assertEquals(listOf("candidate:1", "candidate:2"), peers.last?.candidates?.toList())
        // and a candidate after the peer exists goes straight to it
        assertTrue(machine.signal(CALL, IntercomSignalKind.CANDIDATE, "candidate:3"))
        assertEquals(listOf("candidate:1", "candidate:2", "candidate:3"), peers.last?.candidates?.toList())
    }

    @Test
    fun `an answered call whose offer never arrives still times out`() {
        // The connect deadline runs from this device's own answer too
        // (review of #171, finding 3)
        ring()
        machine.answer()
        scheduler.advance(Constants.INTERCOM_CONNECT_TIMEOUT_S * 1000L - 1)
        assertEquals(IntercomState.IN_CALL, machine.state.value)
        scheduler.advance(1)
        assertEquals(IntercomState.IDLE, machine.state.value)
        assertEquals(
            listOf<EngineEvent>(EngineEvent.Answered(CALL), EngineEvent.Ended(CALL, IntercomEndReason.TIMEOUT)),
            events(),
        )
    }

    @Test
    fun `declining ends the ring and reports only a decline`() {
        ring()
        assertTrue(machine.decline())
        assertEquals(IntercomState.IDLE, machine.state.value)
        assertNull(machine.info.value)
        assertEquals(1, locks.released)
        // HA ends both sides on a decline (P9.1), so no ended event is duplicated
        assertEquals(listOf<EngineEvent>(EngineEvent.Declined(CALL)), events())
        assertFalse(machine.answer())
    }

    @Test
    fun `a broadcast rings into a playing call that never captures`() {
        assertTrue(ring(mode = IntercomMode.BROADCAST))
        assertEquals(IntercomState.IN_CALL, machine.state.value)
        assertFalse(machine.info.value!!.usesMicrophone)
        assertEquals(PeerDirection.RECEIVE_ONLY, peers.last?.direction)
        // nobody answers a broadcast
        assertFalse(machine.answer())
    }

    @Test
    fun `a broadcast this device sends is broadcasting and sends only`() {
        val outcome = machine.start(StartRequest(IntercomMode.BROADCAST, peerName = "Everyone"))
        assertEquals(StartOutcome.Started(CALL_TEST), outcome)
        assertEquals(IntercomState.BROADCASTING, machine.state.value)
        assertEquals(PeerDirection.SEND_ONLY, peers.last?.direction)
        assertTrue(peers.last!!.offered)
        // the start request goes out before the offer, so HA has the call id
        // before the first signal named after it (review of #171, finding 2)
        assertEquals(
            listOf(
                EngineEvent.StartRequested(CALL_TEST, IntercomMode.BROADCAST, null, null),
                EngineEvent.Signal(CALL_TEST, IntercomSignalKind.OFFER, "v=0 offer"),
            ),
            events(),
        )
    }

    @Test
    fun `a call this device starts offers and reaches in call on the answer`() {
        val outcome =
            machine.start(StartRequest(IntercomMode.CALL, targetDeviceId = "ha-device-2", peerName = "Desk"))
        assertEquals(StartOutcome.Started(CALL_TEST), outcome)
        assertEquals(IntercomState.IN_CALL, machine.state.value)
        assertEquals(CallDirection.OUTGOING, machine.info.value?.direction)
        assertTrue(peers.last!!.offered)
        assertEquals(PeerDirection.SEND_RECEIVE, peers.last?.direction)

        assertTrue(machine.signal(CALL_TEST, IntercomSignalKind.ANSWER, "v=0 answer"))
        assertEquals("v=0 answer", peers.last?.answerReceived)
        peers.last!!.connect()
        assertTrue(machine.signal(CALL_TEST, IntercomSignalKind.CANDIDATE, "candidate:1"))
        assertEquals(listOf("candidate:1"), peers.last?.candidates?.toList())
    }

    @Test
    fun `a call this device starts that is never answered times out at the ring limit`() {
        // HA can refuse a start without telling the device, so the caller ends
        // itself rather than holding the microphone until the hard limit
        // (review of #171, finding 1)
        assertTrue(machine.start(StartRequest(IntercomMode.CALL, targetDeviceId = "d2")) is StartOutcome.Started)
        scheduler.advance(Constants.INTERCOM_RING_LIMIT_S * 1000L - 1)
        assertEquals(IntercomState.IN_CALL, machine.state.value)
        scheduler.advance(1)
        assertEquals(IntercomState.IDLE, machine.state.value)
        assertEquals(
            listOf<EngineEvent>(EngineEvent.Ended(CALL_TEST, IntercomEndReason.TIMEOUT)),
            events().filterIsInstance<EngineEvent.Ended>(),
        )
        assertEquals(1, locks.released)
        assertTrue(peers.last!!.closed)
    }

    @Test
    fun `an answer cancels the caller's no-answer deadline`() {
        machine.start(StartRequest(IntercomMode.CALL, targetDeviceId = "d2"))
        machine.signal(CALL_TEST, IntercomSignalKind.ANSWER, "v=0 answer")
        peers.last!!.connect()
        scheduler.advance(Constants.INTERCOM_RING_LIMIT_S * 1000L + 1)
        assertEquals(IntercomState.IN_CALL, machine.state.value)
    }

    @Test
    fun `a signal of a kind this side does not take is acknowledged, not refused`() {
        // a caller ignores an offer, a callee ignores an answer: both are this
        // call's signals (review of #171, finding 4)
        machine.start(StartRequest(IntercomMode.CALL, targetDeviceId = "d2"))
        assertTrue(machine.signal(CALL_TEST, IntercomSignalKind.OFFER, "v=0 offer"))
        machine.end(CALL_TEST, IntercomEndReason.HANGUP)

        ring()
        assertTrue(machine.signal(CALL, IntercomSignalKind.ANSWER, "v=0 answer"))
        assertEquals(IntercomState.RINGING, machine.state.value)
    }

    @Test
    fun `a caller is in call from its own start`() {
        // Decision 10: the caller's connection and capture exist before the
        // callee answers, so `ringing` - a call offered to this device - is not
        // its state (review of #171, finding 4)
        machine.start(StartRequest(IntercomMode.CALL, targetDeviceId = "d2"))
        assertEquals(IntercomState.IN_CALL, machine.state.value)
        assertTrue(machine.info.value!!.usesMicrophone)
    }

    @Test
    fun `a duplicate answer is ignored`() {
        machine.start(StartRequest(IntercomMode.CALL, targetDeviceId = "d2"))
        assertTrue(machine.signal(CALL_TEST, IntercomSignalKind.ANSWER, "v=0 answer"))
        assertTrue(machine.signal(CALL_TEST, IntercomSignalKind.ANSWER, "v=0 again"))
        assertEquals(1, peers.last?.answerReceipts)
        assertEquals("v=0 answer", peers.last?.answerReceived)
    }

    @Test
    fun `a call that loses its connection ends with disconnect`() {
        // A lost link must not keep the microphone open and both locks held
        // (review of #171, finding 6)
        ring()
        machine.answer()
        machine.signal(CALL, IntercomSignalKind.OFFER, "v=0 offer")
        peers.last!!.connect()
        peers.last!!.disconnect()
        assertEquals(IntercomState.IDLE, machine.state.value)
        assertEquals(
            listOf<EngineEvent>(EngineEvent.Ended(CALL, IntercomEndReason.DISCONNECT)),
            events().filterIsInstance<EngineEvent.Ended>(),
        )
        assertEquals(1, locks.released)
    }

    @Test
    fun `an over-long signal ends the call, not the session`() {
        // A frame over INTERCOM_SIGNAL_MAX_BYTES would throw in the frame
        // builder and take the TLP session down (review of #171, finding 3)
        ring()
        machine.answer()
        machine.signal(CALL, IntercomSignalKind.OFFER, "v=0 offer")
        events()
        peers.last!!.local(IntercomSignalKind.CANDIDATE, "x".repeat(Constants.INTERCOM_SIGNAL_MAX_BYTES + 1))
        assertEquals(IntercomState.IDLE, machine.state.value)
        val emitted = events()
        assertTrue(emitted.none { it is EngineEvent.Signal })
        assertEquals(
            listOf<EngineEvent>(EngineEvent.Ended(CALL, IntercomEndReason.ERROR)),
            emitted.filterIsInstance<EngineEvent.Ended>(),
        )
        assertEquals(1, locks.released)
    }

    @Test
    fun `stale deadlines from an ended call cannot touch the next one`() {
        // A deadline that still runs after its call ended must not end the next
        // one, which is why every callback compares the call id
        val clock = TestClock()
        val scheduler = ManualScheduler(clock).apply { ignoreCancellation = true }
        val peers = FakePeerFactory()
        val locks = FakeLocks()
        var next = 0
        val machine = CallMachine(
            peers = peers,
            scheduler = scheduler,
            clockMs = { clock.now },
            canTalk = { true },
            locks = locks,
            newCallId = { "call-${++next}" },
            closeExecutor = DIRECT,
        )

        // The first call is answered, so its connect deadline is armed, then it
        // ends with that deadline still scheduled
        machine.start(StartRequest(IntercomMode.CALL, targetDeviceId = "d1"))
        assertTrue(machine.signal("call-1", IntercomSignalKind.ANSWER, "v=0 answer"))
        machine.end("call-1", IntercomEndReason.HANGUP)
        assertEquals(1, locks.released)

        // The second call has a different id and its own deadlines
        machine.start(StartRequest(IntercomMode.CALL, targetDeviceId = "d2"))
        assertEquals("call-2", machine.info.value?.callId)

        // The first call's connect deadline (10 s) fires here and must be ignored
        scheduler.advance(Constants.INTERCOM_CONNECT_TIMEOUT_S * 1000L)
        assertEquals(IntercomState.IN_CALL, machine.state.value)
        assertEquals("call-2", machine.info.value?.callId)
        // one acquire per call, one release for the call that ended
        assertEquals(2, locks.acquired)
        assertEquals(1, locks.released)

        // The second call's own no-answer deadline (45 s) still works
        scheduler.advance(Constants.INTERCOM_RING_LIMIT_S * 1000L)
        assertEquals(IntercomState.IDLE, machine.state.value)
        assertEquals(2, locks.released)
    }

    @Test
    fun `a second call is refused while one is up`() {
        ring()
        assertFalse(ring(callId = "call-2"))
        assertEquals(IntercomState.RINGING, machine.state.value)
        assertEquals(CALL, machine.info.value?.callId)
        assertEquals(StartOutcome.Busy, machine.start(StartRequest(IntercomMode.CALL, targetDeviceId = "d2")))
    }

    // --- the guards ------------------------------------------------------------

    @Test
    fun `the ring limit ends an unanswered call as a timeout`() {
        ring()
        scheduler.advance(Constants.INTERCOM_RING_LIMIT_S * 1000L - 1)
        assertEquals(IntercomState.RINGING, machine.state.value)
        scheduler.advance(1)
        assertEquals(IntercomState.IDLE, machine.state.value)
        assertEquals(listOf<EngineEvent>(EngineEvent.Ended(CALL, IntercomEndReason.TIMEOUT)), events())
        assertEquals(1, locks.released)
    }

    @Test
    fun `an answered call that never connects times out`() {
        ring()
        machine.answer()
        machine.signal(CALL, IntercomSignalKind.OFFER, "v=0 offer")
        scheduler.advance(Constants.INTERCOM_CONNECT_TIMEOUT_S * 1000L - 1)
        assertEquals(IntercomState.IN_CALL, machine.state.value)
        scheduler.advance(1)
        assertEquals(IntercomState.IDLE, machine.state.value)
        assertEquals(
            listOf<EngineEvent>(EngineEvent.Answered(CALL), EngineEvent.Ended(CALL, IntercomEndReason.TIMEOUT)),
            events(),
        )
    }

    @Test
    fun `a connected call does not hit the connect timeout`() {
        ring()
        machine.answer()
        machine.signal(CALL, IntercomSignalKind.OFFER, "v=0 offer")
        peers.last!!.connect()
        scheduler.advance(Constants.INTERCOM_CONNECT_TIMEOUT_S * 1000L + 1)
        assertEquals(IntercomState.IN_CALL, machine.state.value)
    }

    @Test
    fun `the hard limit ends a connected call by itself`() {
        // Guard 2: the limit runs from the moment the call is up (ADR 0002)
        ring()
        machine.answer()
        machine.signal(CALL, IntercomSignalKind.OFFER, "v=0 offer")
        peers.last!!.connect()
        scheduler.advance(Constants.INTERCOM_CALL_LIMIT_S * 1000L - 1)
        assertEquals(IntercomState.IN_CALL, machine.state.value)
        scheduler.advance(1)
        assertEquals(IntercomState.IDLE, machine.state.value)
        val ended = events().filterIsInstance<EngineEvent.Ended>()
        assertEquals(listOf(EngineEvent.Ended(CALL, IntercomEndReason.LIMIT)), ended)
        assertEquals(1, locks.released)
        assertTrue(peers.last!!.closed)
    }

    @Test
    fun `the hard limit also ends a broadcast`() {
        ring(mode = IntercomMode.BROADCAST)
        scheduler.advance(Constants.INTERCOM_CALL_LIMIT_S * 1000L)
        assertEquals(IntercomState.IDLE, machine.state.value)
        assertEquals(listOf<EngineEvent>(EngineEvent.Ended(CALL, IntercomEndReason.LIMIT)), events())
    }

    @Test
    fun `an end from HA stops the call in every state`() {
        // Guard 3: ringing, connecting, in call, broadcasting, a playing broadcast
        val cases = listOf<Pair<String, (CallMachine, FakePeerFactory) -> Unit>>(
            "ringing" to { m, _ -> m.ring(RingRequest(CALL, "caller-1", "Office", IntercomMode.CALL)) },
            "connecting" to { m, _ ->
                m.ring(RingRequest(CALL, "caller-1", "Office", IntercomMode.CALL))
                m.answer()
            },
            "in call" to { m, p ->
                m.ring(RingRequest(CALL, "caller-1", "Office", IntercomMode.CALL))
                m.answer()
                m.signal(CALL, IntercomSignalKind.OFFER, "v=0 offer")
                p.last!!.connect()
            },
            "broadcasting" to { m, _ -> m.start(StartRequest(IntercomMode.BROADCAST)) },
            "playing a broadcast" to { m, _ ->
                m.ring(RingRequest(CALL, "caller-1", "Office", IntercomMode.BROADCAST))
            },
        )
        for ((name, begin) in cases) {
            val casePeers = FakePeerFactory()
            val caseLocks = FakeLocks()
            val caseScheduler = ManualScheduler(TestClock())
            val caseMachine = CallMachine(
                peers = casePeers,
                scheduler = caseScheduler,
                clockMs = { 0 },
                canTalk = { true },
                locks = caseLocks,
                newCallId = { CALL_TEST },
                closeExecutor = DIRECT,
            )
            begin(caseMachine, casePeers)
            val id = caseMachine.info.value?.callId ?: error("$name has no call")
            assertTrue(caseMachine.end(id, IntercomEndReason.COMMAND), "end from HA did not take in $name")
            assertEquals(IntercomState.IDLE, caseMachine.state.value, name)
            assertNull(caseMachine.info.value, name)
            assertEquals(1, caseLocks.released, name)
            val ended = buildList {
                while (true) add(caseMachine.events.tryReceive().getOrNull() ?: break)
            }.filterIsInstance<EngineEvent.Ended>()
            assertEquals(listOf(EngineEvent.Ended(id, IntercomEndReason.COMMAND)), ended, name)
        }
    }

    @Test
    fun `an end for another call changes nothing`() {
        ring()
        assertFalse(machine.end("call-other", IntercomEndReason.COMMAND))
        assertEquals(IntercomState.RINGING, machine.state.value)
        assertTrue(events().isEmpty())
    }

    @Test
    fun `a call ends after the transport grace and not before`() {
        ring()
        machine.answer()
        machine.signal(CALL, IntercomSignalKind.OFFER, "v=0 offer")
        peers.last!!.connect()
        machine.onTransportDown()
        scheduler.advance(Constants.PONG_TIMEOUT_S.toLong() * 1000L - 1)
        assertEquals(IntercomState.IN_CALL, machine.state.value)
        scheduler.advance(1)
        assertEquals(IntercomState.IDLE, machine.state.value)
        assertEquals(
            listOf<EngineEvent>(EngineEvent.Ended(CALL, IntercomEndReason.DISCONNECT)),
            events().filterIsInstance<EngineEvent.Ended>(),
        )
    }

    @Test
    fun `a transport that comes back inside the grace leaves the call up`() {
        ring()
        machine.answer()
        machine.signal(CALL, IntercomSignalKind.OFFER, "v=0 offer")
        peers.last!!.connect()
        machine.onTransportDown()
        scheduler.advance(Constants.PONG_TIMEOUT_S.toLong() * 1000L - 1)
        machine.onTransportUp()
        // one millisecond past the moment the grace would have fired
        scheduler.advance(1)
        assertEquals(IntercomState.IN_CALL, machine.state.value)
    }

    @Test
    fun `a restart starts idle with no call`() {
        // Guard 4: nothing is persisted, so a new engine cannot resume anything
        ring()
        machine.answer()
        val restarted = CallMachine(
            peers = FakePeerFactory(),
            scheduler = ManualScheduler(TestClock()),
            clockMs = { 0 },
            canTalk = { true },
            locks = FakeLocks(),
            closeExecutor = DIRECT,
        )
        assertEquals(IntercomState.IDLE, restarted.state.value)
        assertNull(restarted.info.value)
    }

    // --- listen-only and the connection ---------------------------------------

    @Test
    fun `a device without a microphone cannot start a call but answers one listening only`() {
        canTalk = false
        assertEquals(
            StartOutcome.CannotTalk,
            machine.start(StartRequest(IntercomMode.CALL, targetDeviceId = "d2")),
        )
        assertTrue(ring())
        assertTrue(machine.answer())
        assertEquals(PeerDirection.RECEIVE_ONLY, peers.last?.direction)
        assertFalse(machine.info.value!!.usesMicrophone)
        assertEquals(IntercomState.IN_CALL, machine.state.value)
    }

    @Test
    fun `a signal for another call is ignored`() {
        ring()
        assertFalse(machine.signal("call-other", IntercomSignalKind.OFFER, "v=0 offer"))
        assertTrue(peers.created.isEmpty())
    }

    @Test
    fun `a connection failure ends the call with an error`() {
        ring()
        machine.answer()
        machine.signal(CALL, IntercomSignalKind.OFFER, "v=0 offer")
        peers.last!!.fail()
        assertEquals(IntercomState.IDLE, machine.state.value)
        assertEquals(
            listOf<EngineEvent>(EngineEvent.Ended(CALL, IntercomEndReason.ERROR)),
            events().filterIsInstance<EngineEvent.Ended>(),
        )
        assertEquals(1, locks.released)
    }

    @Test
    fun `a peer that cannot be created ends the call instead of pretending`() {
        peers.failCreate = true
        assertTrue(ring())
        assertFalse(machine.answer())
        assertEquals(IntercomState.IDLE, machine.state.value)
        assertEquals(listOf<EngineEvent>(EngineEvent.Ended(CALL, IntercomEndReason.ERROR)), events())
        assertEquals(1, locks.released)
    }

    @Test
    fun `closing the engine leaves no call and reports nothing`() {
        ring()
        machine.answer()
        machine.close()
        assertEquals(IntercomState.IDLE, machine.state.value)
        assertEquals(1, locks.released)
        assertTrue(events().filterIsInstance<EngineEvent.Ended>().isEmpty())
    }


    // --- the caller's own frame and per-peer connections (P9.1b) --------------

    private fun peer(deviceId: String, name: String) = IntercomPeer(deviceId, name)

    @Test
    fun `prepare adopts the call and opens one connection per peer`() {
        val outcome = machine.prepare(
            CALL_TEST,
            IntercomMode.BROADCAST,
            listOf(peer("aa", "Kitchen"), peer("bb", "Bedroom")),
        )
        assertEquals(PrepareOutcome.Prepared(CALL_TEST), outcome)
        assertEquals(IntercomState.BROADCASTING, machine.state.value)
        assertEquals(CallDirection.OUTGOING, machine.info.value?.direction)
        assertEquals("Kitchen", machine.info.value?.peerName)
        assertEquals(2, peers.created.size)
        assertTrue(peers.created.all { it.offered })
        // Each offer is a signal of its own connection, labelled by its peer id
        val signals = events().filterIsInstance<EngineEvent.Signal>()
        assertEquals(setOf("aa", "bb"), signals.mapNotNull { it.peerDeviceId }.toSet())
        assertEquals(1, locks.acquired)
    }

    @Test
    fun `an answer is routed by its peer and an unknown peer is ignored`() {
        machine.prepare(CALL_TEST, IntercomMode.CALL, listOf(peer("aa", "Kitchen")))
        // A two-device call's answer carries no peer id and reaches the one peer
        assertTrue(machine.signal(CALL_TEST, IntercomSignalKind.ANSWER, "v=0 answer"))
        assertEquals("v=0 answer", peers.created.single().answerReceived)

        // A broadcast's answer names its connection
        val broadcast = CALL_TEST + "-b"
        machine.end(CALL_TEST, IntercomEndReason.HANGUP)
        machine.prepare(broadcast, IntercomMode.BROADCAST, listOf(peer("aa", "Kitchen"), peer("bb", "Bedroom")))
        assertTrue(machine.signal(broadcast, IntercomSignalKind.ANSWER, "v=0 answer", "bb"))
        assertEquals("v=0 answer", peers.created.last().answerReceived)
        assertNull(peers.created[peers.created.size - 2].answerReceived)

        // A peer id this call does not hold is ignored, and the call survives
        assertTrue(machine.signal(broadcast, IntercomSignalKind.ANSWER, "v=0 other", "cc"))
        assertEquals(IntercomState.BROADCASTING, machine.state.value)
        assertTrue(events().filterIsInstance<EngineEvent.Ended>().isEmpty())
    }

    @Test
    fun `an end closes every connection of the star`() {
        machine.prepare(CALL_TEST, IntercomMode.BROADCAST, listOf(peer("aa", "Kitchen"), peer("bb", "Bedroom")))
        assertTrue(machine.end(CALL_TEST, IntercomEndReason.COMMAND))
        assertEquals(IntercomState.IDLE, machine.state.value)
        assertTrue(peers.created.all { it.closed })
        assertEquals(1, locks.released)
    }

    @Test
    fun `prepare merges the peer list of a call it already holds`() {
        machine.prepare(CALL_TEST, IntercomMode.BROADCAST, listOf(peer("aa", "Kitchen"), peer("bb", "Bedroom")))
        val first = peers.created.toList()

        // bb is gone, cc is new: aa keeps its connection and is not offered to again
        assertEquals(
            PrepareOutcome.Prepared(CALL_TEST),
            machine.prepare(CALL_TEST, IntercomMode.BROADCAST, listOf(peer("aa", "Kitchen"), peer("cc", "Hall"))),
        )
        assertEquals(3, peers.created.size)
        assertFalse(first[0].closed)
        assertTrue(first[1].closed)
        assertEquals(1, peers.created.count { it.offered })
        assertEquals(1, locks.acquired)
    }

    @Test
    fun `prepare for another call is busy and changes nothing`() {
        machine.prepare(CALL_TEST, IntercomMode.CALL, listOf(peer("aa", "Kitchen")))
        assertEquals(
            PrepareOutcome.Busy,
            machine.prepare("call-other", IntercomMode.CALL, listOf(peer("bb", "Bedroom"))),
        )
        assertEquals(CALL_TEST, machine.info.value?.callId)
        assertEquals(1, peers.created.size)
    }

    @Test
    fun `a prepared caller closes its connections when its call ends`() {
        machine.prepare(CALL_TEST, IntercomMode.CALL, listOf(peer("aa", "Kitchen")))
        scheduler.advance(Constants.INTERCOM_CALL_LIMIT_S * 1000L)
        assertEquals(IntercomState.IDLE, machine.state.value)
        assertTrue(peers.created.single().closed)
        assertEquals(listOf<EngineEvent>(EngineEvent.Ended(CALL_TEST, IntercomEndReason.LIMIT)),
            events().filterIsInstance<EngineEvent.Ended>())
    }

    @Test
    fun `a callee never labels its signals`() {
        // A receiver neither sends nor sees peer_device_id (ADR 0037 decision 3)
        ring()
        machine.answer()
        events()
        val calleePeer = peers.last!!
        calleePeer.local(IntercomSignalKind.ANSWER, "v=0 answer")
        val signals = events().filterIsInstance<EngineEvent.Signal>()
        assertEquals(1, signals.size)
        assertNull(signals.single().peerDeviceId)
    }

    companion object {
        /** Runs a peer close inline: the fakes have no threads. */
        private val DIRECT = java.util.concurrent.Executor { it.run() }

        private const val CALL = "call-abc"
        private const val CALL_TEST = "call-test-1"
    }
}

// --- test doubles -------------------------------------------------------------

class TestClock(var now: Long = 0L)

class ManualScheduler(private val clock: TestClock) : CallScheduler {
    /** Fire cancelled entries too, to prove a stale deadline cannot act. */
    var ignoreCancellation = false

    private class Entry(val at: Long, val action: () -> Unit) {
        var cancelled = false
    }

    private val entries = mutableListOf<Entry>()

    override fun schedule(delayMs: Long, action: () -> Unit): CallScheduler.Cancelable {
        val entry = Entry(clock.now + delayMs, action)
        entries += entry
        return object : CallScheduler.Cancelable {
            override fun cancel() {
                entry.cancelled = true
            }
        }
    }

    fun advance(ms: Long) {
        val target = clock.now + ms
        while (true) {
            val next = entries
                .filter { (ignoreCancellation || !it.cancelled) && it.at <= target }
                .minByOrNull { it.at } ?: break
            entries.remove(next)
            clock.now = next.at
            next.action()
        }
        clock.now = target
    }
}

class FakeLocks : CallLocks {
    var acquired = 0
        private set
    var released = 0
        private set

    override fun acquire() {
        acquired++
    }

    override fun release() {
        released++
    }
}

class FakePeer(
    val info: CallInfo,
    val direction: PeerDirection,
    private val listener: CallPeer.Listener,
) : CallPeer {
    var offered = false
        private set
    var answeredOffer: String? = null
        private set
    var answerReceived: String? = null
        private set
    var answerReceipts = 0
        private set
    val candidates = mutableListOf<String>()
    var closed = false
        private set

    override fun offer() {
        offered = true
        listener.onLocalSignal(IntercomSignalKind.OFFER, "v=0 offer")
    }

    override fun answer(offerSdp: String) {
        answeredOffer = offerSdp
    }

    override fun answerReceived(answerSdp: String) {
        answerReceived = answerSdp
        answerReceipts++
    }

    override fun candidate(payload: String) {
        candidates += payload
    }

    override fun close() {
        closed = true
    }

    fun local(kind: IntercomSignalKind, payload: String) = listener.onLocalSignal(kind, payload)

    fun connect() = listener.onConnected()

    fun fail() = listener.onFailed("connection failed")

    fun disconnect() = listener.onDisconnected()
}

class FakePeerFactory : CallPeerFactory {
    val created = mutableListOf<FakePeer>()
    var failCreate = false

    val last: FakePeer? get() = created.lastOrNull()

    override fun create(info: CallInfo, direction: PeerDirection, listener: CallPeer.Listener): CallPeer {
        if (failCreate) throw IllegalStateException("no audio device")
        return FakePeer(info, direction, listener).also { created += it }
    }
}
