package com.keel.samples.aigateway.airelay.protocol

import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject

interface ProtocolCodec {
    val protocol: WireProtocol

    fun decodeRequest(rawJson: JsonObject): IrRequest
    fun encodeRequest(ir: IrRequest): JsonObject
    fun decodeResponse(rawJson: JsonObject): IrResponse
    fun encodeResponse(ir: IrResponse): JsonObject

    fun decodeStream(upstream: Flow<ServerSentEvent>): Flow<IrStreamEvent>
    fun encodeStream(events: Flow<IrStreamEvent>): Flow<ServerSentEvent>
}

class ProtocolTranscoder(
    codecs: List<ProtocolCodec>
) {
    private val byProtocol = codecs.associateBy { it.protocol }

    fun decodeRequest(protocol: WireProtocol, rawJson: JsonObject): IrRequest = codec(protocol).decodeRequest(rawJson)

    fun encodeRequest(protocol: WireProtocol, ir: IrRequest): JsonObject = codec(protocol).encodeRequest(ir)

    fun decodeResponse(protocol: WireProtocol, rawJson: JsonObject): IrResponse = codec(protocol).decodeResponse(rawJson)

    fun encodeResponse(protocol: WireProtocol, ir: IrResponse): JsonObject = codec(protocol).encodeResponse(ir)

    fun transcodeResponse(from: WireProtocol, to: WireProtocol, rawJson: JsonObject): JsonObject {
        return encodeResponse(to, decodeResponse(from, rawJson))
    }

    fun transcodeStream(from: WireProtocol, to: WireProtocol, upstream: Flow<ServerSentEvent>): Flow<ServerSentEvent> {
        return codec(to).encodeStream(codec(from).decodeStream(upstream))
    }

    private fun codec(protocol: WireProtocol): ProtocolCodec = byProtocol[protocol]
        ?: error("No codec registered for $protocol")
}

fun Flow<IrStreamEvent>.asJsonLineEvents(encoder: (IrStreamEvent) -> String): Flow<ServerSentEvent> = map { event ->
    when (event) {
        is IrStreamEvent.ResponseDone -> ServerSentEvent(data = encoder(event), event = "done")
        is IrStreamEvent.Error -> ServerSentEvent(data = encoder(event), event = "error")
        else -> ServerSentEvent(data = encoder(event), event = "delta")
    }
}
