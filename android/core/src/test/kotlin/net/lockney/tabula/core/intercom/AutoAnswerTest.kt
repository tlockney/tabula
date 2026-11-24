package net.lockney.tabula.core.intercom

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.lockney.tabula.core.Constants
import net.lockney.tabula.core.settings.Intercom
import net.lockney.tabula.core.settings.IntercomAutoAnswer
import net.lockney.tabula.core.tlp.IntercomEndReason
import net.lockney.tabula.core.tlp.IntercomMode
import net.lockney.tabula.core.tlp.IntercomState

/**
 * Auto-answer (packet P9.5, ADR 0025 decision 1, ADR 0028 guard 1): which rings
 * a device answers with no tap, and the tone that runs before its microphone
 * opens. A virtual clock, a fake connection and a recording tone - no speaker,
 * no microphone, no socket.
 */
class AutoAnswerTest {
    private val clock = TestClock()
    private val scheduler = ManualScheduler(clock)
    private val peers = FakePeerFactory()
    private val locks = FakeLocks()
    private val tone = RecordingTone(peers)
    private var canTalk = true

    private fun machine(policy: AutoAnswerPolicy) = CallMachine(
        peers = peers,
        scheduler = scheduler,
        clockMs = { clock.now },
        canTalk = { canTalk },
        locks = locks,
        newCallId = { CALL },
        autoAnswer = { caller -> policy.answers(caller) },
        tone = tone,
        closeExecutor = { it.run() },
    )

    private fun ring(m: CallMachine, caller: String = CALLER, mode: IntercomMode = IntercomMode.CALL) =
        m.ring(RingRequest(CALL, caller, "Front door", mode))

    private fun events(m: CallMachine): List<EngineEvent> = buildList {
        while (true) add(m.events.tryReceive().getOrNull() ?: break)
    }

    private val toneMs = Constants.INTERCOM_ANSWER_TONE_MS.toLong()

    // --- the rule --------------------------------------------------------------

    private val other = "11111111-2222-4333-8444-555555555555"

    @Test
    fun `off answers nothing, even a named caller`() {
        val policy = AutoAnswerPolicy.of(Intercom())
        assertFalse(policy.answers(CALLER))
        assertFalse(policy.answers(other))
    }

    @Test
    fun `listed answers the listed callers only`() {
        val policy = AutoAnswerPolicy.of(
            Intercom(IntercomAutoAnswer.Listed, listOf(CALLER)),
        )
        assertTrue(policy.answers(CALLER))
        assertFalse(policy.answers(other))
    }

    @Test
    fun `listed with an empty list answers nobody`() {
        // The fail-safe reading of a half-filled setting: ring normally
        val policy = AutoAnswerPolicy.of(Intercom(IntercomAutoAnswer.Listed, emptyList()))
        assertFalse(policy.answers(CALLER))
    }

    @Test
    fun `all answers every device`() {
        val policy = AutoAnswerPolicy.of(Intercom(IntercomAutoAnswer.All, emptyList()))
        assertTrue(policy.answers(CALLER))
        assertTrue(policy.answers(other))
    }

    // --- the machine -----------------------------------------------------------

    @Test
    fun `an answered ring stays ringing until the tone is over, then answers itself`() {
        val m = machine(AutoAnswerPolicy.of(Intercom(IntercomAutoAnswer.All)))
        assertTrue(ring(m))
        assertEquals(IntercomState.RINGING, m.state.value)
        assertEquals(1, tone.plays)
        assertTrue(peers.created.isEmpty(), "the microphone opens after the tone, not before")

        scheduler.advance(toneMs - 1)
        assertEquals(IntercomState.RINGING, m.state.value)
        assertTrue(peers.created.isEmpty())

        scheduler.advance(1)
        assertEquals(IntercomState.IN_CALL, m.state.value)
        assertEquals(PeerDirection.SEND_RECEIVE, peers.last?.direction)
        assertEquals(listOf<EngineEvent>(EngineEvent.Answered(CALL)), events(m))
    }

    @Test
    fun `the tone plays before any connection exists`() {
        // ADR 0028 guard 1: the tone announces an unanswered microphone, so the
        // beep must be out before the capture starts
        val m = machine(AutoAnswerPolicy.of(Intercom(IntercomAutoAnswer.All)))
        ring(m)
        assertEquals(0, tone.peersAtPlay)
        scheduler.advance(toneMs)
        assertEquals(1, peers.created.size)
    }

    @Test
    fun `an unlisted caller still rings and is never answered by itself`() {
        val m = machine(AutoAnswerPolicy.of(Intercom(IntercomAutoAnswer.Listed, listOf(other))))
        assertTrue(ring(m))
        assertEquals(0, tone.plays)
        scheduler.advance(toneMs * 4)
        assertEquals(IntercomState.RINGING, m.state.value)
        assertTrue(peers.created.isEmpty())
        assertTrue(events(m).isEmpty())
    }

    @Test
    fun `a device without a usable microphone rings instead of answering itself`() {
        // Auto-answer never runs where the device could not talk anyway: it
        // degrades to a listen-only ring (ADR 0028, "Listen-only")
        canTalk = false
        val m = machine(AutoAnswerPolicy.of(Intercom(IntercomAutoAnswer.All)))
        assertTrue(ring(m))
        assertEquals(IntercomState.RINGING, m.state.value)
        assertEquals(false, m.info.value?.usesMicrophone)
        assertEquals(0, tone.plays)
        scheduler.advance(toneMs * 4)
        assertEquals(IntercomState.RINGING, m.state.value)
        assertTrue(peers.created.isEmpty())
    }

    @Test
    fun `a broadcast is never auto-answered`() {
        // A broadcast plays at once and never captures, so there is nothing for
        // the setting to answer (P9.5 item 2)
        val m = machine(AutoAnswerPolicy.of(Intercom(IntercomAutoAnswer.All)))
        assertTrue(ring(m, mode = IntercomMode.BROADCAST))
        assertEquals(IntercomState.IN_CALL, m.state.value)
        assertEquals(PeerDirection.RECEIVE_ONLY, peers.last?.direction)
        assertEquals(0, tone.plays)
        assertTrue(events(m).isEmpty())
    }

    @Test
    fun `a decline during the tone's run-up cancels the answer`() {
        val m = machine(AutoAnswerPolicy.of(Intercom(IntercomAutoAnswer.All)))
        ring(m)
        assertTrue(m.decline())
        assertEquals(IntercomState.IDLE, m.state.value)
        scheduler.advance(toneMs * 2)
        assertEquals(IntercomState.IDLE, m.state.value)
        assertTrue(peers.created.isEmpty(), "a declined call never opens a connection")
        assertEquals(listOf<EngineEvent>(EngineEvent.Declined(CALL)), events(m))
    }

    @Test
    fun `an end from HA during the tone's run-up cancels the answer`() {
        val m = machine(AutoAnswerPolicy.of(Intercom(IntercomAutoAnswer.All)))
        ring(m)
        assertTrue(m.end(CALL, IntercomEndReason.HANGUP))
        scheduler.advance(toneMs * 2)
        assertEquals(IntercomState.IDLE, m.state.value)
        assertTrue(peers.created.isEmpty())
        assertEquals(
            listOf<EngineEvent>(EngineEvent.Ended(CALL, IntercomEndReason.HANGUP)),
            events(m),
        )
    }

    @Test
    fun `a tap during the tone's run-up answers once and the stale timer does nothing`() {
        val m = machine(AutoAnswerPolicy.of(Intercom(IntercomAutoAnswer.All)))
        ring(m)
        assertTrue(m.answer())
        assertEquals(1, peers.created.size)
        // Fire cancelled entries too: the timer must be harmless, not just gone
        scheduler.ignoreCancellation = true
        scheduler.advance(toneMs)
        assertEquals(1, peers.created.size)
        assertEquals(listOf<EngineEvent>(EngineEvent.Answered(CALL)), events(m))
    }

    @Test
    fun `a link that dies during the tone's run-up never opens the microphone`() {
        // HA cannot end a call it cannot reach, so a ring whose link is gone
        // must not answer itself and leave a live call nobody can stop (P9.5)
        val m = machine(AutoAnswerPolicy.of(Intercom(IntercomAutoAnswer.All)))
        ring(m)
        m.onTransportDown()
        scheduler.ignoreCancellation = true
        scheduler.advance(toneMs * 2)
        assertTrue(peers.created.isEmpty(), "the microphone stays closed")
        assertEquals(IntercomState.RINGING, m.state.value)
    }

    @Test
    fun `a second ring during the tone's run-up is refused`() {
        val m = machine(AutoAnswerPolicy.of(Intercom(IntercomAutoAnswer.All)))
        assertTrue(ring(m))
        assertFalse(ring(m, caller = other))
        assertEquals(1, tone.plays)
    }

    @Test
    fun `a tone that cannot play still answers`() {
        // The tone is a courtesy, not a precondition: a busy speaker must not
        // leave a caller ringing hands-free in an empty room
        val m = CallMachine(
            peers = peers,
            scheduler = scheduler,
            clockMs = { clock.now },
            canTalk = { canTalk },
            locks = locks,
            newCallId = { CALL },
            autoAnswer = { true },
            tone = { throw IllegalStateException("speaker busy") },
            closeExecutor = { it.run() },
        )
        assertTrue(ring(m))
        scheduler.advance(toneMs)
        assertEquals(IntercomState.IN_CALL, m.state.value)
        assertEquals(1, peers.created.size)
    }

    @Test
    fun `an auto-answered call that never connects still times out`() {
        // The connect deadline runs from the auto-answer like any other answer
        val m = machine(AutoAnswerPolicy.of(Intercom(IntercomAutoAnswer.All)))
        ring(m)
        scheduler.advance(toneMs)
        scheduler.advance(Constants.INTERCOM_CONNECT_TIMEOUT_S * 1000L)
        assertEquals(IntercomState.IDLE, m.state.value)
        assertEquals(1, locks.released)
        assertNull(m.info.value)
    }

    private class RecordingTone(private val peers: FakePeerFactory) : CallTone {
        var plays = 0
            private set
        var peersAtPlay = -1
            private set

        override fun play() {
            plays++
            peersAtPlay = peers.created.size
        }
    }

    companion object {
        private const val CALL = "call-auto-1"
        private const val CALLER = "0f8fad5b-d9cb-469f-a165-70867728950e"
    }
}
