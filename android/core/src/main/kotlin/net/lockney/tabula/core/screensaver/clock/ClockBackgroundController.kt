package net.lockney.tabula.core.screensaver.clock

/**
 * The clock's background-image state machine (F-SSV-09, packet P3.3), free
 * of Android so it is tested on a plain JVM. The app fetches or reads and
 * decodes each [Effect.Load]'s source at panel resolution and answers with
 * [onLoaded] or [onLoadFailed]; this class owns the reload rules:
 *
 * - A settings write always triggers a fresh load, identical source or not
 *   (F-SSV-09: the revision change is the signal; the app calls
 *   [applySettings] on every write while the clock shows).
 * - A failed load keeps the previous image — no effect, nothing cleared —
 *   and retries later: at the next re-fetch tick when an interval is
 *   configured, otherwise on the next settings write or screensaver start
 *   (F-SSV-09; ADR 0018, no new constant: the retry rides the interval the
 *   user already chose, or the write that is F-SSV-09's own reload signal).
 * - The re-fetch interval applies to URLs only (F-SSV-09); a local file is
 *   read at each start and on each write, never on a timer.
 * - A blank source shows nothing over the configured background color
 *   ([Effect.Clear]).
 *
 * The app drops superseded loads by generation (like the media player, P2.5),
 * so a late callback never reaches this class. Thread-safe; times are epoch
 * milliseconds from the injected clock.
 */
class ClockBackgroundController(private val now: () -> Long) {
    /** What the app must do to the real loader and view. */
    sealed interface Effect {
        /** Fetch or read and decode [source] at panel resolution; answer with [onLoaded] or [onLoadFailed]. */
        data class Load(val source: String) : Effect

        /** Show the decoded image behind the face, darkened by the configured strength (F-SSV-09). */
        data class Show(val handle: Any) : Effect

        /** The background left: the configured background color shows (F-SSV-08). */
        data object Clear : Effect
    }

    data class Outcome(val effects: List<Effect> = emptyList())

    private var showing = false
    private var loading = false
    private var source = ""
    private var refreshMs = 0L
    private var dueMs: Long? = null

    /** When the next re-fetch is due, or null while none is (the engine's wait). */
    @get:Synchronized
    val nextDueMs: Long?
        get() = dueMs

    /** True while a load is in flight: the engine waits on its wake channel instead of spinning. */
    @get:Synchronized
    val busy: Boolean
        get() = showing && loading

    /** Begin with [source] (blank: no image) and a URL re-fetch interval in milliseconds (0: never). */
    @Synchronized
    fun start(source: String, refreshMs: Long, nowMs: Long): Outcome {
        showing = true
        loading = false
        return configure(source, refreshMs, nowMs)
    }

    /**
     * A settings write arrived while the clock shows (F-SSV-09): always a
     * fresh load, identical source or not; a blank source clears.
     */
    @Synchronized
    fun applySettings(source: String, refreshMs: Long, nowMs: Long): Outcome {
        if (!showing) return Outcome()
        return configure(source, refreshMs, nowMs)
    }

    /** The load succeeded: show it and schedule the next re-fetch. */
    @Synchronized
    fun onLoaded(handle: Any, nowMs: Long): Outcome {
        if (!showing || !loading) return Outcome()
        loading = false
        dueMs = if (refreshMs > 0 && isUrl(source)) nowMs + refreshMs else null
        return Outcome(listOf(Effect.Show(handle)))
    }

    /** The load failed: the previous image stays and the next attempt waits for a retry (F-SSV-09). */
    @Synchronized
    fun onLoadFailed(nowMs: Long): Outcome {
        if (!showing || !loading) return Outcome()
        loading = false
        dueMs = if (refreshMs > 0 && isUrl(source)) nowMs + refreshMs else null
        return Outcome()
    }

    /** The clock moved on: re-fetch a URL background whose interval has passed. */
    @Synchronized
    fun onTick(nowMs: Long): Outcome {
        val due = dueMs ?: return Outcome()
        if (!showing || loading || nowMs < due) return Outcome()
        loading = true
        return Outcome(listOf(Effect.Load(source)))
    }

    /** The clock left: no loads, no re-fetches. */
    @Synchronized
    fun stop(): Outcome {
        showing = false
        loading = false
        dueMs = null
        return Outcome()
    }

    /** Apply the current source and interval: load, clear, or reschedule. */
    private fun configure(source: String, refreshMs: Long, nowMs: Long): Outcome {
        this.source = source
        this.refreshMs = refreshMs
        dueMs = if (refreshMs > 0 && isUrl(source)) nowMs + refreshMs else null
        if (source.isBlank()) {
            loading = false
            return Outcome(listOf(Effect.Clear))
        }
        loading = true
        return Outcome(listOf(Effect.Load(source)))
    }
}
