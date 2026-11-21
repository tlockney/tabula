package net.lockney.tabula

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import java.io.File
import java.util.Locale
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.lockney.tabula.core.Constants
import net.lockney.tabula.core.screensaver.gallery.FitPlan
import net.lockney.tabula.core.screensaver.gallery.GalleryController
import net.lockney.tabula.core.screensaver.gallery.PanelCorner
import net.lockney.tabula.core.screensaver.gallery.SlideTransition
import net.lockney.tabula.core.screensaver.gallery.detailsCorner
import net.lockney.tabula.core.screensaver.gallery.edgeZoneAt
import net.lockney.tabula.core.settings.ScreensaverPhotos
import net.lockney.tabula.core.settings.ScreensaverPhotosSource

/**
 * The photo slideshow's overlay (packet P3.8, F-SSV-12), drawn over the whole
 * window with plain framework views (ADR 0013). It reads the photos settings
 * each time it shows (a settings edit applies the next time the screensaver
 * starts; live reconfiguration arrives with the schedule packet, F-SSV-18),
 * builds the engine and renders each slide:
 *
 * - Fit: letterbox centers the photo on black; a crop covers the panel; smart
 *   fill's bar case puts a blurred, darkened enlargement of the photo behind
 *   the whole photo (F-SSV-12).
 * - Transitions: none, crossfade, slide, zoom, and pan-and-zoom, which fades
 *   in and then pans and zooms slowly across the slide's display (ADR 0016).
 * - The EXIF details overlay (F-SSV-12) sits in a free corner, bottom first;
 *   corner widgets (P3.11) take priority and push it aside (F-WGT-02).
 *
 * The overlay takes every touch so nothing reaches the dashboard; edge taps
 * step slides (F-SSV-04's exception) and the activity dismisses on any other
 * tap. The replaced slide's bitmap is released once its transition has run
 * out (the engine holds it GALLERY_RELEASE_MARGIN_MS past the animation,
 * ADR 0016, and renders synchronously before any release applies, so a
 * recycled bitmap is never drawn).
 */
@SuppressLint("ViewConstructor")
class GalleryView(
    context: Context,
    private val scope: CoroutineScope,
    private val clockStandIn: () -> Unit,
) : FrameLayout(context) {
    /** Smart fill's bars: a blurred, darkened enlargement of the photo (F-SSV-12). */
    private val blur = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        visibility = View.GONE
    }

    private val scrim = View(context).apply {
        setBackgroundColor(0xB3000000.toInt()) // the darkening layer over the blur
        visibility = View.GONE
    }

    /** Two alternating image views: a transition needs the outgoing slide alive. */
    private val slides = listOf(
        ImageView(context).apply { visibility = View.GONE },
        ImageView(context).apply { visibility = View.GONE },
    )

    /** The EXIF details overlay (F-SSV-12). */
    private val details = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 14f
        setShadowLayer(4f, 0f, 0f, Color.BLACK)
        visibility = View.GONE
    }

    private var showingFront = false
    private var photos: ScreensaverPhotos? = null
    private var engine: GalleryEngine? = null
    private var backgroundBitmap: Bitmap? = null

    /** The window's last known state (F-SSV-02), applied to each new engine. */
    private var active = true

    init {
        setBackgroundColor(Color.BLACK)
        addView(blur, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(scrim, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(slides[0], LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(slides[1], LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(details, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
        visibility = View.GONE
        isClickable = true
        isFocusable = false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean = true

    /**
     * Show the photos mode with [photos], the current settings; false when the
     * gallery cannot render (an unbuilt source, an empty playlist, a blank
     * URL), in which case the caller shows the clock stand-in (ADR 0013
     * item 3, ADR 0016). The source folder is listed off the main thread: a
     * large card tree must not ANR the activity as the screensaver starts.
     */
    suspend fun show(photos: ScreensaverPhotos?): Boolean {
        stopEngine()
        this.photos = photos ?: return false
        val panel = context.resources.displayMetrics
        // P3.9's source; until it ships there is no content to render
        if (photos.source == ScreensaverPhotosSource.MediaBrowser) return false
        val fetcher = UrlPhotoFetcher(File(context.cacheDir, "gallery"))
        val files: List<String> = withContext(Dispatchers.IO) {
            fetcher.reset()
            when (photos.source) {
                ScreensaverPhotosSource.Folder -> AndroidGallery.listImages(File(photos.folderPath), photos.includeSubfolders)

                ScreensaverPhotosSource.Picker -> AndroidGallery.listImages(AndroidGallery.pickerDir(context), recursive = false)

                else -> emptyList()
            }
        }
        if (photos.source != ScreensaverPhotosSource.Url && files.isEmpty()) return false
        if (photos.source == ScreensaverPhotosSource.Url && photos.imageUrl.isBlank()) return false
        val engine = GalleryEngine(
            controller = GalleryController(now = System::currentTimeMillis, random = Random(System.nanoTime())),
            decode = { path, width, height -> AndroidGallery.decode(path, width, height) },
            fetch = { url -> fetcher.fetch(url) },
            release = { handle -> (handle as? Bitmap)?.recycle() },
            // synchronous, on the carry-out's own main thread: the draw clears
            // or reuses the old layers before any release in the same outcome
            // recycles them (ADR 0016 item 4), and nothing sits in the post
            // queue for a dismissal to strand (a recycled bitmap must never
            // be drawn, even one frame late)
            render = { show -> draw(show) },
            onDeadEnd = ::deadEnd,
            scope = scope,
        )
        val started = engine.start(
            GalleryController.Config(
                source = photos.source,
                intervalMs = photos.slideInterval * 1000L,
                transition = photos.transition,
                fit = photos.fit,
                exif = photos.exifDetails,
                width = panel.widthPixels,
                height = panel.heightPixels,
                url = photos.imageUrl,
                files = files,
                shuffle = photos.shuffle,
            ),
        )
        if (!started) return false
        this.engine = engine
        engine.setActive(active)
        visibility = View.VISIBLE
        return true
    }

    /** Stop the slideshow and clear every layer (the mode left, or the gallery cannot show). */
    fun hide() {
        stopEngine()
        visibility = View.GONE
    }

    /**
     * Pause or resume with the window (F-SSV-02): no slide advances while
     * Tabula is not in front — which includes the panel being off, since a
     * powered-off panel stops the activity — so no decode or fetch runs
     * against a dark screen.
     */
    fun setActive(on: Boolean) {
        active = on
        engine?.setActive(on)
    }

    /** A playlist with nothing decodable: fall back to the clock stand-in (ADR 0016 item 1). */
    private fun deadEnd() {
        hide()
        clockStandIn()
    }

    /**
     * An edge-zone tap (F-SSV-12): true when this tap stepped a slide (back on
     * the left edge, forward on the right, the interval restarting), in which
     * case the activity does not treat it as a dismissal (F-SSV-04). False
     * when edge taps are off, the tap fell outside the zones, or no slideshow
     * is showing.
     */
    fun edgeTap(x: Float): Boolean {
        val engine = engine ?: return false
        val photos = photos ?: return false
        if (visibility != View.VISIBLE || !photos.edgeTaps) return false
        val zone = edgeZoneAt(x / width) ?: return false
        engine.step(zone)
        return true
    }

    /** Clear the views before the engine recycles the bitmaps they reference. */
    private fun stopEngine() {
        clearSlide(slides[0])
        clearSlide(slides[1])
        backgroundBitmap?.recycle()
        backgroundBitmap = null
        blur.setImageDrawable(null)
        blur.visibility = View.GONE
        scrim.visibility = View.GONE
        details.visibility = View.GONE
        engine?.stop()
        engine = null
    }

    private fun clearSlide(view: ImageView) {
        view.animate().cancel()
        view.visibility = View.GONE
        view.setImageDrawable(null)
        view.alpha = 1f
        view.translationX = 0f
        view.scaleX = 1f
        view.scaleY = 1f
    }

    /** Render one slide: the fit's layers, the transition, and the details overlay. */
    private fun draw(show: GalleryController.Effect.Show) {
        val bitmap = show.handle as? Bitmap ?: return
        // a stale carry-out racing a stop: the engine released it already
        if (bitmap.isRecycled) return
        // the hardware check counts these lines as slide evidence (check_p38.sh)
        android.util.Log.i(TAG, "slide: transition=${show.transition} fit=${show.fit.kind}")
        // Cancel both layers' animations before placing anything: a pending
        // end action from an earlier transition would otherwise clear the
        // slide this draw just placed (a cancelled animator never runs its
        // end action), and the outgoing layer's half-run transform would
        // skew this transition.
        slides.forEach { it.animate().cancel() }
        val w = width.takeIf { it > 0 } ?: context.resources.displayMetrics.widthPixels
        val h = height.takeIf { it > 0 } ?: context.resources.displayMetrics.heightPixels
        val incoming = slides[if (showingFront) 1 else 0]
        val outgoing = slides[if (showingFront) 0 else 1]
        showingFront = !showingFront

        incoming.translationX = 0f
        incoming.scaleX = 1f
        incoming.scaleY = 1f
        incoming.scaleType =
            if (show.fit.kind == FitPlan.Kind.CROP) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
        incoming.setImageBitmap(bitmap)
        incoming.alpha = 0f
        incoming.visibility = View.VISIBLE
        // the outgoing layer exits from the settled state, not whatever a
        // cancelled animation left mid-flight
        outgoing.alpha = 1f
        outgoing.scaleX = 1f
        outgoing.scaleY = 1f
        placeBackground(show, bitmap, w, h)

        val fade = Constants.GALLERY_TRANSITION_MS
        when (show.transition) {
            SlideTransition.NONE -> {
                incoming.alpha = 1f
                clearSlide(outgoing)
            }

            SlideTransition.CROSSFADE ->
                incoming.animate().alpha(1f).setDuration(fade).withEndAction { clearSlide(outgoing) }

            SlideTransition.SLIDE -> {
                incoming.translationX = w.toFloat()
                outgoing.translationX = 0f
                outgoing.animate().translationX(-w.toFloat()).setDuration(fade)
                incoming.animate().translationX(0f).setDuration(fade).withEndAction { clearSlide(outgoing) }
            }

            SlideTransition.ZOOM -> {
                incoming.scaleX = 1.06f
                incoming.scaleY = 1.06f
                incoming.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(fade).withEndAction { clearSlide(outgoing) }
            }

            SlideTransition.PAN_ZOOM ->
                incoming.animate().alpha(1f).setDuration(fade).withEndAction {
                    clearSlide(outgoing)
                    // slow pan-and-zoom across the rest of the slide's display (F-SSV-12, ADR 0016)
                    val ms = (photos?.slideInterval ?: 300) * 1000L - Constants.GALLERY_TRANSITION_MS
                    if (ms > 0) {
                        incoming.animate()
                            .scaleX(1.12f)
                            .scaleY(1.12f)
                            .translationXBy(w * 0.03f)
                            .translationYBy(h * 0.02f)
                            .setDuration(ms)
                            .start()
                    }
                }
        }

        showDetails(show)
    }

    /** Smart fill's bar case: a blurred, darkened enlargement behind the whole photo (F-SSV-12). */
    private fun placeBackground(show: GalleryController.Effect.Show, bitmap: Bitmap, w: Int, h: Int) {
        backgroundBitmap?.recycle()
        backgroundBitmap = null
        blur.setImageDrawable(null)
        if (show.fit.kind != FitPlan.Kind.BLUR_BARS) {
            blur.visibility = View.GONE
            scrim.visibility = View.GONE
            return
        }
        // a heavy downscale and upscale back is a cheap blur on a weak SoC
        val small = Bitmap.createScaledBitmap(bitmap, (w / 16).coerceAtLeast(1), (h / 16).coerceAtLeast(1), true)
        val cover = Bitmap.createScaledBitmap(small, w, h, true)
        if (cover !== small) small.recycle()
        backgroundBitmap = cover
        blur.setImageBitmap(cover)
        blur.visibility = View.VISIBLE
        scrim.visibility = View.VISIBLE
    }

    /** The EXIF overlay (F-SSV-12): what the photo says, in a free corner (F-WGT-02). */
    private fun showDetails(show: GalleryController.Effect.Show) {
        val text = show.details?.format(Locale.getDefault())
        if (text == null) {
            details.visibility = View.GONE
            return
        }
        details.text = text
        val margin = (16 * resources.displayMetrics.density).toInt()
        // F-WGT-02: corner widgets (P3.11) will pass their corners in as
        // `occupied`; until then every corner is free
        val gravity = when (detailsCorner(occupied = emptySet())) {
            PanelCorner.BOTTOM_LEFT -> Gravity.BOTTOM or Gravity.START
            PanelCorner.BOTTOM_RIGHT -> Gravity.BOTTOM or Gravity.END
            PanelCorner.TOP_RIGHT -> Gravity.TOP or Gravity.END
            PanelCorner.TOP_LEFT -> Gravity.TOP or Gravity.START
        }
        details.layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, gravity).apply {
            setMargins(margin, margin, margin, margin)
        }
        details.visibility = View.VISIBLE
    }

    private companion object {
        const val TAG = "TabulaGallery"
    }
}
