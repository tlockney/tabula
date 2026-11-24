package net.lockney.tabula.core.intercom

import net.lockney.tabula.core.Constants
import net.lockney.tabula.core.tlp.IntercomEndReason
import net.lockney.tabula.core.tlp.IntercomMode
import net.lockney.tabula.core.tlp.IntercomState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The intercom screen (packet P9.4 item 5): every state renders, each button
 * asks the engine for exactly the thing it names, the in-call view stays up
 * for as long as the call does, and a listen-only tablet says so. Nothing here
 * opens a connection, a speaker or a microphone: the engine is P9.3's fake.
 */
class IntercomUiTest {
    private val engine = FakeCallEngine()
    private var now = 0L
    private var canTalk = true

    private val ui = IntercomUi(
        engine = engine,
        canTalk = { canTalk },
        clockMs = { now },
    )

    private val kitchen = IntercomPeer(
        deviceId = "ha-kitchen",
        name = "Kitchen frame",
        areaId = "kitchen",
        areaName = "Kitchen",
    )
    private val bedroom = IntercomPeer(
        deviceId = "ha-bedroom",
        name = "Bedroom frame",
        areaId = "bedroom",
        areaName = "Bedroom",
    )
    private val hall = IntercomPeer(deviceId = "ha-hall", name = "Hall frame")

    private fun directoryOpened(vararg peers: IntercomPeer): IntercomScreen.Directory {
        ui.perform(IntercomAction.OpenDirectory)
        ui.onDirectory(peers.toList())
        return assertIs(ui.screen)
    }

    // --- every state renders ---------------------------------------------------

    @Test
    fun `nothing is shown until the screen is opened or a call arrives`() {
        assertEquals(IntercomScreen.Closed, ui.tick())
    }

    @Test
    fun `the directory lists the peers HA sent, labelled by area`() {
        val screen = directoryOpened(kitchen, bedroom, hall)

        assertEquals(listOf("Kitchen", "Bedroom", "Hall frame"), screen.peers.map { it.label })
        assertTrue(screen.canTalk)
        assertNull(screen.note)
        assertEquals(IntercomScreen.Closed, ui.perform(IntercomAction.CloseDirectory))
    }

    @Test
    fun `an empty directory says so rather than showing nothing`() {
        val screen = directoryOpened()

        assertTrue(screen.peers.isEmpty())
        assertEquals(IntercomUi.NO_PEERS, screen.note)
    }

    @Test
    fun `a directory that could not be fetched says why`() {
        ui.perform(IntercomAction.OpenDirectory)
        ui.onDirectoryFailed(IntercomUi.UNREACHABLE)

        assertEquals(IntercomUi.UNREACHABLE, assertIs<IntercomScreen.Directory>(ui.screen).note)
    }

    @Test
    fun `a two-way ring shows the caller and the time left to answer`() {
        engine.ring(RingRequest(CALL_ID, "ha-kitchen", "Kitchen frame", IntercomMode.CALL))
        now = 5_000

        val screen = assertIs<IntercomScreen.Ringing>(ui.tick())

        assertEquals("Kitchen frame", screen.callerName)
        assertEquals(IntercomMode.CALL, screen.mode)
        assertEquals(Constants.INTERCOM_RING_LIMIT_S - 5L, screen.secondsLeft)
        assertFalse(screen.cannotTalkBack)
    }

    @Test
    fun `the ring stops counting at zero rather than going negative`() {
        engine.ring(RingRequest(CALL_ID, "ha-kitchen", "Kitchen frame", IntercomMode.CALL))
        now = 60_000

        assertEquals(0L, assertIs<IntercomScreen.Ringing>(ui.tick()).secondsLeft)
    }

    @Test
    fun `a call this device started reads as calling, not as connected`() {
        ui.onDirectory(listOf(kitchen))
        ui.perform(IntercomAction.Call(kitchen))
        now = 65_000

        val screen = assertIs<IntercomScreen.InCall>(ui.tick())

        assertEquals(IntercomUi.CALLING, screen.title)
        assertEquals("Kitchen", screen.peerName)
        assertEquals(IntercomMode.CALL, screen.mode)
        assertEquals(CallDirection.OUTGOING, screen.direction)
        assertEquals("1:05", screen.durationText)
        assertEquals("3:55", screen.remainingText)
        assertFalse(screen.cannotTalkBack)
        assertFalse(screen.canReply)
    }

    @Test
    fun `an answered call shows who is connected and the time to the hard limit`() {
        engine.ring(RingRequest(CALL_ID, "ha-kitchen", "Kitchen frame", IntercomMode.CALL))
        ui.perform(IntercomAction.Answer)

        val screen = assertIs<IntercomScreen.InCall>(ui.tick())

        assertEquals(IntercomUi.IN_CALL, screen.title)
        assertEquals("Kitchen frame", screen.peerName)
        assertEquals("0:00", screen.durationText)
        assertEquals(
            IntercomUi.clockOf(Constants.INTERCOM_CALL_LIMIT_S * 1000L),
            screen.remainingText,
        )
    }

    @Test
    fun `a broadcast this device sends reads as broadcasting`() {
        ui.perform(IntercomAction.Broadcast)

        val screen = assertIs<IntercomScreen.InCall>(ui.tick())

        assertEquals(IntercomUi.BROADCASTING, screen.title)
        assertEquals(IntercomMode.BROADCAST, screen.mode)
        assertEquals(CallDirection.OUTGOING, screen.direction)
        assertFalse(screen.canReply)
    }

    @Test
    fun `a broadcast being played shows the speaker and offers a call back`() {
        engine.ring(RingRequest(CALL_ID, "ha-kitchen", "Kitchen frame", IntercomMode.BROADCAST))

        val screen = assertIs<IntercomScreen.InCall>(ui.tick())

        assertEquals(IntercomUi.LISTENING, screen.title)
        assertEquals("Kitchen frame", screen.peerName)
        assertTrue(screen.canReply)
        // A broadcast's receiver keeps its microphone closed by design, so the
        // "cannot talk back" line is for the listen-only device, not this one.
        assertFalse(screen.cannotTalkBack)
    }

    // --- the banner stays while the call is up ---------------------------------

    @Test
    fun `the in-call view stays up while the call does, tick after tick`() {
        engine.ring(RingRequest(CALL_ID, "ha-kitchen", "Kitchen frame", IntercomMode.CALL))
        ui.perform(IntercomAction.Answer)

        repeat(3) {
            now += 30_000
            val screen = assertIs<IntercomScreen.InCall>(ui.tick())
            assertEquals("Kitchen frame", screen.peerName)
        }
        assertEquals("1:30", assertIs<IntercomScreen.InCall>(ui.tick()).durationText)
    }

    @Test
    fun `the directory gives way to the call and the dashboard comes back at its end`() {
        directoryOpened(kitchen)
        ui.perform(IntercomAction.Call(kitchen))
        assertIs<IntercomScreen.InCall>(ui.tick())

        ui.perform(IntercomAction.HangUp)

        assertEquals(1, engine.ends.size)
        // The list was a way in, not a place the call returns to.
        assertEquals(IntercomScreen.Closed, ui.tick())
    }

    // --- the buttons ask the engine for the right thing ------------------------

    @Test
    fun `calling a peer names that device and the label on screen`() {
        ui.onDirectory(listOf(kitchen, hall))
        ui.perform(IntercomAction.Call(hall))

        val request = engine.starts.single()
        assertEquals(IntercomMode.CALL, request.mode)
        assertEquals("ha-hall", request.targetDeviceId)
        assertNull(request.targetAreaId)
        assertEquals("Hall frame", request.peerName)
        // The call is up, so the list is behind it, not closed.
        assertIs<IntercomScreen.InCall>(ui.screen)
    }

    @Test
    fun `a fixed target from a dashboard link resolves the name it can see`() {
        ui.onDirectory(listOf(kitchen, bedroom))

        ui.perform(IntercomAction.CallTarget(areaId = "bedroom"))

        val request = engine.starts.single()
        assertEquals("bedroom", request.targetAreaId)
        assertNull(request.targetDeviceId)
        assertEquals("Bedroom", request.peerName)
    }

    @Test
    fun `a fixed target HA listed no name for still places the call`() {
        ui.perform(IntercomAction.CallTarget(deviceId = "ha-unknown"))

        assertEquals("ha-unknown", engine.starts.single().targetDeviceId)
        assertEquals("", engine.starts.single().peerName)
    }

    @Test
    fun `broadcast starts a broadcast with no target`() {
        ui.perform(IntercomAction.Broadcast)

        val request = engine.starts.single()
        assertEquals(IntercomMode.BROADCAST, request.mode)
        assertNull(request.targetDeviceId)
        assertNull(request.targetAreaId)
    }

    @Test
    fun `answer reaches the ringing call`() {
        engine.ring(RingRequest(CALL_ID, "ha-kitchen", "Kitchen frame", IntercomMode.CALL))

        ui.perform(IntercomAction.Answer)

        assertEquals(listOf(CALL_ID), engine.answers)
        assertTrue(engine.declines.isEmpty())
    }

    @Test
    fun `decline reaches the ringing call and returns to the list`() {
        directoryOpened(kitchen)
        engine.ring(RingRequest(CALL_ID, "ha-kitchen", "Kitchen frame", IntercomMode.CALL))

        ui.perform(IntercomAction.Decline)

        assertEquals(listOf(CALL_ID), engine.declines)
        assertTrue(engine.answers.isEmpty())
        assertEquals(listOf("Kitchen"), assertIs<IntercomScreen.Directory>(ui.screen).peers.map { it.label })
    }

    @Test
    fun `hang up ends the call this device is in`() {
        engine.ring(RingRequest(CALL_ID, "ha-kitchen", "Kitchen frame", IntercomMode.CALL))
        ui.perform(IntercomAction.Answer)

        ui.perform(IntercomAction.HangUp)

        assertEquals(listOf(CALL_ID to IntercomEndReason.HANGUP), engine.ends)
        assertEquals(IntercomScreen.Closed, ui.screen)
    }

    @Test
    fun `a button pressed with no call up does nothing`() {
        ui.perform(IntercomAction.Answer)
        ui.perform(IntercomAction.Decline)
        ui.perform(IntercomAction.HangUp)
        ui.perform(IntercomAction.Reply)

        assertTrue(engine.answers.isEmpty())
        assertTrue(engine.declines.isEmpty())
        assertTrue(engine.ends.isEmpty())
        assertTrue(engine.starts.isEmpty())
    }

    @Test
    fun `a second call while one is up is refused and says why`() {
        directoryOpened(kitchen)
        engine.ring(RingRequest(CALL_ID, "ha-bedroom", "Bedroom frame", IntercomMode.CALL))

        ui.perform(IntercomAction.Call(kitchen))
        ui.perform(IntercomAction.HangUp)

        assertEquals(IntercomUi.BUSY, assertIs<IntercomScreen.Directory>(ui.screen).note)
    }

    // --- a broadcast's reply is a call back ------------------------------------

    @Test
    fun `replying to a broadcast ends it and calls the sender back`() {
        engine.ring(RingRequest(CALL_ID, "ha-kitchen", "Kitchen frame", IntercomMode.BROADCAST))

        ui.perform(IntercomAction.Reply)

        // One call at a time: the broadcast goes first, and the call back is
        // held until the engine is idle again.
        assertEquals(listOf(CALL_ID to IntercomEndReason.HANGUP), engine.ends)
        assertTrue(engine.starts.isEmpty())

        ui.onStateChanged()

        val request = engine.starts.single()
        assertEquals(IntercomMode.CALL, request.mode)
        assertEquals("ha-kitchen", request.targetDeviceId)
        assertEquals("Kitchen frame", request.peerName)
        assertEquals(IntercomUi.CALLING, assertIs<IntercomScreen.InCall>(ui.screen).title)
    }

    @Test
    fun `replying from a tablet with no microphone says it cannot talk`() {
        canTalk = false
        engine.canTalk = false
        engine.ring(RingRequest(CALL_ID, "ha-kitchen", "Kitchen frame", IntercomMode.BROADCAST))

        ui.perform(IntercomAction.Reply)

        // The broadcast is still up: a reply that cannot capture changes nothing.
        assertTrue(engine.ends.isEmpty())
        assertTrue(engine.starts.isEmpty())
        assertEquals(IntercomUi.NO_MICROPHONE, assertIs<IntercomScreen.InCall>(ui.screen).note)
    }

    // --- a listen-only device --------------------------------------------------

    @Test
    fun `a tablet with no microphone offers no call and says why`() {
        canTalk = false
        engine.canTalk = false
        val screen = directoryOpened(kitchen)

        assertFalse(screen.canTalk)
        ui.perform(IntercomAction.Call(kitchen))

        assertTrue(engine.starts.isEmpty())
        assertEquals(IntercomUi.NO_MICROPHONE, assertIs<IntercomScreen.Directory>(ui.screen).note)
    }

    @Test
    fun `a listen-only tablet answering a two-way call says it cannot talk back`() {
        canTalk = false
        engine.ring(RingRequest(CALL_ID, "ha-kitchen", "Kitchen frame", IntercomMode.CALL))
        assertTrue(assertIs<IntercomScreen.Ringing>(ui.tick()).cannotTalkBack)

        ui.perform(IntercomAction.Answer)

        val screen = assertIs<IntercomScreen.InCall>(ui.tick())
        assertTrue(screen.cannotTalkBack)
        assertFalse(screen.canReply)
        // It answered: answering is listening, and only starting needs the mic.
        assertEquals(listOf(CALL_ID), engine.answers)
    }

    @Test
    fun `a listen-only tablet does not answer its own call with a reply`() {
        canTalk = false
        engine.ring(RingRequest(CALL_ID, "ha-kitchen", "Kitchen frame", IntercomMode.BROADCAST))

        assertFalse(assertIs<IntercomScreen.InCall>(ui.tick()).canReply)
    }

    // --- the clock -------------------------------------------------------------

    @Test
    fun `durations read as minutes and seconds and never go below zero`() {
        assertEquals("0:00", IntercomUi.clockOf(0))
        assertEquals("0:09", IntercomUi.clockOf(9_000))
        assertEquals("1:00", IntercomUi.clockOf(60_000))
        assertEquals("4:59", IntercomUi.clockOf(299_400))
        assertEquals("5:00", IntercomUi.clockOf(300_000))
        assertEquals("0:00", IntercomUi.clockOf(-5))
    }

    @Test
    fun `the hard limit is the constant from ADR 0002`() {
        engine.ring(RingRequest(CALL_ID, "ha-kitchen", "Kitchen frame", IntercomMode.CALL))
        ui.perform(IntercomAction.Answer)

        now = (Constants.INTERCOM_CALL_LIMIT_S - 60L) * 1000L

        assertEquals("1:00", assertIs<IntercomScreen.InCall>(ui.tick()).remainingText)
    }

    @Test
    fun `a call already at its limit shows no time left, not a negative one`() {
        engine.ring(RingRequest(CALL_ID, "ha-kitchen", "Kitchen frame", IntercomMode.CALL))
        ui.perform(IntercomAction.Answer)

        now = (Constants.INTERCOM_CALL_LIMIT_S + 30L) * 1000L

        val screen = assertIs<IntercomScreen.InCall>(ui.tick())
        assertEquals("0:00", screen.remainingText)
        assertEquals("5:30", screen.durationText)
    }

    @Test
    fun `a ringing call is not the directory even when the directory was opened`() {
        directoryOpened(kitchen)
        engine.ring(RingRequest(CALL_ID, "ha-bedroom", "Bedroom frame", IntercomMode.CALL))

        assertIs<IntercomScreen.Ringing>(ui.tick())
        assertEquals(IntercomState.RINGING, engine.state.value)
    }

    private companion object {
        const val CALL_ID = "01JC9Z6Q2H3W7K5N8T4V0BXDRA"
    }
}
