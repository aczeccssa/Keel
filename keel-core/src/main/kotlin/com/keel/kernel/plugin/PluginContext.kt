package com.keel.kernel.plugin

import com.keel.db.database.KeelDatabase
import org.jetbrains.exposed.sql.Table
import org.koin.core.Koin
import org.koin.core.qualifier.named
import org.koin.core.scope.Scope

interface PluginInitContext {
    val pluginId: String
    val descriptor: PluginDescriptor
    val kernelKoin: Koin

    fun getDatabase(): KeelDatabase? {
        return try {
            kernelKoin.getOrNull<KeelDatabase>(named("keelDatabase"))
        } catch (_: Exception) {
            null
        }
    }

    fun getTables(): List<Table> = emptyList()
}

interface PluginRuntimeContext : PluginInitContext {
    val privateScope: Scope
    fun registerTeardown(action: () -> Unit)
}
