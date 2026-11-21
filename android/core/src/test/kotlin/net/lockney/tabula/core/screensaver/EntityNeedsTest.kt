package net.lockney.tabula.core.screensaver

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import net.lockney.tabula.core.screensaver.EntityNeeds.Forecast
import net.lockney.tabula.core.settings.AtAGlance
import net.lockney.tabula.core.settings.Screensaver
import net.lockney.tabula.core.settings.ScreensaverWeather
import net.lockney.tabula.core.settings.Settings
import net.lockney.tabula.core.settings.Widgets
import net.lockney.tabula.core.settings.WidgetsEntity
import net.lockney.tabula.core.settings.WidgetsForecast
import net.lockney.tabula.core.settings.WidgetsWeather
import net.lockney.tabula.core.tlp.ScreensaverMode

/** Which entities and forecasts the showing screensaver subscribes to (F-AAG-04). */
class EntityNeedsTest {
    private val glance = AtAGlance(entities = listOf("lock.front", "binary_sensor.garage"))
    private val widgets = Widgets(
        weather = WidgetsWeather(enabled = true, entityId = "weather.home"),
        forecast = WidgetsForecast(enabled = true, entityId = "weather.home"),
        entity = WidgetsEntity(enabled = true, entityId = "sensor.outside"),
    )
    private val full = Settings(atAGlance = glance, widgets = widgets)

    @Test
    fun `nothing is needed while the screensaver is not showing`() {
        assertTrue(EntityNeeds.of(full, null).isEmpty)
    }

    @Test
    fun `the row and the widgets need their own entities, in order, without duplicates`() {
        val needs = EntityNeeds.of(full, ScreensaverMode.CLOCK)
        assertEquals(listOf("lock.front", "binary_sensor.garage", "weather.home", "sun.sun", "sensor.outside"), needs.entityIds)
        assertEquals(listOf(Forecast("weather.home", "daily")), needs.forecasts)
    }

    @Test
    fun `calendar mode draws no overlays and needs nothing`() {
        assertTrue(EntityNeeds.of(full, ScreensaverMode.CALENDAR).isEmpty)
    }

    @Test
    fun `hidden widgets, disabled widgets and widgets without an entity need nothing`() {
        val hidden = full.copy(widgets = widgets.copy(shown = false))
        assertEquals(glance.entities, EntityNeeds.of(hidden, ScreensaverMode.BLACK).entityIds)
        assertTrue(EntityNeeds.of(hidden, ScreensaverMode.BLACK).forecasts.isEmpty())

        val off = Settings(
            widgets = Widgets(
                weather = WidgetsWeather(enabled = false, entityId = "weather.home"),
                forecast = WidgetsForecast(enabled = true, entityId = ""),
                entity = WidgetsEntity(enabled = true, entityId = ""),
            ),
        )
        assertTrue(EntityNeeds.of(off, ScreensaverMode.DIM).isEmpty)
    }

    @Test
    fun `the weather scene follows its entity, the sun and the daily forecast`() {
        val settings = Settings(screensaver = Screensaver(weather = ScreensaverWeather(entityId = "weather.home")))
        val needs = EntityNeeds.of(settings, ScreensaverMode.WEATHER)
        assertEquals(listOf("weather.home", "sun.sun"), needs.entityIds)
        assertEquals(listOf(Forecast("weather.home")), needs.forecasts)
        // the scene's entity is not needed by other modes
        assertTrue(EntityNeeds.of(settings, ScreensaverMode.PHOTOS).isEmpty)
        // with no entity chosen the scene shows its prompt and needs nothing
        assertTrue(EntityNeeds.of(Settings(), ScreensaverMode.WEATHER).isEmpty)
    }
}
