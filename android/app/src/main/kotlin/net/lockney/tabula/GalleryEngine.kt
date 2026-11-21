package net.lockney.tabula

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import net.lockney.tabula.core.Constants
import net.lockney.tabula.core.screensaver.gallery.EdgeZone
import net.lockney.tabula.core.screensaver.gallery.GalleryController
import net.lockney.tabula.core.screensaver.gallery.PhotoDetails
import net.lockney.tabula.core.settings.ScreensaverPhotosSource

/**
 * The photo slideshow (packet P3.8): carries out [GalleryController]'s
 * decisions - decoding each slide at panel resolution, fetching the URL
 * source's photo fresh each slide, rendering each show, and releasing the
 * replaced slide's bitmap once its transition has ended (ADR 0016).
 *
 * Ordering is what makes the release safe (ADR 0016 item 4): a show renders
 * before any release in the same outcome is applied - the render runs
 * synchronously on the main thread, so the old slide's layer is cleared or
 * reused before its bitmap is recycled - and every new show flushes the
 * releases still waiting out an earlier transition, whose layers that draw
 * has just cleared, so at most two decoded bitmaps are alive at once even
 * under rapid taps. GALLERY_RELEASE_MARGIN_MS covers the animation's start
 * lag and the main thread's jitter past the transition itself.
 *
 * Loads are generation-counted like the media player's (P2.5): an edge tap or
 * a stop supersedes a fetch or decode still in flight; the superseded load's
 * late callbacks are dropped - its bitmap released, never rendered - so a
 * replaced load can never show over the newer one.
 *
 * The slideshow pauses while Tabula's window is not in front (F-SSV-02's
 * signals; a powered-off panel stops the activity), so no decode or fetch
 * runs against a dark screen.
 */
class GalleryEngine(
    private val controller: GalleryController,
    private val decode: suspend (path: String, width: Int, height: Int) -> DecodedPhoto?,
    private val fetch: suspend (url: String) -> String?,
    private val release: (Any) -> Unit,
    private val render: (GalleryController.Effect.Show) -> Unit,
    private val scope: CoroutineScope,
    private val onDeadEnd: () -> Unit = {},
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** One decoded photo: the bitmap (opaque to the core), its size and its EXIF details. */
    data class DecodedPhoto(val handle: Any, val width: Int, val height: Int, val details: PhotoDetails?)

    /** Wakes the slide timer to recompute the deadline. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private val generation = AtomicInteger(0)
    private var timer: Job? = null
    private var loadJob: Job? = null

    /** Handles waiting out a transition before their release applies. */
    private val pendingReleases = mutableListOf<Any>()

    /** False while Tabula's window is not in front: no ticks, no loads (F-SSV-02). */
    @Volatile
    private var active = true

    private var config: GalleryController.Config? = null
    private var shown = 0
    private var failures = 0
    private var deadEnded = false

    /**
     * Begin the slideshow; false when the controller found no content, in
     * which case the caller falls back to the clock stand-in (ADR 0016).
     */
    fun start(config: GalleryController.Config): Boolean {
        this.config = config
        shown = 0
        failures = 0
        deadEnded = false
        carryOut(controller.start(config))
        if (!controller.started) return false
        timer = scope.launch {
            while (true) {
                // a pause or a load in flight holds the cadence; wait for its
                // outcome rather than spinning on a time that cannot pass yet
                if (!active || controller.busy) {
                    wake.receive()
                    continue
                }
                val due = controller.nextDueMs ?: return@launch
                val wait = due - now()
                if (wait > 0 && withTimeoutOrNull(wait) { wake.receive() } != null) continue
                carryOut(controller.onTick())
            }
        }
        return true
    }

    /** Pause or resume: no slide advances while Tabula's window is not in front (F-SSV-02). */
    fun setActive(on: Boolean) {
        active = on
        wake.trySend(Unit)
    }

    /** An edge-zone tap (F-SSV-12): step one slide and restart the interval. */
    fun step(zone: EdgeZone) = carryOut(controller.onEdgeTap(zone))

    /** Stop the slideshow; everything decoded is released at once. */
    fun stop() {
        generation.incrementAndGet()
        timer?.cancel()
        timer = null
        loadJob?.cancel()
        loadJob = null
        carryOut(controller.stop())
        // transitions that will never be seen must not hold their bitmaps
        flushPendingReleases()
    }

    /**
     * Carry one outcome out. Shows render before releases apply (the draw
     * clears or reuses the old layers first, ADR 0016 item 4), and a show
     * flushes the releases still pending from an earlier transition - that
     * draw has just cleared their layers - so the two-bitmap bound holds
     * even when slides change faster than a transition ends.
     */
    @Synchronized
    private fun carryOut(outcome: GalleryController.Outcome) {
        val releases = mutableListOf<GalleryController.Effect.Release>()
        var showed = false
        for (effect in outcome.effects) {
            when (effect) {
                is GalleryController.Effect.Fetch -> {
                    val gen = generation.incrementAndGet()
                    loadJob = scope.launch { loadFetch(gen, effect.url) }
                }

                is GalleryController.Effect.Decode -> {
                    val gen = generation.incrementAndGet()
                    loadJob = scope.launch { loadDecode(gen, effect) }
                }

                is GalleryController.Effect.Show -> {
                    render(effect)
                    shown += 1
                    showed = true
                }

                is GalleryController.Effect.Release -> releases += effect
            }
        }
        if (showed) flushPendingReleases()
        for (release in releases) scheduleRelease(release.handle, release.afterTransition)
        wake.trySend(Unit)
    }

    private suspend fun loadFetch(gen: Int, url: String) {
        val path = try {
            fetch(url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null // a failed fetch keeps the current photo (F-SSV-12)
        }
        if (generation.get() != gen) return
        carryOut(if (path != null) controller.onFetched(path) else controller.onFetchFailed())
    }

    private suspend fun loadDecode(gen: Int, effect: GalleryController.Effect.Decode) {
        val photo = try {
            decode(effect.path, effect.width, effect.height)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null // a failed decode keeps the current photo (F-SSV-12)
        }
        if (generation.get() != gen) {
            // superseded: its bitmap is nobody's now - release it, never render it
            photo?.let { release(it.handle) }
            return
        }
        if (photo == null) {
            carryOut(controller.onDecodeFailed())
            maybeDeadEnd()
        } else {
            carryOut(controller.onDecoded(photo.handle, photo.width, photo.height, photo.details))
        }
    }

    /**
     * A file playlist in which every photo has failed to decode and nothing
     * has ever shown (an HEIC folder on an old build, AndroidGallery.kt) is
     * a dead screen; the caller falls back to the clock stand-in (ADR 0016
     * item 1). Any success shows a slide and ends the condition for good.
     */
    private fun maybeDeadEnd() {
        val config = config ?: return
        if (config.source == ScreensaverPhotosSource.Url) return
        failures += 1
        if (!deadEnded && shown == 0 && config.files.isNotEmpty() && failures >= config.files.size) {
            deadEnded = true
            onDeadEnd()
        }
    }

    private fun flushPendingReleases() {
        val pending = synchronized(pendingReleases) {
            val copy = pendingReleases.toList()
            pendingReleases.clear()
            copy
        }
        pending.forEach(release)
    }

    private fun scheduleRelease(handle: Any, afterTransition: Boolean) {
        if (!afterTransition) {
            release(handle)
            return
        }
        synchronized(pendingReleases) { pendingReleases += handle }
        scope.launch {
            delay(Constants.GALLERY_TRANSITION_MS + Constants.GALLERY_RELEASE_MARGIN_MS)
            val due = synchronized(pendingReleases) { pendingReleases.remove(handle) }
            if (due) release(handle)
        }
    }
}
