package net.lockney.tabula.core.screensaver

import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import net.lockney.tabula.core.settings.Schedule
import net.lockney.tabula.core.settings.ScheduleSlots
import net.lockney.tabula.core.settings.ScheduleSlotsMode

/**
 * The schedule engine (F-SSV-18, packet P3.12): slot lookup by wall-clock
 * time, the wrap past midnight, and the next boundary. Times in ms, UTC.
 */
class ScheduleEngineTest {
    private val zone = ZoneId.of("UTC")
    private val t0 = 1_800_000_000_000L // 2027-01-15T08:00:00Z, a whole second

    private fun slot(start: String, mode: ScheduleSlotsMode = ScheduleSlotsMode.Black) =
        ScheduleSlots(start = start, mode = mode)

    private fun engine(vararg slots: ScheduleSlots, enabled: Boolean = true) =
        ScheduleEngine(Schedule(enabled = enabled, slots = slots.toList()), zone)

    @Test
    fun `a slot lasts until the next one starts`() {
        val e = engine(slot("06:00"), slot("20:00", ScheduleSlotsMode.Clock))
        assertEquals(ScheduleSlotsMode.Black, e.slotAt(t0)?.mode, "08:00 is inside the 06:00 slot")
        assertEquals("06:00", e.slotAt(t0)?.start)
    }

    @Test
    fun `the last slot wraps past midnight, into the next day's first start`() {
        val e = engine(slot("06:00", ScheduleSlotsMode.Clock), slot("20:00"))
        val lateNight = t0 + 16 * 3_600_000 // 2027-01-16T00:00:00Z
        assertEquals(ScheduleSlotsMode.Black, e.slotAt(lateNight)?.mode, "00:00 is still the 20:00 slot")
        val earlyMorning = t0 - 8 * 3_600_000 // 2027-01-15T00:00:00Z
        assertEquals(ScheduleSlotsMode.Black, e.slotAt(earlyMorning)?.mode, "before the first start the last one runs from yesterday")
        val afterFirstStart = t0 - 7 * 3_600_000 // 2027-01-15T01:00:00Z - still before 06:00
        assertEquals(ScheduleSlotsMode.Black, e.slotAt(afterFirstStart)?.mode)
        val morning = t0 + 3 * 3_600_000 // 2027-01-15T11:00:00Z, inside 06:00
        assertEquals(ScheduleSlotsMode.Clock, e.slotAt(morning)?.mode)
    }

    @Test
    fun `a slot starts exactly at its start time`() {
        val e = engine(slot("06:00"), slot("08:00", ScheduleSlotsMode.Clock))
        assertEquals(ScheduleSlotsMode.Clock, e.slotAt(t0)?.mode, "08:00:00 is the 08:00 slot, not the 06:00 one")
    }

    @Test
    fun `the next boundary is the next start, and the day's last start is followed by the first`() {
        val e = engine(slot("06:00", ScheduleSlotsMode.Clock), slot("20:00"))
        assertEquals("2027-01-15T20:00:00Z", e.nextBoundaryMs(t0)?.let { java.time.Instant.ofEpochMilli(it).toString() })
        val evening = t0 + 13 * 3_600_000 // 2027-01-15T21:00:00Z
        assertEquals(
            "2027-01-16T06:00:00Z",
            e.nextBoundaryMs(evening)?.let { java.time.Instant.ofEpochMilli(it).toString() },
            "after the last start the next is the first, tomorrow",
        )
        // at a slot's start the slot is already active, so the wait moves on
        assertEquals("2027-01-15T20:00:00Z", e.nextBoundaryMs(t0)?.let { java.time.Instant.ofEpochMilli(it).toString() })
    }

    @Test
    fun `a schedule that is off or empty never answers`() {
        val off = engine(slot("06:00"), enabled = false)
        assertNull(off.slotAt(t0))
        assertNull(off.nextBoundaryMs(t0))
        val empty = engine()
        assertNull(empty.slotAt(t0))
        assertNull(empty.nextBoundaryMs(t0))
    }

    @Test
    fun `slots arrive in any order and are read by start time`() {
        val e = engine(slot("20:00"), slot("06:00", ScheduleSlotsMode.Clock))
        assertEquals(ScheduleSlotsMode.Clock, e.slotAt(t0)?.mode, "08:00 is inside the 06:00 slot either way")
        assertEquals(ScheduleSlotsMode.Black, e.slotAt(t0 + 12 * 3_600_000)?.mode, "20:00 starts its slot in order")
    }
}
