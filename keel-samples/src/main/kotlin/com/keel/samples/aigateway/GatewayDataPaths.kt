package com.keel.samples.aigateway

import java.io.File

object GatewayDataPaths {
    private const val DATA_DIR_PROPERTY = "keel.data.dir"

    fun dataRoot(): File {
        val configured = System.getProperty(DATA_DIR_PROPERTY)?.trim().orEmpty()
        val root = if (configured.isNotEmpty()) {
            File(configured)
        } else {
            File(System.getProperty("user.home"), ".keel/keel-data")
        }
        root.mkdirs()
        return root
    }

    fun databasePath(name: String): String {
        require(name.isNotBlank()) { "name must not be blank" }
        return File(dataRoot(), name).absolutePath
    }
}
