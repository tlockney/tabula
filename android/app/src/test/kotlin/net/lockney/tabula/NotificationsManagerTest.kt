package net.lockney.tabula

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import net.lockney.tabula.core.notifications.NotificationCards
import net.lockney.tabula.core.settings.Notifications
import net.lockney.tabula.core.settings.Settings
import net.lockney.tabula.core.tlp.DismissReason
import net.lockney.tabula.core.tlp.EventName
import net.lockney.tabula.core.tlp.NotificationLevel
import net.lockney.tabula.core.tlp.NotifyArgs

/** The stack against a (virtual) clock: events, rendering, chime, taps (packet P2.5). */
@OptIn(ExperimentalCoroutinesApi::class)
class NotificationsManagerTest {
    private val scope = TestScope()
    private val t0 = 1_800_000_000_000L
    private val rendered = mutableListOf<List<NotificationCards.Card>>()
    private val chimes = mutableListOf<Unit>()
    private var nextId = 0
    private val manager = NotificationsManager(
        controller = NotificationCards(newId = { "card-${nextId++}" }),
        show = { cards -> rendered += cards },
        chime = { chimes += Unit },
        scope = scope,
        now = { t0 + scope.testScheduler.currentTime },
    )

    private fun card(message: String, title: String? = null, level: NotificationLevel? = null, duration: Int? = null, chime: Boolean? = null, tag: String? = null) =
        NotifyArgs(message = message, title = title, level = level, duration = duration, icon = null, chime = chime, tag = tag)

    private fun drainEvents(): List<String> = buildList {
        while (true) {
            val build = manager.events.tryReceive().getOrNull() ?: break
            val frame = build(1)
            val reason = (frame.data["reason"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
            val tag = (frame.data["tag"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "-"
            add("${frame.event.name}:$reason:$tag")
        }
    }

    private fun shown() = manager.current()

    @AfterTest
    fun tearDown() = manager.stop()

    @Test
    fun `a card shows, renders and chimes per its level`() {
        manager.applySettings(Settings())
        manager.start()
        val reply = manager.show(card("Dryer finished", title = "Laundry", level = NotificationLevel.WARNING))
        assertTrue(reply is net.lockney.tabula.ha.CommandHandler.Reply.Ok)
        scope.runCurrent()
        assertEquals(1, rendered.last().size)
        assertEquals("Laundry", rendered.last().single().title)
        assertEquals(1, chimes.size, "warning chimes by default (ADR 0002)")

        assertEquals(listOf("NOTIFICATION_SHOWN::-"), drainEvents())
    }

    @Test
    fun `a card times out on the clock and reports the event`() {
        manager.applySettings(Settings(notifications = Notifications(defaultDuration = 5)))
        manager.start()
        manager.show(card("five seconds"))
        scope.runCurrent()
        assertEquals(1, shown().size)

        scope.advanceTimeBy(4_999)
        scope.runCurrent()
        assertEquals(1, shown().size)

        scope.advanceTimeBy(2)
        scope.runCurrent()
        assertEquals(0, shown().size)
        assertEquals(emptyList(), rendered.last())
        assertEquals(listOf("NOTIFICATION_SHOWN::-", "NOTIFICATION_DISMISSED:timeout:-"), drainEvents())
    }

    @Test
    fun `an until-dismissed card stays and a tap removes only it`() {
        manager.applySettings(Settings())
        manager.start()
        manager.show(card("kept", duration = 0, tag = "keep"))
        manager.show(card("tapped", duration = 0))
        scope.runCurrent()
        val tappedId = shown().first { it.message == "tapped" }.id

        scope.advanceTimeBy(3_600_000)
        scope.runCurrent()
        assertEquals(2, shown().size, "0 means until dismissed (F-NTF-05)")

        manager.dismiss(tappedId)
        assertEquals(listOf("kept"), shown().map { it.message })
        assertEquals(
            listOf("NOTIFICATION_SHOWN::keep", "NOTIFICATION_SHOWN::-", "NOTIFICATION_DISMISSED:touch:-"),
            drainEvents(),
        )
    }

    @Test
    fun `a repeated tag replaces and the events carry the tag`() {
        manager.applySettings(Settings())
        manager.start()
        manager.show(card("old", tag = "door", chime = true))
        manager.show(card("new", tag = "door", chime = true))
        scope.runCurrent()
        assertEquals(listOf("new"), shown().map { it.message })
        assertEquals(
            listOf("NOTIFICATION_SHOWN::door", "NOTIFICATION_DISMISSED:replaced:door", "NOTIFICATION_SHOWN::door"),
            drainEvents(),
        )
    }

    @Test
    fun `overflow evicts past the cap and the chime flag can be silenced`() {
        manager.applySettings(Settings(notifications = Notifications(maxVisible = 1)))
        manager.start()
        manager.show(card("one", chime = true))
        manager.show(card("two", chime = false))
        scope.runCurrent()
        assertEquals(listOf("two"), shown().map { it.message })
        assertEquals(1, chimes.size, "the second card's own flag wins (F-NTF-09)")
        assertTrue(drainEvents().first().startsWith(EventName.NOTIFICATION_SHOWN.name))
    }

    @Test
    fun `a timeout event carries the reason on the wire`() {
        manager.applySettings(Settings(notifications = Notifications(defaultDuration = 1)))
        manager.start()
        manager.show(card("brief"))
        scope.advanceTimeBy(1_001)
        scope.runCurrent()
        val events = drainEvents()
        assertEquals(2, events.size)
        assertTrue(events[1].contains(":timeout:"))
    }
}
