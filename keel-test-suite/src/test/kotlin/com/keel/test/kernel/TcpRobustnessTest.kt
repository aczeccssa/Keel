package com.keel.test.kernel

import com.keel.kernel.plugin.*
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class TcpRobustnessTest {

    @Test
    fun preferredModeIsUsedOnFirstAttempt() = runTest {
        val strategy = JvmCommunicationStrategy(
            preferredMode = JvmCommunicationMode.UDS,
            fallbackMode = JvmCommunicationMode.TCP
        )
        val descriptor = PluginDescriptor(
            pluginId = "test-plugin",
            version = "1.0.0",
            displayName = "Test Plugin",
            communicationStrategy = strategy
        )
        assertEquals(JvmCommunicationMode.UDS, descriptor.communicationStrategy.preferredMode)
        assertEquals(JvmCommunicationMode.TCP, descriptor.communicationStrategy.fallbackMode)
    }

    @Test
    fun canForceTcpMode() = runTest {
        val descriptor = PluginDescriptor(
            pluginId = "test-plugin",
            version = "1.0.0",
            displayName = "Test Plugin",
            communicationStrategy = JvmCommunicationStrategy.PREFER_TCP
        )
        assertEquals(JvmCommunicationMode.TCP, descriptor.communicationStrategy.preferredMode)
    }
}
