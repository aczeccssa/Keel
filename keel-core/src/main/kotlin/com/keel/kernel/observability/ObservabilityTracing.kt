package com.keel.kernel.observability

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey as OtelAttributeKey
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import io.opentelemetry.context.Scope
import io.opentelemetry.context.propagation.ContextPropagators
import io.opentelemetry.context.propagation.TextMapGetter
import io.opentelemetry.context.propagation.TextMapSetter
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.data.StatusData
import io.ktor.util.AttributeKey
import java.util.concurrent.TimeUnit

object ObservabilityTracing {
    val TRACE_CONTEXT_KEY: AttributeKey<Context> = AttributeKey("keel.trace-context")
    private val traceparentKey = OtelAttributeKey.stringKey("traceparent")
    private val tracestateKey = OtelAttributeKey.stringKey("tracestate")
    private val edgeFromKey = OtelAttributeKey.stringKey("keel.edge.from")
    private val edgeToKey = OtelAttributeKey.stringKey("keel.edge.to")

    private val setter = TextMapSetter<MutableMap<String, String>> { carrier, key, value ->
        carrier?.put(key, value)
    }

    private val getter = object : TextMapGetter<Map<String, String>> {
        override fun keys(carrier: Map<String, String>): Iterable<String> = carrier.keys
        override fun get(carrier: Map<String, String>?, key: String): String? = carrier?.get(key)
    }

    @Volatile
    private var kernel: KernelTracing? = null

    fun initKernel(hub: ObservabilityHub): KernelTracing {
        kernel?.let { return it }
        val exporter = KernelSpanExporter(hub)
        val tracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build()
        val openTelemetry = OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
            .build()
        return KernelTracing(openTelemetry, tracerProvider, openTelemetry.getTracer("keel-kernel")).also { kernel = it }
    }

    fun kernelTracer(): Tracer? = kernel?.tracer

    fun initExternal(eventSink: PluginTraceSink): ExternalTracing {
        val exporter = ExternalSpanExporter(eventSink)
        val tracerProvider = SdkTracerProvider.builder()
            .addSpanProcessor(SimpleSpanProcessor.create(exporter))
            .build()
        val openTelemetry = OpenTelemetrySdk.builder()
            .setTracerProvider(tracerProvider)
            .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
            .build()
        return ExternalTracing(openTelemetry, tracerProvider, openTelemetry.getTracer("keel-external"))
    }

    fun inject(context: Context): Pair<String?, String?> {
        val carrier = mutableMapOf<String, String>()
        kernel?.openTelemetry?.propagators?.textMapPropagator?.inject(context, carrier, setter)
        return carrier["traceparent"] to carrier["tracestate"]
    }

    fun extract(traceparent: String?, tracestate: String?): Context {
        val carrier = mutableMapOf<String, String>()
        if (!traceparent.isNullOrBlank()) carrier["traceparent"] = traceparent
        if (!tracestate.isNullOrBlank()) carrier["tracestate"] = tracestate
        val propagator = kernel?.openTelemetry?.propagators?.textMapPropagator
            ?: W3CTraceContextPropagator.getInstance()
        return propagator.extract(Context.current(), carrier, getter)
    }

    fun tagCurrentSpan(key: String, value: String) {
        Span.current().setAttribute(key, value)
    }

    data class KernelTracing(
        val openTelemetry: OpenTelemetry,
        val tracerProvider: SdkTracerProvider,
        val tracer: Tracer
    )

    data class ExternalTracing(
        val openTelemetry: OpenTelemetry,
        val tracerProvider: SdkTracerProvider,
        val tracer: Tracer
    )

    interface PluginTraceSink {
        fun emitTrace(event: TraceSpanEvent)
    }

    private class KernelSpanExporter(
        private val hub: ObservabilityHub
    ) : SpanExporter {
        override fun export(spans: MutableCollection<SpanData>) = runCatching {
            spans.forEach { hub.recordSpan(it.toEvent(service = "kernel")) }
            io.opentelemetry.sdk.common.CompletableResultCode.ofSuccess()
        }.getOrElse { io.opentelemetry.sdk.common.CompletableResultCode.ofFailure() }

        override fun flush() = io.opentelemetry.sdk.common.CompletableResultCode.ofSuccess()
        override fun shutdown() = io.opentelemetry.sdk.common.CompletableResultCode.ofSuccess()
    }

    private class ExternalSpanExporter(
        private val sink: PluginTraceSink
    ) : SpanExporter {
        override fun export(spans: MutableCollection<SpanData>) = runCatching {
            spans.forEach { sink.emitTrace(it.toEvent(service = "plugin")) }
            io.opentelemetry.sdk.common.CompletableResultCode.ofSuccess()
        }.getOrElse { io.opentelemetry.sdk.common.CompletableResultCode.ofFailure() }

        override fun flush() = io.opentelemetry.sdk.common.CompletableResultCode.ofSuccess()
        override fun shutdown() = io.opentelemetry.sdk.common.CompletableResultCode.ofSuccess()
    }

    internal fun SpanData.toEvent(service: String): TraceSpanEvent {
        val attributes = attributes.asMap().entries.associate { (key, value) -> key.key to value.toString() }
        val startMs = TimeUnit.NANOSECONDS.toMillis(startEpochNanos)
        val endMs = if (hasEnded()) TimeUnit.NANOSECONDS.toMillis(endEpochNanos) else null
        val durationMs = endMs?.let { it - startMs }
        return TraceSpanEvent(
            traceId = traceId,
            spanId = spanId,
            parentSpanId = parentSpanId.takeIf { it.isNotBlank() },
            service = service,
            operation = name,
            startEpochMs = startMs,
            endEpochMs = endMs,
            durationMs = durationMs,
            status = status.toStatusName(),
            attributes = attributes,
            edgeFrom = attributes[edgeFromKey.key],
            edgeTo = attributes[edgeToKey.key]
        )
    }

    private fun StatusData.toStatusName(): String = when (statusCode) {
        StatusCode.OK -> "OK"
        StatusCode.ERROR -> "ERROR"
        else -> "UNSET"
    }
}
