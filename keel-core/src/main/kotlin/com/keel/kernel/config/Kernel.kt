package com.keel.kernel.config

import com.keel.kernel.hotreload.DefaultDevHotReloadEngine
import com.keel.kernel.hotreload.DefaultModuleChangeClassifier
import com.keel.kernel.hotreload.DefaultPluginImpactAnalyzer
import com.keel.kernel.hotreload.GradleDevBuildExecutor
import com.keel.kernel.hotreload.ManagerBackedGenerationLoader
import com.keel.kernel.hotreload.PluginDevelopmentSource
import com.keel.kernel.hotreload.PluginSourceInference
import com.keel.kernel.loader.DefaultPluginLoader
import com.keel.kernel.logging.KeelLoggerService
import com.keel.kernel.logging.LogLevel
import com.keel.kernel.observability.ObservabilityConfig
import com.keel.kernel.observability.ObservabilityHub
import com.keel.kernel.observability.ObservabilityTracing
import com.keel.kernel.observability.KeelObservability
import com.keel.kernel.plugin.KeelPlugin
import com.keel.kernel.plugin.PluginRuntimeMode
import com.keel.kernel.plugin.UnifiedPluginManager
import com.keel.kernel.routing.DocRouteInstaller
import com.keel.kernel.routing.GatewayInterceptor
import com.keel.kernel.routing.LogRouteInstaller
import com.keel.kernel.routing.UnifiedSystemRouteInstaller
import com.lestere.opensource.ApplicationMode
import com.lestere.opensource.logger.SoulLogger
import com.lestere.opensource.logger.SoulLoggerPlugin
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.http.content.staticResources
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
import java.io.File
import org.koin.core.Koin
import org.koin.core.KoinApplication
import org.koin.core.context.startKoin
import org.koin.dsl.module

class Kernel(
    private val koin: Koin,
    private val enablePluginHotReload: Boolean = ConfigHotReloader.isDevelopmentMode(),
    private val moduleWatchDirectories: List<String> = emptyList(),
    private val pluginDevelopmentSources: List<PluginDevelopmentSource> = emptyList(),
    private val customRouting: (Route.() -> Unit)? = null,
    private val serverConfig: KeelServerConfig = KeelServerConfig()
) {
    private val logger = KeelLoggerService.getLogger("Kernel")
    private val repoRoot: File = locateRepoRoot(File(System.getProperty("user.dir")).absoluteFile.normalize())
    private val kernelScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val pluginLoader = DefaultPluginLoader()
    private val observabilityHub = ObservabilityHub(ObservabilityConfig.fromSystem())
    private val observabilityTracing = ObservabilityTracing.initKernel(observabilityHub)
    private val pluginManager = UnifiedPluginManager(koin, observabilityHub = observabilityHub)
    private val devHotReloadEngine = DefaultDevHotReloadEngine(
        repoRoot = repoRoot,
        impactAnalyzer = DefaultPluginImpactAnalyzer(repoRoot),
        classifier = DefaultModuleChangeClassifier(),
        buildExecutor = GradleDevBuildExecutor(repoRoot),
        generationLoader = ManagerBackedGenerationLoader(pluginManager)
    )
    private val pluginLifecycleHotReloadAdapter = PluginLifecycleHotReloadAdapter(
        pluginManager,
        if (enablePluginHotReload) devHotReloadEngine else null
    )
    private val gatewayInterceptor = GatewayInterceptor(pluginManager)
    private var serverPort: Int = serverConfig.port

    private var hotReloaderInitialized = false
    private val configHotReloader: ConfigHotReloader by lazy {
        ConfigHotReloader.Builder()
            .watchModuleDirectories(moduleWatchDirectories)
            .onModuleChange { event ->
                kernelScope.launch {
                    if (enablePluginHotReload && pluginDevelopmentSources.isNotEmpty()) {
                        devHotReloadEngine.handleModuleChange(event)
                    } else {
                        handleModuleChange(event)
                    }
                }
            }
            .build()
    }

    fun registerPlugin(
        plugin: KeelPlugin,
        enabledOverride: Boolean? = null
    ): Kernel {
        pluginManager.registerPlugin(plugin, enabledOverride)
        return this
    }

    fun run(port: Int? = null) {
        if (port != null) {
            serverConfig.port = port
        } else if (serverConfig.port == 8080 && ConfigHotReloader.getServerPort() != 8080) {
            // Respect hot reload config port only when neither run() nor server {} set a port.
            serverConfig.port = ConfigHotReloader.getServerPort()
        }
        serverPort = serverConfig.port
        if (ConfigHotReloader.isDevelopmentMode()) {
            System.setProperty("io.ktor.development", "true")
        } else {
            System.setProperty("io.ktor.development", "false")
        }
        
        val engine = KeelEngineStarter.start(serverConfig) {
            install(ContentNegotiation) {
                json(Json {
                    prettyPrint = true
                    isLenient = true
                })
            }
            install(SSE)
            install(SoulLoggerPlugin) {
                if (ConfigHotReloader.isDevelopmentMode()) {
                    mode = ApplicationMode.DEVELOPMENT
                    level = SoulLogger.Level.DEBUG
                } else {
                    mode = ApplicationMode.PRODUCTION
                    level = SoulLogger.Level.INFO
                }
            }
            serverConfig.installConfiguredGlobalKtorPlugins(this)
            pluginManager.installConfiguredPluginApplicationKtorPlugins(this)

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
                UnifiedSystemRouteInstaller.install(this, pluginManager, pluginLoader, devHotReloadEngine)
                LogRouteInstaller.install(this)
                DocRouteInstaller.install(this)
                staticResources("/", "static")
                customRouting?.invoke(this)
            }
        }
        engine.start(wait = true)
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

        app.monitor.subscribe(ApplicationStarted) {
            logger.info("Kernel started")
            BannerPrinter.print(port = serverPort, enablePluginHotReload = enablePluginHotReload)
            kotlinx.coroutines.runBlocking {
                pluginManager.startEnabledPlugins()
            }
            pluginDevelopmentSources.forEach { source ->
                pluginManager.registerPluginSource(source)
                devHotReloadEngine.registerSource(source)
            }
            observabilityHub.start(kernelScope)

            if (ConfigHotReloader.isDevelopmentMode()) {
                hotReloaderInitialized = true
                configHotReloader.startWatching()
                logger.info("Hot-reloader started (development mode: ${ConfigHotReloader.isDevelopmentMode()})")
                if (moduleWatchDirectories.isNotEmpty()) {
                    logger.info("Watching module directories: ${moduleWatchDirectories.joinToString(", ")}")
                }
            }
        }

        app.monitor.subscribe(ApplicationStopping) {
            logger.info("Kernel stopping")
            if (hotReloaderInitialized) {
                configHotReloader.stopWatching()
            }
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

    // Test support: avoid reflection in external test modules.
    fun pluginManager(): UnifiedPluginManager = pluginManager

    // Test support: avoid reflection in external test modules.
    fun pluginDevelopmentSourceIds(): Set<String> = pluginDevelopmentSources.map { it.pluginId }.toSet()
}

class KernelBuilder {
    private data class PluginRegistration(
        val plugin: KeelPlugin,
        val enabled: Boolean,
        val hotReloadEnabled: Boolean
    )

    private val logger = KeelLoggerService.getLogger("KernelBuilder")
    private var koinConfig: (KoinApplication.() -> Unit)? = null
    private val plugins = mutableListOf<PluginRegistration>()
    private val pluginSources = mutableListOf<PluginDevelopmentSource>()
    private var enablePluginHotReload: Boolean = ConfigHotReloader.isDevelopmentMode()
    private val watchDirectories = linkedSetOf<String>()
    private var watchDirectoriesExplicitlySet: Boolean = false
    private var customRouting: (Route.() -> Unit)? = null
    private var serverConfigBlock: (KeelServerConfig.() -> Unit)? = null

    fun koin(config: KoinApplication.() -> Unit) {
        koinConfig = config
    }

    fun server(block: KeelServerConfig.() -> Unit) {
        serverConfigBlock = block
    }

    fun plugin(
        plugin: KeelPlugin,
        enabled: Boolean = true,
        hotReloadEnabled: Boolean = ConfigHotReloader.isDevelopmentMode()
    ) {
        plugins += PluginRegistration(
            plugin = plugin,
            enabled = enabled,
            hotReloadEnabled = hotReloadEnabled
        )
    }

    @Deprecated(
        message = "Use plugin(plugin, enabled, hotReloadEnabled) instead. Source metadata is inferred from plugin class."
    )
    fun pluginSource(
        pluginId: String,
        owningModulePath: String,
        implementationClassName: String,
        runtimeMode: PluginRuntimeMode = PluginRuntimeMode.IN_PROCESS
    ) {
        pluginSources += PluginDevelopmentSource(
            pluginId = pluginId,
            owningModulePath = owningModulePath,
            implementationClassName = implementationClassName,
            runtimeMode = runtimeMode
        )
    }

    fun enablePluginHotReload(enabled: Boolean) {
        this.enablePluginHotReload = enabled
    }

    fun watchDirectories(dirs: Iterable<String>) {
        watchDirectoriesExplicitlySet = true
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
        val cwd = File(System.getProperty("user.dir")).absoluteFile.normalize()
        val repoRoot = locateRepoRoot(cwd)
        val koin = startKoin(koinConfig ?: {}).koin
        val defaultWatchDirectories = DefaultWatchDirectoriesResolver.resolveForCallingModule()
        val effectiveWatchDirectories = buildList {
            if (watchDirectoriesExplicitlySet) {
                addAll(watchDirectories)
            } else {
                addAll(defaultWatchDirectories)
            }
        }.distinct()

        val pluginSourceById = linkedMapOf<String, PluginDevelopmentSource>()
        pluginSources.forEach { source ->
            pluginSourceById[source.pluginId] = source
        }
        plugins
            .asSequence()
            .filter { it.hotReloadEnabled }
            .forEach { registration ->
                val pluginId = registration.plugin.descriptor.pluginId
                if (pluginSourceById.containsKey(pluginId)) {
                    return@forEach
                }
                val inferred = PluginSourceInference.infer(
                    plugin = registration.plugin,
                    repoRoot = repoRoot
                )
                if (inferred != null) {
                    pluginSourceById[pluginId] = inferred
                } else {
                    logger.warn("Unable to infer plugin source for $pluginId; dev hot reload disabled for this plugin")
                }
            }

        val serverConfig = KeelServerConfig().apply {
            serverConfigBlock?.invoke(this)
        }

        val kernel = Kernel(
            koin = koin,
            enablePluginHotReload = enablePluginHotReload,
            moduleWatchDirectories = effectiveWatchDirectories,
            pluginDevelopmentSources = pluginSourceById.values.toList(),
            customRouting = customRouting,
            serverConfig = serverConfig
        )
        val registeredPluginIds = linkedSetOf<String>()
        plugins.forEach { registration ->
            val plugin = registration.plugin
            registeredPluginIds += plugin.descriptor.pluginId
            kernel.registerPlugin(
                plugin = plugin,
                enabledOverride = registration.enabled
            )
        }
        pluginSourceById.values.forEach { source ->
            if (!registeredPluginIds.add(source.pluginId)) {
                return@forEach
            }
            val plugin = instantiatePluginFromSource(source)
            kernel.registerPlugin(plugin)
        }
        return kernel
    }

    private fun instantiatePluginFromSource(source: PluginDevelopmentSource): KeelPlugin {
        val clazz = Class.forName(source.implementationClassName)
        require(KeelPlugin::class.java.isAssignableFrom(clazz)) {
            "pluginSource class ${source.implementationClassName} must implement KeelPlugin"
        }
        val instance = clazz.getDeclaredConstructor().newInstance() as KeelPlugin
        require(instance.descriptor.pluginId == source.pluginId) {
            "pluginSource pluginId ${source.pluginId} does not match descriptor pluginId ${instance.descriptor.pluginId}"
        }
        return instance
    }
}

fun buildKeel(block: KernelBuilder.() -> Unit) = KernelBuilder().apply(block).build()

fun runKeel(block: KernelBuilder.() -> Unit) = buildKeel(block).run()

fun runKeel(port: Int, block: KernelBuilder.() -> Unit) = buildKeel(block).run(port)

private fun locateRepoRoot(start: File): File {
    return generateSequence(start) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").exists() }
        ?: start
}
