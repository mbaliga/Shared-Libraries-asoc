package dev.aarso.localsession

import java.io.Closeable
import java.util.ArrayDeque
import java.util.LinkedHashMap

/**
 * In-process fan-out for same-device sessions and transport adapters.
 *
 * A nearby transport feeds authenticated commands to [dispatch]. Observers receive only accepted,
 * non-duplicate transitions; rejected commands are returned to the caller and do not perturb peers.
 */
class LocalSessionCoordinator(
    private val engine: LocalSessionEngine,
    private val onObserverFailure: (Exception) -> Unit = {},
) {
    private val observers = LinkedHashMap<Long, (SessionResult.Accepted) -> Unit>()
    private val pendingDeliveries = ArrayDeque<PendingDelivery>()
    private val dispatchGate = Any()
    private var nextObserverId = 0L
    private var delivering = false

    @Synchronized
    fun snapshot(): LocalSessionSnapshot = engine.snapshot()

    @Synchronized
    fun subscribe(observer: (SessionResult.Accepted) -> Unit): Closeable {
        val observerId = nextObserverId++
        observers[observerId] = observer
        return Closeable { unsubscribe(observerId) }
    }

    fun dispatch(command: SessionCommand): SessionResult {
        var shouldDrain = false
        val result = synchronized(dispatchGate) {
            val applied = engine.apply(command)
            if (applied is SessionResult.Accepted && !applied.duplicate) {
                synchronized(this) {
                    pendingDeliveries.addLast(PendingDelivery(applied, observers.values.toList()))
                    if (!delivering) {
                        delivering = true
                        shouldDrain = true
                    }
                }
            }
            applied
        }
        if (shouldDrain) drainDeliveries()
        return result
    }

    private fun drainDeliveries() {
        while (true) {
            val delivery = synchronized(this) {
                if (pendingDeliveries.isEmpty()) {
                    delivering = false
                    null
                } else {
                    pendingDeliveries.removeFirst()
                }
            } ?: return
            delivery.listeners.forEach { listener ->
                try {
                    listener(delivery.result)
                } catch (failure: Exception) {
                    onObserverFailure(failure)
                }
            }
        }
    }

    @Synchronized
    private fun unsubscribe(observerId: Long) {
        observers.remove(observerId)
    }

    private data class PendingDelivery(
        val result: SessionResult.Accepted,
        val listeners: List<(SessionResult.Accepted) -> Unit>,
    )
}
