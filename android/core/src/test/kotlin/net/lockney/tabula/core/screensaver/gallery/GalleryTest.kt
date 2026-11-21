package net.lockney.tabula.core.screensaver.gallery

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.lockney.tabula.core.settings.ScreensaverPhotosFit
import net.lockney.tabula.core.settings.ScreensaverPhotosTransition
import kotlin.random.Random

/** The gallery's pure selections (packet P3.8): shuffle, transitions, fit, edge zones, EXIF details. */
class GalleryTest {
    // --- PhotoPlaylist (F-SSV-12: built at start; shuffle determinism injectable) ---

    @Test
    fun `a shuffle with the same random is the same order`() {
        val files = listOf("a.jpg", "b.jpg", "c.jpg", "d.jpg")
        val left = PhotoPlaylist(files, shuffle = true, Random(7))
        val right = PhotoPlaylist(files, shuffle = true, Random(7))

        // walk both playlists through their whole cycle
        val l = buildList { repeat(4) { add(left.step(true)!!) } }
        val r = buildList { repeat(4) { add(right.step(true)!!) } }
        assertEquals(l, r, "the same seed must give the same shuffle")
        assertEquals(files.toSet(), l.toSet(), "a shuffle keeps every photo exactly once")
    }

    @Test
    fun `without shuffle the order is the listing, wrapping both ways`() {
        val files = listOf("a.jpg", "b.jpg", "c.jpg")
        val playlist = PhotoPlaylist(files, shuffle = false, Random(0))
        assertEquals("a.jpg", playlist.current())
        assertEquals("b.jpg", playlist.step(true))
        assertEquals("c.jpg", playlist.step(true))
        assertEquals("a.jpg", playlist.step(true), "forward wraps around")
        assertEquals("c.jpg", playlist.step(false), "back wraps around")
    }

    @Test
    fun `an empty playlist steps to nothing`() {
        val playlist = PhotoPlaylist(emptyList(), shuffle = true, Random(1))
        assertTrue(playlist.isEmpty)
        assertNull(playlist.current())
        assertNull(playlist.step(true))
        assertNull(playlist.step(false))
    }

    // --- transition selection (F-SSV-12) ---

    @Test
    fun `each setting maps to its transition, and random picks per slide from all five`() {
        assertEquals(SlideTransition.NONE, transitionFor(ScreensaverPhotosTransition.None, Random(1)))
        assertEquals(SlideTransition.CROSSFADE, transitionFor(ScreensaverPhotosTransition.Crossfade, Random(1)))
        assertEquals(SlideTransition.SLIDE, transitionFor(ScreensaverPhotosTransition.Slide, Random(1)))
        assertEquals(SlideTransition.ZOOM, transitionFor(ScreensaverPhotosTransition.Zoom, Random(1)))
        assertEquals(SlideTransition.PAN_ZOOM, transitionFor(ScreensaverPhotosTransition.PanZoom, Random(1)))
        val picked = buildSet { repeat(50) { add(transitionFor(ScreensaverPhotosTransition.Random, Random(it))) } }
        assertEquals(SlideTransition.entries.toSet(), picked, "random covers every transition")
    }

    // --- fit selection (F-SSV-12) ---

    @Test
    fun `letterbox and fill are fixed, smart fill follows the tolerance`() {
        assertEquals(FitPlan.Kind.LETTERBOX, fitFor(ScreensaverPhotosFit.Letterbox, 4, 3, 16, 10).kind)
        assertEquals(FitPlan.Kind.CROP, fitFor(ScreensaverPhotosFit.Fill, 4, 3, 16, 10).kind)
        // 3:2 on 16:10 is a 6% difference: nearly matching shapes crop a little
        assertEquals(FitPlan.Kind.CROP, fitFor(ScreensaverPhotosFit.SmartFill, 1500, 1000, 1920, 1200).kind)
        // 4:3 on 16:10 differs too much: the whole photo over blurred, darkened bars
        assertEquals(FitPlan.Kind.BLUR_BARS, fitFor(ScreensaverPhotosFit.SmartFill, 1600, 1200, 1920, 1200).kind)
        // a portrait photo on a landscape panel keeps its frame too
        assertEquals(FitPlan.Kind.BLUR_BARS, fitFor(ScreensaverPhotosFit.SmartFill, 1080, 1920, 1920, 1200).kind)
        // degenerate sizes letterbox rather than divide by zero
        assertEquals(FitPlan.Kind.LETTERBOX, fitFor(ScreensaverPhotosFit.SmartFill, 0, 0, 1920, 1200).kind)
    }

    // --- edge-zone classification (F-SSV-12; the zones are 8% wide, ADR 0002) ---

    @Test
    fun `the edge zones are the outer 8 percent and the middle dismisses`() {
        assertNull(edgeZoneAt(0.5f))
        assertNull(edgeZoneAt(0.08f), "the zone boundary itself is not inside")
        assertEquals(EdgeZone.LEFT, edgeZoneAt(0.079f))
        assertEquals(EdgeZone.RIGHT, edgeZoneAt(0.921f))
    }

    // --- EXIF details (F-SSV-12: date taken and camera, no location) ---

    @Test
    fun `the overlay shows the parsed date and the collapsed camera name`() {
        val details = PhotoDetails("2026:03:14 09:26:00", "Canon", "Canon EOS R6")
        assertEquals("14 Mar 2026, 09:26\nCanon EOS R6", details.format(Locale.ENGLISH))
        assertEquals("Canon EOS R6", details.camera(), "a model naming its make is not repeated")
        assertEquals("Sony A7", PhotoDetails(null, "", "Sony A7").camera())
        assertEquals("Nikon D750", PhotoDetails(null, "Nikon", "D750").camera())
    }

    @Test
    fun `photos without exif show no overlay, and bad dates are dropped`() {
        assertNull(PhotoDetails(null, null, null).format(Locale.ENGLISH))
        assertNull(PhotoDetails("", " ", "").format(Locale.ENGLISH))
        assertNull(PhotoDetails("not a date", null, null).format(Locale.ENGLISH))
        assertEquals("Kodak", PhotoDetails("bad date", "Kodak", null).format(Locale.ENGLISH))
    }

    // --- the details overlay's corner (F-WGT-02: it moves out of widgets' way) ---

    @Test
    fun `the details overlay takes the first free corner, bottom first`() {
        assertEquals(PanelCorner.BOTTOM_LEFT, detailsCorner(emptySet()))
        assertEquals(
            PanelCorner.BOTTOM_RIGHT,
            detailsCorner(setOf(PanelCorner.BOTTOM_LEFT, PanelCorner.TOP_RIGHT)),
        )
        assertEquals(
            PanelCorner.TOP_RIGHT,
            detailsCorner(setOf(PanelCorner.BOTTOM_LEFT, PanelCorner.BOTTOM_RIGHT)),
        )
        assertEquals(
            PanelCorner.TOP_LEFT,
            detailsCorner(setOf(PanelCorner.BOTTOM_LEFT, PanelCorner.BOTTOM_RIGHT, PanelCorner.TOP_RIGHT)),
        )
    }
}
