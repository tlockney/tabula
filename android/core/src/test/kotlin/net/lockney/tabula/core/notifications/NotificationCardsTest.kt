package net.lockney.tabula.core.notifications

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.lockney.tabula.core.settings.Notifications
import net.lockney.tabula.core.tlp.DismissReason
import net.lockney.tabula.core.tlp.NotificationLevel

/** The card stack (packet P2.5): stacking, duration, level, chime, tags (F-NTF). */
class NotificationCardsTest {
    private var nextId = 0
    private val cards = NotificationCards(newId = { "card-${nextId++}" })
    private val t0 = 1_800_000_000_000L

    private fun show(
        message: String = "hello",
        title: String? = null,
        level: NotificationLevel? = null,
        durationS: Int? = null,
        icon: String? = null,
        chime: Boolean? = null,
        tag: String? = null,
        nowMs: Long = t0,
    ) = cards.show(message, title, level, durationS, icon, chime, tag, nowMs)

    @Test
    fun `defaults apply for missing fields`() {
        val outcome = show()
        val card = outcome.cards.single()
        assertNull(card.tag)
        assertNull(card.title)
        assertEquals("hello", card.message)
        // ADR 0002: info level, 10 s duration, no chime for info
        assertEquals(NotificationLevel.INFO, card.level)
        assertEquals(t0 + 10_000, card.expiresAtMs)
        assertFalse(outcome.chime)
        assertEquals(1, outcome.transitions.size)
        val shown = assertIs<NotificationCards.Transition.Shown>(outcome.transitions.single())
        assertNull(shown.tag)
    }

    @Test
    fun `per-card fields override the settings`() {
        cards.applySettings(Notifications(defaultDuration = 60, defaultLevel = net.lockney.tabula.core.settings.NotificationsDefaultLevel.Warning))
        val outcome = show(
            message = "door",
            title = "Front door",
            level = NotificationLevel.ERROR,
            durationS = 300,
            chime = false,
            tag = "door",
        )
        val card = outcome.cards.single()
        assertEquals(NotificationLevel.ERROR, card.level)
        assertEquals(t0 + 300_000, card.expiresAtMs)
        assertEquals("door", card.tag)
        // the chime override wins even for error, whose default is on (F-NTF-09)
        assertFalse(outcome.chime)
        assertFalse(card.chime)
    }

    @Test
    fun `duration zero means until dismissed`() {
        val outcome = show(durationS = 0)
        assertNull(outcome.cards.single().expiresAtMs)
        cards.onTick(t0 + 3_600_000)
        assertEquals(1, cards.cards.size, "an until-dismissed card never times out")
    }

    @Test
    fun `the chime follows chime_by_level`() {
        cards.applySettings(Notifications()) // ADR 0002: warning and error chime, info and success do not
        assertFalse(show(level = NotificationLevel.INFO).chime)
        assertFalse(show(level = NotificationLevel.SUCCESS).chime)
        assertTrue(show(level = NotificationLevel.WARNING).chime)
        assertTrue(show(level = NotificationLevel.ERROR).chime)
        assertTrue(show(level = NotificationLevel.INFO, chime = true).chime, "the card's own flag wins")
    }

    @Test
    fun `newest card goes on top and each times out alone`() {
        show(message = "first", durationS = 10)
        show(message = "second", durationS = 30)
        assertEquals(listOf("second", "first"), outcomeMessages())

        val timedOut = cards.onTick(t0 + 10_000)
        assertEquals(listOf("second"), outcomeMessages())
        val dismissed = assertIs<NotificationCards.Transition.Dismissed>(timedOut.transitions.single())
        assertEquals(DismissReason.TIMEOUT, dismissed.reason)
        assertNull(dismissed.tag)

        cards.onTick(t0 + 30_000)
        assertTrue(cards.cards.isEmpty())
    }

    @Test
    fun `overflow evicts the oldest card`() {
        cards.applySettings(Notifications(maxVisible = 2))
        show(message = "one", durationS = 0)
        show(message = "two", durationS = 0)
        val outcome = show(message = "three", durationS = 0)
        assertEquals(listOf("three", "two"), outcomeMessages())
        val dismissed = outcome.transitions.filterIsInstance<NotificationCards.Transition.Dismissed>()
        assertEquals(1, dismissed.size)
        assertEquals(DismissReason.REPLACED, dismissed.single().reason, "eviction has no wire reason of its own")
    }

    @Test
    fun `a repeated tag replaces the earlier card`() {
        show(message = "old", tag = "door", durationS = 0)
        val outcome = show(message = "new", tag = "door", durationS = 0)
        assertEquals(listOf("new"), outcomeMessages())
        val dismissed = assertIs<NotificationCards.Transition.Dismissed>(outcome.transitions.first())
        assertEquals(DismissReason.REPLACED, dismissed.reason)
        assertEquals("door", dismissed.tag)
        val shown = assertIs<NotificationCards.Transition.Shown>(outcome.transitions.last())
        assertEquals("door", shown.tag)
    }

    @Test
    fun `a tap removes only that card`() {
        show(message = "one", durationS = 0)
        val two = show(message = "two", durationS = 0)
        val outcome = cards.dismiss(two.cards.first().id, DismissReason.TOUCH)
        assertEquals(listOf("one"), outcomeMessages())
        assertEquals(DismissReason.TOUCH, assertIs<NotificationCards.Transition.Dismissed>(outcome.transitions.single()).reason)
        assertEquals(1, cards.cards.size)
    }

    @Test
    fun `the expiry clock sees the next deadline`() {
        assertEquals(null, cards.nextExpiryMs(t0))
        show(message = "a", durationS = 20)
        show(message = "b", durationS = 5)
        assertEquals(t0 + 5_000, cards.nextExpiryMs(t0))
        cards.onTick(t0 + 5_000)
        assertEquals(t0 + 20_000, cards.nextExpiryMs(t0))
    }

    @Test
    fun `a settings change applies the new cap right away`() {
        show(message = "one", durationS = 0)
        show(message = "two", durationS = 0)
        show(message = "three", durationS = 0)
        val outcome = cards.applySettings(Notifications(maxVisible = 1))
        assertEquals(listOf("three"), outcomeMessages())
        assertEquals(2, outcome.transitions.size, "two cards fell off the new cap")
    }

    private fun outcomeMessages(): List<String> = cards.cards.map { it.message }
}
