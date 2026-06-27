package com.keel.samples.aigateway

import com.keel.db.database.DatabaseConfig
import com.keel.db.database.DatabaseFactory
import java.io.File
import java.util.Locale

internal object GatewayDatabaseFactoryResolver {
    private const val ROOT = "keel.aigateway.db"

    fun resolveFactory(
        logicalName: String,
        defaultPoolSize: Int = 5,
        propertyLookup: (String) -> String? = System::getProperty,
        envLookup: (String) -> String? = System::getenv,
    ): DatabaseFactory = DatabaseFactory.fromConfig(
        resolveConfig(
            logicalName = logicalName,
            defaultPoolSize = defaultPoolSize,
            propertyLookup = propertyLookup,
            envLookup = envLookup,
        )
    )

    fun resolveConfig(
        logicalName: String,
        defaultPoolSize: Int = 5,
        propertyLookup: (String) -> String? = System::getProperty,
        envLookup: (String) -> String? = System::getenv,
    ): DatabaseConfig {
        require(logicalName.isNotBlank()) { "logicalName must not be blank" }

        val settings = Settings(propertyLookup, envLookup)
        val configuredPoolSize = settings.int("$ROOT.poolSize") ?: defaultPoolSize

        return when (settings.databaseKind()) {
            GatewayDatabaseKind.H2 -> DatabaseConfig.h2File(
                filePath = settings.h2FilePath(logicalName),
                username = settings.value("$ROOT.h2.username") ?: "sa",
                password = settings.value("$ROOT.h2.password") ?: "",
                poolSize = configuredPoolSize,
            )

            GatewayDatabaseKind.SQLITE -> DatabaseConfig.sqlite(
                filePath = settings.sqliteFilePath(logicalName),
            )

            GatewayDatabaseKind.POSTGRESQL -> DatabaseConfig.postgresql(
                host = settings.required("$ROOT.postgresql.host"),
                port = settings.int("$ROOT.postgresql.port") ?: 5432,
                database = settings.postgresqlDatabaseName(logicalName),
                username = settings.required("$ROOT.postgresql.username"),
                password = settings.required("$ROOT.postgresql.password"),
                poolSize = configuredPoolSize,
            )
        }
    }

    private enum class GatewayDatabaseKind {
        H2,
        SQLITE,
        POSTGRESQL,
    }

    private class Settings(
        private val propertyLookup: (String) -> String?,
        private val envLookup: (String) -> String?,
    ) {
        fun databaseKind(): GatewayDatabaseKind {
            return when (value("$ROOT.kind")?.lowercase(Locale.ROOT)) {
                null, "", "h2" -> GatewayDatabaseKind.H2
                "sqlite" -> GatewayDatabaseKind.SQLITE
                "postgres", "postgresql", "psql" -> GatewayDatabaseKind.POSTGRESQL
                else -> throw IllegalArgumentException("Unsupported gateway database kind: ${value("$ROOT.kind")}")
            }
        }

        fun h2FilePath(logicalName: String): String {
            return value("$ROOT.h2.file.$logicalName")
                ?: File(dataDir(), logicalName).absolutePath
        }

        fun sqliteFilePath(logicalName: String): String {
            return value("$ROOT.sqlite.file.$logicalName")
                ?: File(value("$ROOT.sqlite.dir") ?: dataDir().absolutePath, "$logicalName.sqlite.db").absolutePath
        }

        fun postgresqlDatabaseName(logicalName: String): String {
            return value("$ROOT.postgresql.database.$logicalName")
                ?: buildString {
                    val prefix = value("$ROOT.postgresql.databasePrefix")
                        ?.takeIf { it.isNotBlank() }
                        ?.let(::normalizeDatabaseIdentifier)
                    if (prefix != null) {
                        append(prefix)
                        append('_')
                    }
                    append(normalizeDatabaseIdentifier(logicalName))
                }
        }

        fun required(key: String): String {
            return value(key)?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Missing required gateway database setting: $key")
        }

        fun int(key: String): Int? = value(key)?.toIntOrNull()

        fun value(key: String): String? {
            return propertyLookup(key)?.trim()?.takeIf { it.isNotEmpty() }
                ?: envLookup(key.toEnvKey())?.trim()?.takeIf { it.isNotEmpty() }
        }

        private fun dataDir(): File {
            val configured = value("$ROOT.dir")
                ?: propertyLookup("keel.data.dir")?.trim()?.takeIf { it.isNotEmpty() }
                ?: File(System.getProperty("user.home"), ".keel/keel-data").absolutePath
            return File(configured).apply { mkdirs() }
        }
    }
}

private fun String.toEnvKey(): String = uppercase(Locale.ROOT).replace('.', '_').replace('-', '_')

private fun normalizeDatabaseIdentifier(raw: String): String = raw
    .lowercase(Locale.ROOT)
    .replace(Regex("[^a-z0-9_]+"), "_")
    .trim('_')
    .ifBlank { "keel" }
