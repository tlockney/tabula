package net.lockney.tabula

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import net.lockney.tabula.core.screensaver.clock.ClockBackgroundController

/**
 * The background engine's carry-out against a fake loader and a virtual
 * clock (F-SSV-09, packet P3.3): show-then-release ordering, a failed load
 * keeps the previous image, the re-fetch interval drives URL reloads, a
 * settings write always reloads (identical source or not), superseded loads
 * are released instead of shown, and the pause holds the cadence.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ClockBackgroundEngineTest {
    private val scope = TestScope()
    private val t0 = 1_800_000_000_000L
    private fun now(): Long = t0 + scope.testScheduler.currentTime

    private data class FakeBitmap(val id: Int)

    private var nextId = 0
    private fun bitmap() = FakeBitmap(nextId++)

    /** What the fake loader answers per source; a source missing here fails. */
    private val loads = mutableMapOf<String, Any?>()

    /** Sources the fake loader holds until [releaseSource] lets them finish. */
    private val gates = mutableMapOf<String, CompletableDeferred<Unit>>()

    /** What the engine showed, in order (null clears to the background color). */
    private val shown = mutableListOf<Any?>()

    /** What the engine released, in order. */
    private val released = mutableListOf<Any>()

    /** Shows and releases in the order they happened, for ordering tests. */
    private val events = mutableListOf<String>()

    private val engine = ClockBackgroundEngine(
        controller = ClockBackgroundController(::now),
        load = { source, _, _ ->
            // capture the answer before the gate, so a superseded load
            // returns what it fetched, not what replaced it
            val answer = loads[source]
            gates.remove(source)?.await()
            answer
        },
        show = {
            shown += it
            events += "show"
        },
        release = {
            released += it
            events += "release"
        },
        scope = scope,
        now = ::now,
    )

    private val url = "http://ha.local/cam.jpg"

    @Test
    fun `a URL loads at start, shows, and re-fetches per the interval`() {
        loads[url] = bitmap()
        engine.start(url, 60_000, 1920, 1200)
        scope.runCurrent()
        assertEquals(1, shown.size)

        loads[url] = bitmap()
        scope.advanceTimeBy(60_000)
        scope.runCurrent()
        assertEquals(2, shown.size)
        // the replaced image is released only after the new one drew
        assertEquals(listOf("show", "show", "release"), events)
        assertEquals(listOf(shown[0]), released)
    }

    @Test
    fun `a failed load keeps the previous image and retries an interval later`() {
        loads[url] = bitmap()
        engine.start(url, 60_000, 1920, 1200)
        scope.runCurrent()
        assertEquals(1, shown.size)

        // the re-fetch fails: nothing shows, nothing releases
        loads[url] = null
        scope.advanceTimeBy(60_000)
        scope.runCurrent()
        assertEquals(1, shown.size)
        assertEquals(0, released.size)

        // the next interval's retry succeeds and replaces the image
        loads[url] = bitmap()
        scope.advanceTimeBy(60_000)
        scope.runCurrent()
        assertEquals(2, shown.size)
        assertEquals(listOf(shown[0]), released)
    }

    @Test
    fun `a settings write always reloads, identical source or not`() {
        loads[url] = bitmap()
        engine.start(url, 0, 1920, 1200)
        scope.runCurrent()
        assertEquals(1, shown.size)

        // the same URL: still a fresh load (F-SSV-09)
        loads[url] = bitmap()
        engine.applySettings(url, 0, 1920, 1200)
        scope.runCurrent()
        assertEquals(2, shown.size)

        // a blank source clears: show(null) precedes the release
        engine.applySettings("", 0, 1920, 1200)
        scope.runCurrent()
        assertEquals(listOf<Any?>(shown[0], shown[1], null), shown)
        assertEquals(listOf(shown[0], shown[1]), released)
    }

    @Test
    fun `a superseded load is released, never shown`() {
        val gate = CompletableDeferred<Unit>()
        loads[url] = bitmap()
        gates[url] = gate
        engine.start(url, 0, 1920, 1200)
        scope.runCurrent()
        val gated = loads[url]

        // the write supersedes the gated load and finishes first
        val fresh = bitmap()
        loads[url] = fresh
        engine.applySettings(url, 0, 1920, 1200)
        scope.runCurrent()
        assertEquals(listOf<Any?>(fresh), shown, "only the fresh load's image shows")

        // the superseded load answers late: released, never shown
        gate.complete(Unit)
        scope.runCurrent()
        assertEquals(listOf<Any?>(fresh), shown)
        assertEquals(listOf(gated), released)
    }

    @Test
    fun `stopping shows nothing over the face and releases the current image`() {
        loads[url] = bitmap()
        engine.start(url, 0, 1920, 1200)
        scope.runCurrent()
        val handle = shown.single()

        engine.stop()
        assertEquals(listOf<Any?>(handle, null), shown)
        assertEquals(listOf(handle), released)

        // no re-fetch fires after the stop (the engine is not started)
        loads[url] = bitmap()
        scope.advanceTimeBy(120_000)
        scope.runCurrent()
        assertEquals(2, shown.size)
    }

    @Test
    fun `the pause holds the cadence and resumes where it was`() {
        loads[url] = bitmap()
        engine.start(url, 60_000, 1920, 1200)
        scope.runCurrent()
        assertEquals(1, shown.size)

        // not in front (F-SSV-02): the interval passes without a re-fetch
        engine.setActive(false)
        loads[url] = bitmap()
        scope.advanceTimeBy(60_000)
        scope.runCurrent()
        assertEquals(1, shown.size, "no re-fetch while Tabula is not in front")

        // back in front: the past interval fires at once
        engine.setActive(true)
        scope.runCurrent()
        assertEquals(2, shown.size)
    }

    @Test
    fun `an interval added by a settings write re-fetches`() {
        loads[url] = bitmap()
        engine.start(url, 0, 1920, 1200)
        scope.runCurrent()
        assertEquals(1, shown.size)

        // the write adds the interval and still reloads (F-SSV-09)
        loads[url] = bitmap()
        engine.applySettings(url, 60_000, 1920, 1200)
        scope.runCurrent()
        assertEquals(2, shown.size)

        // the timer the write scheduled fires (the review's probe)
        loads[url] = bitmap()
        scope.advanceTimeBy(60_000)
        scope.runCurrent()
        assertEquals(3, shown.size, "the interval added by the write must re-fetch")
    }

    @Test
    fun `an interval removed by a settings write stops re-fetching`() {
        loads[url] = bitmap()
        engine.start(url, 60_000, 1920, 1200)
        scope.runCurrent()
        assertEquals(1, shown.size)

        // the write removes the interval and still reloads (F-SSV-09)
        loads[url] = bitmap()
        engine.applySettings(url, 0, 1920, 1200)
        scope.runCurrent()
        assertEquals(2, shown.size)

        // no re-fetch past what would have been the old interval
        loads[url] = bitmap()
        scope.advanceTimeBy(120_000)
        scope.runCurrent()
        assertEquals(2, shown.size, "no re-fetch after the write removed the interval")

        // a later write still reloads, and can bring the interval back
        loads[url] = bitmap()
        engine.applySettings(url, 30_000, 1920, 1200)
        scope.runCurrent()
        assertEquals(3, shown.size)
        loads[url] = bitmap()
        scope.advanceTimeBy(30_000)
        scope.runCurrent()
        assertEquals(4, shown.size, "the interval restored by a write re-fetches")
    }

    @Test
    fun `an interval changed by a settings write retimes the re-fetch`() {
        loads[url] = bitmap()
        engine.start(url, 60_000, 1920, 1200)
        scope.runCurrent()
        assertEquals(1, shown.size)

        // the write shortens the interval and reloads; the next re-fetch
        // follows the new interval, not the old one
        loads[url] = bitmap()
        engine.applySettings(url, 30_000, 1920, 1200)
        scope.runCurrent()
        assertEquals(2, shown.size)
        loads[url] = bitmap()
        scope.advanceTimeBy(29_999)
        scope.runCurrent()
        assertEquals(2, shown.size, "no re-fetch before the new interval")
        scope.advanceTimeBy(1)
        scope.runCurrent()
        assertEquals(3, shown.size, "the re-fetch follows the interval the write set")
    }
}
