package com.keel.openapi.runtime

/**
 * Interface for KSP-generated OpenAPI fragment registry objects.
 * Each plugin annotated with @KeelApiPlugin gets a generated object implementing this interface.
 * Fragments are discovered at runtime via ServiceLoader for spec aggregation.
 */
interface OpenApiFragment {
    /** The unique plugin identifier (e.g., "dbdemo"). */
    val pluginId: String

    /** The base API path for this plugin (e.g., "/api/plugins/dbdemo"). */
    val basePath: String

    /** Human-readable title for this plugin's API group. */
    val title: String

    /** Optional description for this plugin's API section. */
    val description: String
        get() = ""

    /** API version string. */
    val version: String
        get() = "1.0.0"
}
