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

/**
 * The photo slideshow (packet P3.8): carries out [GalleryController]'s
 * decisions - decoding each slide at panel resolution, fetching the URL
 * source's photo fresh each slide, rendering each show, and releasing the
 * replaced slide's bitmap once its transition has ended (ADR 0016: the view
 * clears the old ImageView at the animation's end, and
 * GALLERY_RELEASE_MARGIN_MS covers the jitter, so a recycled bitmap is
 * never drawn).
 *
 * Loads are generation-counted like the media player's (P2.5): an edge tap or
 * a stop supersedes a fetch or decode still in flight, and its late callbacks
 * are dropped, so a replaced load can never show over the newer one.
 */
class GalleryEngine(
    private val controller: GalleryController,
    private val decode: suspend (path: String, width: Int, height: Int) -> DecodedPhoto?,
    private val fetch: suspend (url: String) -> String?,
    private val release: (Any) -> Unit,
    private val render: (GalleryController.Effect.Show) -> Unit,
    private val scope: CoroutineScope,
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

    /**
     * Begin the slideshow; false when the controller found no content, in
     * which case the caller falls back to the clock stand-in (ADR 0016).
     */
    fun start(config: GalleryController.Config): Boolean {
        carryOut(controller.start(config))
        if (!controller.started) return false
        timer = scope.launch {
            while (true) {
                // a load in flight holds the cadence; wait for its outcome
                // rather than spinning on a due time that cannot advance yet
                if (controller.busy) {
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
        val pending = synchronized(pendingReleases) {
            val copy = pendingReleases.toList()
            pendingReleases.clear()
            copy
        }
        pending.forEach(release)
    }

    @Synchronized
    private fun carryOut(outcome: GalleryController.Outcome) {
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

                is GalleryController.Effect.Show -> render(effect)
                is GalleryController.Effect.Release -> scheduleRelease(effect.handle, effect.afterTransition)
            }
        }
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
        if (generation.get() != gen) return
        carryOut(
            if (photo != null) {
                controller.onDecoded(photo.handle, photo.width, photo.height, photo.details)
            } else {
                controller.onDecodeFailed()
            },
        )
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
