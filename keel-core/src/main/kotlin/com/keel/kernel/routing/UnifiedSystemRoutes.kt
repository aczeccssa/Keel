package com.keel.kernel.routing

import com.keel.contract.dto.KeelResponse
import com.keel.kernel.config.FrameworkVersion
import com.keel.kernel.hotreload.DevHotReloadEngine
import com.keel.kernel.hotreload.DevReloadEvent
import com.keel.kernel.loader.DefaultPluginLoader
import com.keel.kernel.loader.DiscoveredPlugin
import com.keel.kernel.plugin.PluginChannelHealth
import com.keel.kernel.plugin.PluginFailureRecord
import com.keel.kernel.plugin.PluginHealthState
import com.keel.kernel.plugin.PluginLifecycleState
import com.keel.kernel.plugin.PluginProcessState
import com.keel.kernel.plugin.PluginRuntimeMode
import com.keel.kernel.plugin.PluginRuntimeSnapshot
import com.keel.kernel.plugin.UnifiedPluginManager
import com.keel.openapi.runtime.OpenApiDoc
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.flow.collect
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object UnifiedSystemRouteInstaller {
    fun install(
        route: Route,
        pluginManager: UnifiedPluginManager,
        pluginLoader: DefaultPluginLoader? = null,
        hotReloadEngine: DevHotReloadEngine? = null
    ) {
        with(route) {
            systemApi {
                typedRoute("/plugins") {
                    typedGet<PluginListData>(doc = OpenApiDoc(summary = "List all plugins", tags = listOf("system", "plugins"), responseEnvelope = true)) {
                        val plugins = pluginManager.getRuntimeSnapshots().map { it.toPluginInfo() }
                        call.respond(KeelResponse.success(PluginListData(plugins = plugins, total = plugins.size)))
                    }

                    typedRoute("/{pluginId}") {
                        typedGet<PluginInfo>(doc = OpenApiDoc(summary = "Get plugin details", tags = listOf("system", "plugins"), errorStatuses = setOf(404), responseEnvelope = true)) {
                            val pluginId = call.parameters["pluginId"]
                            val snapshot = pluginId?.let(pluginManager::getRuntimeSnapshot)
                            if (snapshot == null) {
                                call.respond(KeelResponse.failure<Unit>(404, "Plugin not found"))
                                return@typedGet
                            }
                            call.respond(KeelResponse.success(snapshot.toPluginInfo()))
                        }

                        typedGet<PluginInfo>("/health", doc = OpenApiDoc(summary = "Get plugin health", tags = listOf("system", "plugins"), errorStatuses = setOf(404), responseEnvelope = true)) {
                            val pluginId = call.parameters["pluginId"]
                            val snapshot = pluginId?.let(pluginManager::getRuntimeSnapshot)
                            if (snapshot == null) {
                                call.respond(KeelResponse.failure<Unit>(404, "Plugin not found"))
                                return@typedGet
                            }
                            call.respond(KeelResponse.success(snapshot.toPluginInfo()))
                        }

                        typedPost<PluginActionResult>("/start", doc = OpenApiDoc(summary = "Start a plugin", tags = listOf("system", "plugins"), errorStatuses = setOf(400, 500), responseEnvelope = true)) {
                            handleLifecycleAction(pluginManager, "start") { pluginId ->
                                pluginManager.startPlugin(pluginId)
                            }
                        }

                        typedPost<PluginActionResult>("/stop", doc = OpenApiDoc(summary = "Stop a plugin", tags = listOf("system", "plugins"), errorStatuses = setOf(400, 500), responseEnvelope = true)) {
                            handleLifecycleAction(pluginManager, "stop") { pluginId ->
                                pluginManager.stopPlugin(pluginId)
                            }
                        }

                        typedPost<PluginActionResult>("/dispose", doc = OpenApiDoc(summary = "Dispose a plugin", tags = listOf("system", "plugins"), errorStatuses = setOf(400, 500), responseEnvelope = true)) {
                            handleLifecycleAction(pluginManager, "dispose") { pluginId ->
                                pluginManager.disposePlugin(pluginId)
                            }
                        }

                        typedPost<PluginActionResult>("/reload", doc = OpenApiDoc(summary = "Reload a plugin", tags = listOf("system", "plugins"), errorStatuses = setOf(400, 500), responseEnvelope = true)) {
                            handleLifecycleAction(pluginManager, "reload") { pluginId ->
                                pluginManager.reloadPlugin(pluginId)
                            }
                        }

                        typedPost<PluginActionResult>("/replace", doc = OpenApiDoc(summary = "Replace a plugin artifact", tags = listOf("system", "plugins"), errorStatuses = setOf(400, 500), responseEnvelope = true)) {
                            handleLifecycleAction(pluginManager, "replace") { pluginId ->
                                pluginManager.replacePlugin(pluginId)
                            }
                        }
                    }

                    typedPost<PluginDiscoverData>("/discover", doc = OpenApiDoc(summary = "Discover plugins in directory", tags = listOf("system", "plugins"), responseEnvelope = true)) {
                        val discovered = pluginLoader?.discoverPlugins(com.keel.kernel.config.KeelConstants.PLUGINS_DIR).orEmpty()
                        call.respond(
                            KeelResponse.success(
                                PluginDiscoverData(
                                    discovered = discovered.map { it.toDiscoveredPluginInfo() },
                                    total = discovered.size
                                )
                            )
                        )
                    }
                }

                typedRoute("/hotreload") {
                    typedGet<HotReloadStatusData>("/status", doc = OpenApiDoc(summary = "Get dev hot reload status", tags = listOf("system", "hotreload"), responseEnvelope = true)) {
                        val status = hotReloadEngine?.status()
                        call.respond(
                            KeelResponse.success(
                                HotReloadStatusData(
                                    enabled = hotReloadEngine != null,
                                    inProgress = status?.inProgress ?: false,
                                    lastFailureSummary = status?.lastFailureSummary,
                                    lastEvent = status?.lastEvent
                                )
                            )
                        )
                    }

                    typedPost<PluginActionResult>("/reload/{pluginId}", doc = OpenApiDoc(summary = "Trigger manual dev hot reload", tags = listOf("system", "hotreload"), responseEnvelope = true)) {
                        val pluginId = call.parameters["pluginId"]
                        if (hotReloadEngine == null || pluginId.isNullOrBlank()) {
                            call.respond(KeelResponse.failure<Unit>(400, "Hot reload engine unavailable or missing pluginId"))
                            return@typedPost
                        }
                        val result = hotReloadEngine.reloadPlugin(pluginId, reason = "manual-api")
                        call.respond(
                            KeelResponse.success(
                                PluginActionResult(
                                    pluginId = pluginId,
                                    message = result.message,
                                    action = "hotreload",
                                    lifecycleState = pluginManager.getRuntimeSnapshot(pluginId)?.lifecycleState,
                                    healthState = pluginManager.getRuntimeSnapshot(pluginId)?.healthState,
                                    generation = pluginManager.getRuntimeSnapshot(pluginId)?.generation?.value
                                )
                            )
                        )
                    }

                    // SSE endpoint intentionally kept out of OpenAPI body schema detail.
                    sse("/events") {
                        if (hotReloadEngine == null) {
                            send(ServerSentEvent(data = """{"error":"hotreload disabled"}""", event = "error"))
                            close()
                            return@sse
                        }
                        send(ServerSentEvent(data = """{"type":"connected"}""", event = "system"))
                        hotReloadEngine.events.collect { event ->
                            send(
                                ServerSentEvent(
                                    data = Json.encodeToString(DevReloadEvent.serializer(), event),
                                    event = "hotreload"
                                )
                            )
                        }
                    }
                }

                typedGet<HealthData>("/health", doc = OpenApiDoc(summary = "Health check", tags = listOf("system"), responseEnvelope = true)) {
                    call.respond(KeelResponse.success(HealthData("ok", Clock.System.now().toEpochMilliseconds())))
                }

                typedGet<FrameworkVersionData>("/version", doc = OpenApiDoc(summary = "Framework version", tags = listOf("system"), responseEnvelope = true)) {
                    call.respond(
                        KeelResponse.success(
                            FrameworkVersionData(
                                frameworkVersion = FrameworkVersion.current()
                            )
                        )
                    )
                }
            }
        }
    }
}

fun Route.unifiedSystemRoutes(
    pluginManager: UnifiedPluginManager,
    pluginLoader: DefaultPluginLoader? = null,
    hotReloadEngine: DevHotReloadEngine? = null
) {
    UnifiedSystemRouteInstaller.install(this, pluginManager, pluginLoader, hotReloadEngine)
}

private suspend fun io.ktor.server.routing.RoutingContext.handleLifecycleAction(
    pluginManager: UnifiedPluginManager,
    action: String,
    block: suspend (String) -> Unit
) {
    val pluginId = call.parameters["pluginId"]
    if (pluginId.isNullOrBlank()) {
        call.respond(KeelResponse.failure<Unit>(400, "Missing pluginId"))
        return
    }
    runCatching {
        block(pluginId)
    }.onSuccess {
        val snapshot = pluginManager.getRuntimeSnapshot(pluginId)
        call.respond(
            KeelResponse.success(
                PluginActionResult(
                    pluginId = pluginId,
                    message = "Plugin $action completed successfully",
                    action = action,
                    lifecycleState = snapshot?.lifecycleState,
                    healthState = snapshot?.healthState,
                    generation = snapshot?.generation?.value
                )
            )
        )
    }.onFailure { error ->
        call.respond(KeelResponse.failure<Unit>(500, "Failed to $action plugin: ${error.message}"))
    }
}

private fun PluginRuntimeSnapshot.toPluginInfo(): PluginInfo {
    val socketHealthy = diagnostics.adminChannelHealth == PluginChannelHealth.HEALTHY &&
        diagnostics.eventChannelHealth == PluginChannelHealth.HEALTHY
    return PluginInfo(
        pluginId = pluginId,
        version = version,
        runtimeMode = runtimeMode,
        lifecycleState = lifecycleState,
        healthState = healthState,
        generation = generation.value,
        isIsolated = runtimeMode == PluginRuntimeMode.EXTERNAL_JVM,
        processState = processState,
        processId = processId,
        processAlive = diagnostics.processAlive ?: processHandleAlive,
        adminChannelHealth = diagnostics.adminChannelHealth,
        eventChannelHealth = diagnostics.eventChannelHealth,
        socketHealthy = socketHealthy,
        droppedLogCount = diagnostics.droppedLogCount,
        eventQueueDepth = diagnostics.eventQueueDepth,
        eventOverflowed = diagnostics.eventOverflowed,
        inflightInvocations = diagnostics.inflightInvocations,
        lastHealthLatencyMs = diagnostics.lastHealthLatencyMs,
        lastAdminLatencyMs = diagnostics.lastAdminLatencyMs,
        lastEventAtEpochMs = diagnostics.lastEventAtEpochMs,
        lastFailure = lastFailure?.toPluginFailureInfo(),
        displayName = displayName
    )
}

private fun PluginFailureRecord.toPluginFailureInfo(): PluginFailureInfo {
    return PluginFailureInfo(
        timestamp = timestamp,
        source = source,
        message = message
    )
}

private fun DiscoveredPlugin.toDiscoveredPluginInfo() = DiscoveredPluginInfo(
    pluginId = pluginId,
    version = version,
    mainClass = mainClass,
    jarPath = jarPath,
    dependencies = dependencies,
    artifactLastModifiedMs = artifactLastModifiedMs,
    artifactChecksum = artifactChecksum
)

@Serializable
data class PluginInfo(
    val pluginId: String,
    val version: String,
    val runtimeMode: PluginRuntimeMode,
    val lifecycleState: PluginLifecycleState,
    val healthState: PluginHealthState,
    val generation: Long,
    val isIsolated: Boolean = false,
    val processState: PluginProcessState? = null,
    val processId: Long? = null,
    val processAlive: Boolean? = null,
    val adminChannelHealth: PluginChannelHealth = PluginChannelHealth.UNKNOWN,
    val eventChannelHealth: PluginChannelHealth = PluginChannelHealth.UNKNOWN,
    val socketHealthy: Boolean = false,
    val droppedLogCount: Long = 0,
    val eventQueueDepth: Int = 0,
    val eventOverflowed: Boolean = false,
    val inflightInvocations: Int = 0,
    val lastHealthLatencyMs: Long? = null,
    val lastAdminLatencyMs: Long? = null,
    val lastEventAtEpochMs: Long? = null,
    val lastFailure: PluginFailureInfo? = null,
    val displayName: String = pluginId
)

@Serializable
data class PluginFailureInfo(
    val timestamp: Long,
    val source: String,
    val message: String
)

@Serializable
data class PluginListData(
    val plugins: List<PluginInfo>,
    val total: Int
)

@Serializable
data class PluginDiscoverData(
    val discovered: List<DiscoveredPluginInfo>,
    val total: Int
)

@Serializable
data class DiscoveredPluginInfo(
    val pluginId: String,
    val version: String,
    val mainClass: String,
    val jarPath: String,
    val dependencies: List<String>,
    val artifactLastModifiedMs: Long,
    val artifactChecksum: String
)

@Serializable
data class PluginActionResult(
    val pluginId: String,
    val message: String,
    val action: String? = null,
    val lifecycleState: PluginLifecycleState? = null,
    val healthState: PluginHealthState? = null,
    val generation: Long? = null
)

@Serializable
data class HealthData(
    val status: String,
    val timestamp: Long
)

@Serializable
data class HotReloadStatusData(
    val enabled: Boolean,
    val inProgress: Boolean,
    val lastFailureSummary: String? = null,
    val lastEvent: DevReloadEvent? = null
)

@Serializable
data class FrameworkVersionData(
    val frameworkVersion: String
)
