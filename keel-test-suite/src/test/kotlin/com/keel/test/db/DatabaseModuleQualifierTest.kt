package com.keel.test.db

import com.keel.contract.di.KeelDiQualifiers
import com.keel.db.database.DatabaseConfig
import com.keel.db.database.DatabaseFactory
import com.keel.db.database.KeelDatabase
import com.keel.db.di.databaseModule
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class DatabaseModuleQualifierTest {

    private var koinStarted = false

    @AfterTest
    fun teardown() {
        if (koinStarted) {
            stopKoin()
            koinStarted = false
        }
    }

    @Test
    fun configModuleRegistersKeelDatabaseWithUnifiedQualifierOnly() {
        val koin = startKoin {
            modules(databaseModule(DatabaseConfig.h2Memory(name = "cfg-${System.nanoTime()}")))
        }.also { koinStarted = true }.koin

        val qualified = koin.get<KeelDatabase>(KeelDiQualifiers.keelDatabaseQualifier)
        assertNotNull(qualified)
        assertNull(koin.getOrNull<KeelDatabase>())

        koin.get<DatabaseFactory>().close()
    }

    @Test
    fun prebuiltModuleRegistersKeelDatabaseWithUnifiedQualifierOnly() {
        val factory = DatabaseFactory.h2Memory(name = "prebuilt-${System.nanoTime()}")
        val database = factory.init()

        val koin = startKoin {
            modules(databaseModule(database))
        }.also { koinStarted = true }.koin

        val qualified = koin.get<KeelDatabase>(KeelDiQualifiers.keelDatabaseQualifier)
        assertSame(database, qualified)
        assertNull(koin.getOrNull<KeelDatabase>())

        factory.close()
    }
}
