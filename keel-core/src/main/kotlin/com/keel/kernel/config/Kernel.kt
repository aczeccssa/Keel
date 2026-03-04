package com.keel.kernel.config

import com.keel.kernel.loader.DefaultPluginLoader
import com.keel.kernel.logging.KeelLoggerService
import com.keel.kernel.logging.LogLevel
import com.keel.kernel.observability.ObservabilityConfig
import com.keel.kernel.observability.ObservabilityHub
import com.keel.kernel.observability.ObservabilityTracing
import com.keel.kernel.observability.KeelObservability
import com.keel.kernel.plugin.KeelPlugin
import com.keel.kernel.plugin.UnifiedPluginManager
import com.keel.kernel.routing.DocRouteInstaller
import com.keel.kernel.routing.GatewayInterceptor
import com.keel.kernel.routing.LogRouteInstaller
import com.keel.kernel.routing.UnifiedSystemRouteInstaller
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
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.Route
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import io.opentelemetry.context.Context
import org.koin.core.Koin
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.dsl.module

class Kernel(
    private val koin: Koin,
    private val enablePluginHotReload: Boolean = ConfigHotReloader.isDevelopmentMode(),
    private val moduleWatchDirectories: List<String> = emptyList(),
    private val customRouting: (Route.() -> Unit)? = null
) {
    private val logger = KeelLoggerService.getLogger("Kernel")
    private val kernelScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pluginLoader = DefaultPluginLoader()
    private val observabilityHub = ObservabilityHub(ObservabilityConfig.fromSystem())
    private val observabilityTracing = ObservabilityTracing.initKernel(observabilityHub)
    private val pluginManager = UnifiedPluginManager(koin, observabilityHub = observabilityHub)
    private val pluginLifecycleHotReloadAdapter = PluginLifecycleHotReloadAdapter(pluginManager)
    private val gatewayInterceptor = GatewayInterceptor(pluginManager)

    private val configHotReloader: ConfigHotReloader by lazy {
        ConfigHotReloader.Builder()
            .watchConfigDir(KeelConstants.CONFIG_DIR)
            .watchConfigDir(KeelConstants.CONFIG_PLUGINS_DIR)
            .apply {
                if (enablePluginHotReload) {
                    watchPluginDir(KeelConstants.PLUGINS_DIR)
                }
            }
            .watchModuleDirectories(moduleWatchDirectories)
            .onConfigChange { event ->
                kernelScope.launch {
                    runCatching {
                        pluginLifecycleHotReloadAdapter.handleConfigChange(event)
                    }.onFailure { error ->
                        logger.warn("Config-triggered lifecycle update failed for ${event.fileName}: ${error.message}")
                    }
                }
            }
            .onPluginChange { event ->
                kernelScope.launch {
                    runCatching {
                        pluginLifecycleHotReloadAdapter.handlePluginChange(event)
                    }.onFailure { error ->
                        logger.warn("Plugin-triggered lifecycle update failed for ${event.pluginId}: ${error.message}")
                    }
                }
            }
            .onModuleChange { event ->
                handleModuleChange(event)
            }
            .build()
    }

    fun registerPlugin(plugin: KeelPlugin): Kernel {
        pluginManager.registerPlugin(plugin)
        return this
    }

    fun run(port: Int = 8080) {
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

            koin.loadModules(listOf(
                module {
                    single<KeelObservability> { observabilityHub }
                }
            ))
            observabilityHub.setPluginSnapshotProvider { pluginManager.getRuntimeSnapshots() }

            configureApplication(this)

            routing {
                pluginManager.mountRoutes(this)
                UnifiedSystemRouteInstaller.install(this, pluginManager, pluginLoader)
                LogRouteInstaller.install(this)
                DocRouteInstaller.install(this)
                staticResources("/", "static")
                customRouting?.invoke(this)
            }
        }.start(wait = true)
    }

    fun configureApplication(app: Application) {
        app.intercept(ApplicationCallPipeline.Plugins) {
            val span = observabilityTracing.tracer.spanBuilder("http.request")
                .setAttribute("http.method", call.request.httpMethod.value)
                .setAttribute("http.route", call.request.path())
                .setAttribute("keel.jvm", "kernel")
                .startSpan()
            val scope = span.makeCurrent()
            call.attributes.put(ObservabilityTracing.TRACE_CONTEXT_KEY, Context.current())
            try {
                if (gatewayInterceptor.intercept(call)) {
                    span.setStatus(io.opentelemetry.api.trace.StatusCode.ERROR)
                    finish()
                    return@intercept
                }
                proceed()
            } finally {
                scope.close()
                span.end()
            }
        }

        app.environment.monitor.subscribe(ApplicationStarted) {
            logger.info("Kernel started")
            kotlinx.coroutines.runBlocking {
                pluginManager.startEnabledPlugins()
            }
            observabilityHub.start(kernelScope)

            if (ConfigHotReloader.isDevelopmentMode()) {
                configHotReloader.startWatching()
                logger.info("Hot-reloader started (development mode: ${ConfigHotReloader.isDevelopmentMode()})")
                if (moduleWatchDirectories.isNotEmpty()) {
                    logger.info("Watching module directories: ${moduleWatchDirectories.joinToString(", ")}")
                }
            }
        }

        app.environment.monitor.subscribe(ApplicationStopping) {
            logger.info("Kernel stopping")
            configHotReloader.stopWatching()
            kotlinx.coroutines.runBlocking {
                pluginManager.stopAll()
            }
            observabilityHub.shutdown()
            kernelScope.cancel()
            KeelLoggerService.getInstance().shutdown()
        }

        logger.info("Kernel configured")
    }

    private fun handleModuleChange(event: ModuleChangeEvent) {
        val path = event.relativePath
        when {
            path.startsWith("src/") || path == "build.gradle.kts" || path == "settings.gradle.kts" || path == "gradle.properties" -> {
                logger.warn(
                    "Module change detected at ${event.fullPath}. Source/build changes are watched in dev mode, " +
                        "but they still require rebuild + application restart to apply safely."
                )
            }
            path.startsWith("build/") -> {
                logger.warn(
                    "Compiled module output changed at ${event.fullPath}. In-process/kernel classes are already loaded; " +
                        "restart the application to pick up rebuilt module outputs."
                )
            }
            else -> {
                logger.info("Module change detected at ${event.fullPath}")
            }
        }
    }
}

class KernelBuilder {
    private var koinConfig: (KoinApplication.() -> Unit)? = null
    private val plugins = mutableListOf<KeelPlugin>()
    private var enablePluginHotReload: Boolean = ConfigHotReloader.isDevelopmentMode()
    private val watchDirectories = linkedSetOf<String>()
    private var customRouting: (Route.() -> Unit)? = null

    fun koin(config: KoinApplication.() -> Unit) {
        koinConfig = config
    }

    fun plugin(plugin: KeelPlugin) {
        plugins += plugin
    }

    fun enablePluginHotReload(enabled: Boolean) {
        this.enablePluginHotReload = enabled
    }

    fun watchDirectories(dirs: Iterable<String>) {
        dirs.map(String::trim)
            .filter(String::isNotEmpty)
            .forEach(watchDirectories::add)
    }

    fun watchDirectories(vararg dirs: String) {
        watchDirectories(dirs.asIterable())
    }

    fun routing(block: Route.() -> Unit) {
        customRouting = block
    }

    fun build(): Kernel {
        val koin = startKoin(koinConfig ?: {}).koin
        val defaultWatchDirectories = DefaultWatchDirectoriesResolver.resolveForCallingModule()
        val effectiveWatchDirectories = buildList {
            addAll(defaultWatchDirectories)
            addAll(watchDirectories)
        }.distinct()
        val kernel = Kernel(
            koin = koin,
            enablePluginHotReload = enablePluginHotReload,
            moduleWatchDirectories = effectiveWatchDirectories,
            customRouting = customRouting
        )
        plugins.forEach { kernel.registerPlugin(it) }
        return kernel
    }
}

fun buildKernel(block: KernelBuilder.() -> Unit) = KernelBuilder().apply(block).build()

fun runKernel(port: Int = 8080, block: KernelBuilder.() -> Unit) = buildKernel(block).run(port)
