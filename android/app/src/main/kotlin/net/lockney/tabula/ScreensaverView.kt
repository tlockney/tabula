package net.lockney.tabula

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.format.DateFormat
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import net.lockney.tabula.core.Constants
import net.lockney.tabula.core.screensaver.FadeRule
import net.lockney.tabula.core.screensaver.PixelShift
import net.lockney.tabula.core.tlp.ScreensaverMode

/**
 * The screensaver overlay (packet P2.4), drawn over the whole window with
 * plain framework views (ADR 0013).
 *
 * - Clock (F-SSV-08, digital face): time and date on black, updated on the
 *   minute. It follows the system 12/24-hour setting and language.
 * - Black (F-SSV-06): black only; the backlight is held at its lowest.
 * - Dim (F-SSV-07): nothing is drawn, so the dashboard stays visible under the
 *   dimmed backlight.
 *
 * Engine polish (packet P3.14, ADR 0022): the overlay fades in on start and
 * out on dismissal when the fade is on ([fade], F-SSV-23), except for modes
 * that show the live page itself or web content, and its content steps by
 * small offsets on the [pixelShift] schedule (F-SSV-22), never in black mode.
 * Both are read once when a run starts, so an edit lands at the next start
 * and a running screensaver keeps its current shift and fade (ADR 0022).
 *
 * Any mode takes the touch that dismisses it, so that touch does not also
 * press something on the dashboard underneath. The dismissal itself comes
 * from the activity's user-interaction hook through the service.
 */
@SuppressLint("ViewConstructor")
class ScreensaverView(
    context: Context,
    private val pixelShift: () -> PixelShift = { PixelShift() },
    private val fade: () -> FadeRule = { FadeRule() },
) : FrameLayout(context) {
    private val time = TextView(context).apply {
        setTextColor(Color.WHITE)
        textSize = 120f
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        gravity = Gravity.CENTER
    }
    private val date = TextView(context).apply {
        setTextColor(Color.LTGRAY)
        textSize = 32f
        gravity = Gravity.CENTER
    }
    private val clock = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        addView(time)
        addView(date)
    }
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

    init {
        addView(clock, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        visibility = View.GONE
        isClickable = true
        isFocusable = false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean = true

    /** Show [mode], or hide the overlay with null. */
    fun show(mode: ScreensaverMode?) {
        animate().cancel()
        removeCallbacks(tick)
        removeCallbacks(shiftTick)
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
                clock.visibility = View.GONE
            }
            ScreensaverMode.BLACK -> {
                setBackgroundColor(Color.BLACK)
                clock.visibility = View.GONE
            }
            // the clock, also the stand-in for modes not built yet (ADR 0013)
            else -> {
                setBackgroundColor(Color.BLACK)
                clock.visibility = View.VISIBLE
                updateClock()
            }
        }
        if (fadingIn) {
            Log.i(TAG, "fade in")
            animate().alpha(1f).setDuration(Constants.SCREENSAVER_FADE_MS)
        }
        startShift(mode)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        removeCallbacks(shiftTick)
        super.onDetachedFromWindow()
    }

    /**
     * Schedule the first shift from this run's snapshot. Dim draws nothing,
     * so nothing can move; black never shifts (F-SSV-22).
     */
    private fun startShift(mode: ScreensaverMode) {
        resetShift()
        if (mode == ScreensaverMode.DIM || !shift.appliesTo(mode)) return
        postDelayed(shiftTick, shift.intervalMs)
    }

    private fun resetShift() {
        shiftStep = 0
        clock.translationX = 0f
        clock.translationY = 0f
    }

    private fun stepShift() {
        val mode = shownMode ?: return
        if (clock.visibility != View.VISIBLE || !shift.appliesTo(mode)) return
        val offset = shift.offset(shiftStep)
        shiftStep += 1
        clock.translationX = offset.x.toFloat()
        clock.translationY = offset.y.toFloat()
        Log.i(TAG, "pixel shift: dx=${offset.x} dy=${offset.y}")
        postDelayed(shiftTick, shift.intervalMs)
    }

    private fun updateClock() {
        val now = System.currentTimeMillis()
        val locale = Locale.getDefault()
        val timePattern = if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm"
        time.text = SimpleDateFormat(timePattern, locale).format(Date(now))
        date.text = SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "EEEEMMMMd"), locale).format(Date(now))
        // next update on the minute boundary
        postDelayed(tick, MINUTE_MS - now % MINUTE_MS)
    }

    private companion object {
        const val TAG = "TabulaScreensaver"
        const val MINUTE_MS = 60_000L
    }
}
