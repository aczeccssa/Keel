package com.keel.test.fixtures

import com.keel.db.database.KeelDatabase
import com.keel.kernel.plugin.KeelPlugin
import com.keel.kernel.plugin.PluginLifecycleState
import com.keel.kernel.plugin.UnifiedPluginManager
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation as ClientContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation as ServerContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.koin.core.Koin
import org.koin.core.module.Module
import org.koin.dsl.module

class TestKeelPluginScope(
    private val appBuilder: ApplicationTestBuilder,
    val pluginManager: UnifiedPluginManager,
    val koin: Koin,
    internal val context: KeelTestContext
) {
    private var _client: HttpClient? = null

    val client: HttpClient
        get() = _client ?: appBuilder.createClient {
            install(ClientContentNegotiation) {
                json()
            }
        }.also {
            _client = it
            context.attachClient()
        }

    inline fun <reified T : Any> inject(): T = koin.get()

    inline fun <reified T : Any> inject(qualifier: org.koin.core.qualifier.Qualifier): T =
        koin.get(qualifier)

    fun expectPluginState(pluginId: String, expected: PluginLifecycleState) {
        val actual = pluginManager.getLifecycleState(pluginId)
        check(actual == expected) {
            "Expected plugin '$pluginId' to be in state $expected but was $actual"
        }
    }

    fun expectHealthy(pluginId: String) {
        expectPluginState(pluginId, PluginLifecycleState.RUNNING)
    }

    internal fun closeClient() {
        _client?.close()
        _client = null
        context.markClientClosed()
    }
}

class KeelTestDslBuilder {
    internal val plugins = mutableListOf<KeelPlugin>()
    internal val kernelModules = mutableListOf<Module>()
    internal val pluginModules = mutableListOf<Module>()
    internal val applicationConfigs = mutableListOf<Application.() -> Unit>()
    internal val runtimeActions = mutableListOf<suspend TestKeelPluginScope.() -> Unit>()
    internal var testDatabaseProvider: TestDatabaseProvider? = null
    internal var databaseSetupBlock: (KeelDatabase.() -> Unit)? = null

    fun plugin(plugin: KeelPlugin) {
        plugins.add(plugin)
    }

    fun plugins(vararg pluginList: KeelPlugin) {
        plugins.addAll(pluginList)
    }

    fun kernelModule(moduleDeclaration: Module.() -> Unit) {
        kernelModules.add(module { moduleDeclaration() })
    }

    fun kernelModules(vararg modules: Module) {
        kernelModules.addAll(modules)
    }

    fun pluginModule(moduleDeclaration: Module.() -> Unit) {
        pluginModules.add(module { moduleDeclaration() })
    }

    fun pluginModules(vararg modules: Module) {
        pluginModules.addAll(modules)
    }

    fun withInMemoryDatabase(databaseName: String = "test_db_${System.nanoTime()}") {
        testDatabaseProvider = H2InMemoryDatabaseProvider(databaseName)
    }

    fun withDatabaseProvider(provider: TestDatabaseProvider) {
        testDatabaseProvider = provider
    }

    fun databaseSetup(block: KeelDatabase.() -> Unit) {
        databaseSetupBlock = block
    }

    fun configureApplication(block: Application.() -> Unit) {
        applicationConfigs.add(block)
    }

    fun test(block: suspend TestKeelPluginScope.() -> Unit) {
        runtimeActions.add(block)
    }

    fun expectPluginState(pluginId: String, expected: PluginLifecycleState) {
        runtimeActions.add {
            expectPluginState(pluginId, expected)
        }
    }

    fun expectHealthy(pluginId: String) {
        runtimeActions.add {
            expectHealthy(pluginId)
        }
    }

    fun http(block: suspend TestHttpDsl.() -> Unit) {
        runtimeActions.add {
            http(block)
        }
    }

    fun assertions(block: suspend TestKeelPluginScope.() -> Unit) {
        runtimeActions.add(block)
    }
}

suspend fun testKeelPluginSuspend(block: KeelTestDslBuilder.() -> Unit) {
    val builder = KeelTestDslBuilder().apply(block)
    runKeelPluginTest(builder)
}

fun testKeelPlugin(block: KeelTestDslBuilder.() -> Unit) {
    runBlocking {
        testKeelPluginSuspend(block)
    }
}

private suspend fun runKeelPluginTest(builder: KeelTestDslBuilder) {
    val context = KeelTestContext(
        plugins = builder.plugins,
        kernelModules = builder.kernelModules,
        pluginModules = builder.pluginModules,
        testDatabaseProvider = builder.testDatabaseProvider,
        databaseSetup = builder.databaseSetupBlock
    )

    var scope: TestKeelPluginScope? = null
    var primaryError: Throwable? = null
    try {
        context.start()
        testApplication {
            application {
                install(SSE)
                install(ServerContentNegotiation) { json() }
                builder.applicationConfigs.forEach { it(this) }
                routing {
                    context.pluginManager.mountRoutes(this)
                }
            }

            scope = TestKeelPluginScope(
                appBuilder = this,
                pluginManager = context.pluginManager,
                koin = context.koin,
                context = context
            )

            builder.runtimeActions.forEach { action ->
                requireNotNull(scope).action()
            }
        }
    } catch (t: Throwable) {
        primaryError = t
        context.recordFailure("execute", t)
        throw t
    } finally {
        var teardownFailure: Throwable? = null
        try {
            scope?.closeClient()
        } catch (t: Throwable) {
            teardownFailure = t
        }
        try {
            context.dispose()
        } catch (t: Throwable) {
            if (teardownFailure == null) {
                teardownFailure = t
            } else {
                teardownFailure.addSuppressed(t)
            }
        }

        if (primaryError != null || teardownFailure != null) {
            System.err.println(context.diagnosticSnapshot().formatReport())
        }
        if (primaryError == null && teardownFailure != null) {
            throw teardownFailure
        }
        if (primaryError != null && teardownFailure != null) {
            primaryError.addSuppressed(teardownFailure)
        }
    }
}
