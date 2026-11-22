package net.lockney.tabula

import java.time.ZoneId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.serialization.json.jsonPrimitive
import net.lockney.tabula.core.screensaver.EntityNeeds
import net.lockney.tabula.core.screensaver.ScreensaverController
import net.lockney.tabula.core.settings.AtAGlance
import net.lockney.tabula.core.settings.Schedule
import net.lockney.tabula.core.settings.ScheduleSlots
import net.lockney.tabula.core.settings.ScheduleSlotsMode
import net.lockney.tabula.core.settings.Screensaver
import net.lockney.tabula.core.settings.Settings
import net.lockney.tabula.core.tlp.ScreensaverMode

/**
 * The schedule against the (virtual) clock (F-SSV-18, packet P3.12): the idle
 * start lands in the slot's mode, a crossing restarts what shows with the
 * `schedule` trigger on both events, and the entity subscription follows the
 * slot's turn-offs. Times in ms, UTC.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScreensaverManagerScheduleTest {
    private val scope = TestScope()
    private val t0 = 1_800_000_000_000L // 2027-01-15T08:00:00Z
    private val shown = mutableListOf<ScreensaverMode?>()
    private val published = mutableListOf<net.lockney.tabula.core.tlp.StateDelta>()
    private val needs = mutableListOf<EntityNeeds>()
    private val manager = ScreensaverManager(
        controller = ScreensaverController(zone = ZoneId.of("UTC")),
        show = { shown += it },
        holdBrightness = {},
        scope = scope,
        now = { t0 + scope.testScheduler.currentTime },
        entityData = { needs += it },
    ).also { it.listener = { delta -> published += delta } }

    private val settings = Settings(
        screensaver = Screensaver(enabled = true, idleTimeout = 60, mode = net.lockney.tabula.core.settings.ScreensaverMode.Clock, brightness = 20),
        atAGlance = AtAGlance(entities = listOf("lock.front")),
        schedule = Schedule(
            enabled = true,
            slots = listOf(
                ScheduleSlots(start = "06:00", mode = ScheduleSlotsMode.Clock),
                // 08:02: black, and At a Glance turned off for this slot
                ScheduleSlots(start = "08:02", mode = ScheduleSlotsMode.Black, atAGlance = false),
            ),
        ),
    )

    @AfterTest
    fun tearDown() = manager.stop()

    /** Every screensaver event so far, as `name mode trigger`. */
    private fun events(): List<String> = buildList {
        while (true) {
            val build = manager.events.tryReceive().getOrNull() ?: break
            val frame = build(1)
            val mode = frame.data["mode"]?.jsonPrimitive?.content
            val trigger = frame.data["trigger"]?.jsonPrimitive?.content
            add("${frame.event.name} $mode $trigger")
        }
    }

    @Test
    fun `an idle start lands in the slot's mode and a crossing restarts it with the schedule trigger`() {
        manager.applySettings(settings)
        manager.setForeground(true)
        manager.start()
        scope.runCurrent()

        scope.advanceTimeBy(60_001)
        scope.runCurrent()
        assertEquals(listOf<ScreensaverMode?>(ScreensaverMode.CLOCK), shown, "08:01: the idle start, in the 06:00 slot's mode")
        assertEquals(listOf("lock.front"), needs.last().entityIds, "At a Glance is on in this slot")
        assertEquals(
            listOf("SCREENSAVER_STARTED clock idle"),
            events(),
        )

        scope.advanceTimeBy(60_001)
        scope.runCurrent()
        assertEquals(
            listOf<ScreensaverMode?>(ScreensaverMode.CLOCK, ScreensaverMode.BLACK),
            shown,
            "08:02: the crossing restarts what shows",
        )
        assertEquals(
            listOf(
                "SCREENSAVER_STOPPED clock schedule",
                "SCREENSAVER_STARTED black schedule",
            ),
            events(),
            "both events of the restart carry the schedule trigger, the started one the mode shown",
        )
        assertTrue(needs.last().isEmpty, "the slot turned At a Glance off, so the subscription closes (F-SSV-18)")
        assertEquals(ScreensaverMode.BLACK, published.last { it.screensaverMode != null }.screensaverMode)
    }

    @Test
    fun `a live edit of the running slot applies through a settings push`() {
        manager.applySettings(settings)
        manager.setForeground(true)
        manager.start()
        scope.runCurrent()
        scope.advanceTimeBy(60_001)
        scope.runCurrent()
        assertEquals(ScreensaverMode.CLOCK, shown.last())

        // 08:01, still before the 08:02 boundary: the running slot is edited
        // to photos; the screensaver is running, so it restarts live
        manager.applySettings(
            settings.copy(
                schedule = Schedule(
                    enabled = true,
                    slots = listOf(
                        ScheduleSlots(start = "06:00", mode = ScheduleSlotsMode.Photos),
                        ScheduleSlots(start = "08:02", mode = ScheduleSlotsMode.Black, atAGlance = false),
                    ),
                ),
            ),
        )
        assertEquals(
            listOf<ScreensaverMode?>(ScreensaverMode.CLOCK, ScreensaverMode.PHOTOS),
            shown,
            "the edit applies live, including to a running screensaver (F-SSV-18)",
        )
        assertEquals(
            listOf(
                "SCREENSAVER_STARTED clock idle",
                "SCREENSAVER_STOPPED clock schedule",
                "SCREENSAVER_STARTED photos schedule",
            ),
            events(),
        )
    }
}
