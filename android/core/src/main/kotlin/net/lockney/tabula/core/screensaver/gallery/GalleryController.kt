package net.lockney.tabula.core.screensaver.gallery

import kotlin.random.Random
import net.lockney.tabula.core.Constants
import net.lockney.tabula.core.settings.ScreensaverPhotosFit
import net.lockney.tabula.core.settings.ScreensaverPhotosSource
import net.lockney.tabula.core.settings.ScreensaverPhotosTransition

/**
 * The photo slideshow's state machine (F-SSV-12, packet P3.8), free of Android
 * so it is tested on a plain JVM. The app lists the source's files or fetches
 * the URL photo, decodes each slide at panel resolution and renders it; this
 * class owns the order, the cadence, the URL history and the bitmap-release
 * invariant.
 *
 * - The playlist is fixed when the slideshow starts (F-SSV-12: new files
 *   appear the next time); [PhotoPlaylist] holds the order.
 * - Each slide's cadence restarts when it shows, and an edge tap restarts it
 *   too (F-SSV-12). A failed fetch or decode keeps the current photo and
 *   moves the next attempt one interval away (F-SSV-12 "retries later").
 * - The URL source fetches fresh every slide (F-SSV-12) and keeps a small
 *   history ([Constants.GALLERY_URL_HISTORY]) that edge taps step through
 *   without re-fetching.
 * - Release invariant: the outcome that shows slide N+1 also releases slide
 *   N's bitmap — immediately for the no-transition case, after the
 *   transition for the others — so at most two decoded slides are alive at
 *   once, and only one once a transition has settled (F-SSV-12, low RAM).
 *
 * The app drops superseded decodes and fetches by generation (like the media
 * player, P2.5), so a late callback never reaches this class; nothing here
 * needs to identify which request a callback belongs to.
 *
 * Thread-safe. Times are epoch milliseconds from the injected clock.
 */
class GalleryController(
    private val now: () -> Long,
    private val random: Random = Random.Default,
) {
    /** What the app must do to the real decoder, fetcher and view. */
    sealed interface Effect {
        /** Fetch the URL source's next photo fresh (F-SSV-12); answer with [onFetched] or [onFetchFailed]. */
        data class Fetch(val url: String) : Effect

        /** Decode [path] at panel resolution; answer with [onDecoded] or [onDecodeFailed]. */
        data class Decode(val path: String, val width: Int, val height: Int) : Effect

        /** Render the decoded slide. [details] is null unless the settings ask for the EXIF overlay (F-SSV-12). */
        data class Show(
            val handle: Any,
            val transition: SlideTransition,
            val fit: FitPlan,
            val details: PhotoDetails?,
        ) : Effect

        /** Release the replaced slide's bitmap: now, or when the running transition ends. */
        data class Release(val handle: Any, val afterTransition: Boolean) : Effect
    }

    data class Outcome(val effects: List<Effect> = emptyList())

    /** One run's settings and inputs; the file entries are already listed and sorted. */
    data class Config(
        val source: ScreensaverPhotosSource,
        val intervalMs: Long,
        val transition: ScreensaverPhotosTransition,
        val fit: ScreensaverPhotosFit,
        val exif: Boolean,
        val width: Int,
        val height: Int,
        val url: String = "",
        val files: List<String> = emptyList(),
        val shuffle: Boolean = false,
    )

    private class Slide(val handle: Any)

    private var config: Config? = null
    private var playlist: PhotoPlaylist? = null
    private var running = false
    private var loading = false
    private var current: Slide? = null
    private var dueMs: Long? = null

    // the URL source's history, oldest first; the cursor is the showing entry's index
    private val history = ArrayDeque<String>()
    private var cursor = 0

    /** Whether [start] found content to show (the app falls back to the clock otherwise, ADR 0014). */
    @get:Synchronized
    val started: Boolean
        get() = running

    /** When the next slide advance is due, or null while not running (the engine's wait). */
    @get:Synchronized
    val nextDueMs: Long?
        get() = dueMs

    /** True while a fetch or decode is in flight: the engine's loop waits on its wake channel instead of spinning. */
    @get:Synchronized
    val busy: Boolean
        get() = running && loading

    /** Begin the slideshow: decode (or fetch) the first slide. Returns empty when the source has no content. */
    @Synchronized
    fun start(config: Config): Outcome {
        this.config = config
        playlist = null
        history.clear()
        cursor = 0
        current = null
        loading = false
        when (config.source) {
            ScreensaverPhotosSource.Folder, ScreensaverPhotosSource.Picker -> {
                val list = PhotoPlaylist(config.files, config.shuffle, random)
                val first = list.current() ?: return Outcome().also { running = false }
                playlist = list
                running = true
                loading = true
                dueMs = now() + config.intervalMs
                return Outcome(listOf(Effect.Decode(first, config.width, config.height)))
            }

            ScreensaverPhotosSource.Url -> {
                if (config.url.isBlank()) return Outcome().also { running = false }
                running = true
                loading = true
                dueMs = now() + config.intervalMs
                return Outcome(listOf(Effect.Fetch(config.url)))
            }

            // P3.9's source; until it ships the app shows the clock stand-in (ADR 0013 item 3)
            ScreensaverPhotosSource.MediaBrowser -> return Outcome().also { running = false }
        }
    }

    /** The URL fetch succeeded: [path] holds the fresh photo (F-SSV-12). */
    @Synchronized
    fun onFetched(path: String): Outcome {
        if (!running || !loading) return Outcome()
        history.addLast(path)
        while (history.size > Constants.GALLERY_URL_HISTORY) {
            history.removeFirst()
            cursor = (cursor - 1).coerceAtLeast(0)
        }
        cursor = history.size - 1
        return Outcome(listOf(Effect.Decode(path, config?.width ?: 0, config?.height ?: 0)))
    }

    /** The URL fetch failed: the current photo stays and the next tick fetches fresh again (F-SSV-12). */
    @Synchronized
    fun onFetchFailed(): Outcome = failed()

    /** The decode succeeded: show it and release the slide it replaces (the release invariant). */
    @Synchronized
    fun onDecoded(handle: Any, photoW: Int, photoH: Int, details: PhotoDetails?): Outcome {
        if (!running || !loading) return Outcome()
        val config = config ?: return Outcome()
        loading = false
        val transition = transitionFor(config.transition, random)
        val fit = fitFor(config.fit, photoW, photoH, config.width, config.height)
        val old = current
        current = Slide(handle)
        // the interval restarts when a slide shows (ADR 0014)
        dueMs = now() + config.intervalMs
        val effects = buildList {
            // the replaced slide's release precedes the show of its successor;
            // for `none` it is immediate, for a transition it lands when the
            // transition ends (the soonest the old bitmap is unneeded)
            old?.let { add(Effect.Release(it.handle, afterTransition = transition != SlideTransition.NONE)) }
            add(Effect.Show(handle, transition, fit, if (config.exif) details else null))
        }
        return Outcome(effects)
    }

    /** The decode failed: the current photo stays and the next tick moves on (F-SSV-12). */
    @Synchronized
    fun onDecodeFailed(): Outcome = failed()

    /** The clock moved on: advance when the interval has passed and nothing is loading. */
    @Synchronized
    fun onTick(): Outcome {
        if (!running || loading) return Outcome()
        val due = dueMs ?: return Outcome()
        if (now() < due) return Outcome()
        return advance()
    }

    /**
     * An edge-zone tap (F-SSV-12): one slide back or forward, and the interval
     * restarts. A load already in flight is superseded — the app's generation
     * check drops its late callbacks — and the URL source steps through its
     * history without re-fetching, fetching fresh only at the newest end.
     */
    @Synchronized
    fun onEdgeTap(zone: EdgeZone): Outcome {
        if (!running) return Outcome()
        val config = config ?: return Outcome()
        val forward = zone == EdgeZone.RIGHT
        val effects = when (config.source) {
            ScreensaverPhotosSource.Folder, ScreensaverPhotosSource.Picker -> {
                val next = playlist?.step(forward) ?: return Outcome()
                listOf(Effect.Decode(next, config.width, config.height))
            }

            ScreensaverPhotosSource.Url ->
                when {
                    !forward && cursor > 0 -> {
                        cursor -= 1
                        listOf(Effect.Decode(history[cursor], config.width, config.height))
                    }

                    forward && cursor < history.size - 1 -> {
                        cursor += 1
                        listOf(Effect.Decode(history[cursor], config.width, config.height))
                    }

                    forward -> listOf(Effect.Fetch(config.url))

                    else -> return Outcome() // nothing earlier in the history
                }

            ScreensaverPhotosSource.MediaBrowser -> emptyList()
        }
        if (effects.isEmpty()) return Outcome()
        loading = true
        // the interval restarts (F-SSV-12)
        dueMs = now() + config.intervalMs
        return Outcome(effects)
    }

    /** Stop the slideshow, releasing the showing slide's bitmap at once. */
    @Synchronized
    fun stop(): Outcome {
        if (!running) return Outcome()
        running = false
        loading = false
        dueMs = null
        val shown = current
        current = null
        return Outcome(listOfNotNull(shown?.let { Effect.Release(it.handle, afterTransition = false) }))
    }

    /** The next slide's request; only called past the interval. */
    private fun advance(): Outcome {
        val config = config ?: return Outcome()
        return when (config.source) {
            ScreensaverPhotosSource.Folder, ScreensaverPhotosSource.Picker -> {
                val next = playlist?.step(true) ?: return Outcome()
                loading = true
                Outcome(listOf(Effect.Decode(next, config.width, config.height)))
            }

            // every slide is a fresh fetch (F-SSV-12)
            ScreensaverPhotosSource.Url -> {
                loading = true
                Outcome(listOf(Effect.Fetch(config.url)))
            }

            ScreensaverPhotosSource.MediaBrowser -> Outcome()
        }
    }

    /** A fetch or decode failed: keep the current photo, retry one interval later (F-SSV-12). */
    private fun failed(): Outcome {
        if (!running || !loading) return Outcome()
        loading = false
        dueMs = now() + (config?.intervalMs ?: 0)
        return Outcome()
    }
}
