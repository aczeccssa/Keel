package com.keel.kernel.plugin

import io.ktor.http.HttpMethod
import kotlin.reflect.KType
import kotlin.reflect.typeOf

interface PluginAvailability {
    fun isPluginEnabled(pluginId: String): Boolean
}

enum class PluginExecutionMode {
    IN_PROCESS,
    ISOLATED_JVM
}

enum class PluginProcessState {
    STARTING,
    RUNNING,
    STOPPING,
    STOPPED,
    FAILED
}

data class PluginDescriptor(
    val pluginId: String,
    val version: String,
    val displayName: String,
    val defaultExecutionMode: PluginExecutionMode = PluginExecutionMode.IN_PROCESS,
    val supportedExecutionModes: Set<PluginExecutionMode> = setOf(
        PluginExecutionMode.IN_PROCESS,
        PluginExecutionMode.ISOLATED_JVM
    )
)

interface PluginInitContextV2 {
    val pluginId: String
    val config: PluginRuntimeConfig
}

interface PluginScopeV2 {
    val pluginId: String
}

interface PluginRequestContext {
    val pluginId: String
    val method: String
    val rawPath: String
    val pathParameters: Map<String, String>
    val queryParameters: Map<String, List<String>>
    val requestHeaders: Map<String, List<String>>
    val requestId: String
}

data class PluginResult<T>(
    val status: Int = 200,
    val headers: Map<String, List<String>> = emptyMap(),
    val body: T? = null
)

class PluginApiException(
    val status: Int,
    override val message: String
) : RuntimeException(message)

interface KeelPluginV2 {
    val descriptor: PluginDescriptor

    suspend fun onInit(context: PluginInitContextV2) {}

    suspend fun onInstall(scope: PluginScopeV2) {}

    fun endpoints(): List<PluginEndpointDefinition<*, *>>

    suspend fun onStop() {}
}

data class PluginEndpointDefinition<Req : Any, Res : Any>(
    val endpointId: String,
    val method: HttpMethod,
    val path: String,
    val requestType: KType?,
    val responseType: KType,
    val handler: suspend PluginRequestContext.(Req?) -> PluginResult<Res>
) {
    @Suppress("UNCHECKED_CAST")
    suspend fun execute(context: PluginRequestContext, request: Any?): PluginResult<Any?> {
        val result = (handler as suspend PluginRequestContext.(Any?) -> PluginResult<Any?>)
            .invoke(context, request)
        return result
    }
}

fun pluginEndpoints(
    pluginId: String,
    block: PluginEndpointDsl.() -> Unit
): List<PluginEndpointDefinition<*, *>> = PluginEndpointDsl(pluginId).apply(block).build()

class PluginEndpointDsl internal constructor(pluginId: String) {
    @PublishedApi
    internal val pluginIdValue: String = pluginId

    @PublishedApi
    internal val endpoints = mutableListOf<PluginEndpointDefinition<*, *>>()

    inline fun <reified Res : Any> get(
        path: String = "",
        noinline handler: suspend PluginRequestContext.() -> PluginResult<Res>
    ) {
        endpoints += PluginEndpointDefinition(
            endpointId = buildEndpointId(pluginIdValue, HttpMethod.Get, path),
            method = HttpMethod.Get,
            path = normalizePath(path),
            requestType = null,
            responseType = typeOf<Res>(),
            handler = { _: Unit? -> handler() }
        )
    }

    inline fun <reified Req : Any, reified Res : Any> post(
        path: String = "",
        noinline handler: suspend PluginRequestContext.(Req) -> PluginResult<Res>
    ) {
        endpoints += PluginEndpointDefinition<Req, Res>(
            endpointId = buildEndpointId(pluginIdValue, HttpMethod.Post, path),
            method = HttpMethod.Post,
            path = normalizePath(path),
            requestType = typeOf<Req>(),
            responseType = typeOf<Res>(),
            handler = { request -> handler(requireNotNull(request)) }
        )
    }

    inline fun <reified Res : Any> post(
        path: String = "",
        noinline handler: suspend PluginRequestContext.() -> PluginResult<Res>
    ) {
        endpoints += PluginEndpointDefinition(
            endpointId = buildEndpointId(pluginIdValue, HttpMethod.Post, path),
            method = HttpMethod.Post,
            path = normalizePath(path),
            requestType = null,
            responseType = typeOf<Res>(),
            handler = { _: Unit? -> handler() }
        )
    }

    inline fun <reified Req : Any, reified Res : Any> put(
        path: String = "",
        noinline handler: suspend PluginRequestContext.(Req) -> PluginResult<Res>
    ) {
        endpoints += PluginEndpointDefinition<Req, Res>(
            endpointId = buildEndpointId(pluginIdValue, HttpMethod.Put, path),
            method = HttpMethod.Put,
            path = normalizePath(path),
            requestType = typeOf<Req>(),
            responseType = typeOf<Res>(),
            handler = { request -> handler(requireNotNull(request)) }
        )
    }

    inline fun <reified Res : Any> delete(
        path: String = "",
        noinline handler: suspend PluginRequestContext.() -> PluginResult<Res>
    ) {
        endpoints += PluginEndpointDefinition(
            endpointId = buildEndpointId(pluginIdValue, HttpMethod.Delete, path),
            method = HttpMethod.Delete,
            path = normalizePath(path),
            requestType = null,
            responseType = typeOf<Res>(),
            handler = { _: Unit? -> handler() }
        )
    }

    fun build(): List<PluginEndpointDefinition<*, *>> = endpoints.toList()

    @PublishedApi
    internal fun normalizePath(path: String): String {
        return path.takeIf { it.isNotBlank() }?.let {
            if (it.startsWith("/")) it else "/$it"
        } ?: ""
    }
}

fun buildEndpointId(pluginId: String, method: HttpMethod, path: String): String {
    val normalizedPath = path.takeIf { it.isNotBlank() }?.let {
        if (it.startsWith("/")) it else "/$it"
    } ?: ""
    return "$pluginId:${method.value}:${normalizedPath.ifBlank { "/" }}"
}
