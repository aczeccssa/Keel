package com.keel.kernel.plugin

import java.util.concurrent.ConcurrentHashMap

internal class PluginRegistry(
    private val entries: ConcurrentHashMap<String, ManagedPlugin>
) {
    fun register(entry: ManagedPlugin) {
        entries[entry.plugin.descriptor.pluginId] = entry
    }

    fun get(pluginId: String): ManagedPlugin? = entries[pluginId]

    fun contains(pluginId: String): Boolean = entries.containsKey(pluginId)

    fun ids(): Set<String> = entries.keys

    fun values(): Collection<ManagedPlugin> = entries.values

    fun sortedValues(): List<ManagedPlugin> = entries.values.sortedBy { it.plugin.descriptor.pluginId }

    fun allPlugins(): Map<String, KeelPlugin> = entries.mapValues { it.value.plugin }.toSortedMap()
}
