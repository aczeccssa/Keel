package com.keel.kernel.plugin

import io.ktor.http.HttpMethod
import io.ktor.server.sse.ServerSSESession
import io.ktor.sse.ServerSentEvent
import kotlin.reflect.KType
import kotlin.reflect.typeOf
import org.koin.core.module.Module

interface PluginAvailability {
    fun isPluginEnabled(pluginId: String): Boolean
}

enum class PluginDispatchDisposition {
    AVAILABLE,
    UNAVAILABLE,
    NOT_FOUND,
    PASS_THROUGH
}

enum class PluginRuntimeMode {
    IN_PROCESS,
    EXTERNAL_JVM
}

enum class JvmCommunicationMode {
    UDS,
    TCP
}

data class JvmCommunicationStrategy(
    val preferredMode: JvmCommunicationMode = JvmCommunicationMode.UDS,
    val fallbackMode: JvmCommunicationMode = JvmCommunicationMode.TCP,
    val maxAttempts: Int = 3
) {
    companion object {
        val DEFAULT = JvmCommunicationStrategy()
        val PREFER_TCP = JvmCommunicationStrategy(preferredMode = JvmCommunicationMode.TCP)
        val REQUIRE_UDS = JvmCommunicationStrategy(fallbackMode = JvmCommunicationMode.UDS)
    }
}

enum class PluginLifecycleState {
    REGISTERED,
    INITIALIZING,
    STOPPED,
    STARTING,
    RUNNING,
    STOPPING,
    DISPOSING,
    DISPOSED,
    FAILED
}

enum class PluginHealthState {
    UNKNOWN,
    HEALTHY,
    DEGRADED,
    UNREACHABLE
}

@JvmInline
value class PluginGeneration(val value: Long) {
    init {
        require(value >= 1) { "Plugin generation must be >= 1" }
    }

    fun next(): PluginGeneration = PluginGeneration(value + 1)

    companion object {
        val INITIAL = PluginGeneration(1)
    }
}

data class PluginDescriptor(
    val pluginId: String,
    val version: String,
    val displayName: String,
    val defaultRuntimeMode: PluginRuntimeMode = PluginRuntimeMode.IN_PROCESS,
    val communicationStrategy: JvmCommunicationStrategy = JvmCommunicationStrategy.DEFAULT,
    val supportedRuntimeModes: Set<PluginRuntimeMode> = setOf(
        PluginRuntimeMode.IN_PROCESS,
        PluginRuntimeMode.EXTERNAL_JVM
    ),
    // Technical knobs moved from PluginConfig to be code-driven
    val startupTimeoutMs: Long = 5000,
    val callTimeoutMs: Long = 3000,
    val stopTimeoutMs: Long = 3000,
    val healthCheckIntervalMs: Long = 10000,
    val maxConcurrentCalls: Int = 128,
    val eventLogRingBufferSize: Int = 4096,
    val criticalEventQueueSize: Int = 256
) {
    init {
        require(pluginId.isNotBlank()) { "pluginId must not be blank" }
        require(version.isNotBlank()) { "version must not be blank" }
        require(displayName.isNotBlank()) { "displayName must not be blank" }
        require(supportedRuntimeModes.isNotEmpty()) { "supportedRuntimeModes must not be empty" }
        require(defaultRuntimeMode in supportedRuntimeModes) {
            "defaultRuntimeMode $defaultRuntimeMode must be included in supportedRuntimeModes"
        }
    }
}

data class EndpointExecutionPolicy(
    val timeoutMs: Long? = null,
    val maxPayloadBytes: Long? = null,
    val allowChunkedTransfer: Boolean = false
) {
    init {
        require(timeoutMs == null || timeoutMs > 0) { "timeoutMs must be > 0 when provided" }
        require(maxPayloadBytes == null || maxPayloadBytes > 0) { "maxPayloadBytes must be > 0 when provided" }
    }
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

interface KeelPlugin {
    val descriptor: PluginDescriptor

    suspend fun onInit(context: PluginInitContext) {}

    suspend fun onStart(context: PluginRuntimeContext) {}

    suspend fun onStop(context: PluginRuntimeContext) {}

    suspend fun onDispose(context: PluginRuntimeContext) {}

    fun modules(): List<Module> = emptyList()

    fun endpoints(): List<PluginRouteDefinition>
}

sealed interface PluginRouteDefinition {
    val path: String
}

data class PluginEndpointDefinition<Req : Any, Res : Any>(
    val endpointId: String,
    val method: HttpMethod,
    override val path: String,
    val requestType: KType?,
    val responseType: KType,
    val executionPolicy: EndpointExecutionPolicy = EndpointExecutionPolicy(),
    val handler: suspend PluginRequestContext.(Req?) -> PluginResult<Res>
) : PluginRouteDefinition {
    @Suppress("UNCHECKED_CAST")
    suspend fun execute(context: PluginRequestContext, request: Any?): PluginResult<Any?> {
        val result = (handler as suspend PluginRequestContext.(Any?) -> PluginResult<Any?>)
            .invoke(context, request)
        return result
    }
}

data class PluginSseDefinition(
    override val path: String,
    val handler: suspend PluginSseSession.() -> Unit
) : PluginRouteDefinition

data class PluginStaticResourceDefinition(
    override val path: String,
    val basePackage: String,
    val index: String? = null
) : PluginRouteDefinition

class PluginSseSession internal constructor(
    val request: PluginRequestContext,
    private val session: ServerSSESession
) {
    suspend fun send(event: ServerSentEvent) {
        session.send(event)
    }
}

object PluginEndpointBuilders {
    fun pluginEndpoints(
        pluginId: String,
        block: PluginEndpointDsl.() -> Unit
    ): List<PluginRouteDefinition> = PluginEndpointDsl(pluginId).apply(block).build()
}

class PluginEndpointDsl internal constructor(
    pluginId: String,
    private val basePath: String = "",
    @PublishedApi internal val endpoints: MutableList<PluginRouteDefinition> = mutableListOf()
) {
    @PublishedApi
    internal val pluginIdValue: String = pluginId

    fun route(
        path: String,
        block: PluginEndpointDsl.() -> Unit
    ) {
        val combinedPath = joinPaths(basePath, path)
        PluginEndpointDsl(pluginIdValue, combinedPath, endpoints).apply(block)
    }

    inline fun <reified Res : Any> get(
        path: String = "",
        executionPolicy: EndpointExecutionPolicy = EndpointExecutionPolicy(),
        noinline handler: suspend PluginRequestContext.() -> PluginResult<Res>
    ) {
        val resolvedPath = resolvePath(path)
        endpoints += PluginEndpointDefinition(
            endpointId = buildEndpointId(pluginIdValue, HttpMethod.Get, resolvedPath),
            method = HttpMethod.Get,
            path = resolvedPath,
            requestType = null,
            responseType = typeOf<Res>(),
            executionPolicy = executionPolicy,
            handler = { _: Unit? -> handler() }
        )
    }

    inline fun <reified Req : Any, reified Res : Any> post(
        path: String = "",
        executionPolicy: EndpointExecutionPolicy = EndpointExecutionPolicy(),
        noinline handler: suspend PluginRequestContext.(Req) -> PluginResult<Res>
    ) {
        val resolvedPath = resolvePath(path)
        endpoints += PluginEndpointDefinition<Req, Res>(
            endpointId = buildEndpointId(pluginIdValue, HttpMethod.Post, resolvedPath),
            method = HttpMethod.Post,
            path = resolvedPath,
            requestType = typeOf<Req>(),
            responseType = typeOf<Res>(),
            executionPolicy = executionPolicy,
            handler = { request -> handler(requireNotNull(request)) }
        )
    }

    inline fun <reified Res : Any> post(
        path: String = "",
        executionPolicy: EndpointExecutionPolicy = EndpointExecutionPolicy(),
        noinline handler: suspend PluginRequestContext.() -> PluginResult<Res>
    ) {
        val resolvedPath = resolvePath(path)
        endpoints += PluginEndpointDefinition(
            endpointId = buildEndpointId(pluginIdValue, HttpMethod.Post, resolvedPath),
            method = HttpMethod.Post,
            path = resolvedPath,
            requestType = null,
            responseType = typeOf<Res>(),
            executionPolicy = executionPolicy,
            handler = { _: Unit? -> handler() }
        )
    }

    inline fun <reified Req : Any, reified Res : Any> put(
        path: String = "",
        executionPolicy: EndpointExecutionPolicy = EndpointExecutionPolicy(),
        noinline handler: suspend PluginRequestContext.(Req) -> PluginResult<Res>
    ) {
        val resolvedPath = resolvePath(path)
        endpoints += PluginEndpointDefinition<Req, Res>(
            endpointId = buildEndpointId(pluginIdValue, HttpMethod.Put, resolvedPath),
            method = HttpMethod.Put,
            path = resolvedPath,
            requestType = typeOf<Req>(),
            responseType = typeOf<Res>(),
            executionPolicy = executionPolicy,
            handler = { request -> handler(requireNotNull(request)) }
        )
    }

    inline fun <reified Res : Any> delete(
        path: String = "",
        executionPolicy: EndpointExecutionPolicy = EndpointExecutionPolicy(),
        noinline handler: suspend PluginRequestContext.() -> PluginResult<Res>
    ) {
        val resolvedPath = resolvePath(path)
        endpoints += PluginEndpointDefinition(
            endpointId = buildEndpointId(pluginIdValue, HttpMethod.Delete, resolvedPath),
            method = HttpMethod.Delete,
            path = resolvedPath,
            requestType = null,
            responseType = typeOf<Res>(),
            executionPolicy = executionPolicy,
            handler = { _: Unit? -> handler() }
        )
    }

    fun sse(
        path: String,
        handler: suspend PluginSseSession.() -> Unit
    ) {
        endpoints += PluginSseDefinition(path = resolvePath(path), handler = handler)
    }

    fun staticResources(
        path: String,
        basePackage: String,
        index: String? = null
    ) {
        require(basePackage.isNotBlank()) { "basePackage must not be blank" }
        endpoints += PluginStaticResourceDefinition(
            path = resolvePath(path),
            basePackage = basePackage,
            index = index
        )
    }

    fun build(): List<PluginRouteDefinition> = endpoints.toList()

    @PublishedApi
    internal fun resolvePath(path: String): String = joinPaths(basePath, path)
}

@PublishedApi
internal fun joinPaths(basePath: String, path: String): String {
    val baseTrimmed = basePath.trim()
    val pathTrimmed = path.trim()
    if (baseTrimmed.isBlank() && pathTrimmed.isBlank()) return ""
    val baseClean = baseTrimmed.trim('/')
    val pathClean = pathTrimmed.trim('/')
    return when {
        baseClean.isBlank() && pathClean.isBlank() -> "/"
        baseClean.isBlank() -> "/$pathClean"
        pathClean.isBlank() -> "/$baseClean"
        else -> "/$baseClean/$pathClean"
    }
}

fun buildEndpointId(pluginId: String, method: HttpMethod, path: String): String {
    val normalizedPath = path.takeIf { it.isNotBlank() }?.let {
        if (it.startsWith("/")) it else "/$it"
    } ?: ""
    return "$pluginId:${method.value}:${normalizedPath.ifBlank { "/" }}"
}
