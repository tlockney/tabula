package net.lockney.tabula

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.time.LocalDateTime
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.lockney.tabula.core.Constants
import net.lockney.tabula.core.screensaver.FadeRule
import net.lockney.tabula.core.screensaver.PixelShift
import net.lockney.tabula.core.screensaver.clock.AnalogHands
import net.lockney.tabula.core.screensaver.clock.analogHands
import net.lockney.tabula.core.screensaver.clock.analogTexts
import net.lockney.tabula.core.screensaver.clock.dateLocale
import net.lockney.tabula.core.screensaver.clock.digitalFace
import net.lockney.tabula.core.screensaver.clock.isUrl
import net.lockney.tabula.core.settings.ScreensaverClock
import net.lockney.tabula.core.settings.ScreensaverClockFace
import net.lockney.tabula.core.tlp.ScreensaverMode
import net.lockney.tabula.ha.MediaFetcher

/**
 * The screensaver overlay (packet P2.4), drawn over the whole window with
 * plain framework views (ADR 0013).
 *
 * - Clock (F-SSV-08, packet P3.3): the digital face - time with optional
 *   seconds, AM/PM marker and date line, in the configured font family,
 *   weight, size and color - or the analog face with hands, the date line
 *   and AM/PM under the dial (ADR 0018). The clock follows the settings
 *   schema's hour_24; the date line uses the app language with the system
 *   language as fallback.
 * - Background image (F-SSV-09): a local file or a URL, decoded at panel
 *   resolution behind the face, with a darkening layer of the configured
 *   strength. A settings write always reloads it (the activity re-applies
 *   on every revision), a failed load keeps the previous image, and a URL
 *   re-fetches on the configured interval ([ClockBackgroundEngine]).
 * - Black (F-SSV-06): black only; the backlight is held at its lowest.
 * - Dim (F-SSV-07): nothing is drawn, so the dashboard stays visible under
 *   the dimmed backlight.
 *
 * Engine polish (packet P3.14, ADR 0022): the overlay fades in on start and
 * out on dismissal when the fade is on ([fade], F-SSV-23), and the face
 * and the background image step together by small offsets on the
 * [pixelShift] schedule (F-SSV-22), never in
 * black mode. Both are read once when a run starts, so an edit lands at the
 * next start and a running screensaver keeps its current shift and fade
 * (ADR 0022) - a settings write through [applyClock] re-renders the face
 * and reloads the background without touching the run's shift or fade.
 *
 * Any mode takes the touch that dismisses it, so that touch does not also
 * press something on the dashboard underneath. The dismissal itself comes
 * from the activity's user-interaction hook through the service.
 */
@SuppressLint("ViewConstructor")
class ScreensaverView(
    context: Context,
    private val scope: CoroutineScope,
    private val pixelShift: () -> PixelShift = { PixelShift() },
    private val fade: () -> FadeRule = { FadeRule() },
) : FrameLayout(context) {
    /** The background image (F-SSV-09), cropped to cover the panel. */
    private val background = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        visibility = View.GONE
    }

    /** The darkening layer over the background image (F-SSV-09). */
    private val darken = View(context).apply {
        setBackgroundColor(Color.BLACK)
        visibility = View.GONE
    }

    /** The face's views, rebuilt for the configured face (F-SSV-08). */
    private val face = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
    }

    private var settings: ScreensaverClock? = null
    private var dial: AnalogClockView? = null
    private var timeText: TextView? = null
    private var amPmText: TextView? = null
    private var dateText: TextView? = null

    /** The date line's locale (F-SSV-08): app language, system fallback. */
    private var dateLanguage: Locale = Locale.getDefault()

    private val tick = Runnable { updateClock() }
    private val shiftTick = Runnable { stepShift() }

    /** The mode this overlay is showing, for the dismissal fade (F-SSV-23). */
    private var shownMode: ScreensaverMode? = null

    /**
     * This run's shift and fade, read once when the run started: an edit to
     * the settings lands at the next start, and a running screensaver keeps
     * its current shift and fade (ADR 0022's Consequences). The dismissal
     * fade uses the rule the run started with.
     */
    private var shift = PixelShift()
    private var fadeRule = FadeRule()
    private var shiftStep = 0

    private val backgroundEngine = ClockBackgroundEngine(
        controller = net.lockney.tabula.core.screensaver.clock.ClockBackgroundController(System::currentTimeMillis),
        load = ::loadBackground,
        show = ::showBackground,
        release = { handle -> (handle as? Bitmap)?.recycle() },
        scope = scope,
    )

    init {
        addView(background, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(darken, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(face, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        visibility = View.GONE
        isClickable = true
        isFocusable = false
    }

    /** True while a clock face is drawn (any mode that shows the clock, including the stand-in, ADR 0013 item 3). */
    @get:Synchronized
    var showingFace: Boolean = false
        private set

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean = true

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        removeCallbacks(shiftTick)
        backgroundEngine.stop()
        super.onDetachedFromWindow()
    }

    /** Show [mode] (the clock, also the stand-in for modes not built yet, ADR 0013) with [clock] settings, or hide the overlay with null. */
    fun show(mode: ScreensaverMode?, clock: ScreensaverClock? = null) {
        animate().cancel()
        removeCallbacks(tick)
        removeCallbacks(shiftTick)
        backgroundEngine.stop()
        showingFace = false
        if (mode == null) {
            // the dismissal fade is the started run's own (ADR 0022's cadence)
            val fadingOut = shownMode?.let(fadeRule::onDismiss) == true
            shownMode = null
            if (fadingOut) {
                Log.i(TAG, "fade out")
                animate()
                    .alpha(0f)
                    .setDuration(Constants.SCREENSAVER_FADE_MS)
                    .withEndAction {
                        visibility = View.GONE
                        alpha = 1f
                        // the content keeps its last offset for the fade; back at rest once it ends
                        resetShift()
                    }
            } else {
                visibility = View.GONE
                alpha = 1f
                resetShift()
            }
            return
        }
        // a start: this run's shift and fade, read once (ADR 0022's cadence)
        shift = pixelShift()
        fadeRule = fade()
        val fadingIn = fadeRule.onShow(mode)
        shownMode = mode
        visibility = View.VISIBLE
        alpha = if (fadingIn) 0f else 1f
        when (mode) {
            ScreensaverMode.DIM -> {
                setBackgroundColor(Color.TRANSPARENT)
                face.visibility = View.GONE
            }

            ScreensaverMode.BLACK -> {
                setBackgroundColor(Color.BLACK)
                face.visibility = View.GONE
            }

            // the clock, also the stand-in for modes not built yet (ADR 0013)
            else -> showFace(clock ?: ScreensaverClock(), freshStart = true)
        }
        if (fadingIn) {
            Log.i(TAG, "fade in")
            animate().alpha(1f).setDuration(Constants.SCREENSAVER_FADE_MS)
        }
        startShift(mode)
    }

    /**
     * A settings write arrived while a face shows (F-SSV-09: every write
     * reloads the background, identical source or not; the revision change
     * is the signal). Re-renders the face and orders the fresh load; the
     * showing image stays until the fresh one loads. The run's shift and
     * fade are kept (ADR 0022: they change at the next start).
     */
    fun applyClock(clock: ScreensaverClock) {
        if (!showingFace) return
        showFace(clock, freshStart = false)
    }

    /** Pause or resume with the window (F-SSV-02): no background loads while Tabula is not in front. */
    fun setActive(on: Boolean) {
        backgroundEngine.setActive(on)
    }

    /**
     * Render the configured face over its background (F-SSV-08, F-SSV-09).
     * [freshStart] marks the screensaver's own start; a settings write
     * ([applyClock]) reloads the background without first clearing it
     * (F-SSV-09).
     */
    @Synchronized
    private fun showFace(clock: ScreensaverClock, freshStart: Boolean) {
        settings = clock
        showingFace = true
        setBackgroundColor(Color.parseColor(clock.backgroundColor))
        // F-SSV-08: the app's language for the date when the app carries a
        // translation for it, the system language otherwise
        dateLanguage = dateLocale(
            appLocale = resources.configuration.locales[0],
            shipped = context.assets.locales.map { it.substringBefore('-').lowercase() }.toSet(),
            systemLocale = Locale.getDefault(),
        )
        buildFace(clock)
        // the darkening layer sits over the image only (F-SSV-09)
        darken.alpha = clock.backgroundDarken / 100f
        face.visibility = View.VISIBLE
        updateClock()
        val metrics = context.resources.displayMetrics
        if (freshStart) {
            backgroundEngine.start(
                clock.backgroundSource,
                clock.backgroundRefresh * 1000L,
                metrics.widthPixels,
                metrics.heightPixels,
            )
        } else {
            // a settings write always reloads the background, identical
            // source or not (F-SSV-09); the previous image stays until the
            // fresh one loads
            backgroundEngine.applySettings(
                clock.backgroundSource,
                clock.backgroundRefresh * 1000L,
                metrics.widthPixels,
                metrics.heightPixels,
            )
        }
    }

    /** Build the configured face's views (F-SSV-08): digital texts, or the analog dial with its texts (ADR 0018). */
    private fun buildFace(clock: ScreensaverClock) {
        face.removeAllViews()
        dial = null
        timeText = null
        val date = dateView(clock)
        dateText = date
        amPmText = amPmView(clock)
        when (clock.face) {
            ScreensaverClockFace.Digital -> {
                val time = TextView(context).apply {
                    setTextColor(Color.parseColor(clock.textColor))
                    typeface = typeface(clock)
                    gravity = Gravity.CENTER
                }
                timeText = time
                face.addView(time)
                amPmText?.let { face.addView(it) }
                date?.let { face.addView(it) }
            }

            ScreensaverClockFace.Analog -> {
                val view = AnalogClockView(context).apply {
                    color = Color.parseColor(clock.textColor)
                }
                dial = view
                val metrics = context.resources.displayMetrics
                val side = (
                    minOf(metrics.widthPixels, metrics.heightPixels) *
                        Constants.CLOCK_DIAL_FRACTION * clock.size / 100f
                    ).toInt().coerceAtLeast(1)
                face.addView(view, LinearLayout.LayoutParams(side, side))
                amPmText?.let { face.addView(it) }
                date?.let { face.addView(it) }
            }
        }
    }

    /** The date line's view (F-SSV-08), or null while the option is off. */
    private fun dateView(clock: ScreensaverClock): TextView? {
        if (!clock.showDate) return null
        return TextView(context).apply {
            setTextColor(Color.parseColor(clock.textColor))
            typeface = typeface(clock)
            gravity = Gravity.CENTER
            textSize = Constants.CLOCK_DATE_BASE_SP * clock.size / 100f
        }
    }

    /** The AM/PM marker's view (F-SSV-08), or null while hidden. */
    private fun amPmView(clock: ScreensaverClock): TextView? {
        // the marker pairs with 12-hour time (F-SSV-08); a dial is always 12-hour (ADR 0018)
        if (clock.face == ScreensaverClockFace.Digital && clock.hour24) return null
        if (!clock.showAmPm) return null
        return TextView(context).apply {
            setTextColor(Color.parseColor(clock.textColor))
            typeface = typeface(clock)
            gravity = Gravity.CENTER
        }
    }

    /** The settings' font family and weight (F-SSV-08); Android's weight axis is API 26+, older builds step to bold (ADR 0018). */
    private fun typeface(clock: ScreensaverClock): Typeface =
        if (Build.VERSION.SDK_INT >= 26) {
            Typeface.create(Typeface.create(clock.fontFamily, Typeface.NORMAL), clock.fontWeight, false)
        } else {
            Typeface.create(clock.fontFamily, if (clock.fontWeight >= 600) Typeface.BOLD else Typeface.NORMAL)
        }

    /** Update the face's time content and schedule the next update on the second or minute boundary. */
    @Synchronized
    private fun updateClock() {
        val clock = settings ?: return
        val now = LocalDateTime.now()
        when (clock.face) {
            ScreensaverClockFace.Digital -> {
                val layout = digitalFace(now, clock, Locale.getDefault(), dateLanguage)
                timeText?.text = layout.time
                timeText?.textSize = layout.timeSizeSp
                amPmText?.let {
                    it.text = layout.amPm
                    it.textSize = layout.amPmSizeSp
                    it.visibility = if (layout.amPm == null) View.GONE else View.VISIBLE
                }
                dateText?.let {
                    it.text = layout.date
                    it.visibility = if (layout.date == null) View.GONE else View.VISIBLE
                }
            }

            ScreensaverClockFace.Analog -> {
                dial?.hands = analogHands(now.toLocalTime(), clock.showSeconds)
                dial?.invalidate()
                val texts = analogTexts(now, clock, dateLanguage, Locale.getDefault())
                amPmText?.let {
                    it.text = texts.amPm
                    it.textSize = texts.amPmSizeSp
                    it.visibility = if (texts.amPm == null) View.GONE else View.VISIBLE
                }
                dateText?.let {
                    it.text = texts.date
                    it.visibility = if (texts.date == null) View.GONE else View.VISIBLE
                }
            }
        }
        // seconds (the second hand included) update on the second; the rest on the minute boundary
        val period = if (clock.showSeconds) SECOND_MS else MINUTE_MS
        val at = System.currentTimeMillis()
        postDelayed(tick, period - at % period)
    }

    /**
     * Schedule the first shift from this run's snapshot. Dim draws nothing,
     * so nothing can move; black never shifts (F-SSV-22). The shift moves
     * the face and the background image together (ADR 0018: the face and
     * background move as one): while a shift runs the background is
     * oversized by the amplitude on every side, so no offset ever bares
     * the panel's edge (ADR 0022's mechanism, as the gallery's container).
     * The darkening layer is a uniform overlay and stays full-screen.
     */
    private fun startShift(mode: ScreensaverMode) {
        resetShift()
        if (mode == ScreensaverMode.DIM || !shift.appliesTo(mode)) return
        setBackgroundInset(shift.amplitudePx)
        postDelayed(shiftTick, shift.intervalMs)
    }

    private fun resetShift() {
        shiftStep = 0
        face.translationX = 0f
        face.translationY = 0f
        background.translationX = 0f
        background.translationY = 0f
        setBackgroundInset(0)
    }

    private fun stepShift() {
        val mode = shownMode ?: return
        if (face.visibility != View.VISIBLE || !shift.appliesTo(mode)) return
        val offset = shift.offset(shiftStep)
        shiftStep += 1
        face.translationX = offset.x.toFloat()
        face.translationY = offset.y.toFloat()
        background.translationX = offset.x.toFloat()
        background.translationY = offset.y.toFloat()
        Log.i(TAG, "pixel shift: dx=${offset.x} dy=${offset.y}")
        postDelayed(shiftTick, shift.intervalMs)
    }

    /** Oversize the background by [inset] on every side, so a shift never bares the panel edge (ADR 0022). */
    private fun setBackgroundInset(inset: Int) {
        val lp = background.layoutParams as FrameLayout.LayoutParams
        if (lp.leftMargin == -inset && lp.topMargin == -inset) return
        lp.setMargins(-inset, -inset, -inset, -inset)
        background.layoutParams = lp
    }

    /** Show the loaded background image with its darkening layer, or clear to the configured background color (F-SSV-09). */
    private fun showBackground(handle: Any?) {
        val bitmap = handle as? Bitmap
        if (bitmap == null || bitmap.isRecycled) {
            background.setImageDrawable(null)
            background.visibility = View.GONE
            darken.visibility = View.GONE
            return
        }
        // the hardware check counts these lines as background evidence (check_p33.sh)
        android.util.Log.i(CLOCK_TAG, "background: shown")
        background.setImageBitmap(bitmap)
        background.visibility = View.VISIBLE
        darken.visibility = View.VISIBLE
    }

    /**
     * Fetch (URL) or read (local path) and decode one background at panel
     * resolution (F-SSV-09): the URL rides the P2.5 media fetch — no
     * credential is ever attached, redirects within the ADR 0002 bounds
     * (ADR 0018) — and lands in a cache file deleted after the decode; a
     * local file decodes straight. Null when anything fails: the previous
     * image stays (F-SSV-09).
     */
    private suspend fun loadBackground(source: String, width: Int, height: Int): Any? =
        withContext(Dispatchers.IO) {
            if (!isUrl(source)) {
                return@withContext AndroidGallery.decode(source, width, height)?.handle
            }
            val file = File.createTempFile("tabula-clock-bg", null, context.cacheDir)
            try {
                MediaFetcher().fetch(source, file)
                AndroidGallery.decode(file.absolutePath, width, height)?.handle
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            } finally {
                file.delete() // no partial file survives a failed fetch (P2.5's rule)
            }
        }

    /**
     * The analog face's dial (F-SSV-08): twelve tick marks, the hands and a
     * center dot, in the configured text color. A dial is 12-hour by nature
     * (ADR 0018); the second hand exists while `show_seconds` is on.
     */
    private class AnalogClockView(context: Context) : View(context) {
        var hands: AnalogHands? = null
        var color: Int = Color.WHITE

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

        override fun onDraw(canvas: Canvas) {
            val hands = hands ?: return
            val cx = width / 2f
            val cy = height / 2f
            val radius = minOf(cx, cy) * 0.9f
            paint.color = color
            // twelve tick marks, a longer stroke on the quarters
            for (i in 0 until 12) {
                val major = i % 3 == 0
                paint.strokeWidth = if (major) 8f else 4f
                val inner = radius * if (major) 0.82f else 0.88f
                canvas.drawLine(
                    cx + (sinDeg(i * 30f) * inner),
                    cy - (cosDeg(i * 30f) * inner),
                    cx + (sinDeg(i * 30f) * radius),
                    cy - (cosDeg(i * 30f) * radius),
                    paint,
                )
            }
            hand(canvas, hands.hour, radius * 0.5f, radius * 0.075f)
            hand(canvas, hands.minute, radius * 0.75f, radius * 0.05f)
            hands.second?.let { hand(canvas, it, radius * 0.85f, radius * 0.025f) }
            paint.style = Paint.Style.FILL
            canvas.drawCircle(cx, cy, radius * 0.05f, paint)
            paint.style = Paint.Style.STROKE
        }

        /** One hand from the center to [length], [stroke] px wide. */
        private fun hand(canvas: Canvas, degrees: Float, length: Float, stroke: Float) {
            val cx = this.width / 2f
            val cy = this.height / 2f
            paint.strokeWidth = stroke
            canvas.drawLine(cx, cy, cx + sinDeg(degrees) * length, cy - cosDeg(degrees) * length, paint)
        }

        private fun sinDeg(degrees: Float) = Math.sin(Math.toRadians(degrees.toDouble())).toFloat()
        private fun cosDeg(degrees: Float) = Math.cos(Math.toRadians(degrees.toDouble())).toFloat()
    }

    private companion object {
        /** The engine-polish logs (fade, pixel shift, P3.14). */
        const val TAG = "TabulaScreensaver"

        /** The clock-background evidence the P3.3 hardware check greps (check_p33.sh, DEVICE.md). */
        const val CLOCK_TAG = "TabulaClock"
        const val SECOND_MS = 1_000L
        const val MINUTE_MS = 60_000L
    }
}
