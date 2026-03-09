package com.keel.kernel.config

import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationEnvironment
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.ApplicationEngineFactory
import io.ktor.events.Events
import io.ktor.server.netty.NettyApplicationEngine
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.koin.core.context.stopKoin

class KeelEngineStarterTest {

    @AfterTest
    fun teardown() {
        stopKoin()
    }

    @Test
    fun nettyStarterDisablesWatchPathsAndAppliesEngineConfig() {
        val server = KeelEngineStarter.start(
            KeelServerConfig().apply {
                connectionGroupSize = 1
                workerGroupSize = 2
                callGroupSize = 3
                configureEngine<NettyApplicationEngine.Configuration> {
                    runningLimit = 64
                }
            }
        ) { }

        assertEquals(emptyList(), configuredWatchPaths(server))

        val engineConfig = server.engineConfig as NettyApplicationEngine.Configuration
        assertEquals(1, engineConfig.connectionGroupSize)
        assertEquals(2, engineConfig.workerGroupSize)
        assertEquals(3, engineConfig.callGroupSize)
        assertEquals(64, engineConfig.runningLimit)
        assertEquals(8080, engineConfig.connectors.single().port)
        assertEquals("0.0.0.0", engineConfig.connectors.single().host)
    }

    @Test
    fun genericStarterPathAppliesConnectorsAndCustomConfig() {
        val server = KeelEngineStarter.start(
            KeelServerConfig().apply {
                engine = KeelEngine.CIO
                port = 9090
                host = "127.0.0.1"
                connectionGroupSize = 4
                configureEngine<CIOApplicationEngine.Configuration> {
                    reuseAddress = true
                }
            }
        ) { }

        assertEquals(emptyList(), configuredWatchPaths(server))

        val engineConfig = server.engineConfig as CIOApplicationEngine.Configuration
        assertEquals(4, engineConfig.connectionGroupSize)
        assertEquals(9090, engineConfig.connectors.single().port)
        assertEquals("127.0.0.1", engineConfig.connectors.single().host)
        assertTrue(engineConfig.reuseAddress)
    }

    @Test
    fun keelBuilderAndRunnerEntryPointsAreAvailable() {
        val builder = ::buildKeel
        val runnerWithPort: (Int, KernelBuilder.() -> Unit) -> Unit = ::runKeel
        val runnerWithoutPort: (KernelBuilder.() -> Unit) -> Unit = ::runKeel

        assertNotNull(builder)
        assertNotNull(runnerWithPort)
        assertNotNull(runnerWithoutPort)
    }

    @Test
    fun runKeelAllowsBuilderConfiguredPortWhenPortArgumentOmitted() {
        val capturedPort = AtomicInteger(-1)

        val thrown = assertFailsWith<IllegalStateException> {
            runKeel {
                server {
                    port = 9090
                    engine = KeelEngine.Custom(
                        object : ApplicationEngineFactory<ApplicationEngine, ApplicationEngine.Configuration> {
                            override fun configuration(
                                configure: ApplicationEngine.Configuration.() -> Unit
                            ) = ApplicationEngine.Configuration().apply(configure)

                            override fun create(
                                environment: ApplicationEnvironment,
                                monitor: Events,
                                developmentMode: Boolean,
                                configuration: ApplicationEngine.Configuration,
                                applicationProvider: () -> Application
                            ): ApplicationEngine {
                                capturedPort.set(configuration.connectors.single().port)
                                throw IllegalStateException("stop after capture")
                            }
                        }
                    )
                }
            }
        }

        assertEquals("stop after capture", thrown.message)
        assertEquals(9090, capturedPort.get())
    }

    private fun configuredWatchPaths(server: Any): List<String> {
        val rootConfigField = server.javaClass.getDeclaredField("rootConfig")
        rootConfigField.isAccessible = true
        val rootConfig = rootConfigField.get(server)

        val watchPathsField = rootConfig.javaClass.getDeclaredField("watchPaths")
        watchPathsField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return watchPathsField.get(rootConfig) as List<String>
    }
}
