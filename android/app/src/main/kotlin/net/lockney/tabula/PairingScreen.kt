package net.lockney.tabula

/**
 * The pairing screen's state (issue #127): the confirm screen only with a code
 * in hand, the form with the reason otherwise.
 *
 * The phase alone cannot decide this. [Phase.PAIRING] means "an attempt is
 * running", and an attempt that cannot reach HA yet has no code: setting the
 * phase when the attempt started put the confirm screen up with an empty code
 * and no explanation. So the phase changes only when the service has a code
 * to show, and the screen derives what to draw from these values.
 */
sealed interface PairingScreenState {
    /** The address form, with [message] as the line above it when there is one. */
    data class Form(val message: String? = null) : PairingScreenState

    /** The code to confirm in HA. Never built without a code. */
    data class Confirm(val code: String, val deviceIdShort: String) : PairingScreenState
}

/**
 * Writes the pairing screen's state to [TabulaStatus] and reads it back, in
 * one place, so the service cannot put the screen in a state the activity
 * would have to second-guess. The service is the only writer.
 */
object PairingScreen {
    /** What the form says while the pair request cannot reach HA at [haUrl]. */
    fun unreachable(haUrl: String): String = "Can't reach Home Assistant at $haUrl. Retrying…"

    /** A new attempt: nothing is confirmable, so the form is up until HA issues a code. */
    fun attemptStarted() {
        TabulaStatus.pairing.value = null
        TabulaStatus.pairingError.value = null
        TabulaStatus.pairingNotice.value = null
        TabulaStatus.phase.value = Phase.UNPAIRED
    }

    /** HA issued a code: only now is there something to confirm. */
    fun codeIssued(code: String, deviceIdShort: String) {
        TabulaStatus.pairing.value = TabulaStatus.PairingView(code, deviceIdShort)
        TabulaStatus.pairingNotice.value = null
        TabulaStatus.phase.value = Phase.PAIRING
    }

    /** The pair request could not reach HA; the attempt keeps retrying in the background. */
    fun unreachableNow(haUrl: String) {
        // no code is on hand for this attempt, and a stale one belongs to an
        // attempt the server has already dropped: never show either
        TabulaStatus.pairing.value = null
        TabulaStatus.pairingNotice.value = unreachable(haUrl)
        TabulaStatus.phase.value = Phase.UNPAIRED
    }

    /** A terminal failure (PROTOCOL 2.5): back to the form, saying what went wrong. */
    fun failed(message: String) {
        TabulaStatus.pairing.value = null
        TabulaStatus.pairingNotice.value = null
        TabulaStatus.pairingError.value = message
        TabulaStatus.phase.value = Phase.UNPAIRED
    }

    /** Paired: nothing left to show on the form. The caller starts the session. */
    fun paired() {
        TabulaStatus.pairing.value = null
        TabulaStatus.pairingNotice.value = null
    }

    /** What the native screen draws for the current state. */
    fun state(): PairingScreenState {
        val pairing = TabulaStatus.pairing.value
        if (TabulaStatus.phase.value == Phase.PAIRING && pairing != null && pairing.code.isNotBlank()) {
            return PairingScreenState.Confirm(pairing.code, pairing.deviceIdShort)
        }
        return PairingScreenState.Form(
            TabulaStatus.pairingError.value ?: TabulaStatus.pairingNotice.value,
        )
    }
}
