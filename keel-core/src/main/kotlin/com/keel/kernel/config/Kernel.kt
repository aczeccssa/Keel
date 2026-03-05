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
import com.keel.kernel.routing.GatewayInterceptor
import com.keel.kernel.routing.docRoutes
import com.keel.kernel.routing.logRoutes
import com.keel.kernel.routing.unifiedSystemRoutes
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
    private val enablePluginHotReload: Boolean = ConfigHotReloader.isDevelopmentMode()
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
            .watchAdditionalDir("${KeelConstants.CONFIG_DIR}/plugins")
            .apply {
                if (enablePluginHotReload) {
                    watchPluginDir(KeelConstants.PLUGINS_DIR)
                }
            }
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
                unifiedSystemRoutes(pluginManager, pluginLoader)
                logRoutes()
                docRoutes()
                staticResources("/", "static")
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
            observabilityHub.shutdown()
            kernelScope.cancel()
            KeelLoggerService.getInstance().shutdown()
        }

        logger.info("Kernel configured")
    }
}

class KernelBuilder {
    private var koinConfig: (KoinApplication.() -> Unit)? = null
    private val plugins = mutableListOf<KeelPlugin>()
    private var enablePluginHotReload: Boolean = ConfigHotReloader.isDevelopmentMode()

    fun koin(config: KoinApplication.() -> Unit) {
        koinConfig = config
    }

    fun plugin(plugin: KeelPlugin) {
        plugins += plugin
    }

    fun enablePluginHotReload(enabled: Boolean) {
        this.enablePluginHotReload = enabled
    }

    fun build(): Kernel {
        val koin = startKoin(koinConfig ?: {}).koin
        val kernel = Kernel(koin = koin, enablePluginHotReload = enablePluginHotReload)
        plugins.forEach { kernel.registerPlugin(it) }
        return kernel
    }
}

fun buildKernel(block: KernelBuilder.() -> Unit) = KernelBuilder().apply(block).build()

fun runKernel(port: Int = 8080, block: KernelBuilder.() -> Unit) = buildKernel(block).run(port)
