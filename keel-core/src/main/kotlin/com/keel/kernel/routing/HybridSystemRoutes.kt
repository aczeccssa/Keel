package com.keel.kernel.routing

import com.keel.contract.dto.KeelResponse
import com.keel.kernel.api.KeelApi
import com.keel.kernel.api.systemApi
import com.keel.kernel.api.typedGet
import com.keel.kernel.api.typedPost
import com.keel.kernel.loader.DefaultPluginLoader
import com.keel.kernel.plugin.HybridPluginManager
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import kotlinx.datetime.Clock

fun Route.hybridSystemRoutes(
    pluginManager: HybridPluginManager,
    pluginLoader: DefaultPluginLoader? = null
) {
    systemApi {
        @KeelApi("List all plugins", tags = ["system", "plugins"], responseEnvelope = true)
        typedGet<PluginListData>("/plugins") {
            val plugins = pluginManager.getAllPlugins().values
                .sortedBy { it.descriptor.pluginId }
                .map { plugin ->
                    val pluginId = plugin.descriptor.pluginId
                    PluginInfo(
                        pluginId = pluginId,
                        version = plugin.descriptor.version,
                        state = pluginManager.getPluginState(pluginId),
                        isDynamic = false,
                        executionMode = pluginManager.getExecutionMode(pluginId),
                        isIsolated = pluginManager.isIsolated(pluginId),
                        processState = pluginManager.getProcessState(pluginId),
                        socketHealthy = pluginManager.getProcessState(pluginId) == com.keel.kernel.plugin.PluginProcessState.RUNNING,
                        displayName = plugin.descriptor.displayName
                    )
                }
            call.respond(KeelResponse.success(PluginListData(plugins = plugins, total = plugins.size)))
        }

        @KeelApi("Get plugin details", tags = ["system", "plugins"], errorStatuses = [404], responseEnvelope = true)
        typedGet<PluginInfo>("/plugins/{pluginId}") {
            val pluginId = call.parameters["pluginId"]
            val plugin = pluginId?.let(pluginManager::getPlugin)
            if (plugin == null) {
                call.respond(KeelResponse.failure<Unit>(404, "Plugin not found"))
                return@typedGet
            }
            call.respond(
                KeelResponse.success(
                    PluginInfo(
                        pluginId = plugin.descriptor.pluginId,
                        version = plugin.descriptor.version,
                        state = pluginManager.getPluginState(plugin.descriptor.pluginId),
                        isDynamic = false,
                        executionMode = pluginManager.getExecutionMode(plugin.descriptor.pluginId),
                        isIsolated = pluginManager.isIsolated(plugin.descriptor.pluginId),
                        processState = pluginManager.getProcessState(plugin.descriptor.pluginId),
                        socketHealthy = pluginManager.getProcessState(plugin.descriptor.pluginId) == com.keel.kernel.plugin.PluginProcessState.RUNNING,
                        displayName = plugin.descriptor.displayName
                    )
                )
            )
        }

        @KeelApi("Enable a plugin", tags = ["system", "plugins"], errorStatuses = [400, 500], responseEnvelope = true)
        typedPost<PluginActionResult>("/plugins/{pluginId}/enable") {
            val pluginId = call.parameters["pluginId"]
            if (pluginId.isNullOrBlank()) {
                call.respond(KeelResponse.failure<Unit>(400, "Missing pluginId"))
                return@typedPost
            }
            runCatching { pluginManager.enablePlugin(pluginId) }
                .onSuccess {
                    call.respond(KeelResponse.success(PluginActionResult(pluginId, "Plugin enabled successfully")))
                }
                .onFailure { error ->
                    call.respond(KeelResponse.failure<Unit>(500, "Failed to enable plugin: ${error.message}"))
                }
        }

        @KeelApi("Disable a plugin", tags = ["system", "plugins"], errorStatuses = [400, 500], responseEnvelope = true)
        typedPost<PluginActionResult>("/plugins/{pluginId}/disable") {
            val pluginId = call.parameters["pluginId"]
            if (pluginId.isNullOrBlank()) {
                call.respond(KeelResponse.failure<Unit>(400, "Missing pluginId"))
                return@typedPost
            }
            runCatching { pluginManager.disablePlugin(pluginId) }
                .onSuccess {
                    call.respond(KeelResponse.success(PluginActionResult(pluginId, "Plugin disabled successfully")))
                }
                .onFailure { error ->
                    call.respond(KeelResponse.failure<Unit>(500, "Failed to disable plugin: ${error.message}"))
                }
        }

        @KeelApi("Discover plugins in directory", tags = ["system", "plugins"], responseEnvelope = true)
        typedPost<PluginDiscoverData>("/plugins/discover") {
            val discovered = pluginLoader?.discoverPlugins(com.keel.kernel.config.KeelConstants.PLUGINS_DIR).orEmpty()
            call.respond(
                KeelResponse.success(
                    PluginDiscoverData(
                        discovered = discovered.map {
                            DiscoveredPluginInfo(
                                pluginId = it.pluginId,
                                version = it.version,
                                mainClass = it.mainClass,
                                jarPath = it.jarPath,
                                dependencies = it.dependencies
                            )
                        },
                        total = discovered.size
                    )
                )
            )
        }

        @KeelApi("Health check", tags = ["system"], responseEnvelope = true)
        typedGet<HealthData>("/health") {
            call.respond(KeelResponse.success(HealthData("ok", Clock.System.now().toEpochMilliseconds())))
        }
    }
}
