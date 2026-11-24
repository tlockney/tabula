package net.lockney.tabula.core.screensaver.glance

import net.lockney.tabula.core.Constants
import net.lockney.tabula.core.settings.AtAGlance
import net.lockney.tabula.core.settings.AtAGlanceStyle
import net.lockney.tabula.core.tlp.ScreensaverMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P3.6's row layout: ordering, placement, wrapping, the shrink-then-truncate
 * fitting order, the empty-value drop-out and the night behavior (F-AAG-01 to
 * F-AAG-03, F-SSV-10). The text measure is a fake font - 0.6 × the size per
 * character - so every width in these tests is arithmetic, not a device.
 */
class AtAGlanceRowTest {
    private val planner = AtAGlanceRow { text, sizePx -> text.length * sizePx * 0.6f }

    private fun value(id: String, name: String, state: String, icon: String? = null, active: Boolean = true) =
        GlanceValue(entityId = id, name = name, value = state, icon = icon, active = active)

    /** Two chips of the same shape: "On" / "Front Door" and "Open" / "Garage". */
    private val lock = value("lock.front", "Front Door", "On")
    private val garage = value("cover.garage", "Garage", "Open")

    private fun settings(vararg ids: String, hideNames: Boolean = false, scale: Int = 100, style: AtAGlanceStyle = AtAGlanceStyle.Pill) =
        AtAGlance(entities = ids.toList(), hideNames = hideNames, scale = scale, style = style)

    @Test
    fun `nothing shows without a mode, without entities or without data`() {
        val configured = settings("lock.front")
        assertFalse(planner.plan(configured, listOf(lock), null, widthPx = 1000f).visible)
        assertFalse(planner.plan(settings(), listOf(lock), ScreensaverMode.CLOCK, widthPx = 1000f).visible)
        assertFalse(planner.plan(configured, emptyList(), ScreensaverMode.CLOCK, widthPx = 1000f).visible)
        assertFalse(planner.plan(configured, listOf(lock), ScreensaverMode.CLOCK, widthPx = 0f).visible)
    }

    @Test
    fun `the row follows the settings' order, whatever order the data arrives in`() {
        val row = planner.plan(
            settings("cover.garage", "lock.front"),
            listOf(lock, garage),
            ScreensaverMode.CLOCK,
            widthPx = 4000f,
        )
        assertEquals(listOf("cover.garage", "lock.front"), row.chips.map { it.entityId })
    }

    @Test
    fun `unlisted entities and blank values drop out, unknown and unavailable stay`() {
        val row = planner.plan(
            settings("lock.front", "cover.garage", "sensor.temp"),
            listOf(
                lock,
                garage.copy(value = ""),
                value("sensor.temp", "Temperature", "Unavailable", active = false),
                value("light.hall", "Hall Light", "On"),
            ),
            ScreensaverMode.CLOCK,
            widthPx = 4000f,
        )
        assertEquals(listOf("lock.front", "sensor.temp"), row.chips.map { it.entityId })
        assertEquals("Unavailable", row.chips.last().value)
        assertFalse(row.chips.last().active)
    }

    @Test
    fun `placement follows the mode, and calendar and dim never draw it`() {
        assertEquals(GlancePlacement.UnderDate, planner.placement(ScreensaverMode.CLOCK))
        assertEquals(GlancePlacement.Centered, planner.placement(ScreensaverMode.BLACK))
        assertEquals(GlancePlacement.Bottom, planner.placement(ScreensaverMode.PHOTOS))
        assertEquals(GlancePlacement.Bottom, planner.placement(ScreensaverMode.WEBSITE))
        assertNull(planner.placement(ScreensaverMode.CALENDAR))
        assertNull(planner.placement(ScreensaverMode.DIM))
        assertNull(planner.placement(null))
        val calendar = planner.plan(settings("lock.front"), listOf(lock), ScreensaverMode.CALENDAR, widthPx = 1000f)
        assertFalse(calendar.visible)
    }

    @Test
    fun `night hides the row when the hide-overlays option is on and recolors it otherwise`() {
        val configured = settings("lock.front")
        val hidden = planner.plan(
            configured,
            listOf(lock),
            ScreensaverMode.CLOCK,
            night = true,
            hideAtNight = true,
            widthPx = 1000f,
        )
        assertFalse(hidden.visible)
        val dimmed = planner.plan(
            configured,
            listOf(lock),
            ScreensaverMode.CLOCK,
            night = true,
            hideAtNight = false,
            widthPx = 1000f,
        )
        assertTrue(dimmed.visible)
        assertTrue(dimmed.night)
    }

    @Test
    fun `portrait wraps onto two lines where landscape cannot`() {
        val configured = settings("lock.front", "cover.garage")
        // two chips need 166 + 125.2 + 12 = 303.2 px at full scale; 200 px is one per line
        val portrait = planner.plan(configured, listOf(lock, garage), ScreensaverMode.CLOCK, portrait = true, widthPx = 200f)
        assertTrue(portrait.visible)
        assertEquals(2, portrait.lines.size)
        assertEquals(1f, portrait.textScale)
        assertFalse(portrait.truncateNames)
        val landscape = planner.plan(configured, listOf(lock, garage), ScreensaverMode.CLOCK, widthPx = 200f)
        assertTrue(landscape.visible)
        assertEquals(1, landscape.lines.size)
        assertTrue(landscape.truncateNames)
    }

    @Test
    fun `text shrinks before names are truncated`() {
        val configured = settings("lock.front", "cover.garage", "light.hall")
        val hall = value("light.hall", "Hall Light", "On")
        // 481.2 px at full scale, 438.7 px at 85 %: 440 px must shrink, not truncate
        val row = planner.plan(configured, listOf(lock, garage, hall), ScreensaverMode.CLOCK, widthPx = 440f)
        assertTrue(row.visible)
        assertEquals(1, row.lines.size)
        assertTrue(row.textScale < 1f)
        assertTrue(row.textScale >= Constants.GLANCE_MIN_TEXT_SCALE)
        assertFalse(row.truncateNames)
        assertEquals(listOf("Front Door", "Garage", "Hall Light"), row.chips.map { it.name })
    }

    @Test
    fun `at the shrink floor a long name is ellipsized, and the value is kept whole`() {
        val configured = settings("lock.front", "cover.garage")
        val row = planner.plan(configured, listOf(lock, garage), ScreensaverMode.CLOCK, widthPx = 240f)
        assertTrue(row.visible)
        assertEquals(Constants.GLANCE_MIN_TEXT_SCALE, row.textScale)
        assertTrue(row.truncateNames)
        val first = row.chips.first()
        assertTrue(first.name.endsWith(Constants.GLANCE_ELLIPSIS))
        assertEquals("On", first.value)
        assertEquals(2, row.lines.sumOf { it.size } + row.omitted)
    }

    @Test
    fun `hide-names widens the values and the scale setting sizes the row`() {
        val shown = planner.plan(settings("lock.front"), listOf(lock), ScreensaverMode.CLOCK, widthPx = 1000f)
        val hidden = planner.plan(
            settings("lock.front", hideNames = true),
            listOf(lock),
            ScreensaverMode.CLOCK,
            widthPx = 1000f,
        )
        assertEquals(1, shown.chips.size)
        assertEquals(1, hidden.chips.size)
        val big = GlanceMetrics.of(200, 1f)
        val small = GlanceMetrics.of(50, 1f)
        assertEquals(4f * small.valuePx, big.valuePx, 0.001f)
    }

    @Test
    fun `glyphs come from the icon, then the domain, then the domain's initial`() {
        assertEquals(GlanceGlyph.Lock, GlanceIcons.glyph("mdi:lock", "sensor"))
        assertEquals(GlanceGlyph.Unlock, GlanceIcons.glyph("mdi:lock-open", "lock"))
        assertEquals(GlanceGlyph.Garage, GlanceIcons.glyph(null, "garage_door"))
        assertEquals(GlanceGlyph.Light, GlanceIcons.glyph("mdi:lightbulb-outline", "binary_sensor"))
        assertEquals(GlanceGlyph.Initial, GlanceIcons.glyph("mdi:something-new", "vacuum"))
        assertEquals(GlanceGlyph.Initial, GlanceIcons.glyph(null, "vacuum"))
        assertEquals("V", GlanceIcons.initial("vacuum"))
        val chip = planner.plan(
            settings("vacuum.roomba"),
            listOf(value("vacuum.roomba", "Roomba", "Docked", icon = "mdi:robot-vacuum")),
            ScreensaverMode.CLOCK,
            widthPx = 1000f,
        ).chips.single()
        assertEquals(GlanceGlyph.Initial, chip.glyph)
        assertEquals("V", chip.initial)
    }

    @Test
    fun `a state change re-renders the same chip with the new value`() {
        val configured = settings("lock.front")
        val before = planner.plan(configured, listOf(lock), ScreensaverMode.CLOCK, widthPx = 1000f).chips.single()
        val after = planner.plan(
            configured,
            listOf(lock.copy(value = "Off", active = false)),
            ScreensaverMode.CLOCK,
            widthPx = 1000f,
        ).chips.single()
        assertEquals("On", before.value)
        assertEquals("Off", after.value)
        assertFalse(after.active)
    }
}
