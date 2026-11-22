package net.lockney.tabula.core.screensaver

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import net.lockney.tabula.core.settings.Schedule
import net.lockney.tabula.core.settings.ScheduleSlots

/**
 * The screensaver schedule (F-SSV-18, packet P3.12): daily time slots, each
 * with a start time and a mode; a slot lasts until the next one starts and
 * the last wraps past midnight. No day-of-week variation. Plain JVM and
 * immutable: the owner rebuilds one whenever the schedule settings change.
 *
 * The engine only answers *which slot is active when*. What a slot's
 * overrides mean against the global configuration is resolved where each
 * consumer knows its own global setting - the controller (mode, brightness,
 * power-off delay, presence dismissal) and the entity subscription (widgets,
 * At a Glance) - because a slot can only turn off overlays that are
 * configured globally, never invent them (F-SSV-18).
 *
 * Slot starts are local wall times at minute resolution (the schema's HH:MM);
 * [zone] is the clock that reads them, so the same schedule follows the
 * tablet through a timezone change.
 */
class ScheduleEngine(
    private val schedule: Schedule = Schedule(),
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    /** Whether any slot ever applies: the switch is on and at least one slot exists (F-SSV-18). */
    val active: Boolean get() = slots.isNotEmpty() && schedule.enabled

    /**
     * The slot that has started at [nowMs] and not been superseded, or null
     * when the schedule cannot answer (off or no slots: inherit everything).
     */
    fun slotAt(nowMs: Long): ScheduleSlots? {
        if (!active) return null
        val minute = minuteOfDay(nowMs)
        // the last slot that has started; before the day's first start the
        // last slot is still running from yesterday (the wrap, F-SSV-18)
        return (slots.lastOrNull { it.startMinutes <= minute } ?: slots.last()).slot
    }

    /**
     * When the next slot starts after [nowMs], or null when none ever does.
     * The clock owner waits on this so a crossing applies the minute it
     * happens - while the screensaver is showing, idle, or paused.
     */
    fun nextBoundaryMs(nowMs: Long): Long? {
        if (!active) return null
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        val minute = now.toLocalTime().let { it.hour * 60 + it.minute }
        // strictly after the current minute: at a slot's start the slot is
        // already active, so the wait moves to the next one
        val next = slots.firstOrNull { it.startMinutes > minute } ?: slots.first()
        val day = if (next.startMinutes > minute) now.toLocalDate() else now.toLocalDate().plusDays(1)
        return day.atTime(LocalTime.of(next.startMinutes / 60, next.startMinutes % 60))
            .atZone(zone).toInstant().toEpochMilli()
    }

    private data class Entry(val startMinutes: Int, val slot: ScheduleSlots)

    /** Start times in order; two slots at one time - the later list entry wins. */
    private val slots: List<Entry> =
        schedule.slots.map { Entry(startMinutes(it.start), it) }.sortedBy { it.startMinutes }

    private fun minuteOfDay(nowMs: Long): Int {
        val time = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalTime()
        return time.hour * 60 + time.minute
    }

    private companion object {
        /** "HH:MM" to minutes past midnight; the settings schema validated the shape. */
        fun startMinutes(start: String): Int =
            start.substringBefore(':').toInt() * 60 + start.substringAfter(':').toInt()
    }
}
