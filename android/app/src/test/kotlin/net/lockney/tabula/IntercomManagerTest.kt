package net.lockney.tabula

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.JsonPrimitive
import net.lockney.tabula.core.intercom.EngineEvent
import net.lockney.tabula.core.intercom.FakeCallEngine
import net.lockney.tabula.core.intercom.StartOutcome
import net.lockney.tabula.core.tlp.ErrorCode
import net.lockney.tabula.core.tlp.EventFrame
import net.lockney.tabula.core.tlp.EventName
import net.lockney.tabula.core.tlp.IntercomEndArgs
import net.lockney.tabula.core.tlp.IntercomEndReason
import net.lockney.tabula.core.tlp.IntercomMode
import net.lockney.tabula.core.tlp.IntercomPeer
import net.lockney.tabula.core.tlp.IntercomRingArgs
import net.lockney.tabula.core.tlp.IntercomSignalArgs
import net.lockney.tabula.core.tlp.IntercomSignalKind
import net.lockney.tabula.core.tlp.IntercomStartArgs
import net.lockney.tabula.core.tlp.IntercomState
import net.lockney.tabula.core.tlp.StateDelta
import net.lockney.tabula.ha.CommandHandler

/**
 * The intercom's TLP wiring against a fake engine (packet P9.3 item 9): the
 * three commands and their results, the six event frames, the state key, the
 * connection guards and the microphone hand-off signal.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IntercomManagerTest {
    private val scope = TestScope()
    private val engine = FakeCallEngine()
    private val deltas = mutableListOf<StateDelta>()
    private val holds = mutableListOf<Boolean>()
    private val manager = IntercomManager(
        engine = engine,
        scope = scope,
        publishState = { delta -> deltas += delta },
        onMicrophoneHold = { held -> holds += held },
    )

    private fun frames(): List<EventFrame> = buildList {
        while (true) {
            val frame = manager.events.tryReceive().getOrNull() ?: break
            add(frame(7))
        }
    }

    private fun ring(callId: String = CALL, mode: IntercomMode = IntercomMode.CALL) =
        IntercomRingArgs(callId, CALLER, "Office Kiosk", mode)

    private fun text(frame: EventFrame, key: String): String =
        (frame.data[key] as? JsonPrimitive)?.content ?: ""

    // --- commands --------------------------------------------------------------

    @Test
    fun `a ring is answered ok and the ringing state reaches the session`() {
        val reply = manager.ring(ring())
        assertTrue(reply is CommandHandler.Reply.Ok)
        assertEquals(1, engine.rings.size)
        scope.runCurrent()
        // the StateFlow's idle goes first, then the ring
        assertEquals(IntercomState.RINGING, deltas.last().intercomState)
    }

    @Test
    fun `a ring while a call is up is refused busy`() {
        assertTrue(manager.ring(ring()) is CommandHandler.Reply.Ok)
        val second = manager.ring(ring(callId = "call-second"))
        val error = second as CommandHandler.Reply.Error
        assertEquals(ErrorCode.BUSY, error.code)
        assertEquals(1, engine.rings.size)
    }

    @Test
    fun `a signal is ok for the live call and invalid args for another`() {
        manager.ring(ring())
        assertTrue(manager.signal(IntercomSignalArgs(CALL, IntercomSignalKind.OFFER, "v=0 offer")) is CommandHandler.Reply.Ok)
        val other = manager.signal(IntercomSignalArgs("call-other", IntercomSignalKind.OFFER, "v=0 offer"))
        assertEquals(ErrorCode.INVALID_ARGS, (other as CommandHandler.Reply.Error).code)
        assertEquals(1, engine.signals.size)
    }

    @Test
    fun `an end is always ok and reaches the engine for the live call`() {
        manager.ring(ring())
        assertTrue(manager.end(IntercomEndArgs(CALL, IntercomEndReason.COMMAND)) is CommandHandler.Reply.Ok)
        assertEquals(listOf(CALL to IntercomEndReason.COMMAND), engine.ends)
    }

    @Test
    fun `an end for a call this device is not in is ok and changes nothing`() {
        manager.ring(ring())
        assertTrue(manager.end(IntercomEndArgs("call-other", IntercomEndReason.COMMAND)) is CommandHandler.Reply.Ok)
        assertTrue(engine.ends.isEmpty())
        assertEquals(IntercomState.RINGING, manager.state())
    }

    // --- events ----------------------------------------------------------------

    @Test
    fun `every engine event becomes its TLP event frame`() {
        engine.emit(EngineEvent.Answered(CALL))
        engine.emit(EngineEvent.Declined(CALL))
        engine.emit(EngineEvent.Signal(CALL, IntercomSignalKind.CANDIDATE, "candidate:1"))
        engine.emit(EngineEvent.Ended(CALL, IntercomEndReason.LIMIT))
        engine.emit(
            EngineEvent.StartRequested("call-out", IntercomMode.CALL, TARGET, null),
        )
        scope.runCurrent()
        val frames = frames()
        assertEquals(
            listOf(
                EventName.INTERCOM_ANSWER,
                EventName.INTERCOM_DECLINE,
                EventName.INTERCOM_SIGNAL,
                EventName.INTERCOM_ENDED,
                EventName.INTERCOM_START_REQUEST,
            ),
            frames.map { it.event },
        )
        assertEquals(CALL, text(frames[0], "call_id"))
        assertEquals("candidate", text(frames[2], "kind"))
        assertEquals("candidate:1", text(frames[2], "payload"))
        assertEquals("limit", text(frames[3], "reason"))
        assertEquals("call-out", text(frames[4], "call_id"))
        assertEquals(TARGET, text(frames[4], "target_device_id"))
        assertEquals("call", text(frames[4], "mode"))
    }

    @Test
    fun `a start from the screen emits a start request and no ring`() {
        val outcome = manager.start(IntercomMode.CALL, targetDeviceId = TARGET, peerName = "Desk")
        assertTrue(outcome is StartOutcome.Started)
        scope.runCurrent()
        val frame = frames().single()
        assertEquals(EventName.INTERCOM_START_REQUEST, frame.event)
        assertEquals(TARGET, text(frame, "target_device_id"))
        assertEquals(IntercomState.IN_CALL, manager.state())
    }

    @Test
    fun `a device without a microphone cannot start a call`() {
        engine.canTalk = false
        assertEquals(StartOutcome.CannotTalk, manager.start(IntercomMode.CALL, targetDeviceId = TARGET))
        assertEquals(IntercomState.IDLE, manager.state())
    }

    @Test
    fun `a start with a target shape the protocol forbids is refused`() {
        // a call needs one target, a broadcast none (PROTOCOL 4.5)
        assertEquals(StartOutcome.InvalidTarget, manager.start(IntercomMode.CALL))
        assertEquals(
            StartOutcome.InvalidTarget,
            manager.start(IntercomMode.CALL, targetDeviceId = TARGET, targetAreaId = "area-1"),
        )
        assertEquals(
            StartOutcome.InvalidTarget,
            manager.start(IntercomMode.BROADCAST, targetDeviceId = TARGET),
        )
        assertTrue(engine.starts.isEmpty())
        assertEquals(IntercomState.IDLE, manager.state())
        scope.runCurrent()
        assertTrue(frames().none { it.event == EventName.INTERCOM_START_REQUEST })
    }

    @Test
    fun `a broadcast start takes no target`() {
        assertTrue(manager.start(IntercomMode.BROADCAST) is StartOutcome.Started)
        scope.runCurrent()
        val frame = frames().single()
        assertEquals(EventName.INTERCOM_START_REQUEST, frame.event)
        assertEquals("broadcast", text(frame, "mode"))
        assertEquals("", text(frame, "target_device_id"))
        assertEquals("", text(frame, "target_area_id"))
    }

    // --- the microphone hand-off ----------------------------------------------

    @Test
    fun `only a call that captures holds the microphone`() {
        manager.ring(ring())
        scope.runCurrent()
        assertEquals(listOf(false), holds)

        manager.answer()
        scope.runCurrent()
        assertEquals(listOf(false, true), holds)

        manager.hangUp()
        scope.runCurrent()
        assertEquals(listOf(false, true, false), holds)
    }

    @Test
    fun `a ringing call does not hold the microphone and a broadcast never does`() {
        manager.ring(ring(callId = "call-broadcast", mode = IntercomMode.BROADCAST))
        scope.runCurrent()
        // a broadcast plays immediately, with the microphone closed
        assertEquals(listOf(false), holds)
    }

    // --- the guards' entry points ---------------------------------------------

    @Test
    fun `the transport guards reach the engine`() {
        manager.onTransportDown()
        manager.onTransportUp()
        assertEquals(1, engine.transportDowns)
        assertEquals(1, engine.transportUps)
    }

    @Test
    fun `the capabilities follow the microphone grant`() {
        // The caller capability is unconditional: this app can always be the
        // calling end of a call HA started (P9.1b); only talk follows the grant.
        assertEquals(listOf("intercom_listen", "intercom_caller"), intercomCapabilities(canTalk = false))
        assertEquals(
            listOf("intercom_listen", "intercom_talk", "intercom_caller"),
            intercomCapabilities(canTalk = true),
        )
    }

    // --- the caller's own frame and per-peer signaling (P9.1b) ----------------

    private fun start(
        callId: String = CALL,
        mode: IntercomMode = IntercomMode.BROADCAST,
        peers: List<IntercomPeer> = listOf(IntercomPeer(TARGET, "Kitchen Kiosk")),
    ) = manager.start(IntercomStartArgs(callId, mode, peers))

    @Test
    fun `an intercom_start prepares the engine and is ok`() {
        assertTrue(start() is CommandHandler.Reply.Ok)
        assertEquals(1, engine.prepares.size)
        assertEquals(listOf(TARGET), engine.prepares.single().peers.map { it.deviceId })
        assertEquals(IntercomState.BROADCASTING, manager.state())
    }

    @Test
    fun `an intercom_start for a call this device is not in is refused busy`() {
        manager.ring(ring())
        val reply = start(callId = "call-other", mode = IntercomMode.CALL)
        assertEquals(ErrorCode.BUSY, (reply as CommandHandler.Reply.Error).code)
        assertTrue(engine.prepares.isEmpty())
    }

    @Test
    fun `a signal carries the peer id to the engine and out again`() {
        start()
        assertTrue(
            manager.signal(IntercomSignalArgs(CALL, IntercomSignalKind.OFFER, "v=0 offer")) is
                CommandHandler.Reply.Ok,
        )
        assertEquals(null, engine.signals.single().peerDeviceId)

        engine.emit(EngineEvent.Signal(CALL, IntercomSignalKind.ANSWER, "v=0 answer", TARGET))
        scope.runCurrent()
        val frame = frames().last()
        assertEquals(TARGET, text(frame, "peer_device_id"))
        assertEquals("v=0 answer", text(frame, "payload"))
    }

    @Test
    fun `a signal naming a peer the call does not hold is refused invalid_args`() {
        // ADR 0037 decision 4: the same answer the mock device gives
        start(peers = listOf(IntercomPeer(TARGET, "Kitchen Kiosk")))
        val reply = manager.signal(
            IntercomSignalArgs(CALL, IntercomSignalKind.ANSWER, "v=0 answer", CALLER),
        )
        assertEquals(ErrorCode.INVALID_ARGS, (reply as CommandHandler.Reply.Error).code)
        assertTrue(engine.signals.isEmpty())
    }

    companion object {
        private const val CALL = "call-abc"
        private const val CALLER = "11111111-1111-4111-8111-111111111111"
        private const val TARGET = "22222222-2222-4222-8222-222222222222"
    }
}
