package net.lockney.tabula.core.intercom

import net.lockney.tabula.core.Constants
import net.lockney.tabula.core.tlp.IntercomEndReason
import net.lockney.tabula.core.tlp.IntercomMode
import net.lockney.tabula.core.tlp.IntercomState

/**
 * One tablet HA says this device may ring (packet P9.4 item 1, ADR 0025
 * decision 6). [deviceId] is the HA device registry id the
 * `intercom_start_request` carries; [areaName] is the label the screen shows,
 * with [name] standing in when the tablet has no area.
 */
data class IntercomPeer(
    val deviceId: String,
    val name: String,
    val areaId: String? = null,
    val areaName: String? = null,
    /** The peer declares `intercom_talk`, so it can hear this device talk back. */
    val canTalk: Boolean = true,
    /** The peer is in a call now; the screen marks it rather than hiding it. */
    val inCall: Boolean = false,
) {
    /** What the screen calls this peer: the room it is in, or its own name. */
    val label: String get() = areaName?.takeIf { it.isNotBlank() } ?: name
}

/**
 * What the intercom screen shows (packet P9.4 items 2 and 3, ADR 0025
 * decision 6, ADR 0028). The service renders one of these on every change and
 * the activity draws it; nothing here touches Android, audio or the network,
 * so every state is a JVM test.
 */
sealed interface IntercomScreen {
    /** No call up and the directory closed. */
    data object Closed : IntercomScreen

    /** Who to call. [note] explains an empty list or the last refusal. */
    data class Directory(
        val peers: List<IntercomPeer>,
        val canTalk: Boolean,
        val note: String? = null,
    ) : IntercomScreen

    /** An incoming two-way call: who, which mode, how long it has left. */
    data class Ringing(
        val callerName: String,
        val mode: IntercomMode,
        val secondsLeft: Long,
        /** This device has no microphone: it can answer, but only listen. */
        val cannotTalkBack: Boolean,
    ) : IntercomScreen

    /**
     * A call that is up. [title] is the state line ("Calling", "In call",
     * "Broadcasting", "Listen to a broadcast"), [peerName] who it is with.
     * [durationText] and [remainingText] are formatted here so both are one
     * testable rule; the hard limit is ADR 0002's `INTERCOM_CALL_LIMIT_S`.
     */
    data class InCall(
        val title: String,
        val peerName: String,
        val mode: IntercomMode,
        val direction: CallDirection,
        val durationText: String,
        val remainingText: String,
        val cannotTalkBack: Boolean,
        /** A broadcast's receiver places a two-way call back (ADR 0025 decision 2). */
        val canReply: Boolean,
        /** Why the last button press was refused, if it was. */
        val note: String? = null,
    ) : IntercomScreen
}

/** Everything a button on the intercom screen can ask for (packet P9.4 items 2-4). */
sealed interface IntercomAction {
    /** The screen was opened, by a link or by returning from a call. */
    data object OpenDirectory : IntercomAction

    data object CloseDirectory : IntercomAction

    /** Call one tablet this device's own directory listed. */
    data class Call(val peer: IntercomPeer) : IntercomAction

    /**
     * Call a fixed target: a device or an area id, as an action registry link
     * names it (ADR 0025 decision 6). The label is resolved from the last
     * directory, so a fixed call shows the same name the list would.
     */
    data class CallTarget(val deviceId: String? = null, val areaId: String? = null) : IntercomAction

    data object Broadcast : IntercomAction

    data object Answer : IntercomAction

    data object Decline : IntercomAction

    data object HangUp : IntercomAction

    /** Answer a broadcast with a two-way call back to the broadcaster. */
    data object Reply : IntercomAction
}

/**
 * The intercom screen's brain (packet P9.4). It holds no Android, no timer and
 * no connection: the service hands it the engine's state, its own clock, what
 * HA listed when the screen asked, and every button press; it answers with
 * what to draw. The one call at a time and the explicit-action guard are the
 * engine's (ADR 0028 guards 1 and 4), so nothing here can start a call that a
 * person did not ask for.
 *
 * Durations come from the clock the engine stamps calls with, and the hard
 * limit from ADR 0002's constant through [callLimitMs]; nothing is hard-coded.
 */
class IntercomUi(
    private val engine: CallEngine,
    /**
     * How a call is started. The app passes the wiring's own entry point, so
     * the screen gets the same target rule and logging as every other start
     * (P9.3's `IntercomManager.start`); the tests use the engine directly.
     */
    private val start: (StartRequest) -> StartOutcome = { engine.start(it) },
    private val canTalk: () -> Boolean,
    private val clockMs: () -> Long,
    private val callLimitMs: Long = Constants.INTERCOM_CALL_LIMIT_S * 1000L,
    private val ringLimitMs: Long = Constants.INTERCOM_RING_LIMIT_S * 1000L,
) {
    private var directoryOpen = false
    private var peers: List<IntercomPeer> = emptyList()
    private var note: String? = null

    /** A broadcast's receiver answered with a call back; started when idle. */
    private var pendingReply: StartRequest? = null

    /** The last render, so a caller that only needs the state is not surprised. */
    var screen: IntercomScreen = IntercomScreen.Closed
        private set

    /** What HA answered for the directory, or why it could not be fetched. */
    fun onDirectory(listed: List<IntercomPeer>, failure: String? = null) {
        peers = listed
        note = failure ?: when {
            listed.isEmpty() -> NO_PEERS
            else -> null
        }
        screen = render()
    }

    /** The directory request failed: HA was not reachable on the device's connection. */
    fun onDirectoryFailed(reason: String) {
        peers = emptyList()
        note = reason
        screen = render()
    }

    /** The engine's state or info changed: a call may have started or ended. */
    fun onStateChanged() {
        // A broadcast's reply waits for the broadcast to be down: a device is
        // in one call at a time, and the integration ends the old one first.
        val waiting = pendingReply
        if (waiting != null && engine.state.value == IntercomState.IDLE) {
            pendingReply = null
            begin(waiting)
        }
        screen = render()
    }

    /** Re-renders with a fresh clock: the tick that moves the duration on. */
    fun tick(): IntercomScreen {
        screen = render()
        return screen
    }

    /** Runs one button press and returns what to draw next. */
    fun perform(action: IntercomAction): IntercomScreen {
        when (action) {
            IntercomAction.OpenDirectory -> {
                directoryOpen = true
                note = null
            }

            IntercomAction.CloseDirectory -> if (engine.state.value == IntercomState.IDLE) {
                directoryOpen = false
                note = null
            }

            is IntercomAction.Call -> begin(
                StartRequest(
                    mode = IntercomMode.CALL,
                    targetDeviceId = action.peer.deviceId,
                    peerName = action.peer.label,
                ),
            )

            is IntercomAction.CallTarget -> {
                val peer = peers.firstOrNull { listed ->
                    (action.deviceId != null && listed.deviceId == action.deviceId) ||
                        (action.areaId != null && listed.areaId == action.areaId)
                }
                begin(
                    StartRequest(
                        mode = IntercomMode.CALL,
                        targetDeviceId = action.deviceId,
                        targetAreaId = action.areaId,
                        peerName = peer?.label.orEmpty(),
                    ),
                )
            }

            IntercomAction.Broadcast -> begin(StartRequest(mode = IntercomMode.BROADCAST))

            IntercomAction.Answer -> {
                note = null
                engine.answer()
            }

            IntercomAction.Decline -> {
                engine.decline()
                // back to the list the person came from, if they came from it
                note = null
            }

            IntercomAction.HangUp -> {
                // The note is not cleared here: a refusal that a person has not
                // seen yet (a fixed-target call that found a call already up)
                // still explains itself when the list comes back.
                call()?.let { engine.end(it.callId, IntercomEndReason.HANGUP) }
            }

            IntercomAction.Reply -> reply()
        }
        screen = render()
        return screen
    }

    /**
     * A two-way call back to a broadcast's sender (ADR 0025 decision 2). The
     * broadcast is ended first: the engine allows one call at a time, so the
     * call back is held until this device is idle again ([onStateChanged]).
     */
    private fun reply() {
        val current = call() ?: return
        if (current.mode != IntercomMode.BROADCAST) return
        val target = current.peerDeviceId ?: return
        if (!canTalk()) {
            note = NO_MICROPHONE
            return
        }
        pendingReply = StartRequest(
            mode = IntercomMode.CALL,
            targetDeviceId = target,
            peerName = current.peerName,
        )
        engine.end(current.callId, IntercomEndReason.HANGUP)
    }

    private fun begin(request: StartRequest) {
        note = when (val outcome = start(request)) {
            is StartOutcome.Started -> null
            StartOutcome.Busy -> BUSY
            StartOutcome.CannotTalk -> NO_MICROPHONE
            StartOutcome.Unavailable -> UNAVAILABLE
            StartOutcome.InvalidTarget -> INVALID_TARGET
        }
        if (note == null) directoryOpen = false
    }

    private fun call() = engine.info.value

    private fun render(now: Long = clockMs()): IntercomScreen {
        val info = call()
        val state = engine.state.value
        if (info == null || state == IntercomState.IDLE) {
            return if (directoryOpen) {
                IntercomScreen.Directory(peers = peers, canTalk = canTalk(), note = note)
            } else {
                IntercomScreen.Closed
            }
        }
        val elapsed = (now - info.startedAtMs).coerceAtLeast(0L)
        val talkBack = canTalk()
        return when (state) {
            IntercomState.RINGING -> IntercomScreen.Ringing(
                callerName = info.peerName.ifBlank { SOMEONE },
                mode = info.mode,
                secondsLeft = ((ringLimitMs - elapsed) / 1000L).coerceAtLeast(0L),
                cannotTalkBack = !talkBack,
            )

            else -> IntercomScreen.InCall(
                title = titleOf(info),
                peerName = info.peerName,
                mode = info.mode,
                direction = info.direction,
                durationText = clockOf(elapsed),
                remainingText = clockOf(callLimitMs - elapsed),
                cannotTalkBack = !talkBack,
                canReply = info.mode == IntercomMode.BROADCAST &&
                    info.direction == CallDirection.INCOMING &&
                    talkBack,
                note = note,
            )
        }
    }

    /**
     * The state line. P9.3 decision 9 puts a caller in `in_call` from its own
     * start, so the direction, not the state, says whether this is still
     * "calling" or an established call the other side answered by answering
     * the ring.
     */
    private fun titleOf(info: CallInfo): String = when {
        info.mode == IntercomMode.BROADCAST && info.direction == CallDirection.OUTGOING -> BROADCASTING
        info.mode == IntercomMode.BROADCAST -> LISTENING
        info.direction == CallDirection.OUTGOING -> CALLING
        else -> IN_CALL
    }

    companion object {
        /** `m:ss` for a call's length and its remaining time; never negative. */
        fun clockOf(ms: Long): String {
            val seconds = (ms.coerceAtLeast(0L) / 1000L)
            return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
        }

        const val SOMEONE = "Someone"
        const val CALLING = "Calling"
        const val IN_CALL = "In call"
        const val BROADCASTING = "Broadcasting"
        const val LISTENING = "Listening to a broadcast"

        const val BUSY = "A call is already up."
        const val NO_MICROPHONE = "This tablet has no microphone, so it cannot start a call."
        const val UNAVAILABLE = "The audio connection could not be created."
        const val INVALID_TARGET = "That target cannot be called."
        const val NO_PEERS = "No other intercom devices."
        const val CANNOT_TALK_BACK = "This tablet cannot talk back."
        const val UNREACHABLE = "Home Assistant is not reachable, so there is no device list."
    }
}
