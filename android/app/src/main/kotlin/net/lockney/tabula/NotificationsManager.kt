package net.lockney.tabula

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import net.lockney.tabula.core.Constants
import net.lockney.tabula.core.notifications.NotificationCards
import net.lockney.tabula.core.settings.Settings
import net.lockney.tabula.core.tlp.EventFrame
import net.lockney.tabula.core.tlp.NotifyArgs
import net.lockney.tabula.ha.CommandHandler

/** What [TabulaCommands] needs from the notification stack. */
interface NotificationControl {
    /** The `notify` command (PROTOCOL 4.5). */
    fun show(args: NotifyArgs): CommandHandler.Reply

    /** A settings document was applied (startup or set_settings). */
    fun applySettings(settings: Settings)
}

/**
 * The notification stack (packet P2.5): runs [NotificationCards]' expiry
 * clock and carries out each outcome - the rendered stack through [show],
 * the chime through [chime] (F-NTF-09) and each lifecycle change as a
 * `tabula/event` (F-NTF-08, F-EVT-02).
 *
 * Runs for the service's lifetime, like screen control and the screensaver,
 * so cards already up keep timing out while HA is down. Notifications have
 * no state keys (PROTOCOL 4.4); the session learns of them only through the
 * events.
 */
class NotificationsManager(
    private val controller: NotificationCards,
    private val show: (List<NotificationCards.Card>) -> Unit,
    private val chime: () -> Unit,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) : NotificationControl {
    /**
     * Lifecycle events for the TLP session. Bounded: while HA is down only the
     * latest few are kept, and the session drops those from before it
     * registered (Constants.NOTIFICATION_EVENT_BUFFER, ADR 0002).
     */
    private val eventQueue =
        Channel<(Int) -> EventFrame>(Constants.NOTIFICATION_EVENT_BUFFER, BufferOverflow.DROP_OLDEST)
    val events: ReceiveChannel<(Int) -> EventFrame> get() = eventQueue

    /** Wakes the expiry loop to recompute the next deadline. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private var timer: Job? = null

    /** The stack, newest first, for whoever renders it. */
    fun current(): List<NotificationCards.Card> = controller.cards

    /** Start the expiry clock. */
    fun start() {
        timer = scope.launch {
            while (true) {
                val deadline = controller.nextExpiryMs(now())
                if (deadline == null) {
                    wake.receive()
                    continue
                }
                val wait = deadline - now()
                if (wait > 0 && withTimeoutOrNull(wait) { wake.receive() } != null) continue
                carryOut(controller.onTick(now()))
            }
        }
    }

    fun stop() {
        timer?.cancel()
    }

    override fun applySettings(settings: Settings) {
        carryOut(controller.applySettings(settings.notifications))
    }

    override fun show(args: NotifyArgs): CommandHandler.Reply {
        val outcome = controller.show(
            message = args.message,
            title = args.title,
            level = args.level,
            durationS = args.duration,
            icon = args.icon,
            chime = args.chime,
            tag = args.tag,
            nowMs = now(),
        )
        carryOut(outcome)
        return CommandHandler.Reply.Ok()
    }

    /** A card's tap: removes only that card (F-NTF-08). */
    fun dismiss(id: String) {
        carryOut(controller.dismiss(id, net.lockney.tabula.core.tlp.DismissReason.TOUCH))
    }

    @Synchronized
    private fun carryOut(outcome: NotificationCards.Outcome) {
        for (transition in outcome.transitions) {
            val build: (Int) -> EventFrame = when (transition) {
                is NotificationCards.Transition.Shown ->
                    { id -> EventFrame.notificationShown(id, transition.tag) }
                is NotificationCards.Transition.Dismissed ->
                    { id -> EventFrame.notificationDismissed(id, transition.reason, transition.tag) }
            }
            eventQueue.trySend(build)
        }
        show(outcome.cards)
        if (outcome.chime) chime()
        wake.trySend(Unit)
    }
}
