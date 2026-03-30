package com.keel.kernel.config

import com.keel.kernel.hotreload.DevHotReloadEngine
import com.keel.kernel.loader.DefaultPluginLoader
import com.keel.kernel.logging.KeelLoggerService
import com.keel.kernel.logging.LogLevel
import com.keel.kernel.logging.ScopeLogger
import com.keel.kernel.observability.KeelObservability
import com.keel.kernel.observability.ObservabilityHub
import com.keel.kernel.plugin.UnifiedPluginManager
import com.keel.kernel.routing.DocRouteInstaller
import com.keel.kernel.routing.LogRouteInstaller
import com.keel.kernel.routing.UnifiedSystemRouteInstaller
import com.lestere.opensource.ApplicationMode
import com.lestere.opensource.logger.SoulLogger
import com.lestere.opensource.logger.SoulLoggerPlugin
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.install
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.serialization.json.Json
import org.koin.core.Koin
import org.koin.dsl.module

internal class KernelApplicationInstaller(
    private val serverConfig: KeelServerConfig,
    private val pluginManager: UnifiedPluginManager,
    private val observabilityHub: ObservabilityHub,
    private val koin: Koin
) {
    fun install(app: Application) {
        app.install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                isLenient = true
            })
        }
        app.install(SSE)
        app.install(SoulLoggerPlugin) {
            if (ConfigHotReloader.isDevelopmentMode()) {
                mode = ApplicationMode.DEVELOPMENT
                level = SoulLogger.Level.DEBUG
            } else {
                mode = ApplicationMode.PRODUCTION
                level = SoulLogger.Level.INFO
            }
        }
        serverConfig.installConfiguredGlobalKtorPlugins(app)
        pluginManager.installConfiguredPluginApplicationKtorPlugins(app)

        val loggerService = KeelLoggerService.initialize()
        if (ConfigHotReloader.isDevelopmentMode()) {
            loggerService.setLevel(LogLevel.DEBUG)
        }

        koin.loadModules(
            listOf(
                module {
                    single<KeelObservability> { observabilityHub }
                }
            )
        )
        observabilityHub.setPluginSnapshotProvider { pluginManager.getRuntimeSnapshots() }
    }
}

internal class KernelRouteInstaller(
    private val pluginManager: UnifiedPluginManager,
    private val pluginLoader: DefaultPluginLoader,
    private val devHotReloadEngine: DevHotReloadEngine,
    private val customRouting: (Route.() -> Unit)?
) {
    fun install(app: Application) {
        app.routing {
            pluginManager.mountRoutes(this)
            UnifiedSystemRouteInstaller.install(this, pluginManager, pluginLoader, devHotReloadEngine)
            LogRouteInstaller.install(this)
            DocRouteInstaller.install(this)
            staticResources("/", "static")
            customRouting?.invoke(this)
        }
    }
}

internal class KernelLifecycleHooks(
    private val logger: ScopeLogger,
    private val serverPort: () -> Int,
    private val enablePluginHotReload: Boolean,
    private val pluginManager: UnifiedPluginManager,
    private val pluginDevelopmentSources: List<com.keel.kernel.hotreload.PluginDevelopmentSource>,
    private val devHotReloadEngine: DevHotReloadEngine,
    private val observabilityHub: ObservabilityHub,
    private val kernelScope: CoroutineScope,
    private val configHotReloader: () -> ConfigHotReloader,
    private val onHotReloaderStarted: () -> Unit,
    private val isHotReloaderInitialized: () -> Boolean,
    private val moduleWatchDirectories: List<String>
) {
    fun install(app: Application) {
        app.monitor.subscribe(ApplicationStarted) {
            logger.info("Kernel started")
            BannerPrinter.print(port = serverPort(), enablePluginHotReload = enablePluginHotReload)
            kotlinx.coroutines.runBlocking {
                pluginManager.startEnabledPlugins()
            }
            pluginDevelopmentSources.forEach { source ->
                pluginManager.registerPluginSource(source)
                devHotReloadEngine.registerSource(source)
            }
            observabilityHub.start(kernelScope)

            if (ConfigHotReloader.isDevelopmentMode()) {
                onHotReloaderStarted()
                configHotReloader().startWatching()
                logger.info("Hot-reloader started (development mode: ${ConfigHotReloader.isDevelopmentMode()})")
                if (moduleWatchDirectories.isNotEmpty()) {
                    logger.info("Watching module directories: ${moduleWatchDirectories.joinToString(", ")}")
                }
            }
        }

        app.monitor.subscribe(ApplicationStopping) {
            logger.info("Kernel stopping")
            if (isHotReloaderInitialized()) {
                configHotReloader().stopWatching()
            }
            kotlinx.coroutines.runBlocking {
                pluginManager.stopAll()
            }
            observabilityHub.shutdown()
            kernelScope.cancel()
            KeelLoggerService.getInstance().shutdown()
        }
    }
}
