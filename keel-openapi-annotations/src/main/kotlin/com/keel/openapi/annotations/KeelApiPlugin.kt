package com.keel.openapi.annotations

/**
 * Marks a KPlugin class for OpenAPI spec generation.
 * KSP processor reads this annotation and generates an [OpenApiFragment] registry object
 * plus SPI registration for runtime discovery.
 *
 * @param pluginId The unique plugin identifier (e.g., "dbdemo")
 * @param title Human-readable title for the OpenAPI info section
 * @param description Optional description for the plugin's API group
 * @param version API version string (e.g., "1.0.0")
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class KeelApiPlugin(
    val pluginId: String,
    val title: String,
    val description: String = "",
    val version: String = "1.0.0"
)
