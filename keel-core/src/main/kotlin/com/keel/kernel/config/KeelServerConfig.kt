package com.keel.kernel.config

import io.ktor.server.application.Application
import io.ktor.server.application.Plugin
import io.ktor.server.application.install

/**
 * Configuration for the Keel Server Engine.
 */
class KeelServerConfig {
    private val globalKtorPluginInstallers = mutableListOf<Application.() -> Unit>()

    /**
     * The engine to use for the Keel Server. Defaults to Netty.
     */
    var engine: KeelEngine = KeelEngine.Netty

    /**
     * The port to bind the server to. Defaults to 8080.
     */
    var port: Int = 8080

    /**
     * The host to bind the server to. Defaults to "0.0.0.0".
     */
    var host: String = "0.0.0.0"

    /**
     * Size of the event group for accepting connections.
     * Specific behavior depends on the chosen engine.
     */
    var connectionGroupSize: Int? = null

    /**
     * Size of the event group for processing connections, parsing messages and doing engine's internal work.
     * Specific behavior depends on the chosen engine.
     */
    var workerGroupSize: Int? = null

    /**
     * Size of the event group for running application code.
     * Specific behavior depends on the chosen engine.
     */
    var callGroupSize: Int? = null

    /**
     * Allows engine-specific configuration block. This is an escape hatch to configure engine-specific settings.
     * The parameter is the engine configuration class (e.g. NettyApplicationEngine.Configuration).
     */
    var engineConfigBlock: (Any.() -> Unit)? = null

    /**
     * Set a custom engine-specific configuration block.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> configureEngine(block: T.() -> Unit) {
        engineConfigBlock = block as (Any.() -> Unit)
    }

    /**
     * Register global Ktor plugins for the application scope.
     */
    fun globalKtorPlugin(configure: GlobalKtorPluginConfig.() -> Unit) {
        val config = GlobalKtorPluginConfig().apply(configure)
        globalKtorPluginInstallers += config.toInstallers()
    }

    fun installConfiguredGlobalKtorPlugins(application: Application) {
        globalKtorPluginInstallers.forEach { installer ->
            installer(application)
        }
    }
}

class GlobalKtorPluginConfig {
    private val installers = mutableListOf<Application.() -> Unit>()

    fun <B : Any, F : Any> install(
        plugin: Plugin<Application, B, F>,
        configure: B.() -> Unit = {}
    ) {
        installers += {
            install(plugin, configure)
        }
    }

    internal fun toInstallers(): List<Application.() -> Unit> = installers.toList()
}
