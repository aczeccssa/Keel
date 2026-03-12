package com.keel.test.kernel

import com.keel.kernel.hotreload.DevReloadOutcome
import com.keel.kernel.plugin.EndpointPlugin
import com.keel.kernel.plugin.KeelPlugin
import com.keel.kernel.plugin.LifecyclePlugin
import com.keel.kernel.plugin.ModulePlugin
import com.keel.kernel.plugin.PluginEndpointBuilders.pluginEndpoints
import com.keel.kernel.plugin.PluginDescriptor
import com.keel.kernel.plugin.PluginDispatchDisposition
import com.keel.kernel.plugin.PluginGeneration
import com.keel.kernel.plugin.PluginInitContext
import com.keel.kernel.plugin.PluginLifecycleState
import com.keel.kernel.plugin.PluginResult
import com.keel.kernel.plugin.PluginRuntimeContext
import com.keel.kernel.plugin.UnifiedPluginManager
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.system.measureTimeMillis
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UnifiedPluginManagerTest {

    private var koinStarted = false

    @AfterTest
    fun teardown() {
        if (koinStarted) {
            stopKoin()
            koinStarted = false
        }
    }

    @Test
    fun ktorScopeDriftCheckReturnsFalseWhenSignaturesMatch() {
        val before = UnifiedPluginManager.KtorScopeSignature(
            applicationPluginKeys = listOf("app-a"),
            servicePluginKeys = listOf("svc-a")
        )
        val after = UnifiedPluginManager.KtorScopeSignature(
            applicationPluginKeys = listOf("app-a"),
            servicePluginKeys = listOf("svc-a")
        )

        assertFalse(UnifiedPluginManager.hasKtorScopeDrift(before, after))
    }

    @Test
    fun ktorScopeDriftCheckReturnsTrueWhenApplicationScopeChanges() {
        val before = UnifiedPluginManager.KtorScopeSignature(
            applicationPluginKeys = listOf("app-a"),
            servicePluginKeys = listOf("svc-a")
        )
        val after = UnifiedPluginManager.KtorScopeSignature(
            applicationPluginKeys = listOf("app-b"),
            servicePluginKeys = listOf("svc-a")
        )

        assertTrue(UnifiedPluginManager.hasKtorScopeDrift(before, after))
    }

    @Test
    fun ktorScopeDriftCheckReturnsTrueWhenServiceScopeChanges() {
        val before = UnifiedPluginManager.KtorScopeSignature(
            applicationPluginKeys = listOf("app-a"),
            servicePluginKeys = listOf("svc-a")
        )
        val after = UnifiedPluginManager.KtorScopeSignature(
            applicationPluginKeys = listOf("app-a"),
            servicePluginKeys = listOf("svc-b")
        )

        assertTrue(UnifiedPluginManager.hasKtorScopeDrift(before, after))
    }

    @Test
    fun reloadCompatibilityIsReloadedWhenTopologyAndScopeMatch() {
        val decision = UnifiedPluginManager.decideReloadCompatibility(
            previousTopology = setOf("GET /api/plugins/ping"),
            newTopology = setOf("GET /api/plugins/ping"),
            previousKtorScope = UnifiedPluginManager.KtorScopeSignature(
                applicationPluginKeys = listOf("app-a"),
                servicePluginKeys = listOf("svc-a")
            ),
            newKtorScope = UnifiedPluginManager.KtorScopeSignature(
                applicationPluginKeys = listOf("app-a"),
                servicePluginKeys = listOf("svc-a")
            )
        )

        assertEquals(DevReloadOutcome.RELOADED, decision.outcome)
    }

    @Test
    fun reloadCompatibilityRequiresRestartWhenApplicationScopeChanges() {
        val decision = UnifiedPluginManager.decideReloadCompatibility(
            previousTopology = setOf("GET /api/plugins/ping"),
            newTopology = setOf("GET /api/plugins/ping"),
            previousKtorScope = UnifiedPluginManager.KtorScopeSignature(
                applicationPluginKeys = listOf("app-a"),
                servicePluginKeys = listOf("svc-a")
            ),
            newKtorScope = UnifiedPluginManager.KtorScopeSignature(
                applicationPluginKeys = listOf("app-b"),
                servicePluginKeys = listOf("svc-a")
            )
        )

        assertEquals(DevReloadOutcome.RESTART_REQUIRED, decision.outcome)
    }

    @Test
    fun reloadCompatibilityRequiresRestartWhenServiceScopeChanges() {
        val decision = UnifiedPluginManager.decideReloadCompatibility(
            previousTopology = setOf("GET /api/plugins/ping"),
            newTopology = setOf("GET /api/plugins/ping"),
            previousKtorScope = UnifiedPluginManager.KtorScopeSignature(
                applicationPluginKeys = listOf("app-a"),
                servicePluginKeys = listOf("svc-a")
            ),
            newKtorScope = UnifiedPluginManager.KtorScopeSignature(
                applicationPluginKeys = listOf("app-a"),
                servicePluginKeys = listOf("svc-b")
            )
        )

        assertEquals(DevReloadOutcome.RESTART_REQUIRED, decision.outcome)
    }

    @Test
    fun pluginLifecycleMutationsAreSerializedPerPluginNotGlobally() = runTest {
        val koin = startKoin {}.also { koinStarted = true }.koin
        val manager = UnifiedPluginManager(koin)
        manager.registerPlugin(DelayedPlugin("plugin-a", 250))
        manager.registerPlugin(DelayedPlugin("plugin-b", 250))

        val elapsedMs = measureTimeMillis {
            coroutineScope {
                awaitAll(
                    async { manager.startPlugin("plugin-a") },
                    async { manager.startPlugin("plugin-b") }
                )
            }
        }

        assertTrue(elapsedMs < 450, "Expected per-plugin locking, but startup took ${elapsedMs}ms")
        assertEquals(PluginLifecycleState.RUNNING, manager.getLifecycleState("plugin-a"))
        assertEquals(PluginLifecycleState.RUNNING, manager.getLifecycleState("plugin-b"))
        assertEquals(PluginGeneration.INITIAL, manager.getGeneration("plugin-a"))
        assertEquals(PluginGeneration.INITIAL, manager.getGeneration("plugin-b"))
        assertEquals(null, manager.getProcessId("plugin-a"))
        assertFalse(manager.forceKill("plugin-a"))
    }

    @Test
    fun pluginPrivateModulesAreIsolatedWhileKernelServicesStayShared() = runTest {
        val koin = startKoin {
            modules(
                module {
                    single { SharedKernelDependency() }
                }
            )
        }.also { koinStarted = true }.koin
        val manager = UnifiedPluginManager(koin)
        val pluginA = RecordingScopePlugin("plugin-a")
        val pluginB = RecordingScopePlugin("plugin-b")

        manager.registerPlugin(pluginA)
        manager.registerPlugin(pluginB)
        manager.startPlugin("plugin-a")
        manager.startPlugin("plugin-b")

        assertNotEquals(pluginA.startedPrivateIds.single(), pluginB.startedPrivateIds.single())
        assertEquals(pluginA.sharedKernelInstanceIds.single(), pluginB.sharedKernelInstanceIds.single())
    }

    @Test
    fun privateScopeIsRecreatedAndTeardownRunsOnDisable() = runTest {
        val koin = startKoin {
            modules(
                module {
                    single { SharedKernelDependency() }
                }
            )
        }.also { koinStarted = true }.koin
        val manager = UnifiedPluginManager(koin)
        val plugin = RecordingScopePlugin("plugin-a")

        manager.registerPlugin(plugin)
        manager.startPlugin("plugin-a")
        val firstPrivateId = plugin.startedPrivateIds.single()

        manager.disposePlugin("plugin-a")
        assertEquals(1, plugin.teardownCount.get())

        manager.startPlugin("plugin-a")
        val secondPrivateId = plugin.startedPrivateIds.last()
        assertNotEquals(firstPrivateId, secondPrivateId)
    }

    @Test
    fun stopAndStartPreserveGenerationAndPrivateScope() = runTest {
        val koin = startKoin {
            modules(
                module {
                    single { SharedKernelDependency() }
                }
            )
        }.also { koinStarted = true }.koin
        val manager = UnifiedPluginManager(koin)
        val plugin = RecordingScopePlugin("plugin-a")

        manager.registerPlugin(plugin)
        manager.startPlugin("plugin-a")
        val firstPrivateId = plugin.startedPrivateIds.single()
        val firstGeneration = manager.getGeneration("plugin-a")

        manager.stopPlugin("plugin-a")
        assertEquals(PluginDispatchDisposition.UNAVAILABLE, manager.resolveDispatchDisposition("plugin-a"))

        manager.startPlugin("plugin-a")
        assertEquals(firstGeneration, manager.getGeneration("plugin-a"))
        assertEquals(firstPrivateId, plugin.startedPrivateIds.last())
    }

    @Test
    fun reloadIncrementsGenerationAndRecreatesPrivateScope() = runTest {
        val koin = startKoin {
            modules(
                module {
                    single { SharedKernelDependency() }
                }
            )
        }.also { koinStarted = true }.koin
        val manager = UnifiedPluginManager(koin)
        val plugin = RecordingScopePlugin("plugin-a")

        manager.registerPlugin(plugin)
        manager.startPlugin("plugin-a")
        val firstPrivateId = plugin.startedPrivateIds.single()
        val firstGeneration = manager.getGeneration("plugin-a")

        manager.reloadPlugin("plugin-a")

        assertEquals(firstGeneration.next(), manager.getGeneration("plugin-a"))
        assertNotEquals(firstPrivateId, plugin.startedPrivateIds.last())
        assertEquals(PluginLifecycleState.RUNNING, manager.getLifecycleState("plugin-a"))
    }

    @Test
    fun replaceIncrementsGenerationAndRecreatesPrivateScope() = runTest {
        val koin = startKoin {
            modules(
                module {
                    single { SharedKernelDependency() }
                }
            )
        }.also { koinStarted = true }.koin
        val manager = UnifiedPluginManager(koin)
        val plugin = RecordingScopePlugin("plugin-a")

        manager.registerPlugin(plugin)
        manager.startPlugin("plugin-a")
        val firstPrivateId = plugin.startedPrivateIds.single()
        val firstGeneration = manager.getGeneration("plugin-a")

        manager.replacePlugin("plugin-a")

        assertEquals(firstGeneration.next(), manager.getGeneration("plugin-a"))
        assertNotEquals(firstPrivateId, plugin.startedPrivateIds.last())
        assertEquals(PluginLifecycleState.RUNNING, manager.getLifecycleState("plugin-a"))
    }

    @Test
    fun disposeMarksPluginNotFoundUntilStartedAgain() = runTest {
        val koin = startKoin {
            modules(
                module {
                    single { SharedKernelDependency() }
                }
            )
        }.also { koinStarted = true }.koin
        val manager = UnifiedPluginManager(koin)
        val plugin = RecordingScopePlugin("plugin-a")

        manager.registerPlugin(plugin)
        manager.startPlugin("plugin-a")

        manager.disposePlugin("plugin-a")

        assertEquals(PluginLifecycleState.DISPOSED, manager.getLifecycleState("plugin-a"))
        assertEquals(PluginDispatchDisposition.NOT_FOUND, manager.resolveDispatchDisposition("plugin-a"))

        manager.startPlugin("plugin-a")

        assertEquals(PluginLifecycleState.RUNNING, manager.getLifecycleState("plugin-a"))
    }

    @Test
    fun oldPrivateScopeObjectsCanBeGarbageCollectedAfterDisable() = runTest {
        val koin = startKoin {
            modules(
                module {
                    single { SharedKernelDependency() }
                }
            )
        }.also { koinStarted = true }.koin
        val manager = UnifiedPluginManager(koin)
        val plugin = RecordingScopePlugin("plugin-a")

        manager.registerPlugin(plugin)
        manager.startPlugin("plugin-a")
        val leakedRef = requireNotNull(plugin.privateReferences.singleOrNull())

        manager.disposePlugin("plugin-a")
        repeat(20) {
            if (leakedRef.get() == null) return@repeat
            System.gc()
            delay(50)
        }

        assertNull(leakedRef.get(), "Expected old private scope objects to be collectable after disable")
    }

    @Test
    fun lifecycleOnlyPluginRunsWithoutEndpointCapability() = runTest {
        val koin = startKoin {}.also { koinStarted = true }.koin
        val manager = UnifiedPluginManager(koin)
        val plugin = LifecycleOnlyPlugin("lifecycle-only")

        manager.registerPlugin(plugin)
        manager.startPlugin("lifecycle-only")
        manager.stopPlugin("lifecycle-only")

        assertEquals(1, plugin.startedCount.get())
        assertEquals(1, plugin.stoppedCount.get())
    }

    @Test
    fun endpointOnlyPluginRunsWithoutLifecycleCapability() = runTest {
        val koin = startKoin {}.also { koinStarted = true }.koin
        val manager = UnifiedPluginManager(koin)

        manager.registerPlugin(EndpointOnlyPlugin("endpoint-only"))
        manager.startPlugin("endpoint-only")

        assertEquals(PluginLifecycleState.RUNNING, manager.getLifecycleState("endpoint-only"))
        assertEquals(PluginDispatchDisposition.AVAILABLE, manager.resolveDispatchDisposition("endpoint-only"))
    }

    @Test
    fun moduleOnlyPluginRunsWithoutEndpointAndLifecycleCapabilities() = runTest {
        val koin = startKoin {}.also { koinStarted = true }.koin
        val manager = UnifiedPluginManager(koin)

        manager.registerPlugin(ModuleOnlyPlugin("module-only"))
        manager.startPlugin("module-only")

        assertEquals(PluginLifecycleState.RUNNING, manager.getLifecycleState("module-only"))
    }

    private class DelayedPlugin(
        pluginId: String,
        private val initDelayMs: Long
    ) : KeelPlugin, LifecyclePlugin {
        override val descriptor: PluginDescriptor = PluginDescriptor(
            pluginId = pluginId,
            version = "1.0.0",
            displayName = pluginId
        )

        override suspend fun onInit(context: PluginInitContext) {
            delay(initDelayMs)
        }
    }

    private class RecordingScopePlugin(
        pluginId: String
    ) : KeelPlugin, LifecyclePlugin, ModulePlugin {
        override val descriptor: PluginDescriptor = PluginDescriptor(
            pluginId = pluginId,
            version = "1.0.0",
            displayName = pluginId
        )

        val startedPrivateIds = mutableListOf<String>()
        val sharedKernelInstanceIds = mutableListOf<Int>()
        val privateReferences = mutableListOf<WeakReference<PrivateScopedDependency>>()
        val teardownCount = AtomicInteger(0)

        override suspend fun onStart(context: PluginRuntimeContext) {
            val privateDependency = context.privateScope.get<PrivateScopedDependency>()
            startedPrivateIds += privateDependency.id
            privateReferences += WeakReference(privateDependency)
            sharedKernelInstanceIds += System.identityHashCode(context.kernelKoin.get<SharedKernelDependency>())
            context.registerTeardown { teardownCount.incrementAndGet() }
        }

        override fun modules() = listOf(
            module {
                single { PrivateScopedDependency(UUID.randomUUID().toString()) }
            }
        )
    }

    private class SharedKernelDependency

    private data class PrivateScopedDependency(val id: String)

    private class LifecycleOnlyPlugin(
        pluginId: String
    ) : KeelPlugin, LifecyclePlugin {
        override val descriptor: PluginDescriptor = PluginDescriptor(pluginId, "1.0.0", pluginId)
        val startedCount = AtomicInteger(0)
        val stoppedCount = AtomicInteger(0)

        override suspend fun onStart(context: PluginRuntimeContext) {
            startedCount.incrementAndGet()
        }

        override suspend fun onStop(context: PluginRuntimeContext) {
            stoppedCount.incrementAndGet()
        }
    }

    private class EndpointOnlyPlugin(
        pluginId: String
    ) : KeelPlugin, EndpointPlugin {
        override val descriptor: PluginDescriptor = PluginDescriptor(pluginId, "1.0.0", pluginId)

        override fun endpoints() = pluginEndpoints(descriptor.pluginId) {
            get<String>("/ping") { PluginResult(body = "pong") }
        }
    }

    private class ModuleOnlyPlugin(
        pluginId: String
    ) : KeelPlugin, ModulePlugin {
        override val descriptor: PluginDescriptor = PluginDescriptor(pluginId, "1.0.0", pluginId)

        override fun modules() = listOf(
            module {
                single { "module-only" }
            }
        )
    }
}
