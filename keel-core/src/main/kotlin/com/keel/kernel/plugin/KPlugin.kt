package com.keel.kernel.plugin

import com.keel.db.database.KeelDatabase
import io.ktor.server.routing.Routing
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.Table
import org.koin.core.Koin
import org.koin.core.qualifier.named
import org.koin.core.scope.Scope

/**
 * Context provided during plugin initialization.
 * Contains configuration and core services available to the plugin.
 */
interface PluginInitContext {
    val pluginId: String
    val config: PluginConfig
    val koin: Koin

    /**
     * Get a KeelDatabase instance from Koin.
     * Returns null if the database module is not installed.
     */
    fun getDatabase(): KeelDatabase? {
        return try {
            koin.getOrNull<KeelDatabase>(named("keelDatabase"))
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Get tables associated with this plugin.
     * Override this in your plugin to return the tables that should be created.
     * The database must be available (getDatabase() must return non-null).
     *
     * Example:
     * ```kotlin
     * override fun getTables(): List<Table> = listOf(UserTable)
     * ```
     */
    fun getTables(): List<Table> = emptyList()
}

/**
 * Plugin configuration data class.
 */
@Serializable
data class PluginConfig(
    val pluginId: String,
    val enabled: Boolean = false,
    val settings: Map<String, String> = emptyMap()
)

/**
 * Lifecycle states for a plugin.
 */
@Serializable
enum class PluginState {
    INIT,       // Plugin discovered, not yet initialized
    INSTALLED,  // onInit completed, resources registered
    ENABLED,    // onEnable completed, routes mounted
    DISABLED,   // onDisable completed, routes removed
    ERROR       // Error occurred, plugin unavailable
}

/**
 * Base interface for all Keel plugins.
 * Each plugin must implement this interface and be registered with the PluginManager.
 */
interface KPlugin {
    /**
     * Unique identifier for this plugin.
     * Will be used for route prefixing and table prefixing.
     */
    val pluginId: String

    /**
     * Plugin version string.
     */
    val version: String

    /**
     * Called once when the plugin is first loaded.
     * Use this to:
     * - Load plugin-specific configuration
     * - Register Exposed Tables with the database
     * - Initialize any global resources
     *
     * Example with database:
     * ```kotlin
     * override suspend fun onInit(context: PluginInitContext) {
     *     context.getDatabase()?.let { db ->
     *         db.createTables(*getTables().toTypedArray())
     *     }
     * }
     * ```
     */
    suspend fun onInit(context: PluginInitContext)

    /**
     * Called after onInit when the plugin should install its dependencies.
     * Use this to:
     * - Create a private Koin Scope
     * - Register services specific to this plugin
     */
    suspend fun onInstall(scope: Scope)

    /**
     * Called when the plugin should activate its routes.
     * Use this to:
     * - Register routes with the Ktor Routing
     * - Routes will be mounted at /api/plugins/{pluginId}/
     */
    suspend fun onEnable(routing: Routing)

    /**
     * Called when the plugin should deactivate.
     * Use this to:
     * - Remove routes
     * - Clean up resources
     * - Do NOT close database connections (managed by kernel)
     */
    suspend fun onDisable()
}
