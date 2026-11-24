package net.lockney.tabula.core.intercom

import net.lockney.tabula.core.Constants
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The call screen's two timing values are ADR 0002's, not literals in the UI
 * (review of #176): the tick that moves the call clock, and the command
 * timeout the directory request waits on. Both are asserted here so a silent
 * edit to either has to face a test that names the decision.
 */
class IntercomConstantsTest {
    @Test
    fun `the call screen's refresh interval is the recorded one`() {
        assertEquals(1_000, Constants.INTERCOM_UI_TICK_MS)
    }

    @Test
    fun `the directory request uses the recorded command timeout`() {
        // the same value HA gives every other command it is asked for
        assertEquals(10, Constants.COMMAND_TIMEOUT_S)
    }
}
