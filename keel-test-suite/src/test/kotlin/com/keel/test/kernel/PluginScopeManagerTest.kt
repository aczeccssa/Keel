package com.keel.test.kernel

import com.keel.kernel.di.PluginScopeManager
import kotlinx.coroutines.test.runTest
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull

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
    fun createAndCloseScope() = runTest {
        val koin = startKoin {}.also { koinStarted = true }.koin
        val manager = PluginScopeManager(koin)

        val scope = manager.createScope("plug-1")
        assertNotNull(scope)

        manager.closeScope("plug-1")
    }
}
