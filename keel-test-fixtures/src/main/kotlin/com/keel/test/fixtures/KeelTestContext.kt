package com.keel.test.fixtures

import com.keel.contract.di.KeelDiQualifiers
import com.keel.db.database.KeelDatabase
import com.keel.kernel.plugin.KeelPlugin
import com.keel.kernel.plugin.PluginEndpointDefinition
import com.keel.kernel.plugin.PluginLifecycleState
import com.keel.kernel.plugin.PluginRouteDefinition
import com.keel.kernel.plugin.PluginSseDefinition
import com.keel.kernel.plugin.PluginStaticResourceDefinition
import com.keel.kernel.plugin.UnifiedPluginManager
import com.keel.openapi.runtime.OpenApiRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.koin.core.Koin
import org.koin.core.KoinApplication
import org.koin.core.module.Module
import org.koin.dsl.koinApplication

data class RouteMountDiagnostic(
    val pluginId: String,
    val method: String,
    val path: String,
    val kind: String
)

data class HttpFailureDiagnostic(
    val method: String,
    val path: String,
    val expectedStatus: Int?,
    val actualStatus: Int?,
    val responseSnippet: String?,
    val responseHeaders: Map<String, String>
)

class KeelTestContext(
    private val plugins: List<KeelPlugin>,
    private val kernelModules: List<Module> = emptyList(),
    private val pluginModules: List<Module> = emptyList(),
    private val testDatabaseProvider: TestDatabaseProvider? = null,
    private val databaseSetup: (KeelDatabase.() -> Unit)? = null
) {
    private val effectivePlugins: List<KeelPlugin> = plugins.map { plugin ->
        if (pluginModules.isEmpty()) plugin else TestPluginModuleWrapper(plugin, pluginModules)
    }

    private var koinApp: KoinApplication? = null
    private var _pluginManager: UnifiedPluginManager? = null
    private var _coroutineScope: CoroutineScope? = null
    private var _scopeJob: Job? = null
    private var _clientClosed = true
    private var disposed = false

    private var _lastStage: String? = null
    private var _lastError: String? = null
    private var _routeMounts: List<RouteMountDiagnostic> = emptyList()
    private var _lastHttpFailure: HttpFailureDiagnostic? = null

    val koin: Koin
        get() = koinApp?.koin ?: error("KeelTestContext has not been started yet.")

    val pluginManager: UnifiedPluginManager
        get() = _pluginManager ?: error("KeelTestContext has not been started yet.")

    val coroutineScope: CoroutineScope
        get() = _coroutineScope ?: error("KeelTestContext has not been started yet.")

    internal fun attachClient() {
        _clientClosed = false
    }

    internal fun markClientClosed() {
        _clientClosed = true
    }

    internal fun recordHttpFailure(diagnostic: HttpFailureDiagnostic) {
        _lastHttpFailure = diagnostic
    }

    suspend fun start() {
        check(!disposed) { "Cannot start a disposed KeelTestContext." }
        _lastStage = "start"
        OpenApiRegistry.clear()

        try {
            testDatabaseProvider?.beforeStart()

            val modules = buildList {
                testDatabaseProvider?.let { add(it.createModule()) }
                addAll(kernelModules)
            }

            koinApp = koinApplication {
                modules(modules)
            }

            _scopeJob = SupervisorJob()
            _coroutineScope = CoroutineScope(requireNotNull(_scopeJob))

            val manager = UnifiedPluginManager(koin)
            _pluginManager = manager

            _routeMounts = effectivePlugins.flatMap(::extractRouteDiagnostics)
            effectivePlugins.forEach { manager.registerPlugin(it) }

            databaseSetup?.let { setup ->
                val db = resolveDatabase()
                    ?: error("databaseSetup was configured but no KeelDatabase is available.")
                db.setup()
            }

            effectivePlugins.forEach { manager.startPlugin(it.descriptor.pluginId) }
        } catch (t: Throwable) {
            recordFailure("start", t)
            throw t
        }
    }

    suspend fun dispose() {
        if (disposed) return
        disposed = true
        _lastStage = "dispose"

        val errors = mutableListOf<Throwable>()

        _pluginManager?.let { manager ->
            effectivePlugins.forEach { plugin ->
                try {
                    manager.disposePlugin(plugin.descriptor.pluginId)
                } catch (e: Exception) {
                    errors.add(e)
                }
            }
        }

        try {
            _coroutineScope?.cancel("KeelTestContext disposed")
        } catch (e: Exception) {
            errors.add(e)
        }

        try {
            testDatabaseProvider?.cleanup()
        } catch (e: Exception) {
            errors.add(e)
        }

        try {
            testDatabaseProvider?.afterDispose()
        } catch (e: Exception) {
            errors.add(e)
        }

        try {
            OpenApiRegistry.clear()
        } catch (e: Exception) {
            errors.add(e)
        }

        try {
            koinApp?.close()
        } catch (e: Exception) {
            errors.add(e)
        }

        _pluginManager = null
        _coroutineScope = null
        _scopeJob = null
        koinApp = null

        if (errors.isNotEmpty()) {
            val combined = KeelTestTeardownException(
                "KeelTestContext dispose encountered ${errors.size} error(s)",
                errors
            )
            recordFailure("dispose", combined)
            throw combined
        }
    }

    fun recordFailure(stage: String, throwable: Throwable) {
        _lastStage = stage
        _lastError = "${throwable::class.simpleName}: ${throwable.message}"
    }

    fun diagnosticSnapshot(): KeelTestDiagnosticSnapshot {
        val manager = _pluginManager
        val pluginStates = if (manager != null) {
            effectivePlugins.associate { plugin ->
                plugin.descriptor.pluginId to manager.getLifecycleState(plugin.descriptor.pluginId)
            }
        } else {
            emptyMap()
        }
        return KeelTestDiagnosticSnapshot(
            pluginStates = pluginStates,
            koinActive = koinApp != null && !disposed,
            coroutineScopeActive = _coroutineScope != null && !disposed,
            jobActive = _scopeJob?.isActive == true,
            clientClosed = _clientClosed,
            routeMounts = _routeMounts,
            lastStage = _lastStage,
            lastError = _lastError,
            lastHttpFailure = _lastHttpFailure
        )
    }

    private fun resolveDatabase(): KeelDatabase? {
        return runCatching {
            koin.get<KeelDatabase>(KeelDiQualifiers.keelDatabaseQualifier)
        }.getOrNull()
    }

    private fun extractRouteDiagnostics(plugin: KeelPlugin): List<RouteMountDiagnostic> {
        val pluginId = plugin.descriptor.pluginId
        return plugin.endpoints().map { route ->
            when (route) {
                is PluginEndpointDefinition<*, *> -> RouteMountDiagnostic(
                    pluginId = pluginId,
                    method = route.method.value,
                    path = route.path,
                    kind = "endpoint"
                )
                is PluginSseDefinition -> RouteMountDiagnostic(
                    pluginId = pluginId,
                    method = "GET",
                    path = route.path,
                    kind = "sse"
                )
                is PluginStaticResourceDefinition -> RouteMountDiagnostic(
                    pluginId = pluginId,
                    method = "GET",
                    path = route.path,
                    kind = "static"
                )
            }
        }
    }

}

private class TestPluginModuleWrapper(
    private val delegate: KeelPlugin,
    private val extraModules: List<Module>
) : KeelPlugin by delegate {
    override fun modules(): List<Module> = delegate.modules() + extraModules
}

data class KeelTestDiagnosticSnapshot(
    val pluginStates: Map<String, PluginLifecycleState>,
    val koinActive: Boolean,
    val coroutineScopeActive: Boolean,
    val jobActive: Boolean,
    val clientClosed: Boolean,
    val routeMounts: List<RouteMountDiagnostic>,
    val lastStage: String?,
    val lastError: String?,
    val lastHttpFailure: HttpFailureDiagnostic?
) {
    fun formatReport(): String = buildString {
        appendLine("=== Keel Test Diagnostic Report ===")
        appendLine("Stage: ${lastStage ?: "unknown"}")
        appendLine("Error: ${lastError ?: "none"}")
        appendLine("Koin Active: $koinActive")
        appendLine("Coroutine Scope Active: $coroutineScopeActive")
        appendLine("Coroutine Job Active: $jobActive")
        appendLine("HttpClient Closed: $clientClosed")

        appendLine("Plugin States:")
        if (pluginStates.isEmpty()) {
            appendLine("  (none registered)")
        } else {
            pluginStates.forEach { (id, state) ->
                appendLine("  - $id: $state")
            }
        }

        appendLine("Mounted Routes:")
        if (routeMounts.isEmpty()) {
            appendLine("  (none)")
        } else {
            routeMounts.forEach { route ->
                appendLine("  - [${route.pluginId}] ${route.method} ${route.path} (${route.kind})")
            }
        }

        lastHttpFailure?.let { http ->
            appendLine("HTTP Failure:")
            appendLine("  - ${http.method} ${http.path}")
            appendLine("  - expected=${http.expectedStatus} actual=${http.actualStatus}")
            appendLine("  - body=${http.responseSnippet ?: "(none)"}")
            if (http.responseHeaders.isNotEmpty()) {
                appendLine("  - headers=${http.responseHeaders}")
            }
        }
    }
}

class KeelTestTeardownException(
    message: String,
    val errors: List<Throwable>
) : RuntimeException(message)
