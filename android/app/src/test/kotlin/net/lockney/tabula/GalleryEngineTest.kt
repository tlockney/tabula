package net.lockney.tabula

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import net.lockney.tabula.core.Constants
import net.lockney.tabula.core.screensaver.gallery.EdgeZone
import net.lockney.tabula.core.screensaver.gallery.GalleryController
import net.lockney.tabula.core.screensaver.gallery.PhotoDetails
import net.lockney.tabula.core.screensaver.gallery.SlideTransition
import net.lockney.tabula.core.settings.ScreensaverPhotosFit
import net.lockney.tabula.core.settings.ScreensaverPhotosSource
import net.lockney.tabula.core.settings.ScreensaverPhotosTransition

/**
 * The engine's carry-out against a fake decoder and a virtual clock (packet
 * P3.8): decode-to-render ordering, the release ordering and its two-bitmap
 * bound, failure handling, superseded loads, the pause and the dead-end
 * fallback.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class GalleryEngineTest {
    private val scope = TestScope()
    private val t0 = 1_800_000_000_000L
    private fun now(): Long = t0 + scope.testScheduler.currentTime

    private data class FakeBitmap(val id: Int)

    private var nextId = 0
    private fun bitmap() = FakeBitmap(nextId++)

    /** What the engine rendered, in order. */
    private val rendered = mutableListOf<GalleryController.Effect.Show>()

    /** What the engine released, in order. */
    private val released = mutableListOf<Any>()

    /** Renders and releases in the order they happened, for ordering tests. */
    private val events = mutableListOf<String>()

    /** How many times the engine reported a playlist with nothing decodable. */
    private var deadEnds = 0

    /** Decodes this run answers; a path missing here fails the decode. */
    private var decodes = mutableMapOf<String, GalleryEngine.DecodedPhoto?>()

    /** Fetch results in call order; null fails the fetch (F-SSV-12). */
    private val fetches = mutableListOf<String?>()

    private fun engine(controller: GalleryController): GalleryEngine = GalleryEngine(
        controller = controller,
        decode = { path, _, _ -> decodes[path] },
        fetch = { fetches.removeFirst() },
        release = {
            released += it
            events += "release"
        },
        render = {
            rendered += it
            events += "render"
        },
        scope = scope,
        onDeadEnd = { deadEnds += 1 },
        now = ::now,
    )

    private fun photo(path: String) {
        decodes[path] = GalleryEngine.DecodedPhoto(bitmap(), 1800, 1200, null)
    }

    private fun config(
        source: ScreensaverPhotosSource = ScreensaverPhotosSource.Folder,
        transition: ScreensaverPhotosTransition = ScreensaverPhotosTransition.Crossfade,
        files: List<String> = listOf("p1.jpg", "p2.jpg", "p3.jpg"),
        url: String = "http://ha.local/photo.jpg",
    ) = GalleryController.Config(
        source = source,
        intervalMs = 5_000,
        transition = transition,
        fit = ScreensaverPhotosFit.SmartFill,
        exif = true,
        width = 1920,
        height = 1200,
        url = url,
        files = files,
    )

    @Test
    fun `a folder slideshow decodes, renders, advances per interval and releases past the transition`() {
        listOf("p1.jpg", "p2.jpg", "p3.jpg").forEach { photo(it) }
        val engine = engine(GalleryController(::now))
        assertTrue(engine.start(config()))
        scope.runCurrent()

        // the first slide renders at once, and nothing is released yet
        assertEquals(1, rendered.size)
        assertEquals(0, released.size)

        // the interval passes and the next slide shows
        scope.advanceTimeBy(5_000)
        scope.runCurrent()
        assertEquals(2, rendered.size)

        // the replaced slide's bitmap is released only after the transition
        // ran out (the view clears the old image at the animation's end,
        // ADR 0016), and exactly once
        scope.advanceTimeBy(Constants.GALLERY_TRANSITION_MS + Constants.GALLERY_RELEASE_MARGIN_MS - 1)
        scope.runCurrent()
        assertEquals(0, released.size, "no release before the transition and margin have run out")
        scope.advanceTimeBy(1)
        scope.runCurrent()
        assertEquals(listOf(rendered[0].handle), released)

        // stopping releases the showing slide at once, and a pending
        // transition release of the slide before it is flushed too
        engine.stop()
        assertEquals(
            listOf(rendered[0].handle, rendered[1].handle),
            released,
            "stop flushes the pending transition release and the showing slide",
        )
    }

    @Test
    fun `with no transition the new slide renders before the replaced one is released`() {
        listOf("p1.jpg", "p2.jpg").forEach { photo(it) }
        val engine = engine(GalleryController(::now))
        engine.start(config(transition = ScreensaverPhotosTransition.None))
        scope.runCurrent()
        assertEquals(1, rendered.size)
        assertEquals(0, released.size)

        scope.advanceTimeBy(5_000)
        scope.runCurrent()
        assertEquals(2, rendered.size)
        // the render runs before the release applies: the draw clears or
        // reuses the old layer first, so a recycled bitmap is never drawn,
        // not even for one frame (ADR 0016 item 4)
        assertEquals(
            listOf("render", "render", "release"),
            events,
            "release follows its successor's render in the same outcome",
        )
        assertEquals(listOf(rendered[0].handle), released, "'none' releases at once, not on a timer")
    }

    @Test
    fun `shows inside one transition flush the replaced slides' release, keeping the two-bitmap bound`() {
        listOf("p1.jpg", "p2.jpg", "p3.jpg").forEach { photo(it) }
        val engine = engine(GalleryController(::now))
        engine.start(config())
        scope.runCurrent()
        scope.advanceTimeBy(5_000)
        scope.runCurrent() // p2 shows; p1's release is waiting out the transition
        assertEquals(0, released.size)

        // a third slide inside p2's transition window: its draw cancels the
        // running transition and clears or reuses p1's layer, so p1's
        // pending release applies at the show instead of waiting
        engine.step(EdgeZone.RIGHT)
        scope.runCurrent()
        assertEquals(3, rendered.size)
        assertEquals(
            listOf(rendered[0].handle),
            released,
            "the show flushed the release its draw made safe (two-bitmap bound, ADR 0016)",
        )

        // and p2's release still waits out p3's transition
        scope.advanceTimeBy(Constants.GALLERY_TRANSITION_MS + Constants.GALLERY_RELEASE_MARGIN_MS - 1)
        scope.runCurrent()
        assertEquals(listOf(rendered[0].handle), released)
        scope.advanceTimeBy(1)
        scope.runCurrent()
        assertEquals(listOf(rendered[0].handle, rendered[1].handle), released)
    }

    @Test
    fun `a failed decode keeps the current photo and moves on one interval later`() {
        photo("p1.jpg")
        decodes["p2.jpg"] = null // a corrupt photo
        photo("p3.jpg")
        val engine = engine(GalleryController(::now))
        engine.start(config())
        scope.runCurrent()
        assertEquals(1, rendered.size)

        scope.advanceTimeBy(5_000)
        scope.runCurrent()
        assertEquals(1, rendered.size, "the failed decode renders nothing")
        assertEquals(0, released.size, "the current photo is not released by a failure")
        assertEquals(0, deadEnds, "a slideshow that has shown a slide is not a dead end")

        scope.advanceTimeBy(5_000)
        scope.runCurrent()
        assertEquals(2, rendered.size, "the next interval moves on to the next photo")
    }

    @Test
    fun `a failed fetch keeps the current photo and fetches fresh again an interval later`() {
        fetches += "cache/slide-0.jpg"
        fetches += null // the fetch for slide 2 fails
        fetches += "cache/slide-2.jpg"
        photo("cache/slide-0.jpg")
        photo("cache/slide-2.jpg")
        val engine = engine(GalleryController(::now))
        assertTrue(engine.start(config(source = ScreensaverPhotosSource.Url)))
        scope.runCurrent()
        assertEquals(1, rendered.size, "the first fetched slide shows")

        scope.advanceTimeBy(5_000)
        scope.runCurrent()
        assertEquals(1, rendered.size, "the failed fetch keeps the current photo")
        assertEquals(0, released.size)

        scope.advanceTimeBy(5_000)
        scope.runCurrent()
        assertEquals(2, rendered.size, "the retry one interval later fetches fresh and shows")
    }

    @Test
    fun `an edge tap supersedes a decode still in flight and its late answer is released, not rendered`() {
        photo("p1.jpg")
        photo("p3.jpg")
        // p2's decode never completes on its own: it hangs on the gate
        val gate = CompletableDeferred<GalleryEngine.DecodedPhoto>()
        val stale = GalleryEngine.DecodedPhoto(bitmap(), 1800, 1200, null)
        val engine = GalleryEngine(
            controller = GalleryController(::now),
            decode = { path, _, _ -> if (path == "p2.jpg") gate.await() else decodes[path] },
            fetch = { fetches.removeFirst() },
            release = { released += it },
            render = { rendered += it },
            scope = scope,
            now = ::now,
        )
        engine.start(config())
        scope.runCurrent()
        assertEquals(1, rendered.size)

        scope.advanceTimeBy(5_000)
        scope.runCurrent()
        assertEquals(1, rendered.size, "p2's decode hangs on the gate")

        // an edge tap supersedes the hung decode; p3 decodes and shows
        engine.step(EdgeZone.RIGHT)
        scope.runCurrent()
        assertEquals(2, rendered.size, "the stepped slide shows")

        // the superseded decode's late answer is dropped and its bitmap released
        gate.complete(stale)
        scope.runCurrent()
        assertEquals(2, rendered.size, "the superseded load never renders")
        assertEquals(listOf<Any>(stale.handle), released, "the superseded load's bitmap is released (ADR 0016 item 4)")
    }

    @Test
    fun `a superseded decode is released and never rendered while its replacement still loads`() {
        photo("p1.jpg")
        // p2 and p3's decodes both hang: the replacement is still loading when
        // the stale one answers - the only case the engine's generation
        // guard, not the controller's loading flag, decides the outcome
        val staleGate = CompletableDeferred<GalleryEngine.DecodedPhoto>()
        val freshGate = CompletableDeferred<GalleryEngine.DecodedPhoto>()
        val stale = GalleryEngine.DecodedPhoto(bitmap(), 1800, 1200, null)
        val fresh = GalleryEngine.DecodedPhoto(bitmap(), 1800, 1200, null)
        val engine = GalleryEngine(
            controller = GalleryController(::now),
            decode = { path, _, _ ->
                when (path) {
                    "p2.jpg" -> staleGate.await()
                    "p3.jpg" -> freshGate.await()
                    else -> decodes[path]
                }
            },
            fetch = { fetches.removeFirst() },
            release = { released += it },
            render = { rendered += it },
            scope = scope,
            now = ::now,
        )
        engine.start(config())
        scope.runCurrent()
        assertEquals(1, rendered.size)

        scope.advanceTimeBy(5_000)
        scope.runCurrent() // p2's decode hangs
        engine.step(EdgeZone.RIGHT)
        scope.runCurrent() // superseded; p3's decode hangs too
        assertEquals(1, rendered.size)

        staleGate.complete(stale) // the stale answer lands while p3 still loads
        scope.runCurrent()
        assertEquals(1, rendered.size, "the superseded decode never renders over its replacement")
        assertEquals(listOf<Any>(stale.handle), released, "its bitmap is released instead")

        freshGate.complete(fresh)
        scope.runCurrent()
        assertEquals(2, rendered.size)
        assertEquals(listOf<Any>(stale.handle), released, "the showing slide is never released by a supersession")
    }

    @Test
    fun `an empty or unbuilt source does not start and stops cleanly`() {
        val empty = engine(GalleryController(::now))
        assertFalse(empty.start(config(files = emptyList())))
        empty.stop()
        assertEquals(0, rendered.size)
        assertEquals(0, released.size)

        val browser = engine(GalleryController(::now))
        assertFalse(browser.start(config(source = ScreensaverPhotosSource.MediaBrowser)))
    }

    @Test
    fun `the slideshow pauses while Tabula is not in front and resumes where it was`() {
        listOf("p1.jpg", "p2.jpg", "p3.jpg").forEach { photo(it) }
        val engine = engine(GalleryController(::now))
        engine.start(config())
        scope.runCurrent()
        assertEquals(1, rendered.size)

        // F-SSV-02's pause signals: another app in front, or the panel off
        engine.setActive(false)
        scope.advanceTimeBy(20_000)
        scope.runCurrent()
        assertEquals(1, rendered.size, "no slide advances while paused")

        engine.setActive(true)
        scope.runCurrent()
        assertEquals(2, rendered.size, "the slide that came due while paused shows on resume")
        scope.advanceTimeBy(5_000)
        scope.runCurrent()
        assertEquals(3, rendered.size, "the interval runs again afterwards")
    }

    @Test
    fun `a folder whose photos all fail to decode falls back to the clock stand-in`() {
        decodes["p1.jpg"] = null
        decodes["p2.jpg"] = null
        val engine = engine(GalleryController(::now))
        engine.start(config(files = listOf("p1.jpg", "p2.jpg")))
        scope.runCurrent() // p1 fails
        assertEquals(0, deadEnds, "one failure is not a dead end")

        scope.advanceTimeBy(5_000)
        scope.runCurrent() // p2 fails: every photo has failed with nothing shown
        assertEquals(1, deadEnds, "the caller falls back to the clock (ADR 0016 item 1)")
        assertEquals(0, rendered.size)

        // the retries continue, but the fallback fires once
        scope.advanceTimeBy(5_000)
        scope.runCurrent()
        assertEquals(1, deadEnds)
    }

    @Test
    fun `the exif details ride along to the renderer when enabled`() {
        val details = PhotoDetails("2026:03:14 09:26:00", "Canon", "Canon EOS R6")
        decodes["p1.jpg"] = GalleryEngine.DecodedPhoto(bitmap(), 1800, 1200, details)
        val engine = engine(GalleryController(::now))
        engine.start(config())
        scope.runCurrent()
        assertEquals(details, rendered.single().details)
        assertEquals(SlideTransition.CROSSFADE, rendered.single().transition)
    }
}
