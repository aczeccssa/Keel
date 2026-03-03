package com.keel.contract.events

import kotlinx.coroutines.flow.SharedFlow

/**
 * Event bus interface for inter-plugin communication.
 * Uses MutableSharedFlow to allow plugins to publish and subscribe to events.
 *
 * Usage:
 * - Publishers: eventBus.emit(MyEvent(...))
 * - Subscribers: eventBus.events.collect { event -> ... }
 */
interface KeelEventBus {
    /**
     * The shared flow of all events published to the bus.
     */
    val events: SharedFlow<KeelEvent>

    /**
     * Publish an event to all subscribers.
     */
    suspend fun publish(event: KeelEvent)

    /**
     * Subscribe to events of a specific type.
     */
    fun <T : KeelEvent> subscribe(eventClass: Class<T>): SharedFlow<T>
}

/**
 * Base interface for all Keel events.
 */
interface KeelEvent

/**
 * Simple event wrapper with timestamp.
 */
data class GenericEvent(
    val type: String,
    val payload: Map<String, String> = emptyMap()
) : KeelEvent
