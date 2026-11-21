package net.lockney.tabula.ha

import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

class LastResortTest {
    @Test
    fun `an escaped network failure is reported and the scope keeps working`() = runBlocking {
        val reported = mutableListOf<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + lastResortHandler { reported += it })

        // issue #117: the shape of the crash, a POST failing DNS inside a launch
        scope.launch { throw UnknownHostException("Unable to resolve host") }.join()

        assertEquals(1, reported.size)
        assertTrue(reported.single() is UnknownHostException)
        assertTrue(scope.isActive)
        var ran = false
        scope.launch { ran = true }.join()
        assertTrue(ran)
        scope.cancel()
    }

    @Test
    fun `a report that throws is contained`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + lastResortHandler { error("report broke") })
        scope.launch { throw UnknownHostException("x") }.join()
        assertTrue(scope.isActive)
        scope.cancel()
    }
}
