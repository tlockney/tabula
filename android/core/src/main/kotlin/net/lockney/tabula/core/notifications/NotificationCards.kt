package net.lockney.tabula.core.notifications

import java.util.UUID
import net.lockney.tabula.core.settings.Notifications
import net.lockney.tabula.core.tlp.DismissReason
import net.lockney.tabula.core.tlp.NotificationLevel

/**
 * The on-screen notification card stack (FEATURES F-NTF-01 to F-NTF-09),
 * free of Android so it is tested on a plain JVM. The app feeds it settings,
 * `notify` commands, card taps and the clock, renders the [Card] snapshot it
 * returns and carries out each [Transition] as a TLP event.
 *
 * - Newest card on top; each card times out independently (F-NTF-07).
 * - Visible cards are capped at `notifications.max_visible` (ADR 0002);
 *   overflow evicts the oldest.
 * - A repeated tag replaces the earlier card with that tag (PROTOCOL 4.5).
 * - Duration 0 means until dismissed (F-NTF-05); a missing duration or level
 *   uses the device setting (F-NTF-01, F-NTF-02).
 * - The chime decision (F-NTF-09) is per card: the command's `chime` when
 *   sent, otherwise `notifications.chime_by_level` for the resolved level.
 *
 * Thread-safe; wall-clock epoch milliseconds because the app's timer works in
 * those.
 */
class NotificationCards(
    /** Card ids are unique per tablet (F-NTF-08); UUID by default, fixed in tests. */
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    /** One card on screen. */
    data class Card(
        val id: String,
        val tag: String?,
        val title: String?,
        val message: String,
        val level: NotificationLevel,
        /** The `mdi:…` name as sent (F-NTF-02); v1 renders the level's stock glyph. */
        val icon: String?,
        /** Whether this card chimed when it appeared (decided at show time). */
        val chime: Boolean,
        /** When the card removes itself, or null for until-dismissed (F-NTF-05). */
        val expiresAtMs: Long?,
    )

    /** A lifecycle change for the app to carry out as a tabula/event (F-EVT-02). */
    sealed interface Transition {
        data class Shown(val tag: String?) : Transition

        data class Dismissed(val tag: String?, val reason: DismissReason) : Transition
    }

    data class Outcome(
        val transitions: List<Transition> = emptyList(),
        /** The full stack, newest first; the app renders this snapshot. */
        val cards: List<Card> = emptyList(),
        /** Whether the card this outcome added chimes (F-NTF-09). */
        val chime: Boolean = false,
    )

    private var defaultDurationS = Notifications().defaultDuration
    private var defaultLevel = level(Notifications().defaultLevel.name)
    private var chimeByLevel = Notifications().chimeByLevel
    private var maxVisible = Notifications().maxVisible

    /** Newest first. */
    private val stack = mutableListOf<Card>()

    /** A settings document was applied (startup or set_settings): new defaults, new cap. */
    @Synchronized
    fun applySettings(settings: Notifications): Outcome {
        defaultDurationS = settings.defaultDuration
        defaultLevel = level(settings.defaultLevel.name)
        chimeByLevel = settings.chimeByLevel
        maxVisible = settings.maxVisible
        return evictOverflow(mutableListOf())
    }

    /** The `notify` command (PROTOCOL 4.5). */
    @Synchronized
    fun show(
        message: String,
        title: String?,
        level: NotificationLevel?,
        durationS: Int?,
        icon: String?,
        chime: Boolean?,
        tag: String?,
        nowMs: Long,
    ): Outcome {
        val resolvedLevel = level ?: defaultLevel
        val transitions = mutableListOf<Transition>()
        if (tag != null) {
            // same tag replaces (PROTOCOL 4.5); the card it replaces leaves with
            // reason "replaced" (F-NTF-08: each card's exit emits an event)
            val replaced = stack.removeAll { it.tag == tag }
            if (replaced) transitions += Transition.Dismissed(tag, DismissReason.REPLACED)
        }
        val seconds = durationS ?: defaultDurationS
        val card = Card(
            id = newId(),
            tag = tag,
            title = title,
            message = message,
            level = resolvedLevel,
            icon = icon,
            chime = chime ?: chimesFor(resolvedLevel),
            expiresAtMs = if (seconds == 0) null else nowMs + seconds * 1000L,
        )
        stack.add(0, card)
        transitions += Transition.Shown(tag)
        val outcome = evictOverflow(transitions)
        return outcome.copy(chime = card.chime)
    }

    /** A card's own close control: a tap removes only that card (F-NTF-08). */
    @Synchronized
    fun dismiss(id: String, reason: DismissReason): Outcome {
        val card = stack.firstOrNull { it.id == id } ?: return snapshot(emptyList())
        stack.remove(card)
        return snapshot(listOf(Transition.Dismissed(card.tag, reason)))
    }

    /** The clock moved on: remove cards whose lifetime ran out (F-NTF-05). */
    @Synchronized
    fun onTick(nowMs: Long): Outcome {
        val expired = stack.filter { it.expiresAtMs != null && it.expiresAtMs <= nowMs }
        if (expired.isEmpty()) return Outcome()
        stack.removeAll(expired.toSet())
        return snapshot(expired.map { Transition.Dismissed(it.tag, DismissReason.TIMEOUT) })
    }

    /** When the next card expires, or null when none does (the app's timer waits on this). */
    @Synchronized
    fun nextExpiryMs(nowMs: Long): Long? =
        stack.mapNotNull { it.expiresAtMs }.minOrNull()?.takeIf { it > nowMs }

    /** The stack, newest first. */
    @get:Synchronized
    val cards: List<Card>
        get() = stack.toList()

    private fun evictOverflow(transitions: MutableList<Transition>): Outcome {
        // F-NTF-07: the oldest cards fall off past the cap. The wire reason
        // enum has no "evicted" value; "replaced" (removed for another card)
        // is the closest and is recorded in the packet Result.
        while (stack.size > maxVisible) {
            val oldest = stack.removeAt(stack.lastIndex)
            transitions += Transition.Dismissed(oldest.tag, DismissReason.REPLACED)
        }
        return snapshot(transitions)
    }

    private fun snapshot(transitions: List<Transition>): Outcome =
        Outcome(transitions = transitions, cards = stack.toList())

    private fun chimesFor(level: NotificationLevel): Boolean = when (level) {
        NotificationLevel.INFO -> chimeByLevel.info
        NotificationLevel.SUCCESS -> chimeByLevel.success
        NotificationLevel.WARNING -> chimeByLevel.warning
        NotificationLevel.ERROR -> chimeByLevel.error
    }

    private fun level(name: String): NotificationLevel = NotificationLevel.valueOf(name.uppercase())
}
