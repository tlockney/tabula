package net.lockney.tabula.ha

import kotlinx.coroutines.CoroutineExceptionHandler

/**
 * The last line of defence for a long-lived scope (issue #117): an exception
 * that escapes a `launch` is reported to [report] instead of reaching the
 * thread's uncaught-exception handler, which on Android kills the process.
 *
 * Every network path still catches its own failures; this only keeps a
 * missed one from taking the app down. Use it with a SupervisorJob, so one
 * failed child does not cancel its siblings. [report] runs on the failing
 * coroutine's thread and must not throw.
 */
fun lastResortHandler(report: (Throwable) -> Unit): CoroutineExceptionHandler =
    CoroutineExceptionHandler { _, e -> runCatching { report(e) } }
