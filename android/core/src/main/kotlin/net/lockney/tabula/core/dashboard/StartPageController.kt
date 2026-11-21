package net.lockney.tabula.core.dashboard

import net.lockney.tabula.core.settings.Dashboard

/**
 * The return-to-start countdown (FEATURES F-DSH-15, packet P3.15), free of
 * Android so it is tested on a plain JVM. The app feeds it settings, touches,
 * page changes and the clock, and navigates when [onTick] says so.
 *
 * - The countdown runs from the last touch. Any touch resets it, and so does
 *   any page change - including a client-side route change inside the
 *   frontend - which is this feature's observable form of "HA navigated"
 *   (independent design, ADR 0019 item 3).
 * - It keeps running while a screensaver shows and while the panel is off
 *   (F-OPT: a covered dashboard can reset overnight without lighting the
 *   screen); the screensaver's own idle timer is a separate countdown.
 * - When the deadline passes, the dashboard returns only if the current page
 *   is not already the start target (path only; a fragment target compares
 *   by path, F-DSH-02), and the countdown then starts over.
 * - `return_home_after` 0 means never (the default), so a tablet without the
 *   setting behaves exactly as before.
 *
 * Times are wall-clock epoch milliseconds. Thread-safe.
 */
class StartPageController {
    private var startPath = Dashboard().startPath
    private var returnAfterMs = Dashboard().returnHomeAfter * 1000L
    private var lastActivityMs: Long? = null
    private var holds = 0

    /**
     * The start target as `current_page` compares it: the configured path
     * without its fragment, or "/" when none is set (HA's default page).
     */
    @get:Synchronized
    val targetPath: String
        get() = targetPathOf(startPath)

    /**
     * New settings (startup or set_settings). A changed delay restarts the
     * countdown, like the screensaver's idle timeout (ADR 0015 item 8); a
     * changed start path does not navigate (F-DSH-02).
     */
    @Synchronized
    fun applySettings(dashboard: Dashboard, nowMs: Long) {
        startPath = dashboard.startPath
        val newReturnMs = dashboard.returnHomeAfter * 1000L
        if (newReturnMs != returnAfterMs) {
            returnAfterMs = newReturnMs
            lastActivityMs = nowMs
        }
    }

    /** A touch on the kiosk (F-DSH-15: the timer is reset by any touch). */
    @Synchronized
    fun onActivity(nowMs: Long) {
        lastActivityMs = nowMs
    }

    /**
     * The dashboard's page changed (F-NAV-02): a navigation the user or HA
     * made. The countdown starts over (independent design, ADR 0019 item 3).
     */
    @Synchronized
    fun onPageChanged(nowMs: Long) {
        lastActivityMs = nowMs
    }

    /**
     * Pause or resume the countdown while an interaction holds the view.
     * The single hook for view freeze (F-DSH-17) and page-declared
     * interactions (F-DSH-18), neither built yet; nothing calls it until one
     * of them lands. Calls nest; resuming starts the countdown over.
     */
    @Synchronized
    fun setHeld(held: Boolean, nowMs: Long) {
        holds = if (held) holds + 1 else (holds - 1).coerceAtLeast(0)
        if (holds == 0) lastActivityMs = nowMs
    }

    /** When the countdown fires, or null while it cannot (never, held). */
    @Synchronized
    fun deadlineMs(nowMs: Long): Long? {
        if (returnAfterMs <= 0L || holds > 0) return null
        return (lastActivityMs ?: nowMs.also { lastActivityMs = it }) + returnAfterMs
    }

    /**
     * The clock moved on: whether the dashboard should return to the start
     * target now. False while no countdown runs, before the deadline, or when
     * [currentPage] is already the target (F-DSH-15); the countdown starts
     * over either way once the deadline has passed.
     */
    @Synchronized
    fun onTick(nowMs: Long, currentPage: String?): Boolean {
        if (returnAfterMs <= 0L || holds > 0) return false
        val last = lastActivityMs ?: nowMs.also { lastActivityMs = it }
        if (nowMs < last + returnAfterMs) return false
        lastActivityMs = nowMs
        return targetPath != currentPage
    }

    private fun targetPathOf(path: String): String {
        if (path.isEmpty()) return "/"
        val fragment = path.indexOf('#')
        val target = if (fragment >= 0) path.substring(0, fragment) else path
        return target.ifEmpty { "/" }
    }
}
