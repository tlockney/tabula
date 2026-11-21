package net.lockney.tabula

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import net.lockney.tabula.core.Constants
import net.lockney.tabula.core.screensaver.EntityNeeds
import net.lockney.tabula.core.screensaver.ScreensaverController
import net.lockney.tabula.core.settings.Settings
import net.lockney.tabula.core.tlp.EventFrame
import net.lockney.tabula.core.tlp.ScreensaverMode
import net.lockney.tabula.core.tlp.StateDelta
import net.lockney.tabula.ha.CommandHandler

/** What [TabulaCommands] needs from the screensaver. */
interface ScreensaverControl {
    /** The `screensaver` command (PROTOCOL 4.5). */
    fun command(active: Boolean, mode: ScreensaverMode?): CommandHandler.Reply

    /** A settings document was applied (startup or set_settings). */
    fun applySettings(settings: Settings)
}

/**
 * The screensaver engine (packet P2.4): runs [ScreensaverController]'s idle
 * timer against the clock and carries out each outcome - what the overlay
 * shows ([show]), the backlight while it shows ([holdBrightness], F-SSV-17),
 * the `tabula_screensaver` events ([events], F-SSV-26), screensaver state,
 * and what HA entity data the showing screensaver needs ([entityData],
 * F-AAG-04, packet P3.5).
 *
 * Runs for the service's lifetime, like screen control, so the screensaver
 * works while HA is down. Each controller call and the carrying out of its
 * outcome happen under one monitor, so two outcomes never interleave.
 */
class ScreensaverManager(
    private val controller: ScreensaverController,
    private val show: (ScreensaverMode?) -> Unit,
    private val holdBrightness: (Int?) -> Unit,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
    private val entityData: (EntityNeeds) -> Unit = {},
) : ScreensaverControl {
    /** Receives every screensaver state change; the session sets it while connected. */
    @Volatile
    var listener: ((StateDelta) -> Unit)? = null

    /**
     * Lifecycle events for the TLP session. Bounded: while HA is down only the
     * latest few are kept, and the session drops those from before it registered.
     */
    private val eventQueue = Channel<(Int) -> EventFrame>(Constants.SCREENSAVER_EVENT_BUFFER, BufferOverflow.DROP_OLDEST)
    val events: ReceiveChannel<(Int) -> EventFrame> get() = eventQueue

    /** Wakes the timer loop to recompute the deadline. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private var settings = Settings()
    private var panelOn = true
    private var foreground = false
    private var timer: Job? = null

    init {
        // Nothing of Tabula's is in front until the activity says so (F-SSV-02).
        controller.setPaused(true, now())
    }

    /** Full screensaver state for registration (PROTOCOL 4.2). */
    fun current(): StateDelta = controller.current(now())

    /** Start the idle timer loop. */
    fun start() {
        timer = scope.launch {
            while (true) {
                val deadline = controller.deadlineMs(now())
                if (deadline == null) {
                    wake.receive()
                    continue
                }
                val wait = deadline - now()
                if (wait > 0 && withTimeoutOrNull(wait) { wake.receive() } != null) continue
                carryOut { controller.onTick(now()) }
            }
        }
    }

    fun stop() {
        timer?.cancel()
    }

    override fun applySettings(settings: Settings) = carryOut {
        this.settings = settings
        controller.applySettings(settings.screensaver, now())
    }

    override fun command(active: Boolean, mode: ScreensaverMode?): CommandHandler.Reply {
        carryOut { controller.onCommand(active, mode, now()) }
        return CommandHandler.Reply.Ok()
    }

    /** A touch on the kiosk: restarts the timer and dismisses the screensaver (F-SSV-04). */
    fun onActivity() = carryOut { controller.onActivity(now()) }

    /** The panel went on or off: the timer stops while it is off (F-SSV-02). */
    @Synchronized
    fun setPanelOn(on: Boolean) {
        if (on == panelOn) return
        panelOn = on
        carryOut { controller.setPaused(!on, now()) }
    }

    /** Tabula's window came to the front or left it: the timer stops while it is away (F-SSV-02). */
    @Synchronized
    fun setForeground(inFront: Boolean) {
        if (inFront == foreground) return
        foreground = inFront
        carryOut { controller.setPaused(!inFront, now()) }
    }

    @Synchronized
    private fun carryOut(step: () -> ScreensaverController.Outcome) {
        val outcome = step()
        for (transition in outcome.transitions) {
            val build: (Int) -> EventFrame = when (transition) {
                is ScreensaverController.Transition.Started ->
                    { id -> EventFrame.screensaverStarted(id, transition.mode, transition.trigger) }
                is ScreensaverController.Transition.Stopped ->
                    { id -> EventFrame.screensaverStopped(id, transition.mode, transition.trigger) }
            }
            eventQueue.trySend(build)
        }
        if (outcome.transitions.isNotEmpty()) show(controller.shownMode)
        // also on a settings change, so a new screensaver brightness applies at once
        holdBrightness(controller.brightnessWhileShown)
        outcome.state?.takeIf { it.isNotEmpty }?.let { delta -> listener?.invoke(delta) }
        // the HA entity subscription follows what shows (F-AAG-04): opened
        // with the screensaver, re-targeted by a settings change, closed on
        // dismissal; an unchanged need is a no-op downstream
        entityData(EntityNeeds.of(settings, controller.shownMode))
        wake.trySend(Unit)
    }
}
