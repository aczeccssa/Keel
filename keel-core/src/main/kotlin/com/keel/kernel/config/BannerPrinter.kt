package com.keel.kernel.config

import java.io.File
import java.net.InetAddress
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

internal object BannerPrinter {
    private const val BANNER_RESOURCE = "keel-banner.txt"
    private val tokenRegex = Regex("""\$\{([A-Za-z0-9_.-]+)}""")

    fun print(
        port: Int,
        enablePluginHotReload: Boolean
    ) {
        val banner = loadBanner() ?: return
        val isDev = ConfigHotReloader.isDevelopmentMode()
        val keelVersion = FrameworkVersion.current()
        val values = mapOf(
            "keel.version" to keelVersion,
            "ktor.version" to (versionOf("io.ktor.util.KtorVersion", "VERSION")
                ?: versionOfClassName("io.ktor.server.application.Application")),
            "exposed.version" to (versionOfClassName("org.jetbrains.exposed.sql.Database")
                .takeUnless { it == "unknown" }
                ?: FrameworkVersion.readFrameworkProperty("exposed.version")
                ?: "unknown"),
            "engine" to "Netty",
            "env" to if (isDev) KeelConstants.ENV_DEVELOPMENT else KeelConstants.ENV_PRODUCTION,
            "status" to "RUNNING",
            "hotreload" to if (isDev && enablePluginHotReload) "ENABLED" else "DISABLED",
            "ip" to resolveLocalIp(),
            "port" to port.toString(),
            "time" to ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        )
        val rendered = tokenRegex.replace(banner) { match ->
            values[match.groupValues[1]] ?: "unknown"
        }
        println(rendered)
    }

    private fun loadBanner(): String? {
        val contextLoader = Thread.currentThread().contextClassLoader
        val stream = contextLoader?.getResourceAsStream(BANNER_RESOURCE)
            ?: BannerPrinter::class.java.classLoader?.getResourceAsStream(BANNER_RESOURCE)
            ?: return null
        return stream.bufferedReader().use { it.readText() }
    }

    private fun versionOf(clazz: Class<*>): String {
        return clazz.`package`?.implementationVersion ?: "unknown"
    }

    private fun versionOfClassName(className: String): String {
        val clazz = runCatching { Class.forName(className) }.getOrNull() ?: return "unknown"
        return versionOf(clazz)
    }

    private fun versionOf(className: String, fieldName: String): String? {
        val clazz = runCatching { Class.forName(className) }.getOrNull() ?: return null
        val field = runCatching { clazz.getDeclaredField(fieldName) }.getOrNull() ?: return null
        return runCatching { field.get(null)?.toString() }.getOrNull()
    }

    private fun resolveLocalIp(): String {
        return runCatching { InetAddress.getLocalHost().hostAddress }.getOrElse { "127.0.0.1" }
    }

}
