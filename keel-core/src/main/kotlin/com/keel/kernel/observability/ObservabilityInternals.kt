package com.keel.kernel.observability

import java.util.ArrayDeque
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

internal class ObservabilityBuffers(
    private val traceCapacity: Int,
    private val flowCapacity: Int,
    private val logCapacity: Int = 1000
) {
    private val traceBuffer = ArrayDeque<TraceSpanEvent>()
    private val flowBuffer = ArrayDeque<FlowEvent>()
    private val logBuffer = ArrayDeque<StructuredLogRecord>()

    fun recordSpan(event: TraceSpanEvent) {
        synchronized(traceBuffer) {
            traceBuffer.addLast(event)
            while (traceBuffer.size > traceCapacity) {
                traceBuffer.removeFirst()
            }
        }
    }

    fun recordFlow(event: FlowEvent) {
        synchronized(flowBuffer) {
            flowBuffer.addLast(event)
            while (flowBuffer.size > flowCapacity) {
                flowBuffer.removeFirst()
            }
        }
    }

    fun recordLog(record: StructuredLogRecord) {
        synchronized(logBuffer) {
            logBuffer.addLast(record)
            while (logBuffer.size > logCapacity) {
                logBuffer.removeFirst()
            }
        }
    }

    fun traceSnapshot(): List<TraceSpanEvent> = synchronized(traceBuffer) { traceBuffer.toList() }

    fun flowSnapshot(): List<FlowEvent> = synchronized(flowBuffer) { flowBuffer.toList() }

    fun logSnapshot(): List<StructuredLogRecord> = synchronized(logBuffer) { logBuffer.toList() }
}

internal class ObservabilityEventPublisher {
    private val eventFlow = MutableSharedFlow<ObservabilityStreamEvent>(extraBufferCapacity = 512)

    fun events(): SharedFlow<ObservabilityStreamEvent> = eventFlow

    fun emit(type: String, dataJson: String) {
        eventFlow.tryEmit(ObservabilityStreamEvent(type = type, dataJson = dataJson))
    }
}
