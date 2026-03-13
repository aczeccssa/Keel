package com.keel.kernel.plugin

import com.keel.db.database.KeelDatabase
import com.keel.contract.di.KeelDiQualifiers
import org.jetbrains.exposed.sql.Table
import org.koin.core.Koin
import org.koin.core.scope.Scope

interface PluginInitContext {
    val pluginId: String
    val descriptor: PluginDescriptor
    val kernelKoin: Koin

    fun getDatabase(): KeelDatabase? {
        return try {
            kernelKoin.getOrNull<KeelDatabase>(KeelDiQualifiers.keelDatabaseQualifier)
        } catch (_: Exception) {
            null
        }
    }

    @Suppress("unused")
    fun getTables(): List<Table> = emptyList()
}

interface PluginRuntimeContext : PluginInitContext {
    val privateScope: Scope
    fun registerTeardown(action: () -> Unit)
}
