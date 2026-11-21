package net.lockney.tabula

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
 * Any mode takes the touch that dismisses it, so that touch does not also
 * press something on the dashboard underneath. The dismissal itself comes
 * from the activity's user-interaction hook through the service.
 */
@SuppressLint("ViewConstructor")
class ScreensaverView(context: Context) : FrameLayout(context) {
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
        removeCallbacks(tick)
        if (mode == null) {
            visibility = View.GONE
            return
        }
        visibility = View.VISIBLE
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
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
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
        const val MINUTE_MS = 60_000L
    }
}
