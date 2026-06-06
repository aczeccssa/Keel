package com.keel.kernel.config

/**
 * Central constants for the Keel framework.
 * Eliminates magic strings scattered across the codebase.
 */
object KeelConstants {
    /** Base path for plugin API routes. */
    const val PLUGIN_API_PREFIX = "/api/plugins"

    /** Base path for system management routes. */
    const val SYSTEM_API_PREFIX = "/api/_system"

    /** Default plugin directory for dynamic discovery. */
    const val PLUGINS_DIR = "plugins"

    /** Default configuration directory. */
    const val CONFIG_DIR = "config"

    /** Default plugin config directory under the config root. */
    const val CONFIG_PLUGINS_DIR = "$CONFIG_DIR/plugins"

    /** Manifest attribute: plugin ID. */
    const val MANIFEST_PLUGIN_ID = "Keel-Plugin-Id"

    /** Manifest attribute: plugin version. */
    const val MANIFEST_PLUGIN_VERSION = "Keel-Plugin-Version"

    /** Manifest attribute: plugin main class. */
    const val MANIFEST_PLUGIN_MAIN_CLASS = "Keel-Plugin-Main-Class"

    /** Manifest attribute: plugin dependencies (comma-separated). */
    const val MANIFEST_PLUGIN_DEPENDENCIES = "Keel-Plugin-Dependencies"

    /** System property name for environment mode. */
    const val ENV_SYSTEM_PROPERTY = "keel.env"

    /** Environment variable name for environment mode. */
    const val ENV_ENV_VARIABLE = "KEEL_ENV"

    /** Development environment value. */
    const val ENV_DEVELOPMENT = "development"

    /** Production environment value. */
    const val ENV_PRODUCTION = "production"

    /** System property name for http engine. */
    const val HTTP_ENGINE_SYSTEM_PROPERTY = "keel.http.engine"

    /** Environment variable name for http engine. */
    const val HTTP_ENGINE_ENV_VARIABLE = "HTTP_ENGINE"

    /** Environment variable default for http engine. */
    const val HTTP_ENGINE_ENV_DEFAULT = "NETTY"

    const val PORT_SYSTEM_PROPERTY = "keel.port"

    const val PORT_ENV_VARIABLE = "KEEL_PORT"

    /** Standard preview/platform port environment variable. */
    const val STANDARD_PORT_ENV_VARIABLE = "PORT"

    const val DEFAULT_SERVER_PORT = 8080

}
