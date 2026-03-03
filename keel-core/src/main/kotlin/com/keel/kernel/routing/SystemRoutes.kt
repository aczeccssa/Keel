package com.keel.kernel.routing

import com.keel.contract.dto.KeelResponse
import com.keel.kernel.api.KeelApi
import com.keel.kernel.api.systemApi
import com.keel.kernel.api.typedGet
import com.keel.kernel.api.typedPost
import com.keel.kernel.config.KeelConstants
import com.keel.kernel.loader.DefaultPluginLoader
import com.keel.kernel.loader.DiscoveredPlugin
import com.keel.kernel.plugin.PluginExecutionMode
import com.keel.kernel.plugin.PluginManager
import com.keel.kernel.plugin.PluginProcessState
import com.keel.kernel.plugin.PluginState
import io.ktor.server.routing.*
import io.ktor.server.response.*
import io.ktor.server.application.*
import io.ktor.http.*
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable

/**
 * System routes for plugin management.
 * These routes are mounted at /api/_system/ and provide:
 * - Plugin discovery and listing
 * - Dynamic plugin status information
 * - Plugin reload functionality
 */
fun Route.systemRoutes(
    pluginManager: PluginManager,
    pluginLoader: DefaultPluginLoader,
    enablePlugin: suspend (String, Routing) -> Unit = { _, _ -> },
    disablePlugin: suspend (String) -> Unit = { _ -> }
) {
    systemApi {
        /**
         * GET /api/_system/plugins
         * List all plugins (both static and dynamic)
         */
        @KeelApi("List all plugins", tags = ["system", "plugins"], responseEnvelope = true)
        typedGet<PluginListData>("/plugins") {
            val staticPlugins = pluginManager.getAllPlugins().map { (id, plugin) ->
                PluginInfo(
                    pluginId = id,
                    version = plugin.version,
                    state = pluginManager.getPluginState(id),
                    isDynamic = false
                )
            }

            val dynamicPlugins = pluginLoader.getLoadedPlugins().map { (id, plugin) ->
                PluginInfo(
                    pluginId = id,
                    version = plugin.version,
                    state = pluginManager.getPluginState(id),
                    isDynamic = true
                )
            }

            val allPlugins = (staticPlugins + dynamicPlugins).sortedBy { it.pluginId }

            call.respond(
                KeelResponse.success(
                    data = PluginListData(
                        plugins = allPlugins,
                        total = allPlugins.size
                    )
                )
            )
        }

        /**
         * GET /api/_system/plugins/{id}
         * Get plugin details
         */
        @KeelApi(
            "Get plugin details",
            tags = ["system", "plugins"],
            errorStatuses = [400, 404],
            responseEnvelope = true
        )
        typedGet<PluginInfo>("/plugins/{pluginId}") {
            val pluginId = call.parameters["pluginId"] ?: return@typedGet call.respond(
                KeelResponse.failure<Unit>(400, "Missing pluginId")
            )

            // Check static plugins first
            pluginManager.getPlugin(pluginId)?.let { plugin ->
                return@typedGet call.respond(
                    KeelResponse.success(
                        data = PluginInfo(
                            pluginId = plugin.pluginId,
                            version = plugin.version,
                            state = pluginManager.getPluginState(pluginId),
                            isDynamic = false
                        )
                    )
                )
            }

            // Check dynamic plugins
            pluginLoader.getPlugin(pluginId)?.let { plugin ->
                return@typedGet call.respond(
                    KeelResponse.success(
                        data = PluginInfo(
                            pluginId = plugin.pluginId,
                            version = plugin.version,
                            state = pluginManager.getPluginState(pluginId),
                            isDynamic = true
                        )
                    )
                )
            }

            call.respond(
                KeelResponse.failure<Unit>(404, "Plugin not found: $pluginId")
            )
        }

        /**
         * POST /api/_system/plugins/discover
         * Discover plugins in the plugin directory
         */
        @KeelApi(
            "Discover plugins in directory",
            tags = ["system", "plugins"],
            errorStatuses = [500],
            responseEnvelope = true
        )
        typedPost<PluginDiscoverData>("/plugins/discover") {
            val pluginDir = call.parameters["dir"] ?: KeelConstants.PLUGINS_DIR

            try {
                val discovered = pluginLoader.discoverPlugins(pluginDir)

                call.respond(
                    KeelResponse.success(
                        data = PluginDiscoverData(
                            discovered = discovered.map { it.toDiscoveredPluginInfo() },
                            total = discovered.size
                        )
                    )
                )
            } catch (e: Exception) {
                call.respond(
                    KeelResponse.failure<Unit>(500, "Failed to discover plugins: ${e.message}")
                )
            }
        }

        /**
         * POST /api/_system/plugins/{id}/load
         * Load a discovered plugin
         */
        @KeelApi(
            "Load a discovered plugin",
            tags = ["system", "plugins"],
            errorStatuses = [400, 404, 500],
            responseEnvelope = true
        )
        typedPost<PluginLoadResult>("/plugins/{pluginId}/load") {
            val pluginId = call.parameters["pluginId"] ?: return@typedPost call.respond(
                KeelResponse.failure<Unit>(400, "Missing pluginId")
            )

            try {
                val discovered = pluginLoader.getDiscoveredPlugins()[pluginId]
                    ?: return@typedPost call.respond(
                        KeelResponse.failure<Unit>(404, "Plugin not discovered: $pluginId")
                    )

                val plugin = pluginLoader.loadPlugin(discovered)

                call.respond(
                    KeelResponse.success(
                        data = PluginLoadResult(
                            pluginId = plugin.pluginId,
                            version = plugin.version,
                            message = "Plugin loaded successfully. Note: Routes must be manually mounted."
                        )
                    )
                )
            } catch (e: Exception) {
                call.respond(
                    KeelResponse.failure<Unit>(500, "Failed to load plugin: ${e.message}")
                )
            }
        }

        /**
         * POST /api/_system/plugins/{id}/unload
         * Unload a dynamic plugin
         */
        @KeelApi(
            "Unload a dynamic plugin",
            tags = ["system", "plugins"],
            errorStatuses = [400, 500],
            responseEnvelope = true
        )
        typedPost<PluginActionResult>("/plugins/{pluginId}/unload") {
            val pluginId = call.parameters["pluginId"] ?: return@typedPost call.respond(
                KeelResponse.failure<Unit>(400, "Missing pluginId")
            )

            try {
                pluginLoader.unloadPlugin(pluginId)

                call.respond(
                    KeelResponse.success(
                        data = PluginActionResult(
                            pluginId = pluginId,
                            message = "Plugin unloaded successfully"
                        )
                    )
                )
            } catch (e: Exception) {
                call.respond(
                    KeelResponse.failure<Unit>(500, "Failed to unload plugin: ${e.message}")
                )
            }
        }

        /**
         * POST /api/_system/plugins/{id}/reload
         * Reload a dynamic plugin
         */
        @KeelApi(
            "Reload a dynamic plugin",
            tags = ["system", "plugins"],
            errorStatuses = [400, 404, 500],
            responseEnvelope = true
        )
        typedPost<PluginLoadResult>("/plugins/{pluginId}/reload") {
            val pluginId = call.parameters["pluginId"] ?: return@typedPost call.respond(
                KeelResponse.failure<Unit>(400, "Missing pluginId")
            )

            try {
                val plugin = pluginLoader.reloadPlugin(pluginId)
                    ?: return@typedPost call.respond(
                        KeelResponse.failure<Unit>(404, "Plugin not found for reload: $pluginId")
                    )

                call.respond(
                    KeelResponse.success(
                        data = PluginLoadResult(
                            pluginId = plugin.pluginId,
                            version = plugin.version,
                            message = "Plugin reloaded successfully"
                        )
                    )
                )
            } catch (e: Exception) {
                call.respond(
                    KeelResponse.failure<Unit>(500, "Failed to reload plugin: ${e.message}")
                )
            }
        }

        /**
         * POST /api/_system/plugins/{id}/enable
         * Enable a plugin (register routes, resume traffic)
         */
        @KeelApi(
            "Enable a plugin",
            tags = ["system", "plugins"],
            errorStatuses = [400, 500],
            responseEnvelope = true
        )
        typedPost<PluginActionResult>("/plugins/{pluginId}/enable") {
            val pluginId = call.parameters["pluginId"] ?: return@typedPost call.respond(
                KeelResponse.failure<Unit>(400, "Missing pluginId")
            )

            try {
                val routing = call.application.routing {}
                enablePlugin(pluginId, routing)

                call.respond(
                    KeelResponse.success(
                        data = PluginActionResult(
                            pluginId = pluginId,
                            message = "Plugin enabled successfully"
                        )
                    )
                )
            } catch (e: Exception) {
                call.respond(
                    KeelResponse.failure<Unit>(500, "Failed to enable plugin: ${e.message}")
                )
            }
        }

        /**
         * POST /api/_system/plugins/{id}/disable
         * Disable a plugin (cut traffic, destroy scope)
         */
        @KeelApi(
            "Disable a plugin",
            tags = ["system", "plugins"],
            errorStatuses = [400, 500],
            responseEnvelope = true
        )
        typedPost<PluginActionResult>("/plugins/{pluginId}/disable") {
            val pluginId = call.parameters["pluginId"] ?: return@typedPost call.respond(
                KeelResponse.failure<Unit>(400, "Missing pluginId")
            )

            try {
                disablePlugin(pluginId)

                call.respond(
                    KeelResponse.success(
                        data = PluginActionResult(
                            pluginId = pluginId,
                            message = "Plugin disabled successfully"
                        )
                    )
                )
            } catch (e: Exception) {
                call.respond(
                    KeelResponse.failure<Unit>(500, "Failed to disable plugin: ${e.message}")
                )
            }
        }

        /**
         * GET /api/_system/health
         * Health check endpoint
         */
        @KeelApi("Health check", tags = ["system"], responseEnvelope = true)
        typedGet<HealthData>("/health") {
            call.respond(
                KeelResponse.success(
                    data = HealthData(
                        status = "ok",
                        timestamp = Clock.System.now().toEpochMilliseconds()
                    )
                )
            )
        }
    }
}

/**
 * Plugin info DTO for API responses.
 */
@Serializable
data class PluginInfo(
    val pluginId: String,
    val version: String,
    val state: PluginState,
    val isDynamic: Boolean,
    val executionMode: PluginExecutionMode? = null,
    val isIsolated: Boolean = false,
    val processState: PluginProcessState? = null,
    val socketHealthy: Boolean = false,
    val displayName: String = pluginId
)

/**
 * Data wrapper for plugin list endpoint.
 */
@Serializable
data class PluginListData(
    val plugins: List<PluginInfo>,
    val total: Int
)

/**
 * Data wrapper for plugin discover endpoint.
 */
@Serializable
data class PluginDiscoverData(
    val discovered: List<DiscoveredPluginInfo>,
    val total: Int
)

/**
 * DTO for discovered plugin information.
 */
@Serializable
data class DiscoveredPluginInfo(
    val pluginId: String,
    val version: String,
    val mainClass: String,
    val jarPath: String,
    val dependencies: List<String>
)

/**
 * Extension function to convert DiscoveredPlugin to DiscoveredPluginInfo.
 */
private fun DiscoveredPlugin.toDiscoveredPluginInfo() = DiscoveredPluginInfo(
    pluginId = pluginId,
    version = version,
    mainClass = mainClass,
    jarPath = jarPath,
    dependencies = dependencies
)

/**
 * Result DTO for plugin load/reload endpoint.
 */
@Serializable
data class PluginLoadResult(
    val pluginId: String,
    val version: String,
    val message: String
)

/**
 * Result DTO for plugin action (unload) endpoint.
 */
@Serializable
data class PluginActionResult(
    val pluginId: String,
    val message: String
)

/**
 * Data DTO for health check endpoint.
 */
@Serializable
data class HealthData(
    val status: String,
    val timestamp: Long
)
