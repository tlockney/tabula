package net.lockney.tabula

import java.util.concurrent.CopyOnWriteArrayList

/**
 * The dashboard's control surface, shared between the service (which runs
 * TLP commands on a session thread) and the activity (which owns the
 * WebView). The activity attaches a [Handle] while its dashboard is alive;
 * the service's command handlers and state source reach it through this bus,
 * and everything survives the two having different lifetimes: after
 * restart_app the service is up before the activity re-attaches.
 *
 * Pure Kotlin, no Android imports, so the wiring is unit-tested on the JVM.
 */
class DashboardBus {
    /** What the dashboard can do; implemented by the activity's WebView. */
    interface Handle {
        /** reload: re-fetch the current page (PROTOCOL 4.5). */
        fun reload()

        /** clear_cache: drop the WebView's cache (PROTOCOL 4.5). */
        fun clearCache()

        /** Load an absolute URL that already passed origin checks. */
        fun load(url: String)

        /**
         * Load the start target (F-DSH-02, `dashboard.start_path`): the
         * configured frontend path, validated the way `navigate` validates
         * its own, or HA's default page when none is set or the value is
         * invalid or off-origin (the fallback logs a warning).
         */
        fun loadStart()

        /** The current page's path, without query or fragment (PROTOCOL 4.4). */
        fun currentPage(): String?

        /**
         * Screenshot (P3.13): draw the dashboard into a bitmap and JPEG-encode
         * it, or return null when the dashboard has nothing to show (no view
         * yet, zero size). [quality] is the command's JPEG quality hint.
         * The result must be produced before the command's result is sent
         * (PROTOCOL 5), so this suspends instead of posting.
         */
        suspend fun capture(quality: Int?): ByteArray?
    }

    @Volatile
    private var handle: Handle? = null

    private val pageListeners = CopyOnWriteArrayList<(String?) -> Unit>()

    /** The activity calls this when its dashboard WebView is up. */
    fun attach(handle: Handle) {
        this.handle = handle
    }

    /** And this when it goes away; a stale handle is never called again. */
    fun detach(handle: Handle) {
        if (this.handle === handle) this.handle = null
    }

    /** False when no dashboard is showing; the command then fails (PROTOCOL 6). */
    fun reload(): Boolean = act { it.reload() }

    /** False when no dashboard is showing; the command then fails (PROTOCOL 6). */
    fun clearCache(): Boolean = act { it.clearCache() }

    /** False when no dashboard is showing; the command then fails (PROTOCOL 6). */
    fun load(url: String): Boolean = act { it.load(url) }

    /** False when no dashboard is showing; the command then fails (PROTOCOL 6). */
    fun loadStart(): Boolean = act { it.loadStart() }

    /** The path to report as current_page; null while no dashboard is up. */
    fun currentPage(): String? = handle?.currentPage()

    /**
     * The dashboard's capture, or null when no dashboard is showing (P3.13).
     * Unlike reload/clear_cache there is no error reply here: the screenshot
     * control decides what a missing capture means.
     */
    suspend fun capture(quality: Int?): ByteArray? = handle?.capture(quality)

    /**
     * Called by the dashboard whenever the page changes, so the state source
     * can push a `current_page` delta (F-NAV-02). Called from the main thread;
     * listeners must be quick and thread-safe.
     */
    fun pageChanged(path: String?) {
        pageListeners.forEach { it(path) }
    }

    /** Register a page-change listener; the returned function unregisters it. */
    fun onPageChanged(listener: (String?) -> Unit): () -> Unit {
        pageListeners.add(listener)
        return { pageListeners.remove(listener) }
    }

    private inline fun act(run: (Handle) -> Unit): Boolean {
        val current = handle ?: return false
        run(current)
        return true
    }
}
