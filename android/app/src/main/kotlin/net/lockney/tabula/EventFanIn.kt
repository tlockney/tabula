package net.lockney.tabula

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.launch
import net.lockney.tabula.core.tlp.EventFrame

/**
 * One event channel from the device's several event sources (screensaver,
 * notifications, media), each of which owns a bounded queue while it waits
 * for the TLP session (ADR 0002). The merged channel holds at most the sum
 * of its sources' bounds: the copy loops never block, and the session drains
 * it - dropping what came before registration - exactly as it did when the
 * screensaver was the only source.
 */
class EventFanIn(
    scope: CoroutineScope,
    sources: List<ReceiveChannel<(Int) -> EventFrame>>,
) {
    private val merged = Channel<(Int) -> EventFrame>(Channel.UNLIMITED)

    val events: ReceiveChannel<(Int) -> EventFrame> get() = merged

    init {
        for (source in sources) {
            scope.launch {
                for (frame in source) merged.trySend(frame)
            }
        }
    }
}
