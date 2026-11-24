package net.lockney.tabula.core.intercom

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.lockney.tabula.core.Constants
import net.lockney.tabula.core.tlp.IntercomEndReason
import net.lockney.tabula.core.tlp.IntercomMode
import net.lockney.tabula.core.tlp.IntercomSignalKind
import net.lockney.tabula.core.tlp.IntercomState

/**
 * The call engine's state machine (packet P9.3, ADR 0028): one call at a time,
 * the four wire states, the three deadlines and the guards.
 *
 * Everything that decides is here and none of it touches Android or WebRTC.
 * The connection arrives through [CallPeerFactory], time through
 * [CallScheduler] and [clockMs], and the power and Wi-Fi locks through
 * [CallLocks], so the JVM tests drive every transition and every deadline
 * without opening a speaker, a microphone or a socket.
 *
 * The guards, one by one:
 * 1. **Explicit action only.** Nothing here starts or answers a call on its
 *    own: only [start] (the device's own screen or a schedule) and [ring] (an
 *    HA command) create a call. A device that is merely online is idle.
 * 2. **A hard limit.** A call ends by itself at [Timings.callLimitMs] and says
 *    why (`limit`), whichever state it is in.
 * 3. **An end from HA stops it in every state.** [end] takes the call down
 *    from ringing, connecting, in call and broadcasting alike.
 * 4. **No auto-resume.** No call state is stored anywhere but this object's
 *    fields, and a new machine starts idle, so a service or boot restart
 *    begins with no call and reports idle (PROTOCOL 4.4, ADR 0028).
 */
class CallMachine(
    private val peers: CallPeerFactory,
    private val scheduler: CallScheduler,
    private val clockMs: () -> Long,
    private val canTalk: () -> Boolean,
    private val locks: CallLocks = CallLocks.None,
    private val newCallId: () -> String = CallIdMint::random,
    private val timings: Timings = Timings(),
) : CallEngine {
    /** The deadlines and the grace, all from ADR 0002's constants by default. */
    data class Timings(
        val ringLimitMs: Long = Constants.INTERCOM_RING_LIMIT_S * 1000L,
        val connectTimeoutMs: Long = Constants.INTERCOM_CONNECT_TIMEOUT_S * 1000L,
        val callLimitMs: Long = Constants.INTERCOM_CALL_LIMIT_S * 1000L,
        /**
         * How long a call survives a dead TLP link. `PONG_TIMEOUT_S` is the
         * app's existing "this connection is dead" interval, so a call cannot
         * outlive its link to HA by more than HA's own liveness window - which
         * is what keeps guard 3 true while the link is down.
         */
        val transportGraceMs: Long = Constants.PONG_TIMEOUT_S.toLong() * 1000L,
    )

    private val _state = MutableStateFlow(IntercomState.IDLE)
    override val state: StateFlow<IntercomState> = _state.asStateFlow()

    private val _info = MutableStateFlow<CallInfo?>(null)
    override val info: StateFlow<CallInfo?> = _info.asStateFlow()

    private val _events = Channel<EngineEvent>(
        capacity = EVENT_BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: ReceiveChannel<EngineEvent> = _events

    private var peer: CallPeer? = null
    private var pendingOffer: String? = null

    /**
     * Candidates that arrived while there was no connection to give them to:
     * the caller trickles its host candidates within milliseconds of its offer,
     * which is long before the callee's ring is answered. They are handed over
     * when the connection is created (review of #171, finding 1). Guarded by
     * this object's monitor, like every field here: the machine's public methods
     * are synchronized, so do not "simplify" that away.
     */
    private val pendingCandidates = mutableListOf<String>()
    private var answeredOffer = false
    private var connected = false
    private var closed = false

    private var ringTimer: CallScheduler.Cancelable? = null
    private var connectTimer: CallScheduler.Cancelable? = null
    private var callTimer: CallScheduler.Cancelable? = null
    private var transportTimer: CallScheduler.Cancelable? = null

    private var locksHeld = false

    @Synchronized
    override fun start(request: StartRequest): StartOutcome {
        if (closed || _state.value != IntercomState.IDLE) return StartOutcome.Busy
        // Starting a call means capturing one: intercom_talk is the gate
        // (PROTOCOL 4.5, ADR 0025 decision 7)
        if (!canTalk()) return StartOutcome.CannotTalk
        val callId = newCallId()
        val info = CallInfo(
            callId = callId,
            mode = request.mode,
            direction = CallDirection.OUTGOING,
            peerName = request.peerName,
            peerDeviceId = request.targetDeviceId,
            startedAtMs = clockMs(),
            usesMicrophone = true,
        )
        val target = if (request.mode == IntercomMode.BROADCAST) IntercomState.BROADCASTING else IntercomState.IN_CALL
        begin(info, target)
        // The start request goes out before the offer: HA drops a signal whose
        // call id it has no record for, and the record is created when it
        // processes this event (review of #171, finding 2)
        emit(
            EngineEvent.StartRequested(
                callId = callId,
                mode = request.mode,
                targetDeviceId = request.targetDeviceId,
                targetAreaId = request.targetAreaId,
            ),
        )
        // HA can refuse the start without ever telling this device - it has no
        // call to send an intercom_end for - so a caller that gets no answer
        // ends itself at the ring limit rather than holding the microphone and
        // both locks until the hard limit (review of #171, finding 1)
        armNoAnswerTimer(info)
        // The caller opens its side at once and HA rings the callee; the
        // callee's answer comes back as a relayed signal.
        val direction = if (request.mode == IntercomMode.BROADCAST) PeerDirection.SEND_ONLY else PeerDirection.SEND_RECEIVE
        if (!openPeer(info, direction)) return StartOutcome.Unavailable
        peer?.offer()
        armCallLimit(info)
        return StartOutcome.Started(callId)
    }

    @Synchronized
    override fun ring(request: RingRequest): Boolean {
        if (closed || _state.value != IntercomState.IDLE) return false
        val capture = request.mode == IntercomMode.CALL && canTalk()
        val info = CallInfo(
            callId = request.callId,
            mode = request.mode,
            direction = CallDirection.INCOMING,
            peerName = request.callerName,
            peerDeviceId = request.callerDeviceId,
            startedAtMs = clockMs(),
            usesMicrophone = capture,
        )
        if (request.mode == IntercomMode.BROADCAST) {
            // One-way and live: it plays at once and never opens the microphone
            begin(info, IntercomState.IN_CALL)
            if (!openPeer(info, PeerDirection.RECEIVE_ONLY)) return true
            armCallLimit(info)
        } else {
            begin(info, IntercomState.RINGING)
            armNoAnswerTimer(info)
        }
        return true
    }

    @Synchronized
    override fun answer(): Boolean {
        val info = _info.value ?: return false
        if (_state.value != IntercomState.RINGING) return false
        cancel(ringTimer)
        ringTimer = null
        _state.value = IntercomState.IN_CALL
        // A listen-only device answers with a receive-only connection and the
        // record path closed (ADR 0028, "Listen-only")
        val direction = if (info.usesMicrophone) PeerDirection.SEND_RECEIVE else PeerDirection.RECEIVE_ONLY
        if (!openPeer(info, direction)) return false
        pendingOffer?.let { offer ->
            pendingOffer = null
            answerWith(offer)
        }
        // The connect deadline runs from this answer too: an offer that never
        // arrives must not leave the call up until the hard limit (review of
        // #171, finding 3)
        armConnectTimer(info)
        // ADR 0002: the hard limit runs from the moment the call is up
        armCallLimit(info)
        emit(EngineEvent.Answered(info.callId))
        return true
    }

    @Synchronized
    override fun decline(): Boolean {
        val info = _info.value ?: return false
        if (_state.value != IntercomState.RINGING) return false
        // A decline is not an end: intercom_decline is what tells HA, and HA
        // ends both sides (P9.1's mock sets the same shape)
        finish(info, IntercomEndReason.DECLINE, report = false)
        emit(EngineEvent.Declined(info.callId))
        return true
    }

    @Synchronized
    override fun end(callId: String, reason: IntercomEndReason): Boolean {
        val info = _info.value ?: return false
        if (info.callId != callId) return false
        finish(info, reason, report = true)
        return true
    }

    @Synchronized
    override fun signal(callId: String, kind: IntercomSignalKind, payload: String): Boolean {
        val info = _info.value ?: return false
        if (info.callId != callId) return false
        when (kind) {
            IntercomSignalKind.OFFER -> {
                // Only the answerer takes an offer; the caller made it and
                // ignores a second one (review of #171, finding 4)
                if (info.direction != CallDirection.INCOMING) return true
                when {
                    peer == null -> pendingOffer = payload
                    answeredOffer -> Unit
                    else -> answerWith(payload)
                }
            }

            IntercomSignalKind.ANSWER -> {
                // An answer at a callee is this call's signal, just not one this
                // side takes: acknowledge it rather than NACK a live call
                // (review of #171, finding 4)
                if (info.direction != CallDirection.OUTGOING) return true
                cancel(ringTimer)
                ringTimer = null
                peer?.answerReceived(payload)
                // ADR 0002: the connect timeout runs from the answer
                armConnectTimer(info)
            }

            IntercomSignalKind.CANDIDATE -> {
                val connection = peer
                if (connection != null) connection.candidate(payload) else pendingCandidates += payload
            }
        }
        return true
    }

    @Synchronized
    override fun onTransportUp() {
        cancel(transportTimer)
        transportTimer = null
    }

    @Synchronized
    override fun onTransportDown() {
        if (_state.value == IntercomState.IDLE) return
        // HA cannot send intercom_end over a dead link, so the call may not
        // outlive it (guard 3): the grace is one PONG_TIMEOUT_S
        if (transportTimer != null) return
        transportTimer = scheduler.schedule(timings.transportGraceMs) { onTransportGrace() }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        _info.value?.let { info -> finish(info, IntercomEndReason.HANGUP, report = false) }
        _events.close()
    }

    // --- internals ------------------------------------------------------------

    private fun begin(info: CallInfo, target: IntercomState) {
        _info.value = info
        _state.value = target
        if (!locksHeld) {
            locksHeld = true
            locks.acquire()
        }
    }

    /** Returns false - the call is already over - when the connection cannot be made. */
    private fun openPeer(info: CallInfo, direction: PeerDirection): Boolean {
        val created = runCatching { peers.create(info, direction, listener()) }.getOrNull()
        if (created == null) {
            finish(info, IntercomEndReason.ERROR, report = true)
            return false
        }
        peer = created
        if (pendingCandidates.isNotEmpty()) {
            pendingCandidates.forEach { created.candidate(it) }
            pendingCandidates.clear()
        }
        return true
    }

    private fun listener(): CallPeer.Listener = object : CallPeer.Listener {
        override fun onLocalSignal(kind: IntercomSignalKind, payload: String) {
            onPeerSignal(kind, payload)
        }

        override fun onConnected() {
            onPeerConnected()
        }

        override fun onFailed(message: String) {
            onPeerFailed(message)
        }
    }

    @Synchronized
    private fun onPeerSignal(kind: IntercomSignalKind, payload: String) {
        val info = _info.value ?: return
        emit(EngineEvent.Signal(info.callId, kind, payload))
    }

    @Synchronized
    private fun onPeerConnected() {
        if (_state.value == IntercomState.IDLE) return
        connected = true
        cancel(connectTimer)
        connectTimer = null
    }

    @Synchronized
    private fun onPeerFailed(message: String) {
        val info = _info.value ?: return
        finish(info, IntercomEndReason.ERROR, report = true)
    }

    private fun answerWith(offer: String) {
        val info = _info.value ?: return
        val connection = peer ?: run {
            pendingOffer = offer
            return
        }
        if (answeredOffer) return
        answeredOffer = true
        connection.answer(offer)
        armConnectTimer(info)
    }

    /**
     * How long this side waits for the other: a callee for someone to answer,
     * a caller for the answer to arrive. `INTERCOM_RING_LIMIT_S` is the same
     * window HA gives the callee, and HA ends a call it created first, so the
     * two cannot fight.
     */
    private fun armNoAnswerTimer(info: CallInfo) {
        cancel(ringTimer)
        ringTimer = scheduler.schedule(timings.ringLimitMs) { onRingLimit(info.callId) }
    }

    /** A two-way call's connect deadline; a broadcast has no single peer to reach. */
    private fun armConnectTimer(info: CallInfo) {
        if (info.mode != IntercomMode.CALL || connected || connectTimer != null) return
        connectTimer = scheduler.schedule(timings.connectTimeoutMs) { onConnectTimeout() }
    }

    private fun armCallLimit(info: CallInfo) {
        cancel(callTimer)
        callTimer = scheduler.schedule(timings.callLimitMs) { onCallLimit(info.callId) }
    }

    @Synchronized
    private fun onRingLimit(callId: String) {
        ringTimer = null
        val info = _info.value ?: return
        if (info.callId != callId) return
        // no answer, from either end: a callee's ring, a caller's wait
        finish(info, IntercomEndReason.TIMEOUT, report = true)
    }

    @Synchronized
    private fun onCallLimit(callId: String) {
        callTimer = null
        val info = _info.value ?: return
        if (info.callId != callId) return
        finish(info, IntercomEndReason.LIMIT, report = true)
    }

    @Synchronized
    private fun onConnectTimeout() {
        connectTimer = null
        val info = _info.value ?: return
        if (connected || _state.value != IntercomState.IN_CALL) return
        finish(info, IntercomEndReason.TIMEOUT, report = true)
    }

    @Synchronized
    private fun onTransportGrace() {
        transportTimer = null
        val info = _info.value ?: return
        finish(info, IntercomEndReason.DISCONNECT, report = true)
    }

    /**
     * Leaves the call whatever the state was: releases the connection, the
     * timers and the locks, and - unless this is a decline, which reports
     * itself - emits `intercom_ended` with [reason].
     */
    private fun finish(info: CallInfo, reason: IntercomEndReason, report: Boolean) {
        cancel(ringTimer)
        cancel(connectTimer)
        cancel(callTimer)
        cancel(transportTimer)
        ringTimer = null
        connectTimer = null
        callTimer = null
        transportTimer = null
        val connection = peer
        peer = null
        pendingOffer = null
        pendingCandidates.clear()
        answeredOffer = false
        connected = false
        runCatching { connection?.close() }
        _info.value = null
        _state.value = IntercomState.IDLE
        if (locksHeld) {
            locksHeld = false
            locks.release()
        }
        if (report) emit(EngineEvent.Ended(info.callId, reason))
    }

    private fun emit(event: EngineEvent) {
        _events.trySend(event)
    }

    private fun cancel(timer: CallScheduler.Cancelable?) {
        runCatching { timer?.cancel() }
    }

    companion object {
        /** Events kept while the wiring drains them; a burst of candidates fits. */
        private const val EVENT_BUFFER = 64
    }
}
