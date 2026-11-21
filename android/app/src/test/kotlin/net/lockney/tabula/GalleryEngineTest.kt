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
 * P3.8): decode-to-render ordering, the transition-delayed release, failure
 * handling, superseded loads and the stop flush.
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

    /** Decodes this run answers; a path missing here fails the decode. */
    private var decodes = mutableMapOf<String, GalleryEngine.DecodedPhoto?>()

    /** Fetch results in call order; null fails the fetch (F-SSV-12). */
    private val fetches = mutableListOf<String?>()

    private fun engine(controller: GalleryController): GalleryEngine = GalleryEngine(
        controller = controller,
        decode = { path, _, _ -> decodes[path] },
        fetch = { fetches.removeFirst() },
        release = { released += it },
        render = { rendered += it },
        scope = scope,
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
    fun `with no transition the replaced slide is released at once`() {
        listOf("p1.jpg", "p2.jpg").forEach { photo(it) }
        val engine = engine(GalleryController(::now))
        engine.start(config(transition = ScreensaverPhotosTransition.None))
        scope.runCurrent()
        assertEquals(1, rendered.size)
        assertEquals(0, released.size)

        scope.advanceTimeBy(5_000)
        scope.runCurrent()
        assertEquals(2, rendered.size)
        assertEquals(listOf(rendered[0].handle), released, "'none' releases immediately, not on a timer")
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
    fun `an edge tap supersedes a decode still in flight`() {
        photo("p1.jpg")
        photo("p3.jpg")
        // p2's decode never completes on its own: it hangs on the gate
        val gate = CompletableDeferred<GalleryEngine.DecodedPhoto>()
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

        // the superseded decode's late answer is dropped, not rendered
        gate.complete(GalleryEngine.DecodedPhoto(bitmap(), 1800, 1200, null))
        scope.runCurrent()
        assertEquals(2, rendered.size, "the superseded load never renders")
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
