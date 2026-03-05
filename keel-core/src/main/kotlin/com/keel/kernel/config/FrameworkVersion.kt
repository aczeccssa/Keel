package com.keel.kernel.config

import java.util.Properties

internal object FrameworkVersion {
    private const val FRAMEWORK_PROPERTIES = "keel-framework.properties"

    fun current(): String {
        val fromSystem = System.getProperty("keel.version") ?: System.getenv("KEEL_VERSION")
        if (!fromSystem.isNullOrBlank()) return fromSystem

        val fromResource = readFrameworkProperty("framework.version")
        if (!fromResource.isNullOrBlank()) return fromResource

        val fromManifest = KeelConstants::class.java.`package`?.implementationVersion
        if (!fromManifest.isNullOrBlank()) return fromManifest

        return "dev"
    }

    fun readFrameworkProperty(key: String): String? {
        val contextLoader = Thread.currentThread().contextClassLoader
        val stream = contextLoader?.getResourceAsStream(FRAMEWORK_PROPERTIES)
            ?: FrameworkVersion::class.java.classLoader?.getResourceAsStream(FRAMEWORK_PROPERTIES)
            ?: return null
        return runCatching {
            stream.use {
                Properties().apply { load(it) }.getProperty(key)
            }
        }.getOrNull()
    }
}
