package net.lockney.tabula

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * What the app is doing, shared between the service and the UI. The service is
 * the only writer; the activity collects and renders.
 */
enum class Phase {
    /** No credential: the pairing screen is up. */
    UNPAIRED,

    /** Pairing in progress: [TabulaStatus.code] carries the code to confirm in HA. */
    PAIRING,

    /** Paired, working towards a registered connection. */
    CONNECTING,

    /** Registered and subscribed; commands and state flow. */
    CONNECTED,

    /** The credential was revoked in HA (ADR 0001): back to pairing. */
    REVOKED,

    /** HA refused registration in a way retrying cannot fix (PROTOCOL 6). */
    REJECTED,
}

object TabulaStatus {
    /** Set by the service when it starts; UNPAIRED until then. */
    val phase = MutableStateFlow(Phase.UNPAIRED)

    /** The pairing code shown on screen, with the device id beside it. */
    val pairing = MutableStateFlow<PairingView?>(null)

    /**
     * The last terminal pairing failure, shown on the pairing form
     * (PROTOCOL 2.5: a bad-nonce device shows a pairing error). Cleared when
     * a new pairing attempt starts.
     */
    val pairingError = MutableStateFlow<String?>(null)

    /** The last rejection (code, message) when [Phase.REJECTED]. */
    val rejection = MutableStateFlow<Pair<String, String>?>(null)

    data class PairingView(val code: String, val deviceIdShort: String)
}
