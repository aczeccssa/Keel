package com.keel.kernel.plugin

import com.keel.contract.dto.KeelResponse
import com.keel.openapi.runtime.OpenApiDoc
import com.keel.openapi.runtime.OpenApiOperation
import com.keel.openapi.runtime.OpenApiRegistry
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.serializer
import java.util.UUID
import kotlin.reflect.KType

internal val runtimeJson = Json {
    ignoreUnknownKeys = true
    prettyPrint = false
    encodeDefaults = true
}

internal fun operationKey(method: HttpMethod, path: String): String = "${method.value} $path"

internal fun registerPluginOperation(pluginId: String, endpoint: PluginEndpointDefinition<*, *>) {
    val fullPath = fullPluginPath(pluginId, endpoint.path)
    OpenApiRegistry.register(
        OpenApiOperation(
            method = endpoint.method,
            path = fullPath,
            requestBodyType = endpoint.requestType,
            responseBodyType = endpoint.responseType,
            responseContentTypes = null,
            typeBound = true,
            summary = endpoint.doc.summary,
            description = endpoint.doc.description,
            tags = endpoint.doc.tags,
            responseEnvelope = endpoint.doc.responseEnvelope,
            successStatus = endpoint.doc.successStatus,
            errorStatuses = endpoint.doc.errorStatuses
        )
    )
}

internal fun registerPluginSseOperation(pluginId: String, path: String, doc: OpenApiDoc) {
    val fullPath = fullPluginPath(pluginId, path)
    OpenApiRegistry.register(
        OpenApiOperation(
            method = HttpMethod.Get,
            path = fullPath,
            requestBodyType = null,
            responseBodyType = null,
            responseContentTypes = listOf("text/event-stream"),
            typeBound = false,
            summary = doc.summary,
            description = doc.description,
            tags = doc.tags,
            responseEnvelope = false,
            successStatus = doc.successStatus,
            errorStatuses = doc.errorStatuses
        )
    )
}

internal fun registerPluginStaticOperation(
    pluginId: String,
    path: String,
    hasIndex: Boolean,
    doc: OpenApiDoc
) {
    val fullPath = fullPluginPath(pluginId, path)
    val contentTypes = buildList {
        add("application/octet-stream")
        if (hasIndex) {
            add("text/html")
        }
    }
    OpenApiRegistry.register(
        OpenApiOperation(
            method = HttpMethod.Get,
            path = fullPath,
            requestBodyType = null,
            responseBodyType = null,
            responseContentTypes = contentTypes,
            typeBound = false,
            summary = doc.summary,
            description = doc.description,
            tags = doc.tags,
            responseEnvelope = false,
            successStatus = doc.successStatus,
            errorStatuses = doc.errorStatuses
        )
    )
}

internal fun fullPluginPath(pluginId: String, endpointPath: String): String {
    val prefix = "/api/plugins/$pluginId"
    val normalized = endpointPath.takeIf { it.isNotBlank() } ?: ""
    return if (normalized.isBlank()) prefix else prefix + if (normalized.startsWith("/")) normalized else "/$normalized"
}

internal suspend fun decodeRequestBody(call: ApplicationCall, requestType: KType?): Any? {
    if (requestType == null) return null
    val body = readValidatedRequestBody(call, requestType)
    return decodeRequestBody(body, requestType)
}

internal suspend fun readValidatedRequestBody(call: ApplicationCall, requestType: KType?): String? {
    if (requestType == null) return null
    val body = call.receiveText()
    if (body.isBlank()) {
        throw PluginApiException(400, "Request body is required")
    }
    return body
}

internal fun decodeRequestBody(body: String?, requestType: KType?): Any? {
    if (requestType == null) return null
    val validatedBody = body?.takeIf { it.isNotBlank() }
        ?: throw PluginApiException(400, "Request body is required")
    return runtimeJson.decodeFromString(serializer(requestType), validatedBody)
}

internal fun encodeResponseBody(body: Any?, responseType: KType): String? {
    if (body == null) return null
    return runtimeJson.encodeToString(serializer(responseType), body)
}

@Suppress("UNCHECKED_CAST")
internal fun serializer(type: KType): KSerializer<Any> = runtimeJson.serializersModule.serializer(type) as KSerializer<Any>

internal fun buildRequestContext(call: ApplicationCall, pluginId: String, method: HttpMethod, rawPath: String): PluginRequestContext {
    return object : PluginRequestContext {
        override val pluginId: String = pluginId
        override val method: String = method.value
        override val rawPath: String = rawPath
        override val pathParameters: Map<String, String> = call.parameters.entries().associate { it.key to it.value.first() }
        override val queryParameters: Map<String, List<String>> = call.request.queryParameters.entries().associate { it.key to it.value }
        override val requestHeaders: Map<String, List<String>> = call.request.headers.entries().associate { it.key to it.value }
        override val requestId: String = call.request.headers["X-Request-Id"] ?: UUID.randomUUID().toString()
    }
}

internal suspend fun respondPluginResult(
    call: ApplicationCall,
    result: PluginResult<*>,
    responseType: KType,
    responseEnvelope: Boolean,
    errorMessage: String? = null
) {
    val status = HttpStatusCode.fromValue(result.status)
    result.headers.forEach { (key, values) ->
        values.forEach { call.response.headers.append(key, it, safeOnly = false) }
    }

    if (responseEnvelope) {
        val bodyElement = when {
            result.body == null -> JsonNull
            else -> runtimeJson.encodeToJsonElement(serializer(responseType), result.body)
        }
        val envelopeJson = buildJsonObject {
            put("code", JsonPrimitive(status.value))
            put("message", JsonPrimitive(if (status.value >= 400) errorMessage ?: "Request failed" else "success"))
            put("data", bodyElement)
            put("timestamp", JsonPrimitive(System.currentTimeMillis()))
        }
        call.respondText(envelopeJson.toString(), ContentType.Application.Json, status)
        return
    }

    if (status == HttpStatusCode.NoContent || result.body == null) {
        call.respond(status, "")
        return
    }

    call.respond(status, result.body)
}
