package net.lockney.tabula.core.screensaver.clock

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import net.lockney.tabula.core.screensaver.clock.ClockBackgroundController.Effect

/**
 * The background-image loader's rules against a fake clock (F-SSV-09,
 * packet P3.3): load on start, show on success, failure keeps the previous
 * image and retries later, the re-fetch interval drives URL ticks, and a
 * settings write always orders a fresh load, identical source or not.
 */
class ClockBackgroundControllerTest {
    private var t = 1_800_000_000_000L
    private fun now() = t
    private val controller = ClockBackgroundController(::now)

    private fun effects(outcome: ClockBackgroundController.Outcome) = outcome.effects.map { it::class.simpleName }

    private val url = "http://ha.local/cam.jpg"
    private val path = "/storage/emulated/0/Pictures/wall.jpg"

    @Test
    fun `a blank source clears and schedules nothing`() {
        val outcome = controller.start("", 60_000, now())
        assertEquals(listOf("Clear"), effects(outcome))
        assertNull(controller.nextDueMs)
        // nothing is ever scheduled; a blank source stays blank
        assertTrue(controller.onTick(now() + 120_000).effects.isEmpty())
    }

    @Test
    fun `a URL loads at start, shows on success and re-fetches per the interval`() {
        assertEquals(listOf("Load"), effects(controller.start(url, 60_000, now())))
        assertTrue(controller.busy)
        assertEquals(t + 60_000, controller.nextDueMs)

        val handle = Any()
        val shown = controller.onLoaded(handle, now())
        assertEquals(listOf<Any?>(Effect.Show(handle)), shown.effects)
        assertEquals(t + 60_000, controller.nextDueMs)

        // before the interval: nothing; past it: a fresh load
        assertTrue(controller.onTick(now() + 59_999).effects.isEmpty())
        assertEquals(listOf("Load"), effects(controller.onTick(now() + 60_000)))
        assertTrue(controller.busy)
    }

    @Test
    fun `a failed load keeps the previous image and retries an interval later`() {
        controller.start(url, 60_000, now())
        val first = Any()
        controller.onLoaded(first, now())

        val failed = controller.onLoadFailed(now())
        assertTrue(failed.effects.isEmpty(), "a failure must not clear or show anything")
        assertEquals(t + 60_000, controller.nextDueMs)

        // the retry is the interval away, not immediate
        assertTrue(controller.onTick(now() + 59_999).effects.isEmpty())
        assertEquals(listOf("Load"), effects(controller.onTick(now() + 60_000)))
    }

    @Test
    fun `a failed load without an interval waits for the next write or start`() {
        controller.start(url, 0, now())
        val first = Any()
        controller.onLoaded(first, now())
        assertNull(controller.nextDueMs)

        assertTrue(controller.onLoadFailed(now()).effects.isEmpty())
        assertNull(controller.nextDueMs)
        // no interval: no tick ever orders a retry (ADR 0018)
        assertTrue(controller.onTick(now() + 86_400_000).effects.isEmpty())
    }

    @Test
    fun `a settings write always reloads, identical source or not`() {
        controller.start(url, 0, now())
        val first = Any()
        controller.onLoaded(first, now())

        // the same URL, same interval: still a fresh load (F-SSV-09)
        assertEquals(listOf("Load"), effects(controller.applySettings(url, 0, now())))
        // a different source loads too
        assertEquals(listOf("Load"), effects(controller.applySettings(path, 0, now())))
    }

    @Test
    fun `a settings write to a blank source clears and stops loading`() {
        controller.start(url, 0, now())
        assertEquals(listOf("Clear"), effects(controller.applySettings("", 0, now())))
        // the load in flight is superseded; its late answer is ignored
        assertTrue(controller.onLoaded(Any(), now()).effects.isEmpty())
    }

    @Test
    fun `a local file loads but never re-fetches on a timer`() {
        assertEquals(listOf("Load"), effects(controller.start(path, 60_000, now())))
        assertNull(controller.nextDueMs, "the re-fetch interval applies to URLs only (F-SSV-09)")
        controller.onLoaded(Any(), now())
        assertTrue(controller.onTick(now() + 120_000).effects.isEmpty())
        // a write still reloads a local file (F-SSV-09)
        assertEquals(listOf("Load"), effects(controller.applySettings(path, 60_000, now())))
    }

    @Test
    fun `a write with an interval schedules the next re-fetch from the write`() {
        controller.start(url, 60_000, now())
        controller.onLoaded(Any(), now())
        assertEquals(listOf("Load"), effects(controller.applySettings(url, 30_000, now() + 10)))
        assertEquals(t + 10 + 30_000, controller.nextDueMs)
    }

    @Test
    fun `after a stop nothing loads and late answers are ignored`() {
        controller.start(url, 60_000, now())
        assertTrue(controller.stop().effects.isEmpty())
        assertTrue(!controller.busy)
        assertNull(controller.nextDueMs)
        // no writes land while the clock is not showing
        assertTrue(controller.applySettings(url, 0, now()).effects.isEmpty())
        assertTrue(controller.onLoaded(Any(), now()).effects.isEmpty())
        assertTrue(controller.onTick(now() + 120_000).effects.isEmpty())
    }

    @Test
    fun `a load in flight holds the ticks`() {
        controller.start(url, 60_000, now())
        assertTrue(controller.onTick(now() + 120_000).effects.isEmpty(), "a load already answers the reload rule")
    }
}
