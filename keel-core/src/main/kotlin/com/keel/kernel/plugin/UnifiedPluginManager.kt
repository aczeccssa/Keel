package com.keel.kernel.plugin

import com.keel.kernel.hotreload.PluginDevelopmentSource
import com.keel.kernel.hotreload.ReloadAttemptResult
import com.keel.kernel.hotreload.DevReloadOutcome
import com.keel.openapi.runtime.OpenApiRegistry
import com.keel.kernel.di.PluginScopeManager
import com.keel.kernel.logging.KeelLoggerService
import com.keel.kernel.observability.ObservabilityHub
import com.keel.kernel.observability.ObservabilityTracing
import com.keel.jvm.runtime.PluginSseDataEvent
import io.opentelemetry.context.Context
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.request.path
import io.ktor.server.http.content.staticResources
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.Routing
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.UUID
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.createDirectories
import org.koin.core.Koin

class UnifiedPluginManager(
    private val kernelKoin: Koin,
    runtimeRoot: File = File("/tmp/keel"),
    private val currentClasspath: String = System.getProperty("java.class.path"),
    private val observabilityHub: ObservabilityHub? = null
) : PluginAvailability {
    private val logger = KeelLoggerService.getLogger("UnifiedPluginManager")
    private val entries = ConcurrentHashMap<String, ManagedPlugin>()
    private val registry = PluginRegistry(entries)
    private val developmentSources = ConcurrentHashMap<String, PluginDevelopmentSource>()

    data class KtorScopeSignature(
        val applicationPluginKeys: List<String>,
        val servicePluginKeys: List<String>
    )

    data class ReloadCompatibilityDecision(
        val outcome: DevReloadOutcome,
        val message: String
    )

    companion object {
        private const val EXTERNAL_SSE_IDLE_TIMEOUT_MS: Long = 60_000

        fun hasKtorScopeDrift(previous: KtorScopeSignature, current: KtorScopeSignature): Boolean {
            return PluginReloadCompatibility.hasKtorScopeDrift(
                previous = PluginReloadCompatibility.KtorScopeSignature(
                    applicationPluginKeys = previous.applicationPluginKeys,
                    servicePluginKeys = previous.servicePluginKeys
                ),
                current = PluginReloadCompatibility.KtorScopeSignature(
                    applicationPluginKeys = current.applicationPluginKeys,
                    servicePluginKeys = current.servicePluginKeys
                )
            )
        }

        fun decideReloadCompatibility(
            previousTopology: Set<String>,
            newTopology: Set<String>,
            previousKtorScope: KtorScopeSignature,
            newKtorScope: KtorScopeSignature
        ): ReloadCompatibilityDecision {
            val decision = PluginReloadCompatibility.decideReloadCompatibility(
                previousTopology = previousTopology,
                newTopology = newTopology,
                previousKtorScope = PluginReloadCompatibility.KtorScopeSignature(
                    applicationPluginKeys = previousKtorScope.applicationPluginKeys,
                    servicePluginKeys = previousKtorScope.servicePluginKeys
                ),
                newKtorScope = PluginReloadCompatibility.KtorScopeSignature(
                    applicationPluginKeys = newKtorScope.applicationPluginKeys,
                    servicePluginKeys = newKtorScope.servicePluginKeys
                )
            )
            return ReloadCompatibilityDecision(decision.outcome, decision.message)
        }
    }
    private val pluginScopeManager = PluginScopeManager(kernelKoin)
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var routing: Routing? = null

    private val kernelInstanceId = UUID.randomUUID().toString()
    private val kernelRuntimeDir = runtimeRoot.toPath().resolve(kernelInstanceId.take(8)).createDirectories().toFile()
    private val lifecycleCoordinator = PluginLifecycleCoordinator(
        registry = registry,
        kernelKoin = kernelKoin,
        pluginScopeManager = pluginScopeManager,
        currentClasspath = currentClasspath,
        kernelRuntimeDir = kernelRuntimeDir,
        observabilityHub = observabilityHub,
        managerScope = managerScope
    )
    private val reloadCoordinator = PluginReloadCoordinator(
        currentClasspath = currentClasspath,
        extractCapabilities = ::extractCapabilities,
        stopPluginLocked = { entry -> lifecycleCoordinator.stopPluginLocked(entry, ::normalizeProcessState, ::recordFailure) },
        disposePluginLocked = { entry -> lifecycleCoordinator.disposePluginLocked(entry, ::normalizeProcessState, ::recordFailure) },
        startPluginLocked = { entry -> lifecycleCoordinator.startPluginLocked(entry, ::normalizeProcessState, ::recordFailure) }
    )

    fun registerPlugin(
        plugin: KeelPlugin,
        enabledOverride: Boolean? = null
    ) {
        val descriptor = plugin.descriptor
        val config = descriptor.toConfig(enabled = enabledOverride ?: true)
        val capabilities = extractCapabilities(plugin, descriptor.pluginId)
        registry.register(ManagedPlugin(
            plugin = plugin,
            pluginClassName = plugin.javaClass.name,
            endpointById = capabilities.endpointById.toMutableMap(),
            endpointTopology = capabilities.topology,
            sseByPath = capabilities.sseByPath.toMutableMap(),
            routeDefinitions = capabilities.routeDefinitions,
            config = config,
            lifecycleState = PluginLifecycleState.REGISTERED,
            healthState = PluginHealthState.UNKNOWN,
            generation = PluginGeneration.INITIAL,
            processState = if (config.runtimeMode == PluginRuntimeMode.EXTERNAL_JVM) PluginProcessState.STOPPED else null,
            pluginApplicationInstallers = capabilities.applicationInstallers,
            pluginServiceRouteInstallers = capabilities.serviceInstallers
        ))
        logger.info("Registered unified plugin ${descriptor.pluginId} mode=${config.runtimeMode}")
    }

    fun installConfiguredPluginApplicationKtorPlugins(application: Application) {
        val installedPluginOwners = linkedMapOf<String, String>()
        registry.values()
            .asSequence()
            .filter { it.config.enabled }
            .sortedBy { it.plugin.descriptor.pluginId }
            .forEach { entry ->
                entry.pluginApplicationInstallers.forEach { installer ->
                    val pluginId = entry.plugin.descriptor.pluginId
                    val existingOwner = installedPluginOwners.putIfAbsent(installer.pluginKey, pluginId)
                    if (existingOwner != null) {
                        throw IllegalStateException(
                            "Duplicate plugin application-scope Ktor plugin key='${installer.pluginKey}' " +
                                "already declared by pluginId=$existingOwner, conflicted pluginId=$pluginId"
                        )
                    }
                    runCatching {
                        installer.installer(application)
                    }.getOrElse { error ->
                        throw IllegalStateException(
                            "Failed to install plugin application-scope Ktor plugin key='${installer.pluginKey}' " +
                                "for pluginId=$pluginId: ${error.message}",
                            error
                        )
                    }
                }
            }
    }

    fun registerPluginSource(source: PluginDevelopmentSource) {
        developmentSources[source.pluginId] = source
    }

    fun hasPluginSource(pluginId: String): Boolean = developmentSources.containsKey(pluginId)

    suspend fun reloadPluginFromSource(
        source: PluginDevelopmentSource,
        classpathModulePaths: Set<String>,
        reason: String
    ): ReloadAttemptResult {
        registerPluginSource(source)
        return withPluginLock(source.pluginId) { entry ->
            val snapshot = reloadCoordinator.snapshotEntry(entry)
            val loadResult = reloadCoordinator.loadNewPluginGeneration(source, classpathModulePaths)
            val newGeneration = loadResult.generation ?: return@withPluginLock ReloadAttemptResult(
                pluginId = source.pluginId,
                outcome = DevReloadOutcome.RELOAD_FAILED,
                message = "Source load failed: ${loadResult.error ?: "unknown error"}"
            )
            val validationFailure = reloadCoordinator.validateNewPluginGeneration(source, newGeneration, snapshot)
            if (validationFailure != null) {
                runCatching { newGeneration.classLoader.close() }
                return@withPluginLock validationFailure
            }

            return@withPluginLock runCatching {
                reloadCoordinator.performGenerationSwap(
                    entry = entry,
                    source = source,
                    newGeneration = newGeneration,
                    snapshot = snapshot,
                    normalizeProcessState = ::normalizeProcessState,
                    reason = reason
                )
            }.getOrElse { error ->
                logger.warn("Source reload failed for ${source.pluginId}: ${error.message}")
                reloadCoordinator.rollbackGenerationSwap(entry, snapshot, ::normalizeProcessState)
                runCatching { newGeneration.classLoader.close() }
                ReloadAttemptResult(
                    pluginId = source.pluginId,
                    outcome = DevReloadOutcome.RELOAD_FAILED,
                    message = "Source reload failed: ${error.message}"
                )
            }
        }
    }

    private fun extractCapabilities(plugin: KeelPlugin, topologyPluginId: String): PluginCapabilities {
        val routeDefinitions = mergeGeneratedInterceptorMetadata(plugin, plugin.endpoints())
        validateServiceDeclaration(plugin.descriptor, routeDefinitions)
        val endpointDefinitions = routeDefinitions.filterIsInstance<PluginEndpointDefinition<*, *>>()
        val endpointById = endpointDefinitions.associateBy { it.endpointId }
        val topology = endpointDefinitions
            .map { operationKey(it.method, fullPluginPath(topologyPluginId, it.path)) }
            .toSet()
        val sseByPath = routeDefinitions.filterIsInstance<PluginSseDefinition>().associateBy { it.path }
        val ktorConfig = plugin.ktorPlugins()
        return PluginCapabilities(
            routeDefinitions = routeDefinitions,
            endpointById = endpointById,
            topology = topology,
            sseByPath = sseByPath,
            applicationInstallers = ktorConfig.configuredApplicationInstallers(),
            serviceInstallers = ktorConfig.configuredServiceInstallers()
        )
    }

    fun mountRoutes(routing: Routing) {
        this.routing = routing
        for (entry in registry.values()) {
            mountPluginRoutes(routing, entry)
        }
    }

    suspend fun startEnabledPlugins() {
        lifecycleCoordinator.startEnabledPlugins(::startPlugin)
    }

    suspend fun startPlugin(pluginId: String) {
        withPluginLock(pluginId) { entry ->
            startPluginLocked(entry)
        }
    }

    suspend fun stopPlugin(pluginId: String) {
        withPluginLock(pluginId) { entry ->
            stopPluginLocked(entry)
        }
    }

    suspend fun disposePlugin(pluginId: String) {
        withPluginLock(pluginId) { entry ->
            disposePluginLocked(entry)
        }
    }

    suspend fun reloadPlugin(pluginId: String) {
        withPluginLock(pluginId) { entry ->
            lifecycleCoordinator.restartPluginGenerationLocked(entry, "reload", {
                entry.plugin.descriptor.toConfig()
            }, ::normalizeProcessState, ::recordFailure)
        }
    }

    suspend fun replacePlugin(pluginId: String) {
        withPluginLock(pluginId) { entry ->
            lifecycleCoordinator.restartPluginGenerationLocked(entry, "replace", {
                entry.plugin.descriptor.toConfig()
            }, ::normalizeProcessState, ::recordFailure)
        }
    }

    suspend fun stopAll() {
        lifecycleCoordinator.stopAll(::disposePlugin)
    }

    fun getAllPlugins(): Map<String, KeelPlugin> = registry.allPlugins()

    @Suppress("unused")
    fun getPlugin(pluginId: String): KeelPlugin? = registry.get(pluginId)?.plugin

    fun getRuntimeMode(pluginId: String): PluginRuntimeMode? = registry.get(pluginId)?.config?.runtimeMode

    fun getLifecycleState(pluginId: String): PluginLifecycleState = registry.get(pluginId)?.lifecycleState ?: PluginLifecycleState.REGISTERED

    @Suppress("unused")
    fun getHealthState(pluginId: String): PluginHealthState = registry.get(pluginId)?.healthState ?: PluginHealthState.UNKNOWN

    fun getGeneration(pluginId: String): PluginGeneration = registry.get(pluginId)?.generation ?: PluginGeneration.INITIAL

    @Suppress("unused")
    fun getProcessState(pluginId: String): PluginProcessState? = registry.get(pluginId)?.processState

    fun getProcessId(pluginId: String): Long? = registry.get(pluginId)?.processId

    fun getProcessHandle(pluginId: String): ProcessHandle? = registry.get(pluginId)?.processHandle

    @Suppress("unused")
    fun isProcessAlive(pluginId: String): Boolean = registry.get(pluginId)?.processHandle?.isAlive ?: false

    fun getRuntimeConfig(pluginId: String): PluginConfig? = registry.get(pluginId)?.config

    @Suppress("unused")
    fun getLastFailure(pluginId: String): PluginFailureRecord? = registry.get(pluginId)?.lastFailure

    fun getRuntimeSnapshot(pluginId: String): PluginRuntimeSnapshot? = registry.get(pluginId)?.let(lifecycleCoordinator::buildSnapshot)

    fun getRuntimeSnapshots(): List<PluginRuntimeSnapshot> = registry.sortedValues().map(lifecycleCoordinator::buildSnapshot)

    @Suppress("unused")
    fun isIsolated(pluginId: String): Boolean = getRuntimeMode(pluginId) == PluginRuntimeMode.EXTERNAL_JVM

    override fun isPluginEnabled(pluginId: String): Boolean = getLifecycleState(pluginId) == PluginLifecycleState.RUNNING

    fun resolveDispatchDisposition(pluginId: String): PluginDispatchDisposition {
        val entry = registry.get(pluginId) ?: return PluginDispatchDisposition.PASS_THROUGH
        return when {
            entry.lifecycleState == PluginLifecycleState.DISPOSED -> PluginDispatchDisposition.NOT_FOUND
            entry.lifecycleState != PluginLifecycleState.RUNNING -> PluginDispatchDisposition.UNAVAILABLE
            entry.healthState == PluginHealthState.UNREACHABLE -> PluginDispatchDisposition.UNAVAILABLE
            else -> PluginDispatchDisposition.AVAILABLE
        }
    }

    suspend fun forceKill(pluginId: String): Boolean {
        return withPluginLock(pluginId) { entry ->
            val killed = entry.supervisor?.forceKill() ?: false
            if (killed) {
                entry.lifecycleState = PluginLifecycleState.FAILED
                entry.healthState = PluginHealthState.UNREACHABLE
                entry.processState = PluginProcessState.FAILED
                recordFailure(entry, "force-kill", "Kernel force-killed plugin process")
            }
            killed
        }
    }

    private suspend fun startPluginLocked(entry: ManagedPlugin, resetRecoveryBudget: Boolean = true) {
        lifecycleCoordinator.startPluginLocked(entry, ::normalizeProcessState, ::recordFailure, resetRecoveryBudget)
    }

    private suspend fun attemptProcessRecovery(
        pluginId: String,
        reason: String,
        suggestTcpFallback: Boolean
    ) {
        withPluginLock(pluginId) { entry ->
            lifecycleCoordinator.attemptProcessRecovery(entry, reason, suggestTcpFallback, ::normalizeProcessState, ::recordFailure)
        }
    }

    private suspend fun stopPluginLocked(entry: ManagedPlugin) {
        lifecycleCoordinator.stopPluginLocked(entry, ::normalizeProcessState, ::recordFailure)
    }

    private suspend fun disposePluginLocked(entry: ManagedPlugin) {
        lifecycleCoordinator.disposePluginLocked(entry, ::normalizeProcessState, ::recordFailure)
    }

    private fun normalizeProcessState(entry: ManagedPlugin) {
        lifecycleCoordinator.normalizeProcessState(entry)
    }

    private fun recordFailure(entry: ManagedPlugin, source: String, message: String) {
        entry.lastFailure = PluginFailureRecord(
            timestamp = System.currentTimeMillis(),
            source = source,
            message = message
        )
    }

    private suspend fun <T> withPluginLock(pluginId: String, block: suspend (ManagedPlugin) -> T): T {
        val entry = registry.get(pluginId) ?: error("Plugin not registered: $pluginId")
        return entry.lifecycleMutex.withLock { block(entry) }
    }

    private fun validateServiceDeclaration(
        descriptor: PluginDescriptor,
        routeDefinitions: List<PluginRouteDefinition>
    ) {
        val requiredServices = buildSet {
            if (routeDefinitions.any { it is PluginEndpointDefinition<*, *> }) add(PluginServiceType.ENDPOINT)
            if (routeDefinitions.any { it is PluginSseDefinition }) add(PluginServiceType.SSE)
            if (routeDefinitions.any { it is PluginStaticResourceDefinition }) add(PluginServiceType.STATIC_RESOURCE)
        }
        val missing = requiredServices - descriptor.supportedServices
        require(missing.isEmpty()) {
            "Plugin ${descriptor.pluginId} is missing service declarations: ${missing.joinToString()}"
        }
        val declaredButUnused = descriptor.supportedServices - requiredServices
        if (declaredButUnused.isNotEmpty()) {
            logger.warn(
                "Plugin ${descriptor.pluginId} declares unused services: ${declaredButUnused.joinToString()}"
            )
        }
    }

    private fun mountPluginRoutes(routing: Routing, entry: ManagedPlugin) {
        val pluginId = entry.plugin.descriptor.pluginId
        val routes = entry.routeDefinitions
        val endpoints = routes.filterIsInstance<PluginEndpointDefinition<*, *>>()
        val sseRoutes = routes.filterIsInstance<PluginSseDefinition>()
        val staticRoutes = routes.filterIsInstance<PluginStaticResourceDefinition>()
        val endpointKeys = endpoints.map { operationKey(it.method, fullPluginPath(pluginId, it.path)) }
        val duplicates = endpointKeys.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (duplicates.isNotEmpty()) {
            error("Duplicate plugin endpoint registration for pluginId=$pluginId: ${duplicates.joinToString()}")
        }
        val sseKeys = sseRoutes.map { operationKey(HttpMethod.Get, fullPluginPath(pluginId, it.path)) }
        val duplicateSse = sseKeys.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (duplicateSse.isNotEmpty()) {
            error("Duplicate plugin SSE registration for pluginId=$pluginId: ${duplicateSse.joinToString()}")
        }
        val staticOperationKeys = staticRoutes.map { operationKey(HttpMethod.Get, fullPluginPath(pluginId, it.path)) }
        val staticKeys = staticRoutes.map { fullPluginPath(pluginId, it.path) }
        val duplicateStatic = staticKeys.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (duplicateStatic.isNotEmpty()) {
            error("Duplicate plugin static resource registration for pluginId=$pluginId: ${duplicateStatic.joinToString()}")
        }
        val pathCollisions = endpointKeys.intersect(sseKeys)
        if (pathCollisions.isNotEmpty()) {
            error("Plugin endpoint/SSE path conflict for pluginId=$pluginId: ${pathCollisions.joinToString()}")
        }
        val staticConflicts = staticRoutes.filter { definition ->
            val prefix = fullPluginPath(pluginId, definition.path)
            endpointKeys.any { it == operationKey(HttpMethod.Get, prefix) || it.startsWith("${HttpMethod.Get.value} $prefix/") } ||
                sseKeys.any { it == operationKey(HttpMethod.Get, prefix) || it.startsWith("${HttpMethod.Get.value} $prefix/") }
        }
        if (staticConflicts.isNotEmpty()) {
            error("Plugin static resource path conflict for pluginId=$pluginId: ${staticConflicts.joinToString { it.path }}")
        }
        routing.route("/api/plugins/$pluginId") {
            entry.pluginServiceRouteInstallers.forEach { installer ->
                installer.installer(this)
            }
            for (endpoint in endpoints) {
                registerPluginOperation(pluginId, endpoint)
                val fullPath = endpoint.path.ifBlank { "" }
                when (endpoint.method) {
                    HttpMethod.Get -> mountGet(fullPath, pluginId, endpoint.endpointId, endpoint.doc.responseEnvelope)
                    HttpMethod.Post -> mountPost(fullPath, pluginId, endpoint.endpointId, endpoint.doc.responseEnvelope)
                    HttpMethod.Put -> mountPut(fullPath, pluginId, endpoint.endpointId, endpoint.doc.responseEnvelope)
                    HttpMethod.Delete -> mountDelete(fullPath, pluginId, endpoint.endpointId, endpoint.doc.responseEnvelope)
                    else -> error("Unsupported method: ${endpoint.method}")
                }
            }
            for (definition in sseRoutes) {
                registerPluginSseOperation(pluginId, definition.path, definition.doc)
                mountSse(entry, definition.path, pluginId, definition.path)
            }
            for (definition in staticRoutes) {
                registerPluginStaticOperation(pluginId, definition.path, definition.index != null, definition.doc)
                when (entry.config.runtimeMode) {
                    PluginRuntimeMode.IN_PROCESS -> staticResources(definition.path, definition.basePackage, definition.index)
                    PluginRuntimeMode.EXTERNAL_JVM -> mountExternalStatic(definition.path, pluginId, definition.path)
                }
            }
        }
        validateOpenApiTopologyRegistration(
            pluginId = pluginId,
            expectedKeys = buildSet {
                addAll(endpointKeys)
                addAll(sseKeys)
                addAll(staticOperationKeys)
            }
        )
    }

    private fun validateOpenApiTopologyRegistration(pluginId: String, expectedKeys: Set<String>) {
        if (expectedKeys.isEmpty()) {
            return
        }
        val pluginPrefix = "/api/plugins/$pluginId"
        val actualKeys = OpenApiRegistry.operations()
            .asSequence()
            .filter { operation ->
                operation.path == pluginPrefix || operation.path.startsWith("$pluginPrefix/")
            }
            .map { operationKey(it.method, it.path) }
            .toSet()
        val missingKeys = expectedKeys - actualKeys
        if (missingKeys.isNotEmpty()) {
            error(
                "OpenAPI registered operations for pluginId=$pluginId are missing " +
                    "route topology keys: ${missingKeys.sorted().joinToString()}"
            )
        }
    }

    private fun Route.mountGet(path: String, pluginId: String, endpointId: String, responseEnvelope: Boolean) {
        if (path.isBlank()) {
            get { handleInvocation(pluginId, endpointId, responseEnvelope) }
        } else {
            get(path) { handleInvocation(pluginId, endpointId, responseEnvelope) }
        }
    }

    private fun Route.mountPost(path: String, pluginId: String, endpointId: String, responseEnvelope: Boolean) {
        if (path.isBlank()) {
            post { handleInvocation(pluginId, endpointId, responseEnvelope) }
        } else {
            post(path) { handleInvocation(pluginId, endpointId, responseEnvelope) }
        }
    }

    private fun Route.mountPut(path: String, pluginId: String, endpointId: String, responseEnvelope: Boolean) {
        if (path.isBlank()) {
            put { handleInvocation(pluginId, endpointId, responseEnvelope) }
        } else {
            put(path) { handleInvocation(pluginId, endpointId, responseEnvelope) }
        }
    }

    private fun Route.mountDelete(path: String, pluginId: String, endpointId: String, responseEnvelope: Boolean) {
        if (path.isBlank()) {
            delete { handleInvocation(pluginId, endpointId, responseEnvelope) }
        } else {
            delete(path) { handleInvocation(pluginId, endpointId, responseEnvelope) }
        }
    }

    private fun Route.mountSse(entry: ManagedPlugin, path: String, pluginId: String, ssePath: String) {
        val handler: suspend io.ktor.server.sse.ServerSSESession.() -> Unit = {
            when (resolveDispatchDisposition(pluginId)) {
                PluginDispatchDisposition.NOT_FOUND -> {
                    close()
                }
                PluginDispatchDisposition.UNAVAILABLE -> {
                    send(ServerSentEvent(data = """{"error":"plugin unavailable"}""", event = "error"))
                    close()
                }
                PluginDispatchDisposition.PASS_THROUGH,
                PluginDispatchDisposition.AVAILABLE -> {
                    when (entry.config.runtimeMode) {
                        PluginRuntimeMode.IN_PROCESS -> {
                            val definition = resolveSseDefinition(pluginId, ssePath)
                            if (definition == null) {
                                send(ServerSentEvent(data = """{"error":"plugin route unavailable"}""", event = "error"))
                                close()
                            } else {
                                val context = buildRequestContext(call, pluginId, HttpMethod.Get, call.request.path())
                                definition.handler.invoke(
                                    PluginSseSession(
                                        request = context,
                                        sender = { event -> send(event) }
                                    )
                                )
                            }
                        }
                        PluginRuntimeMode.EXTERNAL_JVM -> {
                            streamSseFromExternal(entry, ssePath)
                        }
                    }
                }
            }
        }
        if (path.isBlank()) {
            sse(handler)
        } else {
            sse(path, handler)
        }
    }

    private fun Route.mountExternalStatic(path: String, pluginId: String, staticPath: String) {
        if (path.isBlank()) {
            get("{...}") { proxyExternalStatic(pluginId, staticPath) }
        } else {
            get("$path/{...}") { proxyExternalStatic(pluginId, staticPath) }
        }
    }

    private suspend fun io.ktor.server.routing.RoutingContext.proxyExternalStatic(
        pluginId: String,
        staticPath: String
    ) {
        val entry = registry.get(pluginId) ?: run {
            call.respond(HttpStatusCode.NotFound, "Plugin not found")
            return
        }
        val supervisor = entry.supervisor ?: run {
            call.respond(HttpStatusCode.ServiceUnavailable, "Plugin supervisor unavailable")
            return
        }
        val tail = call.parameters.getAll("...")?.joinToString("/") ?: ""
        val resourcePath = if (tail.isBlank()) "/" else "/$tail"
        val response = runCatching {
            supervisor.fetchStaticResource(
                routePath = staticPath,
                resourcePath = resourcePath,
                requestHeaders = call.request.headers.entries().associate { it.key to it.value }
            )
        }.getOrElse { error ->
            call.respond(HttpStatusCode.ServiceUnavailable, "Static proxy failed: ${error.message}")
            return
        }
        response.headers.forEach { (key, values) ->
            values.forEach { call.response.headers.append(key, it, safeOnly = false) }
        }
        val status = HttpStatusCode.fromValue(response.status)
        val body = response.bodyBase64?.let { Base64.getDecoder().decode(it) }
        if (body == null) {
            call.respond(status, response.errorMessage ?: "")
            return
        }
        val contentType = response.headers["Content-Type"]?.firstOrNull()?.let { ContentType.parse(it) }
            ?: ContentType.Application.OctetStream
        call.respondBytes(body, contentType = contentType, status = status)
    }

    private suspend fun io.ktor.server.sse.ServerSSESession.streamSseFromExternal(
        entry: ManagedPlugin,
        routePath: String
    ) {
        val supervisor = entry.supervisor ?: run {
            send(ServerSentEvent(data = """{"error":"plugin supervisor unavailable"}""", event = "error"))
            close()
            return
        }
        val streamId = UUID.randomUUID().toString()
        val eventChannel = Channel<PluginSseDataEvent>(capacity = Channel.UNLIMITED)
        supervisor.registerSseStreamListener(
            streamId = streamId,
            onData = { event ->
                if (eventChannel.trySend(event).isFailure) {
                    logger.warn("Dropped SSE event for pluginId=${entry.plugin.descriptor.pluginId} streamId=$streamId because the stream is closed")
                }
            },
            onClosed = {
                eventChannel.close()
            }
        )
        try {
            val call = call
            val requestId = call.request.headers["X-Request-Id"] ?: streamId
            val open = supervisor.openSseStream(
                streamId = streamId,
                routePath = routePath,
                requestId = requestId,
                rawPath = call.request.path(),
                pathParameters = call.parameters.entries().associate { it.key to it.value.first() },
                queryParameters = call.request.queryParameters.entries().associate { it.key to it.value },
                headers = call.request.headers.entries().associate { it.key to it.value }
            )
            if (!open.accepted) {
                send(ServerSentEvent(data = open.errorMessage ?: "SSE open rejected", event = "error"))
                close()
                return
            }
            while (true) {
                val event = withTimeoutOrNull(EXTERNAL_SSE_IDLE_TIMEOUT_MS) {
                    eventChannel.receiveCatching().getOrNull()
                } ?: break
                send(
                    ServerSentEvent(
                        data = event.data,
                        event = event.event,
                        id = event.id,
                        retry = event.retry
                    )
                )
            }
        } finally {
            runCatching { supervisor.closeSseStream(streamId) }
                .onFailure { error ->
                    logger.warn(
                        "Failed to close SSE stream pluginId=${entry.plugin.descriptor.pluginId} streamId=$streamId: ${error.message}"
                    )
                }
            supervisor.unregisterSseStreamListener(streamId)
            eventChannel.close()
        }
    }

    private suspend fun io.ktor.server.routing.RoutingContext.handleInvocation(
        pluginId: String,
        endpointId: String,
        responseEnvelope: Boolean
    ) {
        val entry = registry.get(pluginId) ?: return
        val endpoint = resolveEndpoint(pluginId, endpointId) ?: run {
            call.respond(HttpStatusCode.NotFound, "Plugin endpoint not found")
            return
        }
        try {
            val tracer = ObservabilityTracing.kernelTracer()
            if (!ensureDispatchAvailable(entry, endpoint, responseEnvelope)) return
            if (!tryAcquireInvokeLimiter(entry, endpoint, responseEnvelope)) return
            var invocationStarted = false
            try {
                val requestPayload = readRequestPayload(entry, endpoint, responseEnvelope) ?: return
                entry.inFlightInvocations.incrementAndGet()
                invocationStarted = true
                val timeoutMs = endpoint.executionPolicy.timeoutMs ?: entry.config.callTimeoutMs
                val parentContext = call.attributes.getOrNull(ObservabilityTracing.TRACE_CONTEXT_KEY) ?: Context.current()
                when (entry.config.runtimeMode) {
                    PluginRuntimeMode.IN_PROCESS -> {
                        invokeInProcess(
                            entry = entry,
                            endpoint = endpoint,
                            responseEnvelope = responseEnvelope,
                            rawBody = requestPayload.rawBody,
                            maxPayloadBytes = requestPayload.maxPayloadBytes,
                            timeoutMs = timeoutMs,
                            tracer = tracer,
                            parentContext = parentContext
                        )
                    }
                    PluginRuntimeMode.EXTERNAL_JVM -> {
                        invokeExternal(
                            entry = entry,
                            endpoint = endpoint,
                            responseEnvelope = responseEnvelope,
                            rawBody = requestPayload.rawBody,
                            tracer = tracer,
                            parentContext = parentContext
                        )
                    }
                }
            } finally {
                if (invocationStarted) {
                    entry.inFlightInvocations.decrementAndGet()
                }
                entry.invokeLimiter.release()
            }
        } catch (error: TimeoutCancellationException) {
            respondPluginResult(
                call = call,
                result = PluginResult(status = HttpStatusCode.GatewayTimeout.value, body = null),
                responseType = endpoint.responseType,
                responseEnvelope = responseEnvelope,
                errorMessage = "Plugin call timed out"
            )
        } catch (error: PluginApiException) {
            respondPluginResult(
                call = call,
                result = PluginResult(status = error.status, body = null),
                responseType = endpoint.responseType,
                responseEnvelope = responseEnvelope,
                errorMessage = error.message
            )
        } catch (error: Exception) {
            logger.error("Plugin endpoint failed pluginId=${entry.plugin.descriptor.pluginId} endpoint=${endpoint.endpointId}", error)
            respondPluginResult(
                call = call,
                result = PluginResult(status = HttpStatusCode.InternalServerError.value, body = null),
                responseType = endpoint.responseType,
                responseEnvelope = responseEnvelope,
                errorMessage = error.message ?: "Internal server error"
            )
        }
    }

    private suspend fun io.ktor.server.routing.RoutingContext.ensureDispatchAvailable(
        entry: ManagedPlugin,
        endpoint: PluginEndpointDefinition<*, *>,
        responseEnvelope: Boolean
    ): Boolean {
        return when (resolveDispatchDisposition(entry.plugin.descriptor.pluginId)) {
            PluginDispatchDisposition.NOT_FOUND -> {
                respondPluginResult(
                    call = call,
                    result = PluginResult(status = HttpStatusCode.NotFound.value, body = null),
                    responseType = endpoint.responseType,
                    responseEnvelope = responseEnvelope,
                    errorMessage = "Plugin '${entry.plugin.descriptor.pluginId}' is disposed"
                )
                false
            }
            PluginDispatchDisposition.UNAVAILABLE -> {
                respondPluginResult(
                    call = call,
                    result = PluginResult(status = HttpStatusCode.ServiceUnavailable.value, body = null),
                    responseType = endpoint.responseType,
                    responseEnvelope = responseEnvelope,
                    errorMessage = "Plugin '${entry.plugin.descriptor.pluginId}' is currently unavailable"
                )
                false
            }
            PluginDispatchDisposition.PASS_THROUGH,
            PluginDispatchDisposition.AVAILABLE -> true
        }
    }

    private suspend fun io.ktor.server.routing.RoutingContext.tryAcquireInvokeLimiter(
        entry: ManagedPlugin,
        endpoint: PluginEndpointDefinition<*, *>,
        responseEnvelope: Boolean
    ): Boolean {
        if (entry.invokeLimiter.tryAcquire()) return true
        respondPluginResult(
            call = call,
            result = PluginResult(status = HttpStatusCode.ServiceUnavailable.value, body = null),
            responseType = endpoint.responseType,
            responseEnvelope = responseEnvelope,
            errorMessage = "Plugin '${entry.plugin.descriptor.pluginId}' is at max concurrency"
        )
        return false
    }

    private data class RequestPayload(
        val rawBody: String?,
        val maxPayloadBytes: Long?
    )

    private suspend fun io.ktor.server.routing.RoutingContext.readRequestPayload(
        entry: ManagedPlugin,
        endpoint: PluginEndpointDefinition<*, *>,
        responseEnvelope: Boolean
    ): RequestPayload? {
        val rawBody = readValidatedRequestBody(call, endpoint.requestType)
        val requestBytes = rawBody?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L
        val maxPayloadBytes = endpoint.executionPolicy.maxPayloadBytes
        if (maxPayloadBytes != null && requestBytes > maxPayloadBytes && !endpoint.executionPolicy.allowChunkedTransfer) {
            respondPluginResult(
                call = call,
                result = PluginResult(status = HttpStatusCode.PayloadTooLarge.value, body = null),
                responseType = endpoint.responseType,
                responseEnvelope = responseEnvelope,
                errorMessage = "Request payload exceeds $maxPayloadBytes bytes"
            )
            return null
        }
        return RequestPayload(rawBody, maxPayloadBytes)
    }

    private suspend fun io.ktor.server.routing.RoutingContext.invokeInProcess(
        entry: ManagedPlugin,
        endpoint: PluginEndpointDefinition<*, *>,
        responseEnvelope: Boolean,
        rawBody: String?,
        maxPayloadBytes: Long?,
        timeoutMs: Long,
        tracer: io.opentelemetry.api.trace.Tracer?,
        parentContext: Context
    ) {
        val span = tracer?.spanBuilder("plugin.invoke")
            ?.setParent(parentContext)
            ?.setAttribute("keel.pluginId", entry.plugin.descriptor.pluginId)
            ?.setAttribute("keel.jvm", "kernel")
            ?.setAttribute("keel.edge.from", "kernel")
            ?.setAttribute("keel.edge.to", entry.plugin.descriptor.pluginId)
            ?.setAttribute("http.method", endpoint.method.value)
            ?.setAttribute("http.route", call.request.path())
            ?.setAttribute("network.protocol.name", call.request.local.scheme.uppercase())
            ?.startSpan()
        val scope = span?.makeCurrent()
        try {
            val context = buildRequestContext(call, entry.plugin.descriptor.pluginId, endpoint.method, call.request.path())
            val privateScope = requireNotNull(entry.privateScopeHandle?.privateScope) {
                "No private scope available for plugin ${entry.plugin.descriptor.pluginId}"
            }
            val interceptors = resolveInterceptors(privateScope, endpoint.interceptors)
            val interception = withTimeout(timeoutMs) {
                executeKeelInterceptors(context, interceptors) {
                    val request = decodeRequestBody(rawBody, endpoint.requestType)
                    endpoint.execute(context, request)
                }
            }
            val result = when (interception) {
                is KeelInterceptorResult.Proceed -> interception.result
                is KeelInterceptorResult.Reject -> {
                    respondPluginResult(
                        call = call,
                        result = interception.toPluginResult(),
                        responseType = endpoint.responseType,
                        responseEnvelope = responseEnvelope,
                        errorMessage = interception.message
                    )
                    return
                }
            }
            val responseBytes = encodeResponseBody(result.body, endpoint.responseType)
                ?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: 0L
            if (maxPayloadBytes != null && responseBytes > maxPayloadBytes && !endpoint.executionPolicy.allowChunkedTransfer) {
                respondPluginResult(
                    call = call,
                    result = PluginResult(status = HttpStatusCode.PayloadTooLarge.value, body = null),
                    responseType = endpoint.responseType,
                    responseEnvelope = responseEnvelope,
                    errorMessage = "Response payload exceeds $maxPayloadBytes bytes"
                )
                return
            }
            respondPluginResult(call, result, endpoint.responseType, responseEnvelope)
        } finally {
            if (span != null) {
                call.response.status()?.value?.let { code -> span.setAttribute("http.status_code", code.toLong()) }
            }
            scope?.close()
            span?.end()
        }
    }

    private suspend fun io.ktor.server.routing.RoutingContext.invokeExternal(
        entry: ManagedPlugin,
        endpoint: PluginEndpointDefinition<*, *>,
        responseEnvelope: Boolean,
        rawBody: String?,
        tracer: io.opentelemetry.api.trace.Tracer?,
        parentContext: Context
    ) {
        val span = tracer?.spanBuilder("plugin.dispatch")
            ?.setParent(parentContext)
            ?.setAttribute("keel.pluginId", entry.plugin.descriptor.pluginId)
            ?.setAttribute("keel.jvm", "kernel")
            ?.setAttribute("keel.edge.from", "kernel")
            ?.setAttribute("keel.edge.to", entry.plugin.descriptor.pluginId)
            ?.setAttribute("http.method", endpoint.method.value)
            ?.setAttribute("http.route", call.request.path())
            ?.setAttribute("network.protocol.name", call.request.local.scheme.uppercase())
            ?.startSpan()
        val scope = span?.makeCurrent()
        try {
            val response = requireNotNull(entry.supervisor) { "No supervisor available for isolated plugin ${entry.plugin.descriptor.pluginId}" }
                .invoke(endpoint, call, rawBody)
            val body = response.bodyJson?.let { runtimeJson.decodeFromString(serializer(endpoint.responseType), it) }
            respondPluginResult(
                call = call,
                result = PluginResult(status = response.status, headers = response.headers, body = body),
                responseType = endpoint.responseType,
                responseEnvelope = responseEnvelope,
                errorMessage = response.errorMessage
            )
        } finally {
            if (span != null) {
                call.response.status()?.value?.let { code -> span.setAttribute("http.status_code", code.toLong()) }
            }
            scope?.close()
            span?.end()
        }
    }

    @Suppress("unused")
    fun applicationRouting(): Routing = requireNotNull(routing) { "Routing has not been mounted yet" }

    private fun resolveEndpoint(pluginId: String, endpointId: String): PluginEndpointDefinition<*, *>? {
        return registry.get(pluginId)?.endpointById?.get(endpointId)
    }

    private fun resolveSseDefinition(pluginId: String, path: String): PluginSseDefinition? {
        return registry.get(pluginId)?.sseByPath?.get(path)
    }

}
