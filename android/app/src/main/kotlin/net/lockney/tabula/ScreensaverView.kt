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
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.lockney.tabula.core.Constants
import net.lockney.tabula.core.screensaver.FadeRule
import net.lockney.tabula.core.screensaver.NightState
import net.lockney.tabula.core.screensaver.PixelShift
import net.lockney.tabula.core.screensaver.clock.AnalogHands
import net.lockney.tabula.core.screensaver.clock.analogHands
import net.lockney.tabula.core.screensaver.clock.analogTexts
import net.lockney.tabula.core.screensaver.clock.dateLocale
import net.lockney.tabula.core.screensaver.clock.digitalFace
import net.lockney.tabula.core.screensaver.clock.isUrl
import net.lockney.tabula.core.screensaver.glance.AtAGlanceRow
import net.lockney.tabula.core.screensaver.glance.GlancePlacement
import net.lockney.tabula.core.screensaver.glance.GlanceRow
import net.lockney.tabula.core.screensaver.glance.GlanceValue
import net.lockney.tabula.core.settings.AtAGlance
import net.lockney.tabula.core.settings.ScreensaverClock
import net.lockney.tabula.core.settings.ScreensaverClockFace
import net.lockney.tabula.core.settings.ScreensaverNightMode
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
    private val nightState: NightState = NightState.NEVER,
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

    /** The At a Glance row (F-AAG-01, packet P3.6), drawn over the mode on screen. */
    private val glance = AtAGlanceView(context)

    /** Measures row text with the size it is about to be drawn at (ADR 0031 item 1). */
    private val glancePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glancePlanner = AtAGlanceRow { text, sizePx ->
        glancePaint.textSize = sizePx
        glancePaint.measureText(text)
    }

    /** The row's inputs: its settings, the night-mode options and the live values. */
    private var glanceSettings: AtAGlance? = null
    private var glanceNight: ScreensaverNightMode? = null
    private var glanceValues: List<GlanceValue> = emptyList()
    private var removeNightListener: (() -> Unit)? = null

    /** Where the row last drew, and the offset under the date line it rests at (ADR 0031 item 4). */
    private var glancePlacement: GlancePlacement? = null
    private var glanceBaseY = 0f
    private var glanceShiftX = 0f
    private var glanceShiftY = 0f

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
        // the row draws over the face and the background image (F-AAG-01)
        addView(glance, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
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
            // the row goes with the run: nothing to re-render for, and no night listener left behind
            clearNightListener()
            glancePlacement = null
            glance.visibility = View.GONE
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
        // the row re-renders when night mode changes instead of re-reading the flag per frame (F-SSV-10)
        ensureNightListener()
        renderGlance()
    }

    /**
     * The At a Glance row's inputs (F-AAG-01 to F-AAG-03, F-AAG-04): the
     * appearance settings, the night-mode options whose hide-overlays decides
     * whether night hides the row, and the live values from the screensaver's
     * subscription. Called on every start, settings write and entity update.
     */
    fun applyGlance(settings: AtAGlance, nightMode: ScreensaverNightMode, values: List<GlanceValue>) {
        glanceSettings = settings
        glanceNight = nightMode
        glanceValues = values
        renderGlance()
    }

    /**
     * Plan and draw the row for the mode on screen (F-AAG-01, ADR 0031). The
     * plan decides visibility, placement, wrapping, the shrink and the night
     * scheme; this method only feeds it the panel's width, the night flag and
     * the settings, and logs what it drew - one line per chip, which is the
     * hardware check's evidence that a state change reached the row
     * (check_p36.sh).
     */
    @Synchronized
    private fun renderGlance() {
        val settings = glanceSettings
        val mode = shownMode
        if (settings == null || mode == null) {
            glancePlacement = null
            glance.visibility = View.GONE
            return
        }
        val metrics = resources.displayMetrics
        val panelWidth = (if (width > 0) width else metrics.widthPixels).toFloat()
        val panelHeight = (if (height > 0) height else metrics.heightPixels).toFloat()
        val edge = Constants.GLANCE_ROW_EDGE_SP * metrics.density
        val row = glancePlanner.plan(
            settings = settings,
            values = glanceValues,
            mode = mode,
            night = nightState.isNight,
            hideAtNight = glanceNight?.hideOverlays ?: true,
            portrait = panelHeight > panelWidth,
            widthPx = (panelWidth - 2 * edge).coerceAtLeast(1f),
            density = metrics.density,
        )
        glancePlacement = if (row.visible) row.placement else null
        glance.render(row, settings)
        placeGlance()
        if (!row.visible) {
            Log.i(GLANCE_TAG, "row: hidden (mode=$mode, night=${nightState.isNight}, values=${glanceValues.size})")
            return
        }
        val percent = (row.textScale * 100).roundToInt()
        Log.i(
            GLANCE_TAG,
            "row: ${row.chips.size} chips on ${row.lines.size} line(s), placement=${row.placement}, " +
                "night=${row.night}, scale=$percent%, truncated=${row.truncateNames}, omitted=${row.omitted}",
        )
        for (chip in row.chips) {
            Log.i(GLANCE_TAG, "chip ${chip.entityId}: ${chip.value} (${if (chip.active) "active" else "inactive"})")
        }
    }

    /** Set the row's gravity and margins for its placement (F-AAG-01, ADR 0031 item 4). */
    private fun placeGlance() {
        val placement = glancePlacement ?: return
        val edge = (Constants.GLANCE_ROW_EDGE_SP * resources.displayMetrics.density).roundToInt()
        val bottom = if (placement == GlancePlacement.Bottom) edge else 0
        val gravity = if (placement == GlancePlacement.Bottom) {
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        } else {
            Gravity.CENTER
        }
        val lp = glance.layoutParams as LayoutParams
        if (lp.gravity != gravity || lp.bottomMargin != bottom || lp.leftMargin != edge) {
            lp.gravity = gravity
            lp.setMargins(edge, 0, edge, bottom)
            glance.layoutParams = lp
        }
        applyGlanceOffset()
    }

    /**
     * The row's resting offset: centered on black, under the clock's date line
     * otherwise, plus the run's pixel shift so the row moves with the face
     * (F-SSV-22, ADR 0031 item 4).
     */
    private fun applyGlanceOffset() {
        glance.translationX = glanceShiftX
        if (glancePlacement != GlancePlacement.UnderDate || face.visibility != View.VISIBLE) {
            glanceBaseY = 0f
            glance.translationY = glanceShiftY
            return
        }
        val gap = Constants.GLANCE_ROW_GAP_SP * resources.displayMetrics.density
        glanceBaseY = (face.top + face.height + gap) - glance.top
        glance.translationY = glanceBaseY + glanceShiftY
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        applyGlanceOffset()
    }

    /** Register for night-mode transitions while a run shows (F-SSV-10, ADR 0002's NightState). */
    private fun ensureNightListener() {
        if (removeNightListener != null) return
        removeNightListener = nightState.addListener { isNight ->
            // the listener fires on the thread that made the change; the row draws on this one
            post {
                Log.i(GLANCE_TAG, "night: $isNight")
                renderGlance()
            }
        }
    }

    private fun clearNightListener() {
        removeNightListener?.invoke()
        removeNightListener = null
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
        glanceShiftX = 0f
        glanceShiftY = 0f
        applyGlanceOffset()
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
        // the row moves with the face, so the whole overlay steps together (F-SSV-22)
        glanceShiftX = offset.x.toFloat()
        glanceShiftY = offset.y.toFloat()
        applyGlanceOffset()
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

        /** The At a Glance evidence the P3.6 hardware check greps (check_p36.sh, DEVICE.md). */
        const val GLANCE_TAG = "TabulaGlance"
        const val SECOND_MS = 1_000L
        const val MINUTE_MS = 60_000L
    }
}
