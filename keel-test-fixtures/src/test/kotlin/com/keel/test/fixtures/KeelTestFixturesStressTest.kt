package com.keel.test.fixtures

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.koin.dsl.module
import kotlin.test.Test

@Tag("stress")
class KeelTestFixturesStressTest {

    @Test
    fun `parallel isolation stress test`() = runBlocking {
        val rounds = System.getProperty("keel.test.parallel.rounds")?.toIntOrNull() ?: 60
        repeat(rounds) { round ->
            coroutineScope {
                (1..20).map { idx ->
                    async {
                        val pluginId = "stress-$round-$idx"
                        testKeelPluginSuspend {
                            plugin(SimplePlugin(pluginId))
                            kernelModule { single { KernelService() } }
                            expectHealthy(pluginId)
                        }
                    }
                }.awaitAll()
            }
        }
    }
}
