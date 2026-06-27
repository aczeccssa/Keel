package com.keel.samples.aigateway

import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

class GatewayDatabaseFactoryResolverTest {

    @Test
    fun defaultConfigUsesH2FileDatabaseUnderConfiguredDataDir() {
        val dataDir = createTempDirectory("gateway-db-h2").toFile()
        val props = mapOf(
            "keel.aigateway.db.dir" to dataDir.absolutePath,
        )

        val config = GatewayDatabaseFactoryResolver.resolveConfig(
            logicalName = "aigateway_token",
            defaultPoolSize = 5,
            propertyLookup = props::get,
            envLookup = { null },
        )

        assertEquals("jdbc:h2:file:${dataDir.resolve("aigateway_token").absolutePath}", config.jdbcUrl)
        assertEquals("org.h2.Driver", config.driverClassName)
        assertEquals("sa", config.username)
        assertEquals("", config.password)
        assertEquals(5, config.poolSize)
    }

    @Test
    fun sqliteConfigUsesPerStoreFileInConfiguredDirectory() {
        val dataDir = createTempDirectory("gateway-db-sqlite").toFile()
        val props = mapOf(
            "keel.aigateway.db.kind" to "sqlite",
            "keel.aigateway.db.dir" to dataDir.absolutePath,
        )

        val config = GatewayDatabaseFactoryResolver.resolveConfig(
            logicalName = "aigateway_airelay",
            defaultPoolSize = 5,
            propertyLookup = props::get,
            envLookup = { null },
        )

        assertEquals("jdbc:sqlite:${dataDir.resolve("aigateway_airelay.sqlite.db").absolutePath}", config.jdbcUrl)
        assertEquals("org.sqlite.JDBC", config.driverClassName)
        assertEquals(1, config.poolSize)
    }

    @Test
    fun postgresqlConfigUsesDerivedDatabaseNameAndEnvironmentFallbacks() {
        val props = mapOf(
            "keel.aigateway.db.kind" to "postgresql",
            "keel.aigateway.db.postgresql.host" to "127.0.0.1",
            "keel.aigateway.db.postgresql.port" to "6543",
            "keel.aigateway.db.postgresql.databasePrefix" to "keelgw",
        )
        val env = mapOf(
            "KEEL_AIGATEWAY_DB_POSTGRESQL_USERNAME" to "keel",
            "KEEL_AIGATEWAY_DB_POSTGRESQL_PASSWORD" to "secret",
        )

        val config = GatewayDatabaseFactoryResolver.resolveConfig(
            logicalName = "customer_portal",
            defaultPoolSize = 7,
            propertyLookup = props::get,
            envLookup = env::get,
        )

        assertEquals("jdbc:postgresql://127.0.0.1:6543/keelgw_customer_portal", config.jdbcUrl)
        assertEquals("org.postgresql.Driver", config.driverClassName)
        assertEquals("keel", config.username)
        assertEquals("secret", config.password)
        assertEquals(7, config.poolSize)
    }
}
