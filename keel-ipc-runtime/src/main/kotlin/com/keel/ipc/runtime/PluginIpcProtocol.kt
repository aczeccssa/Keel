package com.keel.ipc.runtime

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

sealed interface PluginIpcMessage

@Serializable
data class InvokeRequest(
    val kind: String = "invoke",
    val authToken: String,
    val requestId: String,
    val endpointId: String,
    val method: String,
    val rawPath: String,
    val pathParameters: Map<String, String>,
    val queryParameters: Map<String, List<String>>,
    val headers: Map<String, List<String>>,
    val bodyJson: String?
) : PluginIpcMessage

@Serializable
data class InvokeResponse(
    val requestId: String,
    val status: Int,
    val headers: Map<String, List<String>> = emptyMap(),
    val bodyJson: String? = null,
    val errorMessage: String? = null
) : PluginIpcMessage

@Serializable
data class HealthRequest(
    val kind: String = "health",
    val authToken: String
) : PluginIpcMessage

@Serializable
data class HealthResponse(
    val pluginId: String,
    val state: String,
    val startedAtEpochMs: Long
) : PluginIpcMessage

@Serializable
data class ShutdownRequest(
    val kind: String = "shutdown",
    val authToken: String
) : PluginIpcMessage

@Serializable
data class ShutdownResponse(
    val accepted: Boolean
) : PluginIpcMessage

object PluginIpcJson {
    val instance: Json = Json {
        ignoreUnknownKeys = true
        prettyPrint = false
        encodeDefaults = true
    }
}
