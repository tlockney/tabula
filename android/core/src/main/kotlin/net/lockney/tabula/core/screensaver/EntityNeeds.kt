package net.lockney.tabula.core.screensaver

import net.lockney.tabula.core.settings.ScheduleSlots
import net.lockney.tabula.core.settings.Settings
import net.lockney.tabula.core.tlp.ScreensaverMode

/**
 * The HA data the showing screensaver needs (F-AAG-04): the explicit entity
 * list for `subscribe_entities` and the forecasts to subscribe to. Empty means
 * no subscription is opened at all.
 *
 * Which overlays draw where (FEATURES):
 * - At a Glance (F-AAG-01) and the corner widgets (F-WGT-01) draw over every
 *   mode except calendar;
 * - widgets only while `widgets.shown`, each only while enabled with an entity;
 * - the weather scene (F-SSV-11) and weather widget (F-WGT-04) also follow
 *   `sun.sun` for time of day, and the scene reads the daily forecast for its
 *   high/low line; the forecast widget (F-WGT-05) reads the daily forecast.
 *
 * The schedule's slot overrides (F-SSV-18, P3.12) can only turn off overlays
 * that are configured globally, never invent them: a slot with widgets or
 * At a Glance off drops those subscriptions; on (or null, inherit) keeps the
 * global configuration. Not modelled yet: the black mode's hide-everything
 * option (F-SSV-06) has no settings key, and the now-playing view's slot
 * override has none either.
 */
data class EntityNeeds(
    val entityIds: List<String> = emptyList(),
    val forecasts: List<Forecast> = emptyList(),
) {
    /** One `weather/subscribe_forecast`; HA's forecast types are daily, hourly and twice_daily. */
    data class Forecast(val entityId: String, val type: String = DAILY)

    val isEmpty: Boolean get() = entityIds.isEmpty() && forecasts.isEmpty()

    companion object {
        const val DAILY = "daily"
        const val SUN = "sun.sun"

        /** What [mode] needs under [settings]; [mode] null (not showing) needs nothing. */
        fun of(settings: Settings, mode: ScreensaverMode?, slot: ScheduleSlots? = null): EntityNeeds {
            if (mode == null || mode == ScreensaverMode.CALENDAR) return EntityNeeds()
            val ids = LinkedHashSet<String>()
            val forecasts = LinkedHashSet<Forecast>()
            // the slot can only turn At a Glance off, never invent it (F-SSV-18)
            if (slot?.atAGlance != false) ids += settings.atAGlance.entities
            if (mode == ScreensaverMode.WEATHER) {
                val weather = settings.screensaver.weather.entityId
                if (weather.isNotEmpty()) {
                    ids += weather
                    ids += SUN
                    forecasts += Forecast(weather)
                }
            }
            // the slot can only turn the widgets off, never invent them (F-SSV-18)
            val widgets = settings.widgets
            if (widgets.shown && slot?.widgets != false) {
                if (widgets.weather.enabled && widgets.weather.entityId.isNotEmpty()) {
                    ids += widgets.weather.entityId
                    ids += SUN
                }
                if (widgets.forecast.enabled && widgets.forecast.entityId.isNotEmpty()) {
                    forecasts += Forecast(widgets.forecast.entityId)
                }
                if (widgets.entity.enabled && widgets.entity.entityId.isNotEmpty()) {
                    ids += widgets.entity.entityId
                }
            }
            return EntityNeeds(ids.toList(), forecasts.toList())
        }
    }
}
