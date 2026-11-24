package net.lockney.tabula.core.tlp

import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * `intercom_start_request`'s target rule (PROTOCOL 4.5, review of #171 finding
 * 5): a call names exactly one of a device and an area, a broadcast neither.
 */
class IntercomStartFrameTest {
    @Test
    fun `a call frame needs exactly one target`() {
        EventFrame.intercomStartRequest(1, "call-abc", IntercomMode.CALL, targetDeviceId = "device-1")
        EventFrame.intercomStartRequest(2, "call-abc", IntercomMode.CALL, targetAreaId = "area_1")
        assertFailsWith<IllegalArgumentException> {
            EventFrame.intercomStartRequest(3, "call-abc", IntercomMode.CALL)
        }
        assertFailsWith<IllegalArgumentException> {
            EventFrame.intercomStartRequest(4, "call-abc", IntercomMode.CALL, "device-1", "area_1")
        }
    }

    @Test
    fun `a broadcast frame takes no target`() {
        EventFrame.intercomStartRequest(1, "call-abc", IntercomMode.BROADCAST)
        assertFailsWith<IllegalArgumentException> {
            EventFrame.intercomStartRequest(2, "call-abc", IntercomMode.BROADCAST, targetDeviceId = "device-1")
        }
        assertFailsWith<IllegalArgumentException> {
            EventFrame.intercomStartRequest(3, "call-abc", IntercomMode.BROADCAST, targetAreaId = "area_1")
        }
    }
}
