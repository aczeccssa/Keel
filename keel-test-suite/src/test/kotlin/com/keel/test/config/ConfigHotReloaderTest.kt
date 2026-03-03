package com.keel.test.config

import com.keel.kernel.config.ConfigHotReloader
import com.keel.kernel.config.KeelConstants
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigHotReloaderTest {

    @AfterTest
    fun cleanup() {
        System.clearProperty(KeelConstants.ENV_SYSTEM_PROPERTY)
    }

    @Test
    fun developmentModeUsesSystemProperty() {
        System.setProperty(KeelConstants.ENV_SYSTEM_PROPERTY, KeelConstants.ENV_DEVELOPMENT)
        assertTrue(ConfigHotReloader.isDevelopmentMode())
    }

    @Test
    fun productionModeIsDefault() {
        System.clearProperty(KeelConstants.ENV_SYSTEM_PROPERTY)
        assertFalse(ConfigHotReloader.isDevelopmentMode())
    }
}
