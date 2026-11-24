package net.lockney.tabula.core.intercom

import net.lockney.tabula.core.settings.Intercom
import net.lockney.tabula.core.settings.IntercomAutoAnswer

/**
 * Which rings this device answers without a tap (P9.5, ADR 0025 decision 1 and
 * ADR 0028 guard 1: auto-answer is off unless the household opts in, and even
 * then [CallMachine] only consults it for a device that can capture).
 *
 * The rule is deliberately small and pure. It decides about the caller id a
 * ring carries (`caller_device_id`, PROTOCOL 4.5) and nothing else, because
 * that is the only caller identity the device has.
 *
 * `listed` with an empty list answers nobody. That is the fail-safe reading of
 * a half-filled setting: a household that picked "only these" and named none
 * gets a normal ring, which it can always answer by hand.
 */
class AutoAnswerPolicy private constructor(
    private val mode: IntercomAutoAnswer,
    private val callers: List<String>,
) {
    fun answers(callerDeviceId: String): Boolean = when (mode) {
        IntercomAutoAnswer.Off -> false
        IntercomAutoAnswer.All -> true
        // The schema's pattern admits lowercase UUIDv4 only, so a plain
        // membership test is the whole rule
        IntercomAutoAnswer.Listed -> callerDeviceId in callers
    }

    companion object {
        /** The `off` setting, and the machine's default. */
        val Off: AutoAnswerPolicy = AutoAnswerPolicy(IntercomAutoAnswer.Off, emptyList())

        /** The policy the current settings ask for. */
        fun of(intercom: Intercom): AutoAnswerPolicy =
            AutoAnswerPolicy(intercom.autoAnswer, intercom.autoAnswerFrom)
    }
}
