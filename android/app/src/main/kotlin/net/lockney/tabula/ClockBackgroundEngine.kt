package net.lockney.tabula

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import net.lockney.tabula.core.screensaver.clock.ClockBackgroundController

/**
 * The clock's background-image carrier (packet P3.3): runs
 * [ClockBackgroundController]'s decisions - fetching or reading and decoding
 * each source at panel resolution, showing each decoded image (or clearing
 * to the configured background color), and releasing the bitmap a new image
 * replaces, only after the view has drawn the new one (a recycled bitmap
 * must never be drawn, ADR 0016's rule).
 *
 * Loads are generation-counted like the media player's (P2.5): a settings
 * write or a stop supersedes a load still in flight; the superseded load's
 * late answer is dropped - its bitmap released, never shown. This is what
 * lets [ClockBackgroundController.applySettings] always order a fresh load
 * (F-SSV-09) without waiting on the previous one.
 *
 * The background pauses while Tabula's window is not in front (F-SSV-02's
 * signals; a powered-off panel stops the activity), so no fetch or decode
 * runs against a dark screen.
 */
class ClockBackgroundEngine(
    private val controller: ClockBackgroundController,
    private val load: suspend (source: String, width: Int, height: Int) -> Any?,
    private val show: (Any?) -> Unit,
    private val release: (Any) -> Unit,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Wakes the re-fetch timer to recompute the deadline. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private val generation = AtomicInteger(0)
    private var timer: Job? = null

    /** The image currently shown; the engine releases it when a replacement draws. */
    private var current: Any? = null

    /** False while Tabula's window is not in front: no ticks, no loads (F-SSV-02). */
    @Volatile
    private var active = true

    /** Begin: load the configured source (blank: clear) and run the re-fetch timer. Follows [stop] (the view stops the engine when the mode leaves); anything still shown was already released and cleared there. */
    fun start(source: String, refreshMs: Long, width: Int, height: Int) {
        stopTimer()
        generation.incrementAndGet()
        carryOut(controller.start(source, refreshMs, now()), width, height)
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
                carryOut(controller.onTick(now()), width, height)
            }
        }
    }

    /** A settings write arrived while the clock shows (F-SSV-09): always a fresh load, identical source or not. */
    fun applySettings(source: String, refreshMs: Long, width: Int, height: Int) {
        carryOut(controller.applySettings(source, refreshMs, now()), width, height)
        wake.trySend(Unit)
    }

    /** Pause or resume with the window (F-SSV-02): no re-fetches while Tabula is not in front. */
    fun setActive(on: Boolean) {
        active = on
        wake.trySend(Unit)
    }

    /** Stop: nothing loads, nothing re-fetches, the showing image is released. */
    fun stop() {
        generation.incrementAndGet()
        stopTimer()
        controller.stop()
        clearCurrent()
    }

    private fun carryOut(outcome: ClockBackgroundController.Outcome, width: Int, height: Int) {
        for (effect in outcome.effects) {
            when (effect) {
                is ClockBackgroundController.Effect.Load -> {
                    // the hardware check counts these lines as load evidence (check_p33.sh)
                    // runCatching: android.util.Log is not mocked in the JVM tests
                    runCatching { android.util.Log.i(TAG, "background: load source=${effect.source}") }
                    val gen = generation.incrementAndGet()
                    scope.launch { loadSource(gen, effect.source, width, height) }
                }

                is ClockBackgroundController.Effect.Show -> showImage(effect.handle)

                ClockBackgroundController.Effect.Clear -> clearCurrent()
            }
        }
        wake.trySend(Unit)
    }

    private suspend fun loadSource(gen: Int, source: String, width: Int, height: Int) {
        val loaded = try {
            load(source, width, height)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null // a failed load keeps the previous image (F-SSV-09)
        }
        if (generation.get() != gen) {
            // superseded: its bitmap is nobody's now - release it, never show it
            loaded?.let { release(it) }
            return
        }
        if (loaded == null) {
            // the hardware check counts these lines as failure evidence (check_p33.sh)
            // runCatching: android.util.Log is not mocked in the JVM tests
            runCatching { android.util.Log.w(TAG, "background: load failed; the previous image stays (F-SSV-09)") }
            carryOut(controller.onLoadFailed(now()), width, height)
        } else {
            carryOut(controller.onLoaded(loaded, now()), width, height)
        }
    }

    /** Draw the new image first, then release the one it replaced (ADR 0016's rule). */
    @Synchronized
    private fun showImage(handle: Any) {
        val old = current
        current = handle
        show(handle)
        if (old != null && old !== handle) release(old)
    }

    @Synchronized
    private fun clearCurrent() {
        val old = current
        current = null
        show(null)
        old?.let { release(it) }
    }

    private fun stopTimer() {
        timer?.cancel()
        timer = null
    }

    private companion object {
        const val TAG = "TabulaClock"
    }
}
