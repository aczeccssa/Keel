package com.keel.test.perf

import com.keel.contract.events.KeelEvent
import com.keel.kernel.events.DefaultKeelEventBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicLong
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertTrue

private object EventBusThresholds {
    const val SINGLE_PRODUCER_MIN_DELIVERY_RATE = 0.90
    const val MULTI_PRODUCER_MIN_DELIVERY_RATE = 0.95
    const val TYPED_MIN_DELIVERY_RATE = 0.95
    const val BUFFER_PRESSURE_MIN_DELIVERY_RATE = 0.85
}

/**
 * Stress tests for DefaultKeelEventBus (MutableSharedFlow-based).
 *
 * Validates:
 *   - Multi-producer concurrent publish throughput
 *   - Subscriber delivery completeness (no dropped events)
 *   - typedFlows ConcurrentHashMap iteration overhead
 *   - Behavior when extraBufferCapacity (64) is exhausted
 */
class EventBusStressTest {

    private data class StressEvent(val index: Long, val producerId: Int) : KeelEvent
    private data class TypeAEvent(val value: Int) : KeelEvent
    private data class TypeBEvent(val value: Int) : KeelEvent

    // ─────────────────────────────────────────────────────────
    //  Test 1: Single producer throughput
    // ─────────────────────────────────────────────────────────

    @Test
    fun singleProducerThroughput() = kotlinx.coroutines.runBlocking {
        val bus = DefaultKeelEventBus()
        val eventCount = 50_000L
        val received = AtomicLong(0)

        val collector = launch(Dispatchers.Default) {
            bus.events.collect {
                received.incrementAndGet()
            }
        }
        yield()

        val elapsedMs = measureTimeMillis {
            for (i in 0 until eventCount) {
                bus.publish(StressEvent(i, 0))
            }
        }

        withTimeoutOrNull(5000) {
            while (received.get() < eventCount) {
                kotlinx.coroutines.delay(10)
            }
        }
        collector.cancel()

        println("[PERF] Single producer: published $eventCount events in ${elapsedMs}ms (${eventCount * 1000 / elapsedMs} events/sec)")
        println("[PERF] Received: ${received.get()} / $eventCount")
        assertTrue(received.get() > 0, "Should have received events")
        val deliveryRate = received.get().toDouble() / eventCount
        assertTrue(deliveryRate >= EventBusThresholds.SINGLE_PRODUCER_MIN_DELIVERY_RATE,
            "[CI GATE] Single producer delivery rate ${"%.2f".format(deliveryRate * 100)}% below min ${(EventBusThresholds.SINGLE_PRODUCER_MIN_DELIVERY_RATE * 100).toInt()}%")
    }

    // ─────────────────────────────────────────────────────────
    //  Test 2: Multi-producer concurrent throughput
    // ─────────────────────────────────────────────────────────

    @Test
    fun multiProducerConcurrentThroughput() = kotlinx.coroutines.runBlocking {
        val bus = DefaultKeelEventBus()
        val producerCount = 10
        val eventsPerProducer = 5_000L
        val totalExpected = producerCount * eventsPerProducer
        val received = AtomicLong(0)

        val collector = launch(Dispatchers.Default) {
            bus.events.collect {
                received.incrementAndGet()
            }
        }
        yield()

        val elapsedMs = measureTimeMillis {
            coroutineScope {
                (0 until producerCount).map { pid ->
                    async(Dispatchers.Default) {
                        for (i in 0 until eventsPerProducer) {
                            bus.publish(StressEvent(i, pid))
                        }
                    }
                }.awaitAll()
            }
        }

        withTimeoutOrNull(5000) {
            while (received.get() < totalExpected) {
                kotlinx.coroutines.delay(10)
            }
        }
        collector.cancel()

        println("[PERF] Multi-producer ($producerCount x $eventsPerProducer): published $totalExpected events in ${elapsedMs}ms (${totalExpected * 1000 / elapsedMs} events/sec)")
        println("[PERF] Received: ${received.get()} / $totalExpected")
        assertTrue(received.get() > 0, "Should have received events")
        val deliveryRate = received.get().toDouble() / totalExpected
        assertTrue(deliveryRate >= EventBusThresholds.MULTI_PRODUCER_MIN_DELIVERY_RATE,
            "[CI GATE] Multi-producer delivery rate ${"%.2f".format(deliveryRate * 100)}% below min ${(EventBusThresholds.MULTI_PRODUCER_MIN_DELIVERY_RATE * 100).toInt()}%")
    }

    // ─────────────────────────────────────────────────────────
    //  Test 3: Typed subscription overhead
    //         (measures typedFlows ConcurrentHashMap iteration cost)
    // ─────────────────────────────────────────────────────────

    @Test
    fun typedSubscriptionOverhead() = kotlinx.coroutines.runBlocking {
        val bus = DefaultKeelEventBus()
        val eventsPerType = 2_000L

        val receivedA = AtomicLong(0)
        val receivedB = AtomicLong(0)

        val collectorA = launch(Dispatchers.Default) {
            bus.subscribe(TypeAEvent::class.java).collect { receivedA.incrementAndGet() }
        }
        val collectorB = launch(Dispatchers.Default) {
            bus.subscribe(TypeBEvent::class.java).collect { receivedB.incrementAndGet() }
        }
        yield()

        val elapsedMs = measureTimeMillis {
            for (i in 0 until eventsPerType) {
                bus.publish(TypeAEvent(i.toInt()))
                bus.publish(TypeBEvent(i.toInt()))
            }
        }

        withTimeoutOrNull(5000) {
            while (receivedA.get() < eventsPerType || receivedB.get() < eventsPerType) {
                kotlinx.coroutines.delay(10)
            }
        }
        collectorA.cancel()
        collectorB.cancel()

        val rateA = receivedA.get().toDouble() / eventsPerType
        val rateB = receivedB.get().toDouble() / eventsPerType
        assertTrue(rateA >= EventBusThresholds.TYPED_MIN_DELIVERY_RATE,
            "[CI GATE] TypeA delivery rate ${"%.2f".format(rateA * 100)}% below min")
        assertTrue(rateB >= EventBusThresholds.TYPED_MIN_DELIVERY_RATE,
            "[CI GATE] TypeB delivery rate ${"%.2f".format(rateB * 100)}% below min")
        println("[PERF] Typed subscription: published ${eventsPerType * 2} events in ${elapsedMs}ms")
        println("[PERF] TypeA received: ${receivedA.get()} / $eventsPerType, TypeB received: ${receivedB.get()} / $eventsPerType")
    }

    // ─────────────────────────────────────────────────────────
    //  Test 4: Buffer pressure test
    //         (emit faster than consumers can process)
    // ─────────────────────────────────────────────────────────

    @Test
    fun bufferPressureTest() = kotlinx.coroutines.runBlocking {
        val bus = DefaultKeelEventBus()
        val eventCount = 10_000L
        val received = AtomicLong(0)
        var publishSuccessCount = 0L

        val collector = launch(Dispatchers.Default) {
            bus.events.collect {
                received.incrementAndGet()
            }
        }
        yield()

        val elapsedMs = measureTimeMillis {
            for (i in 0 until eventCount) {
                bus.publish(StressEvent(i, 0))
                publishSuccessCount++
            }
        }
        
        withTimeoutOrNull(5000) {
            while (received.get() < eventCount) {
                kotlinx.coroutines.delay(10)
            }
        }
        collector.cancel()

        val deliveryRate = received.get().toDouble() / eventCount
        assertTrue(deliveryRate >= EventBusThresholds.BUFFER_PRESSURE_MIN_DELIVERY_RATE,
            "[CI GATE] Buffer pressure delivery rate ${"%.2f".format(deliveryRate * 100)}% below min ${(EventBusThresholds.BUFFER_PRESSURE_MIN_DELIVERY_RATE * 100).toInt()}%")
        println("[PERF] Buffer pressure: published $publishSuccessCount events in ${elapsedMs}ms")
        println("[PERF] Consumer received: ${received.get()} / $eventCount")
        println("[PERF] Drop rate: ${((eventCount - received.get()).toDouble() / eventCount * 100).let { "%.2f".format(it) }}%")
    }
}
