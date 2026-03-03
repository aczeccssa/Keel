package com.keel.kernel.config

import com.keel.kernel.loader.DefaultPluginLoader
import com.keel.kernel.logging.KeelLoggerService
import com.keel.kernel.logging.LogLevel
import com.keel.kernel.plugin.HybridPluginManager
import com.keel.kernel.plugin.KPlugin
import com.keel.kernel.plugin.KeelPluginV2
import com.keel.kernel.routing.GatewayInterceptor
import com.keel.kernel.routing.docRoutes
import com.keel.kernel.routing.hybridSystemRoutes
import com.keel.kernel.routing.logRoutes
import com.lestere.opensource.logger.SoulLoggerPlugin
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticResources
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import org.koin.core.Koin
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import java.util.concurrent.CopyOnWriteArrayList

class Kernel(
    private val koin: Koin,
    private val enablePluginHotReload: Boolean = ConfigHotReloader.isDevelopmentMode()
) {
    private val logger = KeelLoggerService.getLogger("Kernel")
    private val kernelScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pluginLoader = DefaultPluginLoader()
    private val pluginManager = HybridPluginManager()
    private val gatewayInterceptor = GatewayInterceptor(pluginManager)
    private val legacyPlugins = CopyOnWriteArrayList<KPlugin>()

    private val configHotReloader: ConfigHotReloader by lazy {
        ConfigHotReloader.Builder()
            .watchConfigDir(KeelConstants.CONFIG_DIR)
            .apply {
                if (enablePluginHotReload) {
                    watchPluginDir(KeelConstants.PLUGINS_DIR)
                }
            }
            .onConfigChange { event ->
                logger.info("Config changed: ${event.fileName} (${event.type})")
            }
            .onPluginChange { event ->
                logger.info("Plugin file change observed for ${event.pluginId} (${event.type}); isolated hot reload is not supported in V1")
            }
            .build()
    }

    fun registerPlugin(plugin: KeelPluginV2): Kernel {
        pluginManager.registerPlugin(plugin)
        return this
    }

    fun registerPlugin(plugin: KPlugin): Kernel {
        legacyPlugins += plugin
        logger.warn("Registered legacy plugin ${plugin.pluginId}; legacy KPlugin routing is no longer supported by Kernel V2")
        return this
    }

    fun run(port: Int = 8080) {
        require(legacyPlugins.isEmpty()) {
            "Legacy KPlugin registration is no longer supported by Kernel.run(); migrate to KeelPluginV2"
        }

        embeddedServer(Netty, port = port) {
            install(ContentNegotiation) {
                json(Json {
                    prettyPrint = true
                    isLenient = true
                })
            }
            install(SSE)
            install(SoulLoggerPlugin)

            val loggerService = KeelLoggerService.initialize()
            if (ConfigHotReloader.isDevelopmentMode()) {
                loggerService.setLevel(LogLevel.DEBUG)
            }

            configureApplication(this)

            routing {
                pluginManager.mountRoutes(this)
                hybridSystemRoutes(pluginManager, pluginLoader)
                logRoutes()
                docRoutes()
                staticResources("/", "static")
            }
        }.start(wait = true)
    }

    fun configureApplication(app: Application) {
        app.intercept(ApplicationCallPipeline.Plugins) {
            if (gatewayInterceptor.intercept(call)) {
                finish()
            }
        }

        app.environment.monitor.subscribe(ApplicationStarted) {
            logger.info("Kernel started")
            kotlinx.coroutines.runBlocking {
                pluginManager.startEnabledPlugins()
            }

            if (ConfigHotReloader.isDevelopmentMode()) {
                configHotReloader.startWatching()
                logger.info("Hot-reloader started (development mode: ${ConfigHotReloader.isDevelopmentMode()})")
                if (enablePluginHotReload) {
                    logger.info("Plugin directory watching enabled")
                }
            }
        }

        app.environment.monitor.subscribe(ApplicationStopping) {
            logger.info("Kernel stopping")
            configHotReloader.stopWatching()
            kotlinx.coroutines.runBlocking {
                pluginManager.stopAll()
            }
            kernelScope.cancel()
            KeelLoggerService.getInstance().shutdown()
        }

        logger.info("Kernel configured")
    }
}

class KernelBuilder {
    private var koinConfig: (KoinApplication.() -> Unit)? = null
    private val v2Plugins = mutableListOf<KeelPluginV2>()
    private val legacyPlugins = mutableListOf<KPlugin>()
    private var enablePluginHotReload: Boolean = ConfigHotReloader.isDevelopmentMode()

    fun koin(config: KoinApplication.() -> Unit) {
        koinConfig = config
    }

    fun plugin(plugin: KeelPluginV2) {
        v2Plugins += plugin
    }

    fun plugin(plugin: KPlugin) {
        legacyPlugins += plugin
    }

    fun enablePluginHotReload(enabled: Boolean) {
        this.enablePluginHotReload = enabled
    }

    fun build(): Kernel {
        val koin = startKoin(koinConfig ?: {}).koin
        val kernel = Kernel(koin, enablePluginHotReload)
        v2Plugins.forEach { kernel.registerPlugin(it) }
        legacyPlugins.forEach { kernel.registerPlugin(it) }
        return kernel
    }
}

fun buildKernel(block: KernelBuilder.() -> Unit) = KernelBuilder().apply(block).build()

fun runKernel(port: Int = 8080, block: KernelBuilder.() -> Unit) = buildKernel(block).run(port)
