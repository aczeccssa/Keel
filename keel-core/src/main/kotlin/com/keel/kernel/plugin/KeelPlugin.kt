package com.keel.kernel.plugin

import io.ktor.http.HttpMethod
import io.ktor.server.application.Application
import io.ktor.server.application.Plugin
import io.ktor.server.application.install
import io.ktor.server.application.BaseRouteScopedPlugin
import io.ktor.server.routing.Route
import io.ktor.sse.ServerSentEvent
import com.keel.openapi.runtime.OpenApiDoc
import kotlin.reflect.KClass
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

enum class PluginServiceType {
    ENDPOINT,
    SSE,
    STATIC_RESOURCE
}

data class PluginRecoveryPolicy(
    val maxRestarts: Int = 5,
    val baseBackoffMs: Long = 250,
    val maxBackoffMs: Long = 5000,
    val resetWindowMs: Long = 60000
) {
    init {
        require(maxRestarts >= 0) { "maxRestarts must be >= 0" }
        require(baseBackoffMs > 0) { "baseBackoffMs must be > 0" }
        require(maxBackoffMs >= baseBackoffMs) { "maxBackoffMs must be >= baseBackoffMs" }
        require(resetWindowMs > 0) { "resetWindowMs must be > 0" }
    }
}

data class JvmCommunicationStrategy(
    val preferredMode: JvmCommunicationMode = JvmCommunicationMode.UDS,
    val fallbackMode: JvmCommunicationMode = JvmCommunicationMode.TCP,
    val maxAttempts: Int = 3
) {
    companion object {
        val DEFAULT = JvmCommunicationStrategy()
        val PREFER_TCP = JvmCommunicationStrategy(preferredMode = JvmCommunicationMode.TCP)
        @Suppress("unused")
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
    val supportedServices: Set<PluginServiceType> = setOf(
        PluginServiceType.ENDPOINT,
        PluginServiceType.SSE,
        PluginServiceType.STATIC_RESOURCE
    ),
    val recoveryPolicy: PluginRecoveryPolicy = PluginRecoveryPolicy(),
    // Technical knobs moved from PluginConfig to be code-driven
    val startupTimeoutMs: Long = 5000,
    val callTimeoutMs: Long = 3000,
    val stopTimeoutMs: Long = 3000,
    val healthCheckIntervalMs: Long = 10000,
    val maxConcurrentCalls: Int = 128,
    val eventLogRingBufferSize: Int = 4096,
    val criticalEventQueueSize: Int = 256,
    val nodeAssetMetadata: PluginNodeAssetMetadata? = null
) {
    init {
        require(pluginId.isNotBlank()) { "pluginId must not be blank" }
        require(version.isNotBlank()) { "version must not be blank" }
        require(displayName.isNotBlank()) { "displayName must not be blank" }
        require(supportedRuntimeModes.isNotEmpty()) { "supportedRuntimeModes must not be empty" }
        require(supportedServices.isNotEmpty()) { "supportedServices must not be empty" }
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

interface KeelRequestContext {
    val pluginId: String
    val method: String
    val rawPath: String
    val pathParameters: Map<String, String>
    val queryParameters: Map<String, List<String>>
    val requestHeaders: Map<String, List<String>>
    val requestId: String
    val attributes: MutableMap<String, Any?>
    var principal: Any?
    var tenant: Any?
}

interface PluginRequestContext : KeelRequestContext

data class PluginResult<T>(
    val status: Int = 200,
    val headers: Map<String, List<String>> = emptyMap(),
    val body: T? = null
)

interface KeelRequestInterceptor {
    suspend fun intercept(
        context: KeelRequestContext,
        next: suspend () -> KeelInterceptorResult
    ): KeelInterceptorResult
}

sealed interface KeelInterceptorResult {
    data class Proceed(
        val result: PluginResult<Any?>
    ) : KeelInterceptorResult

    data class Reject(
        val status: Int,
        val message: String,
        val headers: Map<String, List<String>> = emptyMap()
    ) : KeelInterceptorResult {
        fun toPluginResult(): PluginResult<Any?> = PluginResult(status = status, headers = headers, body = null)
    }

    companion object {
        fun proceed(result: PluginResult<Any?>): KeelInterceptorResult = Proceed(result)

        fun reject(
            status: Int,
            message: String,
            headers: Map<String, List<String>> = emptyMap()
        ): KeelInterceptorResult = Reject(status = status, message = message, headers = headers)
    }
}

enum class InterceptorMetadataSource {
    NONE,
    DSL,
    GENERATED
}

class PluginApiException(
    val status: Int,
    override val message: String
) : RuntimeException(message)

interface KeelPlugin {
    val descriptor: PluginDescriptor

    // Backward-compatible defaults so plugins compiled against older KeelPlugin APIs keep behavior.
    suspend fun onInit(context: PluginInitContext) {}

    suspend fun onStart(context: PluginRuntimeContext) {}

    suspend fun onStop(context: PluginRuntimeContext) {}

    suspend fun onDispose(context: PluginRuntimeContext) {}

    fun modules(): List<Module> = emptyList()

    fun ktorPlugins(): PluginKtorConfig = PluginKtorConfig()

    fun endpoints(): List<PluginRouteDefinition> = emptyList()
}

interface LifecyclePlugin

interface ModulePlugin

interface KtorScopedPlugin

interface EndpointPlugin

interface StandardKeelPlugin :
    KeelPlugin,
    LifecyclePlugin,
    EndpointPlugin,
    ModulePlugin,
    KtorScopedPlugin

data class ApplicationKtorInstaller(
    val pluginKey: String,
    val installer: Application.() -> Unit
)

data class ServiceKtorInstaller(
    val pluginKey: String,
    val installer: Route.() -> Unit
)

class PluginKtorConfig {
    private val applicationInstallers = mutableListOf<ApplicationKtorInstaller>()
    private val serviceInstallers = mutableListOf<ServiceKtorInstaller>()

    fun application(configure: ApplicationKtorPluginConfig.() -> Unit) {
        val config = ApplicationKtorPluginConfig().apply(configure)
        applicationInstallers += config.toInstallers()
    }

    fun service(configure: ServiceKtorPluginConfig.() -> Unit) {
        val config = ServiceKtorPluginConfig().apply(configure)
        serviceInstallers += config.toInstallers()
    }

    internal fun configuredApplicationInstallers(): List<ApplicationKtorInstaller> = applicationInstallers.toList()

    internal fun configuredServiceInstallers(): List<ServiceKtorInstaller> = serviceInstallers.toList()
}

class ApplicationKtorPluginConfig {
    private val installers = mutableListOf<ApplicationKtorInstaller>()

    fun <B : Any, F : Any> install(
        plugin: Plugin<Application, B, F>,
        configure: B.() -> Unit = {}
    ) {
        installers += ApplicationKtorInstaller(pluginKey = plugin.key.name) {
            install(plugin, configure)
        }
    }

    internal fun toInstallers(): List<ApplicationKtorInstaller> = installers.toList()
}

class ServiceKtorPluginConfig {
    private val installers = mutableListOf<ServiceKtorInstaller>()

    fun <B : Any, F : Any> install(
        plugin: BaseRouteScopedPlugin<B, F>,
        configure: B.() -> Unit = {}
    ) {
        installers += ServiceKtorInstaller(pluginKey = plugin.key.name) {
            install(plugin, configure)
        }
    }

    internal fun toInstallers(): List<ServiceKtorInstaller> = installers.toList()
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
    val doc: OpenApiDoc = OpenApiDoc(),
    val executionPolicy: EndpointExecutionPolicy = EndpointExecutionPolicy(),
    val interceptors: List<KClass<out KeelRequestInterceptor>> = emptyList(),
    val interceptorSource: InterceptorMetadataSource = InterceptorMetadataSource.NONE,
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
    val doc: OpenApiDoc = OpenApiDoc(),
    val handler: suspend PluginSseSession.() -> Unit
) : PluginRouteDefinition

data class PluginStaticResourceDefinition(
    override val path: String,
    val basePackage: String,
    val doc: OpenApiDoc = OpenApiDoc(),
    val index: String? = null
) : PluginRouteDefinition

class PluginSseSession internal constructor(
    val request: PluginRequestContext,
    private val sender: suspend (ServerSentEvent) -> Unit
) {
    suspend fun send(event: ServerSentEvent) {
        sender(event)
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
    @PublishedApi internal val endpoints: MutableList<PluginRouteDefinition> = mutableListOf(),
    @PublishedApi internal var inheritedInterceptors: List<KClass<out KeelRequestInterceptor>> = emptyList(),
    @PublishedApi internal var inheritedInterceptorSource: InterceptorMetadataSource = InterceptorMetadataSource.NONE
) {
    @PublishedApi
    internal val pluginIdValue: String = pluginId

    fun interceptors(vararg interceptors: KClass<out KeelRequestInterceptor>) {
        inheritedInterceptors = interceptors.toList()
        inheritedInterceptorSource = InterceptorMetadataSource.DSL
    }

    fun noInterceptors() {
        inheritedInterceptors = emptyList()
        inheritedInterceptorSource = InterceptorMetadataSource.DSL
    }

    fun route(
        path: String,
        block: PluginEndpointDsl.() -> Unit
    ) {
        val combinedPath = joinPaths(basePath, path)
        PluginEndpointDsl(
            pluginIdValue,
            combinedPath,
            endpoints,
            inheritedInterceptors = inheritedInterceptors,
            inheritedInterceptorSource = inheritedInterceptorSource
        ).apply(block)
    }

    inline fun <reified Res : Any> get(
        path: String = "",
        doc: OpenApiDoc = OpenApiDoc(),
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
            doc = doc,
            executionPolicy = executionPolicy,
            interceptors = inheritedInterceptors,
            interceptorSource = inheritedInterceptorSource,
            handler = { _: Unit? -> handler() }
        )
    }

    inline fun <reified Req : Any, reified Res : Any> post(
        path: String = "",
        doc: OpenApiDoc = OpenApiDoc(),
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
            doc = doc,
            executionPolicy = executionPolicy,
            interceptors = inheritedInterceptors,
            interceptorSource = inheritedInterceptorSource,
            handler = { request -> handler(requireNotNull(request)) }
        )
    }

    inline fun <reified Res : Any> post(
        path: String = "",
        doc: OpenApiDoc = OpenApiDoc(),
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
            doc = doc,
            executionPolicy = executionPolicy,
            interceptors = inheritedInterceptors,
            interceptorSource = inheritedInterceptorSource,
            handler = { _: Unit? -> handler() }
        )
    }

    inline fun <reified Req : Any, reified Res : Any> put(
        path: String = "",
        doc: OpenApiDoc = OpenApiDoc(),
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
            doc = doc,
            executionPolicy = executionPolicy,
            interceptors = inheritedInterceptors,
            interceptorSource = inheritedInterceptorSource,
            handler = { request -> handler(requireNotNull(request)) }
        )
    }

    inline fun <reified Res : Any> delete(
        path: String = "",
        doc: OpenApiDoc = OpenApiDoc(),
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
            doc = doc,
            executionPolicy = executionPolicy,
            interceptors = inheritedInterceptors,
            interceptorSource = inheritedInterceptorSource,
            handler = { _: Unit? -> handler() }
        )
    }

    fun sse(
        path: String,
        doc: OpenApiDoc = OpenApiDoc(),
        handler: suspend PluginSseSession.() -> Unit
    ) {
        endpoints += PluginSseDefinition(path = resolvePath(path), doc = doc, handler = handler)
    }

    fun staticResources(
        path: String,
        basePackage: String,
        doc: OpenApiDoc = OpenApiDoc(),
        index: String? = null
    ) {
        require(basePackage.isNotBlank()) { "basePackage must not be blank" }
        endpoints += PluginStaticResourceDefinition(
            path = resolvePath(path),
            basePackage = basePackage,
            doc = doc,
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
