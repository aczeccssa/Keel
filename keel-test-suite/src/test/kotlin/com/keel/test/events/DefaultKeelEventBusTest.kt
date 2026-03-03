package com.keel.test.events

import com.keel.contract.events.KeelEvent
import com.keel.kernel.events.DefaultKeelEventBus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.async
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DefaultKeelEventBusTest {

    private data class TestEvent(val value: String) : KeelEvent

    @Test
    fun publishDeliversToTypedSubscribers() = runTest {
        val bus = DefaultKeelEventBus()
        val received = bus.subscribe(TestEvent::class.java)
        val event = TestEvent("hello")

        val resultDeferred = async { received.first() }
        yield()
        bus.publish(event)
        val result = resultDeferred.await()
        assertEquals(event, result)
    }
}
