package net.lockney.tabula

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt
import net.lockney.tabula.core.Constants
import net.lockney.tabula.core.notifications.NotificationCards
import net.lockney.tabula.core.tlp.NotificationLevel

/**
 * The notification card stack (packet P2.5, FEATURES F-NTF-01), drawn over
 * the whole window - above the dashboard and above the screensaver overlay
 * (F-NTF-12, F-SSV-21) - with plain framework views (ADR 0013).
 *
 * The top region holds the cards, newest first. A card's accent color and
 * stock glyph come from its severity (F-NTF-02); a card's own close control
 * is its tap (F-NTF-08). The container passes touches outside the cards
 * through, so the screensaver underneath still dismisses on touch.
 */
@SuppressLint("ViewConstructor")
class NotificationCardsView(
    context: Context,
    private val onDismiss: (String) -> Unit,
) : LinearLayout(context) {
    init {
        orientation = VERTICAL
        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        setPadding(dp(16), dp(16), dp(16), 0)
        visibility = GONE
    }

    /** Render the stack, newest first; an empty stack hides the overlay. */
    fun render(cards: List<NotificationCards.Card>) {
        removeAllViews()
        for (card in cards) addView(cardView(card))
        visibility = if (cards.isEmpty()) GONE else VISIBLE
    }

    /**
     * Whether a touch at window coordinates ([x], [y]) lands on a card, so
     * the activity can leave the screensaver alone (F-NTF-12). The event's
     * coordinates are in the window's frame; this view may sit below a status
     * bar or a non-immersive inset, so they are converted first.
     */
    fun cardAt(x: Float, y: Float): Boolean {
        val origin = IntArray(2)
        getLocationInWindow(origin)
        val localX = x - origin[0]
        val localY = y - origin[1]
        val point = Rect()
        for (index in 0 until childCount) {
            getChildAt(index).getHitRect(point)
            if (point.contains(localX.toInt(), localY.toInt())) return true
        }
        return false
    }

    private fun cardView(card: NotificationCards.Card): View {
        val accent = accentOf(card.level)
        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Constants.CARD_BACKGROUND)
            }
            setPadding(dp(12), dp(12), dp(16), dp(12))
            elevation = dp(8).toFloat()
            isClickable = true
            isFocusable = true
            // a tap removes only this card and does nothing else (F-NTF-08)
            setOnClickListener { onDismiss(card.id) }
        }
        row.addView(
            View(context).apply { setBackgroundColor(accent) },
            LayoutParams(dp(6), LayoutParams.MATCH_PARENT),
        )
        row.addView(
            TextView(context).apply {
                text = glyphOf(card.level)
                textSize = 28f
                setTextColor(accent)
                gravity = Gravity.CENTER
            },
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { setMargins(dp(12), 0, dp(12), 0) },
        )
        val column = LinearLayout(context).apply { orientation = VERTICAL }
        if (card.title != null) {
            column.addView(
                TextView(context).apply {
                    text = card.title
                    textSize = 20f
                    setTextColor(Color.WHITE)
                    typeface = Typeface.DEFAULT_BOLD
                    maxWidth = dp(520)
                },
            )
        }
        column.addView(
            TextView(context).apply {
                text = card.message
                textSize = 18f
                setTextColor(Color.LTGRAY)
                maxWidth = dp(520)
            },
        )
        row.addView(column, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        return row
    }

    private fun accentOf(level: NotificationLevel): Int = when (level) {
        NotificationLevel.INFO -> 0xFF4FC3F7.toInt() // blue
        NotificationLevel.SUCCESS -> 0xFF81C784.toInt() // green
        NotificationLevel.WARNING -> 0xFFFFB74D.toInt() // orange
        NotificationLevel.ERROR -> 0xFFE57373.toInt() // red
    }

    private fun glyphOf(level: NotificationLevel): String = when (level) {
        NotificationLevel.INFO -> "ⓘ"
        NotificationLevel.SUCCESS -> "✔"
        NotificationLevel.WARNING -> "⚠"
        NotificationLevel.ERROR -> "✖"
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}
