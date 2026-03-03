package com.keel.kernel.events

import com.keel.contract.events.KeelEvent
import com.keel.contract.events.KeelEventBus
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import com.keel.kernel.logging.KeelLoggerService
import java.util.concurrent.ConcurrentHashMap

/**
 * Default implementation of KeelEventBus using MutableSharedFlow.
 * Provides inter-plugin communication via event publishing and subscription.
 */
class DefaultKeelEventBus : KeelEventBus {

    private val logger = KeelLoggerService.getLogger("EventBus")

    private val _events = MutableSharedFlow<KeelEvent>(
        replay = 0,
        extraBufferCapacity = 64
    )

    private val typedFlows = ConcurrentHashMap<Class<out KeelEvent>, MutableSharedFlow<KeelEvent>>()

    override val events: SharedFlow<KeelEvent> = _events

    override suspend fun publish(event: KeelEvent) {
        logger.debug("Publishing event: ${event::class.simpleName}")
        _events.emit(event)
        typedFlows.forEach { (clazz, flow) ->
            if (clazz.isInstance(event)) {
                flow.emit(event)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : KeelEvent> subscribe(eventClass: Class<T>): SharedFlow<T> {
        val flow = typedFlows.getOrPut(eventClass) {
            MutableSharedFlow(replay = 0, extraBufferCapacity = 64)
        }
        return flow as SharedFlow<T>
    }
}
