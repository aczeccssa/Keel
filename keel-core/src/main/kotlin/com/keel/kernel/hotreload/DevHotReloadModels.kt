package com.keel.kernel.hotreload

import com.keel.kernel.plugin.PluginRuntimeMode
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable

/** Source registration used by development hot reload. */
data class PluginDevelopmentSource(
    val pluginId: String,
    val owningModulePath: String,
    val implementationClassName: String,
    val runtimeMode: PluginRuntimeMode = PluginRuntimeMode.IN_PROCESS
)

data class PluginOwnership(
    val pluginId: String,
    val owningModulePath: String,
    val dependentModulePaths: Set<String>
)

@Serializable
enum class DevReloadOutcome {
    RELOADED,
    RELOAD_FAILED,
    RESTART_REQUIRED
}

@Serializable
enum class ChangeScope {
    PLUGIN_SOURCE,
    PLUGIN_RESOURCE,
    KERNEL_SOURCE,
    BUILD_LOGIC,
    UNKNOWN
}

@Serializable
enum class DevReloadStage {
    CHANGE_DETECTED,
    AFFECTED_PLUGINS_RESOLVED,
    BUILD_STARTED,
    BUILD_SUCCEEDED,
    BUILD_FAILED,
    GENERATION_LOAD_STARTED,
    SWITCH_SUCCEEDED,
    SWITCH_FAILED,
    RELOADED,
    RELOAD_FAILED,
    RESTART_REQUIRED
}

@Serializable
data class DevReloadEvent(
    val stage: DevReloadStage,
    val pluginId: String? = null,
    val modulePath: String? = null,
    val outcome: DevReloadOutcome? = null,
    val message: String,
    val timestamp: Long = Clock.System.now().toEpochMilliseconds()
)

data class ReloadAttemptResult(
    val pluginId: String,
    val outcome: DevReloadOutcome,
    val message: String,
    val modulePath: String? = null,
    val buildSummary: String? = null,
    val durationMs: Long = 0
)

data class DevHotReloadStatus(
    val inProgress: Boolean = false,
    val lastEvent: DevReloadEvent? = null,
    val lastFailureSummary: String? = null
)

data class ClassifiedModuleChange(
    val scope: ChangeScope,
    val modulePath: String,
    val restartRequiredReason: String? = null
)

data class DevBuildResult(
    val success: Boolean,
    val summary: String,
    val output: String
)
