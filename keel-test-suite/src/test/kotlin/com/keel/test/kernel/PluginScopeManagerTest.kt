package com.keel.test.kernel

import com.keel.contract.di.KeelDiQualifiers
import com.keel.kernel.di.PluginScopeManager
import com.keel.kernel.plugin.PluginConfig
import kotlinx.coroutines.test.runTest
import java.util.concurrent.atomic.AtomicInteger
import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame

class PluginScopeManagerTest {

    private var koinStarted = false

    @AfterTest
    fun teardown() {
        if (koinStarted) {
            stopKoin()
            koinStarted = false
        }
    }

    @Test
    fun createScopeLoadsPrivateModulesAndRunsTeardownOnClose() = runTest {
        val koin = startKoin {
            modules(
                module {
                    single { SharedDependency("kernel") }
                }
            )
        }.also { koinStarted = true }.koin
        val manager = PluginScopeManager(koin)
        val teardownCount = AtomicInteger(0)

        val scopeHandle = manager.createScope(
            pluginId = "plug-1",
            config = PluginConfig(pluginId = "plug-1"),
            modules = listOf(
                module {
                    single { PrivateDependency("plug-1-private") }
                }
            )
        )
        scopeHandle.teardownRegistry.register { teardownCount.incrementAndGet() }

        assertNotNull(scopeHandle)
        assertEquals("plug-1-private", scopeHandle.privateScope.get<PrivateDependency>().id)
        assertEquals("kernel", koin.get<SharedDependency>().id)
        manager.closeScope("plug-1")
        assertEquals(1, teardownCount.get())
    }

    @Test
    fun createScopeExposesKernelKoinThroughUnifiedQualifier() = runTest {
        val koin = startKoin {}.also { koinStarted = true }.koin
        val manager = PluginScopeManager(koin)

        val scopeHandle = manager.createScope(
            pluginId = "plug-qualifier",
            config = PluginConfig(pluginId = "plug-qualifier"),
            modules = emptyList()
        )

        val scopedKernelKoin = scopeHandle.privateScope.get<Koin>(KeelDiQualifiers.kernelKoinQualifier)
        assertSame(koin, scopedKernelKoin)
        manager.closeScope("plug-qualifier")
    }

    private data class SharedDependency(val id: String)

    private data class PrivateDependency(val id: String)
}
