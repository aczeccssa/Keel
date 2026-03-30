package com.keel.kernel.plugin

import com.keel.kernel.hotreload.DevReloadOutcome
import com.keel.kernel.hotreload.PluginDevelopmentSource
import com.keel.kernel.hotreload.ReloadAttemptResult
import java.io.File
import java.net.URLClassLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class PluginReloadCoordinator(
    private val currentClasspath: String,
    private val extractCapabilities: (KeelPlugin, String) -> PluginCapabilities,
    private val stopPluginLocked: suspend (ManagedPlugin) -> Unit,
    private val disposePluginLocked: suspend (ManagedPlugin) -> Unit,
    private val startPluginLocked: suspend (ManagedPlugin) -> Unit
) {
    fun snapshotEntry(entry: ManagedPlugin): EntrySnapshot {
        return EntrySnapshot(
            plugin = entry.plugin,
            pluginClassName = entry.pluginClassName,
            routeDefinitions = entry.routeDefinitions,
            endpointById = entry.endpointById.toMap(),
            topology = entry.endpointTopology.toSet(),
            sseByPath = entry.sseByPath.toMap(),
            pluginApplicationInstallers = entry.pluginApplicationInstallers,
            pluginServiceRouteInstallers = entry.pluginServiceRouteInstallers,
            sourceClassLoader = entry.sourceClassLoader,
            config = entry.config,
            generation = entry.generation
        )
    }

    fun loadNewPluginGeneration(
        source: PluginDevelopmentSource,
        classpathModulePaths: Set<String>
    ): LoadResult {
        val classLoader = buildSourceClassLoader(classpathModulePaths, source)
        val plugin = runCatching {
            val clazz = classLoader.loadClass(source.implementationClassName)
            require(KeelPlugin::class.java.isAssignableFrom(clazz)) {
                "Class ${source.implementationClassName} does not implement KeelPlugin"
            }
            clazz.getDeclaredConstructor().newInstance() as KeelPlugin
        }.getOrElse { error ->
            runCatching { classLoader.close() }
            return LoadResult(error = error.message)
        }
        val capabilities = extractCapabilities(plugin, source.pluginId)
        return LoadResult(
            generation = NewGeneration(
                plugin = plugin,
                classLoader = classLoader,
                routeDefinitions = capabilities.routeDefinitions,
                endpointById = capabilities.endpointById,
                topology = capabilities.topology,
                sseByPath = capabilities.sseByPath,
                pluginApplicationInstallers = capabilities.applicationInstallers,
                pluginServiceRouteInstallers = capabilities.serviceInstallers
            )
        )
    }

    fun validateNewPluginGeneration(
        source: PluginDevelopmentSource,
        newGeneration: NewGeneration,
        snapshot: EntrySnapshot
    ): ReloadAttemptResult? {
        if (newGeneration.plugin.descriptor.pluginId != source.pluginId) {
            return ReloadAttemptResult(
                pluginId = source.pluginId,
                outcome = DevReloadOutcome.RELOAD_FAILED,
                message = "Descriptor pluginId mismatch: expected ${source.pluginId}, actual ${newGeneration.plugin.descriptor.pluginId}"
            )
        }
        val previousKtorScope = PluginReloadCompatibility.KtorScopeSignature(
            applicationPluginKeys = snapshot.pluginApplicationInstallers.map { it.pluginKey },
            servicePluginKeys = snapshot.pluginServiceRouteInstallers.map { it.pluginKey }
        )
        val newKtorScope = PluginReloadCompatibility.KtorScopeSignature(
            applicationPluginKeys = newGeneration.pluginApplicationInstallers.map { it.pluginKey },
            servicePluginKeys = newGeneration.pluginServiceRouteInstallers.map { it.pluginKey }
        )
        val decision = PluginReloadCompatibility.decideReloadCompatibility(
            previousTopology = snapshot.topology,
            newTopology = newGeneration.topology,
            previousKtorScope = previousKtorScope,
            newKtorScope = newKtorScope
        )
        if (decision.outcome != DevReloadOutcome.RELOADED) {
            return ReloadAttemptResult(
                pluginId = source.pluginId,
                outcome = decision.outcome,
                message = decision.message
            )
        }
        return null
    }

    suspend fun performGenerationSwap(
        entry: ManagedPlugin,
        source: PluginDevelopmentSource,
        newGeneration: NewGeneration,
        snapshot: EntrySnapshot,
        normalizeProcessState: (ManagedPlugin) -> Unit,
        reason: String
    ): ReloadAttemptResult {
        stopPluginLocked(entry)
        disposePluginLocked(entry)
        entry.plugin = newGeneration.plugin
        entry.pluginClassName = source.implementationClassName
        entry.routeDefinitions = newGeneration.routeDefinitions
        entry.endpointById = newGeneration.endpointById.toMutableMap()
        entry.endpointTopology = newGeneration.topology
        entry.sseByPath = newGeneration.sseByPath.toMutableMap()
        entry.pluginApplicationInstallers = newGeneration.pluginApplicationInstallers
        entry.pluginServiceRouteInstallers = newGeneration.pluginServiceRouteInstallers
        entry.sourceClassLoader = newGeneration.classLoader
        entry.config = newGeneration.plugin.descriptor.toConfig().copy(runtimeMode = source.runtimeMode)
        entry.generation = snapshot.generation.next()
        normalizeProcessState(entry)
        entry.lastFailure = null
        startPluginLocked(entry)
        withContext(Dispatchers.IO) {
            snapshot.sourceClassLoader?.close()
        }
        return ReloadAttemptResult(
            pluginId = source.pluginId,
            outcome = DevReloadOutcome.RELOADED,
            message = "Reloaded from source ($reason)"
        )
    }

    suspend fun rollbackGenerationSwap(
        entry: ManagedPlugin,
        snapshot: EntrySnapshot,
        normalizeProcessState: (ManagedPlugin) -> Unit
    ) {
        runCatching {
            entry.plugin = snapshot.plugin
            entry.pluginClassName = snapshot.pluginClassName
            entry.routeDefinitions = snapshot.routeDefinitions
            entry.endpointById = snapshot.endpointById.toMutableMap()
            entry.endpointTopology = snapshot.topology
            entry.sseByPath = snapshot.sseByPath.toMutableMap()
            entry.pluginApplicationInstallers = snapshot.pluginApplicationInstallers
            entry.pluginServiceRouteInstallers = snapshot.pluginServiceRouteInstallers
            entry.sourceClassLoader = snapshot.sourceClassLoader
            entry.config = snapshot.config
            entry.generation = snapshot.generation
            normalizeProcessState(entry)
            startPluginLocked(entry)
        }
    }

    private fun buildSourceClassLoader(
        classpathModulePaths: Set<String>,
        source: PluginDevelopmentSource
    ): URLClassLoader {
        val urls = linkedSetOf<java.net.URL>()
        classpathModulePaths.forEach { modulePath ->
            val moduleDir = File(modulePath)
            val classDirs = listOf(
                File(moduleDir, "build/classes/kotlin/main"),
                File(moduleDir, "build/classes/java/main"),
                File(moduleDir, "build/resources/main")
            )
            classDirs.filter { it.exists() }.forEach { urls += it.toURI().toURL() }
        }
        val classpathEntries = currentClasspath.split(File.pathSeparator)
            .map(::File)
            .filter(File::exists)
            .map { it.toURI().toURL() }
        urls += classpathEntries
        return SourceFirstClassLoader(
            urls = urls.toTypedArray(),
            parent = this::class.java.classLoader
        )
    }

    private class SourceFirstClassLoader(
        urls: Array<java.net.URL>,
        parent: ClassLoader
    ) : URLClassLoader(urls, parent) {
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            synchronized(getClassLoadingLock(name)) {
                findLoadedClass(name)?.let { return it }
                if (!isAlwaysParentLoaded(name) && hasChildResource(name)) {
                    runCatching { findClass(name) }.getOrNull()?.let { loaded ->
                        if (resolve) resolveClass(loaded)
                        return loaded
                    }
                }
                return super.loadClass(name, resolve)
            }
        }

        private fun hasChildResource(className: String): Boolean {
            val resource = className.replace('.', '/') + ".class"
            return findResource(resource) != null
        }

        private fun isAlwaysParentLoaded(name: String): Boolean {
            return name.startsWith("java.") ||
                name.startsWith("javax.") ||
                name.startsWith("kotlin.") ||
                name.startsWith("kotlinx.")
        }
    }
}
