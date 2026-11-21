package net.lockney.tabula.core.screensaver

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
 * Not modelled yet: the black mode's hide-everything option (F-SSV-06) has no
 * settings key, and per-slot overlay overrides are the schedule's (P3.12).
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
        fun of(settings: Settings, mode: ScreensaverMode?): EntityNeeds {
            if (mode == null || mode == ScreensaverMode.CALENDAR) return EntityNeeds()
            val ids = LinkedHashSet<String>()
            val forecasts = LinkedHashSet<Forecast>()
            ids += settings.atAGlance.entities
            if (mode == ScreensaverMode.WEATHER) {
                val weather = settings.screensaver.weather.entityId
                if (weather.isNotEmpty()) {
                    ids += weather
                    ids += SUN
                    forecasts += Forecast(weather)
                }
            }
            val widgets = settings.widgets
            if (widgets.shown) {
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
