package net.lockney.tabula

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import net.lockney.tabula.core.Constants
import net.lockney.tabula.core.screensaver.glance.GlanceChip
import net.lockney.tabula.core.screensaver.glance.GlanceGlyph
import net.lockney.tabula.core.screensaver.glance.GlanceMetrics
import net.lockney.tabula.core.screensaver.glance.GlanceRow
import net.lockney.tabula.core.settings.AtAGlance
import net.lockney.tabula.core.settings.AtAGlanceStyle

/**
 * The At a Glance row (F-AAG-01, F-AAG-02; packet P3.6): one or two lines of
 * chips, each an icon, an optional name and the value, drawn over the
 * screensaver.
 *
 * What to draw is decided by [net.lockney.tabula.core.screensaver.glance.AtAGlanceRow]
 * (ADR 0032): this view only turns a plan into views - the pill or the plain
 * style, the icon box's active/inactive color, the night scheme and the
 * configured scale and font.
 */
class AtAGlanceView(context: Context) : LinearLayout(context) {
    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        visibility = View.GONE
        isClickable = false
        isFocusable = false
    }

    /** Lay [row] out under [settings]' appearance; a hidden row clears the view. */
    fun render(row: GlanceRow, settings: AtAGlance) {
        removeAllViews()
        if (!row.visible) {
            visibility = View.GONE
            return
        }
        val metrics = GlanceMetrics.of(settings.scale, resources.displayMetrics.density)
        val typeface = runCatching { Typeface.create(settings.font, Typeface.NORMAL) }
            .getOrElse { Typeface.DEFAULT }
        row.lines.forEachIndexed { index, line ->
            val lineView = LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER
            }
            line.forEachIndexed { position, chip ->
                if (position > 0) {
                    lineView.addView(View(context), LayoutParams(metrics.chipGapPx.toInt(), 1))
                }
                lineView.addView(chipView(chip, row, settings, metrics, typeface))
            }
            addView(
                lineView,
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                    if (index < row.lines.lastIndex) bottomMargin = metrics.lineGapPx.toInt()
                },
            )
        }
        visibility = View.VISIBLE
    }

    /** One chip: its icon box and its texts, on the pill or floating plain (F-AAG-02). */
    private fun chipView(
        chip: GlanceChip,
        row: GlanceRow,
        settings: AtAGlance,
        metrics: GlanceMetrics,
        typeface: Typeface,
    ): View {
        val textColor = if (row.night) Constants.GLANCE_NIGHT_TEXT else Constants.GLANCE_TEXT
        // the icon background follows the entity's state as HA's tiles do;
        // night and the monochrome option draw the icon in the text color
        val monochrome = row.night || settings.monochromeIcons
        val iconTint = if (monochrome) {
            textColor
        } else if (chip.active) {
            Constants.GLANCE_ICON_ACTIVE
        } else {
            Constants.GLANCE_ICON_INACTIVE
        }
        val iconBox = if (monochrome) {
            Color.TRANSPARENT
        } else if (chip.active) {
            Constants.GLANCE_ICON_ACTIVE_BG
        } else {
            Constants.GLANCE_ICON_INACTIVE_BG
        }
        val padding = metrics.chipPaddingPx
        val radius = metrics.chipPaddingPx * Constants.GLANCE_CHIP_RADIUS_SP / Constants.GLANCE_CHIP_PADDING_SP
        val chipLayout = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(padding.toInt(), (padding / 2).toInt(), padding.toInt(), (padding / 2).toInt())
        }
        if (settings.style == AtAGlanceStyle.Pill) {
            chipLayout.background = GradientDrawable().apply {
                cornerRadius = radius
                setColor(if (row.night) Constants.GLANCE_NIGHT_PILL_BG else Constants.GLANCE_PILL_BG)
            }
        }
        chipLayout.addView(
            GlyphView(context).apply {
                glyph = chip.glyph
                initial = chip.initial
                tint = iconTint
                box = iconBox
                boxRadius = radius / 2
            },
            LayoutParams(metrics.iconPx.toInt(), metrics.iconPx.toInt()).apply {
                rightMargin = (padding / 2).toInt()
            },
        )
        val texts = LinearLayout(context).apply {
            orientation = VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        if (!settings.hideNames) {
            texts.addView(
                label(chip.name, metrics.namePx * row.textScale, textColor, typeface),
                LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT),
            )
        }
        val valueSize = metrics.valuePx * row.textScale *
            if (settings.hideNames) Constants.GLANCE_VALUE_LARGE_SCALE else 1f
        texts.addView(
            label(chip.value, valueSize, textColor, typeface),
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT),
        )
        chipLayout.addView(texts)
        return chipLayout
    }

    private fun label(text: String, sizePx: Float, color: Int, typeface: Typeface): TextView =
        TextView(context).apply {
            this.text = text
            textSize = sizePx / resources.displayMetrics.scaledDensity
            setTextColor(color)
            this.typeface = typeface
            maxLines = 1
            gravity = Gravity.CENTER_VERTICAL
        }

    /**
     * One chip's icon (ADR 0032 item 1): a curated vector glyph drawn on a
     * canvas, inside a rounded box whose color carries the active/inactive
     * state the way HA's tiles do. [GlanceGlyph.Initial] draws the entity
     * domain's initial instead, the fallback for icons outside the set.
     */
    private class GlyphView(context: Context) : View(context) {
        var glyph: GlanceGlyph = GlanceGlyph.Initial
        var initial: String = ""
        var tint: Int = Color.WHITE
        var box: Int = Color.TRANSPARENT
        var boxRadius: Float = 0f

        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        private val letter = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }

        override fun onDraw(canvas: Canvas) {
            val side = minOf(width, height).toFloat()
            if (side <= 0f) return
            val ox = (width - side) / 2f
            val oy = (height - side) / 2f
            if (Color.alpha(box) != 0) {
                fill.color = box
                canvas.drawRoundRect(ox, oy, ox + side, oy + side, boxRadius, boxRadius, fill)
            }
            stroke.color = tint
            stroke.strokeWidth = side * 0.075f
            fill.color = tint
            letter.color = tint
            letter.textSize = side * 0.6f
            when (glyph) {
                GlanceGlyph.Lock -> lock(canvas, ox, oy, side, open = false)
                GlanceGlyph.Unlock -> lock(canvas, ox, oy, side, open = true)
                GlanceGlyph.Door -> door(canvas, ox, oy, side)
                GlanceGlyph.Garage -> garage(canvas, ox, oy, side)
                GlanceGlyph.Light -> light(canvas, ox, oy, side)
                GlanceGlyph.Power -> power(canvas, ox, oy, side)
                GlanceGlyph.Motion -> motion(canvas, ox, oy, side)
                GlanceGlyph.Thermometer -> thermometer(canvas, ox, oy, side)
                GlanceGlyph.Battery -> battery(canvas, ox, oy, side)
                GlanceGlyph.Water -> water(canvas, ox, oy, side)
                GlanceGlyph.Alert -> alert(canvas, ox, oy, side)
                GlanceGlyph.Initial -> canvas.drawText(
                    initial,
                    width / 2f,
                    height / 2f - (letter.descent() + letter.ascent()) / 2f,
                    letter,
                )
            }
        }

        private fun x(ox: Float, side: Float, u: Float) = ox + u * side

        private fun y(oy: Float, side: Float, v: Float) = oy + v * side

        private fun lock(canvas: Canvas, ox: Float, oy: Float, side: Float, open: Boolean) {
            canvas.drawRoundRect(
                RectF(x(ox, side, 0.22f), y(oy, side, 0.45f), x(ox, side, 0.78f), y(oy, side, 0.88f)),
                side * 0.08f,
                side * 0.08f,
                stroke,
            )
            val left = if (open) 0.46f else 0.32f
            val right = if (open) 0.82f else 0.68f
            canvas.drawArc(
                RectF(x(ox, side, left), y(oy, side, 0.14f), x(ox, side, right), y(oy, side, 0.52f)),
                190f,
                160f,
                false,
                stroke,
            )
        }

        private fun door(canvas: Canvas, ox: Float, oy: Float, side: Float) {
            canvas.drawRect(
                RectF(x(ox, side, 0.28f), y(oy, side, 0.10f), x(ox, side, 0.72f), y(oy, side, 0.90f)),
                stroke,
            )
            canvas.drawCircle(x(ox, side, 0.38f), y(oy, side, 0.54f), side * 0.035f, fill)
        }

        private fun garage(canvas: Canvas, ox: Float, oy: Float, side: Float) {
            canvas.drawRect(
                RectF(x(ox, side, 0.16f), y(oy, side, 0.30f), x(ox, side, 0.84f), y(oy, side, 0.88f)),
                stroke,
            )
            for (line in listOf(0.46f, 0.60f, 0.74f)) {
                canvas.drawLine(
                    x(ox, side, 0.16f),
                    y(oy, side, line),
                    x(ox, side, 0.84f),
                    y(oy, side, line),
                    stroke,
                )
            }
        }

        private fun light(canvas: Canvas, ox: Float, oy: Float, side: Float) {
            canvas.drawCircle(x(ox, side, 0.5f), y(oy, side, 0.38f), side * 0.21f, stroke)
            canvas.drawRect(
                RectF(x(ox, side, 0.40f), y(oy, side, 0.62f), x(ox, side, 0.60f), y(oy, side, 0.72f)),
                stroke,
            )
            canvas.drawLine(
                x(ox, side, 0.42f),
                y(oy, side, 0.82f),
                x(ox, side, 0.58f),
                y(oy, side, 0.82f),
                stroke,
            )
        }

        private fun power(canvas: Canvas, ox: Float, oy: Float, side: Float) {
            canvas.drawArc(
                RectF(x(ox, side, 0.20f), y(oy, side, 0.26f), x(ox, side, 0.80f), y(oy, side, 0.84f)),
                -60f,
                300f,
                false,
                stroke,
            )
            canvas.drawLine(
                x(ox, side, 0.5f),
                y(oy, side, 0.12f),
                x(ox, side, 0.5f),
                y(oy, side, 0.46f),
                stroke,
            )
        }

        private fun motion(canvas: Canvas, ox: Float, oy: Float, side: Float) {
            canvas.drawCircle(x(ox, side, 0.36f), y(oy, side, 0.20f), side * 0.075f, fill)
            canvas.drawLine(x(ox, side, 0.36f), y(oy, side, 0.30f), x(ox, side, 0.30f), y(oy, side, 0.60f), stroke)
            canvas.drawLine(x(ox, side, 0.20f), y(oy, side, 0.44f), x(ox, side, 0.52f), y(oy, side, 0.38f), stroke)
            canvas.drawLine(x(ox, side, 0.30f), y(oy, side, 0.60f), x(ox, side, 0.16f), y(oy, side, 0.88f), stroke)
            canvas.drawLine(x(ox, side, 0.30f), y(oy, side, 0.60f), x(ox, side, 0.46f), y(oy, side, 0.86f), stroke)
        }

        private fun thermometer(canvas: Canvas, ox: Float, oy: Float, side: Float) {
            canvas.drawLine(x(ox, side, 0.5f), y(oy, side, 0.12f), x(ox, side, 0.5f), y(oy, side, 0.60f), stroke)
            canvas.drawCircle(x(ox, side, 0.5f), y(oy, side, 0.74f), side * 0.14f, stroke)
            for (tick in listOf(0.26f, 0.40f)) {
                canvas.drawLine(
                    x(ox, side, 0.56f),
                    y(oy, side, tick),
                    x(ox, side, 0.64f),
                    y(oy, side, tick),
                    stroke,
                )
            }
        }

        private fun battery(canvas: Canvas, ox: Float, oy: Float, side: Float) {
            canvas.drawRect(
                RectF(x(ox, side, 0.12f), y(oy, side, 0.30f), x(ox, side, 0.76f), y(oy, side, 0.70f)),
                stroke,
            )
            canvas.drawRect(
                RectF(x(ox, side, 0.78f), y(oy, side, 0.42f), x(ox, side, 0.90f), y(oy, side, 0.58f)),
                fill,
            )
            canvas.drawRect(
                RectF(x(ox, side, 0.20f), y(oy, side, 0.38f), x(ox, side, 0.50f), y(oy, side, 0.62f)),
                fill,
            )
        }

        private fun water(canvas: Canvas, ox: Float, oy: Float, side: Float) {
            val drop = Path().apply {
                moveTo(x(ox, side, 0.5f), y(oy, side, 0.10f))
                cubicTo(
                    x(ox, side, 0.80f),
                    y(oy, side, 0.46f),
                    x(ox, side, 0.76f),
                    y(oy, side, 0.86f),
                    x(ox, side, 0.5f),
                    y(oy, side, 0.90f),
                )
                cubicTo(
                    x(ox, side, 0.24f),
                    y(oy, side, 0.86f),
                    x(ox, side, 0.20f),
                    y(oy, side, 0.46f),
                    x(ox, side, 0.5f),
                    y(oy, side, 0.10f),
                )
                close()
            }
            canvas.drawPath(drop, stroke)
        }

        private fun alert(canvas: Canvas, ox: Float, oy: Float, side: Float) {
            val triangle = Path().apply {
                moveTo(x(ox, side, 0.5f), y(oy, side, 0.10f))
                lineTo(x(ox, side, 0.90f), y(oy, side, 0.86f))
                lineTo(x(ox, side, 0.10f), y(oy, side, 0.86f))
                close()
            }
            canvas.drawPath(triangle, stroke)
            canvas.drawLine(x(ox, side, 0.5f), y(oy, side, 0.40f), x(ox, side, 0.5f), y(oy, side, 0.62f), stroke)
            canvas.drawCircle(x(ox, side, 0.5f), y(oy, side, 0.73f), side * 0.025f, fill)
        }
    }
}
