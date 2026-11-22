package net.lockney.tabula

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import net.lockney.tabula.core.dashboard.StartPageController
import net.lockney.tabula.core.settings.Settings

/** What [TabulaCommands] needs from the start page (set_settings, P3.15). */
interface StartPageControl {
    /** A settings document was applied (startup or set_settings). */
    fun applySettings(settings: Settings)
}

/**
 * The return-to-start timer (packet P3.15, FEATURES F-DSH-15, ADR 0021): runs
 * [StartPageController]'s countdown against the clock and carries out its
 * verdict by telling the dashboard to load the start target
 * ([DashboardBus.loadStart], F-DSH-02). Touches and page changes reach it from
 * the activity ([TabulaStatus.userActivity]) and the dashboard's page
 * reporting ([DashboardBus.onPageChanged]); a settings push updates it through
 * [applySettings].
 *
 * The countdown is independent of the screensaver's idle timer: it keeps
 * running while a screensaver shows and while the panel is off, so a covered
 * dashboard resets without lighting the screen (F-DSH-15, F-OPT).
 */
class StartPageManager(
    private val controller: StartPageController,
    private val dashboard: DashboardBus,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) : StartPageControl {
    /** Wakes the timer loop to recompute the deadline. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private var timer: Job? = null

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
                if (controller.onTick(now(), dashboard.currentPage())) {
                    dashboard.loadStart()
                }
            }
        }
    }

    fun stop() {
        timer?.cancel()
    }

    override fun applySettings(settings: Settings) {
        controller.applySettings(settings.dashboard, now())
        wake.trySend(Unit)
    }

    /** A touch on the kiosk resets the countdown (F-DSH-15). */
    fun onActivity() {
        controller.onActivity(now())
    }

    /**
     * The dashboard's page changed (F-NAV-02): a navigation by the user or HA
     * resets the countdown (independent design, ADR 0021 item 3).
     */
    fun onPageChanged() {
        controller.onPageChanged(now())
    }
}
