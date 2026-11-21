package net.lockney.tabula.core.screensaver.gallery

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.lockney.tabula.core.Constants
import net.lockney.tabula.core.settings.ScreensaverPhotosFit
import net.lockney.tabula.core.settings.ScreensaverPhotosSource
import net.lockney.tabula.core.settings.ScreensaverPhotosTransition

/**
 * The slideshow's state machine against a fake decoder and fake bitmaps
 * (packet P3.8): the playlist order, the release invariant, the URL source's
 * fresh-fetch-per-slide, failure handling, history and edge taps.
 */
class GalleryControllerTest {
    private var t = 0L
    private val now = { t }

    /** A decoded slide, in tests: identity is all the controller needs. */
    private data class FakeBitmap(val id: Int)

    private var nextId = 0
    private fun bitmap() = FakeBitmap(nextId++)

    private fun config(
        source: ScreensaverPhotosSource = ScreensaverPhotosSource.Folder,
        intervalMs: Long = 5_000,
        transition: ScreensaverPhotosTransition = ScreensaverPhotosTransition.Crossfade,
        fit: ScreensaverPhotosFit = ScreensaverPhotosFit.SmartFill,
        exif: Boolean = false,
        url: String = "http://ha.local/photo.jpg",
        files: List<String> = listOf("p1.jpg", "p2.jpg", "p3.jpg"),
        shuffle: Boolean = false,
    ) = GalleryController.Config(
        source = source,
        intervalMs = intervalMs,
        transition = transition,
        fit = fit,
        exif = exif,
        width = 1920,
        height = 1200,
        url = url,
        files = files,
        shuffle = shuffle,
    )

    /** The fake decode of one good photo: answer the outcome's one Decode request. */
    private fun decodeIt(controller: GalleryController, outcome: GalleryController.Outcome): GalleryController.Outcome {
        val decode = outcome.effects.single() as GalleryController.Effect.Decode
        return controller.onDecoded(bitmap(), 1800, 1200, details = null)
    }

    @Test
    fun `a folder playlist starts at the first file and advances one slide per interval`() {
        val controller = GalleryController(now, Random(0))
        val start = controller.start(config())
        assertTrue(controller.started)
        assertEquals("p1.jpg", (start.effects.single() as GalleryController.Effect.Decode).path)

        val shown = decodeIt(controller, start)
        val show = shown.effects.filterIsInstance<GalleryController.Effect.Show>().single()
        assertEquals(FitPlan.Kind.CROP, show.fit.kind, "1800x1200 (3:2) nearly matches a 1920x1200 panel (smart fill)")
        assertEquals(SlideTransition.CROSSFADE, show.transition)

        t += 4_999
        assertEquals(0, controller.onTick().effects.size, "not due yet")
        t += 1
        val requested = controller.onTick()
        assertEquals("p2.jpg", (requested.effects.single() as GalleryController.Effect.Decode).path)
        // the cadence restarts when the slide shows, not when it was requested (ADR 0016)
        decodeIt(controller, requested)
        assertEquals(t + 5_000, controller.nextDueMs)
    }

    @Test
    fun `the release invariant holds - a replaced slide is released with its successor's show, exactly once`() {
        val controller = GalleryController(now, Random(0))
        val released = mutableMapOf<FakeBitmap, Int>()
        val shownHandles = mutableListOf<FakeBitmap>()
        val live = mutableListOf<FakeBitmap>()

        var outcome = controller.start(config())
        repeat(3) {
            val decoded = decodeIt(controller, outcome)
            for (effect in decoded.effects) {
                when (effect) {
                    is GalleryController.Effect.Release -> {
                        val handle = effect.handle as FakeBitmap
                        released[handle] = (released[handle] ?: 0) + 1
                        // a crossfade keeps the old bitmap until the transition ends
                        assertTrue(effect.afterTransition)
                        live.remove(handle)
                    }

                    is GalleryController.Effect.Show -> {
                        val handle = effect.handle as FakeBitmap
                        shownHandles += handle
                        live += handle
                        assertTrue(live.size <= 2, "at most the showing and arriving slides are alive")
                    }

                    else -> {}
                }
            }
            t += 5_000
            outcome = controller.onTick()
        }
        assertEquals(3, shownHandles.size)
        assertEquals(shownHandles.dropLast(1).associateWith { 1 }, released, "every replaced slide released exactly once")
        assertEquals(1, live.size, "only the showing slide survives a settled transition")

        // stopping releases the showing slide at once (F-SSV-12)
        val stopRelease = controller.stop().effects.single() as GalleryController.Effect.Release
        assertFalse(stopRelease.afterTransition)
        assertEquals(shownHandles.last(), stopRelease.handle as FakeBitmap)
        released[stopRelease.handle as FakeBitmap] = 1
        assertEquals(shownHandles.associateWith { 1 }, released, "each slide released exactly once in total")
    }

    @Test
    fun `with no transition the old slide is released before the successor shows`() {
        val controller = GalleryController(now, Random(0))
        val first = decodeIt(controller, controller.start(config(transition = ScreensaverPhotosTransition.None)))
            .effects.filterIsInstance<GalleryController.Effect.Show>().single()
        t += 5_000
        val decoded = decodeIt(controller, controller.onTick())
        val release = assertIs<GalleryController.Effect.Release>(decoded.effects[0])
        val show = assertIs<GalleryController.Effect.Show>(decoded.effects[1])
        assertFalse(release.afterTransition, "'none' releases at once, not after a transition")
        assertEquals(first.handle, release.handle, "the release precedes the show in the same outcome")
        assertTrue(show.handle != first.handle)
    }

    @Test
    fun `a failed decode keeps the current photo and moves on one interval later`() {
        val controller = GalleryController(now, Random(0))
        val showing = decodeIt(controller, controller.start(config()))
            .effects.filterIsInstance<GalleryController.Effect.Show>().single()

        t += 5_000
        assertEquals("p2.jpg", (controller.onTick().effects.single() as GalleryController.Effect.Decode).path)
        val failed = controller.onDecodeFailed()
        assertEquals(0, failed.effects.size, "no new show and no release of the current slide")

        // the retry is a full interval away, not a hot loop, and moves on
        t += 4_999
        assertEquals(0, controller.onTick().effects.size)
        t += 1
        assertEquals("p3.jpg", (controller.onTick().effects.single() as GalleryController.Effect.Decode).path)

        // the current photo is untouched by the failure: stopping releases it
        assertEquals(showing.handle, (controller.stop().effects.single() as GalleryController.Effect.Release).handle)
    }

    @Test
    fun `an empty folder, a blank url and the media_browser source do not start`() {
        val emptyFolder = GalleryController(now)
        emptyFolder.start(config(files = emptyList()))
        assertFalse(emptyFolder.started)

        val emptyPicker = GalleryController(now)
        emptyPicker.start(config(source = ScreensaverPhotosSource.Picker, files = emptyList()))
        assertFalse(emptyPicker.started)

        val noUrl = GalleryController(now)
        noUrl.start(config(source = ScreensaverPhotosSource.Url, url = ""))
        assertFalse(noUrl.started)

        // P3.9's source: until it ships there is no content (ADR 0013 item 3 stand-in)
        val mediaBrowser = GalleryController(now)
        mediaBrowser.start(config(source = ScreensaverPhotosSource.MediaBrowser))
        assertFalse(mediaBrowser.started)
    }

    // --- the URL source (F-SSV-12: fresh per slide, failure keeps, history steps) ---

    /** One fetched-and-shown URL slide; the clock moves one interval first except for the very first. */
    private fun urlSlide(controller: GalleryController, path: String, first: Boolean = false) {
        if (!first) {
            t += 5_000
            assertTrue(controller.onTick().effects.single() is GalleryController.Effect.Fetch, "each slide is a fresh fetch")
        }
        decodeIt(controller, controller.onFetched(path))
    }

    @Test
    fun `the url source fetches fresh every slide`() {
        val controller = GalleryController(now, Random(0))
        val start = controller.start(config(source = ScreensaverPhotosSource.Url))
        assertEquals("http://ha.local/photo.jpg", (start.effects.single() as GalleryController.Effect.Fetch).url)
        urlSlide(controller, "cache/slide-0.jpg", first = true)
        urlSlide(controller, "cache/slide-1.jpg")
        urlSlide(controller, "cache/slide-2.jpg")
    }

    @Test
    fun `a failed fetch keeps the current photo and retries an interval later`() {
        val controller = GalleryController(now, Random(0))
        controller.start(config(source = ScreensaverPhotosSource.Url))
        val showing = decodeIt(controller, controller.onFetched("cache/slide-0.jpg"))
            .effects.filterIsInstance<GalleryController.Effect.Show>().single()

        t += 5_000
        controller.onTick() // the fetch for slide 2 is in flight
        val failed = controller.onFetchFailed()
        assertEquals(0, failed.effects.size, "the current photo stays (F-SSV-12)")
        assertEquals(t + 5_000, controller.nextDueMs, "the retry is one full interval away, not a hot loop")

        // the retry, one interval later, is a fresh fetch (F-SSV-12)
        t += 5_000
        assertEquals("http://ha.local/photo.jpg", (controller.onTick().effects.single() as GalleryController.Effect.Fetch).url)
        // the current slide was never released by the failure: it is still the one showing
        assertEquals(showing.handle, (controller.stop().effects.single() as GalleryController.Effect.Release).handle)
    }

    @Test
    fun `edge taps step back and forward through the history, then fetch fresh at the newest`() {
        val controller = GalleryController(now, Random(0))
        controller.start(config(source = ScreensaverPhotosSource.Url))
        urlSlide(controller, "cache/slide-0.jpg", first = true)
        urlSlide(controller, "cache/slide-1.jpg")
        urlSlide(controller, "cache/slide-2.jpg")

        val back = controller.onEdgeTap(EdgeZone.LEFT)
        assertEquals("cache/slide-1.jpg", (back.effects.single() as GalleryController.Effect.Decode).path)
        decodeIt(controller, back)

        val furtherBack = controller.onEdgeTap(EdgeZone.LEFT)
        assertEquals("cache/slide-0.jpg", (furtherBack.effects.single() as GalleryController.Effect.Decode).path)
        decodeIt(controller, furtherBack)
        assertEquals(0, controller.onEdgeTap(EdgeZone.LEFT).effects.size, "nothing earlier: a no-op, not a dismissal")

        val forward = controller.onEdgeTap(EdgeZone.RIGHT)
        assertEquals("cache/slide-1.jpg", (forward.effects.single() as GalleryController.Effect.Decode).path)
        decodeIt(controller, forward)
        val forward2 = controller.onEdgeTap(EdgeZone.RIGHT)
        assertEquals("cache/slide-2.jpg", (forward2.effects.single() as GalleryController.Effect.Decode).path)
        decodeIt(controller, forward2)
        assertEquals(
            "http://ha.local/photo.jpg",
            (controller.onEdgeTap(EdgeZone.RIGHT).effects.single() as GalleryController.Effect.Fetch).url,
            "forward from the newest end fetches fresh (F-SSV-12)",
        )
    }

    @Test
    fun `the history is bounded and edge taps restart the interval`() {
        val controller = GalleryController(now, Random(0))
        controller.start(config(source = ScreensaverPhotosSource.Url))
        for (i in 0 until Constants.GALLERY_URL_HISTORY + 2) {
            urlSlide(controller, "cache/slide-$i.jpg", first = i == 0)
        }
        // stepping back through the whole (trimmed) ring never runs off the front
        repeat(Constants.GALLERY_URL_HISTORY) {
            val outcome = controller.onEdgeTap(EdgeZone.LEFT)
            if (outcome.effects.isNotEmpty()) decodeIt(controller, outcome)
        }
        assertEquals(0, controller.onEdgeTap(EdgeZone.LEFT).effects.size)

        // the interval restarts on every edge tap (F-SSV-12)
        t += 123_456
        controller.onEdgeTap(EdgeZone.RIGHT)
        assertEquals(t + 5_000, controller.nextDueMs)
    }

    @Test
    fun `a folder edge tap steps the playlist and restarts the interval`() {
        val controller = GalleryController(now, Random(0))
        decodeIt(controller, controller.start(config(transition = ScreensaverPhotosTransition.None)))

        val back = controller.onEdgeTap(EdgeZone.LEFT)
        assertEquals("p3.jpg", (back.effects.single() as GalleryController.Effect.Decode).path, "back wraps around")
        val forward = controller.onEdgeTap(EdgeZone.RIGHT)
        assertEquals("p1.jpg", (forward.effects.single() as GalleryController.Effect.Decode).path)
        assertEquals(t + 5_000, controller.nextDueMs, "the interval restarted")
    }

    @Test
    fun `the exif overlay is carried only when the settings ask for it`() {
        val details = PhotoDetails("2026:03:14 09:26:00", "Canon", "Canon EOS R6")
        val on = GalleryController(now, Random(0))
        on.start(config(exif = true))
        assertEquals(
            details,
            on.onDecoded(bitmap(), 100, 100, details)
                .effects.filterIsInstance<GalleryController.Effect.Show>().single().details,
        )

        val off = GalleryController(now, Random(0))
        off.start(config(exif = false))
        assertNull(
            off.onDecoded(bitmap(), 100, 100, details)
                .effects.filterIsInstance<GalleryController.Effect.Show>().single().details,
        )
    }

    @Test
    fun `stopping an unstarted or already stopped controller is a no-op`() {
        val controller = GalleryController(now, Random(0))
        assertEquals(0, controller.stop().effects.size)
        controller.start(config(files = emptyList()))
        assertEquals(0, controller.stop().effects.size)
    }
}
