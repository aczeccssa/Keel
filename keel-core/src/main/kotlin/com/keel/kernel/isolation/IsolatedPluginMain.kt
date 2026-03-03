package com.keel.kernel.isolation

import com.keel.ipc.runtime.HealthRequest
import com.keel.ipc.runtime.HealthResponse
import com.keel.ipc.runtime.InvokeRequest
import com.keel.ipc.runtime.InvokeResponse
import com.keel.ipc.runtime.PluginFrameCodec
import com.keel.ipc.runtime.PluginIpcJson
import com.keel.ipc.runtime.ShutdownRequest
import com.keel.ipc.runtime.ShutdownResponse
import com.keel.kernel.logging.KeelLoggerService
import com.keel.kernel.plugin.KeelPluginV2
import com.keel.kernel.plugin.PluginApiException
import com.keel.kernel.plugin.PluginProcessState
import com.keel.kernel.plugin.PluginRequestContext
import com.keel.kernel.plugin.PluginRuntimeConfig
import com.keel.kernel.plugin.PluginRuntimeConfigLoader
import com.keel.kernel.plugin.encodeResponseBody
import com.keel.kernel.plugin.serializer
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists

object IsolatedPluginMain {
    private val logger = KeelLoggerService.getLogger("IsolatedPluginMain")

    @JvmStatic
    fun main(args: Array<String>) {
        val parsedArgs = args.associate {
            val parts = it.removePrefix("--").split("=", limit = 2)
            parts[0] to parts.getOrElse(1) { "" }
        }
        val pluginClass = requireNotNull(parsedArgs["plugin-class"]) { "Missing --plugin-class" }
        val socketPath = Path.of(requireNotNull(parsedArgs["socket-path"]) { "Missing --socket-path" })
        val authToken = requireNotNull(parsedArgs["auth-token"]) { "Missing --auth-token" }

        val plugin = instantiatePlugin(pluginClass)
        val config = PluginRuntimeConfigLoader.load(plugin.descriptor)
        val startedAt = System.currentTimeMillis()
        val endpoints = plugin.endpoints().associateBy { it.endpointId }
        var running = true

        kotlinx.coroutines.runBlocking {
            plugin.onInit(object : com.keel.kernel.plugin.PluginInitContextV2 {
                override val pluginId: String = plugin.descriptor.pluginId
                override val config: PluginRuntimeConfig = config
            })
            plugin.onInstall(object : com.keel.kernel.plugin.PluginScopeV2 {
                override val pluginId: String = plugin.descriptor.pluginId
            })
        }

        socketPath.parent?.let { Files.createDirectories(it) }
        socketPath.deleteIfExists()
        ServerSocketChannel.open(StandardProtocolFamily.UNIX).use { server ->
            server.bind(UnixDomainSocketAddress.of(socketPath))
            logger.info("Isolated plugin ${plugin.descriptor.pluginId} listening on $socketPath")
            while (running) {
                server.accept().use { channel ->
                    val payload = PluginFrameCodec.read(channel)
                    val json = PluginIpcJson.instance.parseToJsonElement(payload).jsonObject
                    val kind = json["kind"]?.jsonPrimitive?.content.orEmpty()
                    val response = when (kind) {
                        "invoke" -> handleInvoke(payload, authToken, endpoints)
                        "health" -> handleHealth(payload, authToken, plugin.descriptor.pluginId, startedAt)
                        "shutdown" -> {
                            running = false
                            handleShutdown(payload, authToken)
                        }
                        else -> PluginIpcJson.instance.encodeToString(
                            InvokeResponse.serializer(),
                            InvokeResponse(requestId = "unknown", status = 400, errorMessage = "Unsupported IPC message type: $kind")
                        )
                    }
                    PluginFrameCodec.write(channel, response)
                }
            }
        }
        socketPath.deleteIfExists()
        kotlinx.coroutines.runBlocking { plugin.onStop() }
    }

    private fun handleInvoke(
        payload: String,
        authToken: String,
        endpoints: Map<String, com.keel.kernel.plugin.PluginEndpointDefinition<*, *>>
    ): String {
        val request = PluginIpcJson.instance.decodeFromString(InvokeRequest.serializer(), payload)
        if (request.authToken != authToken) {
            return PluginIpcJson.instance.encodeToString(
                InvokeResponse.serializer(),
                InvokeResponse(requestId = request.requestId, status = 403, errorMessage = "Invalid auth token")
            )
        }
        val endpoint = endpoints[request.endpointId]
            ?: return PluginIpcJson.instance.encodeToString(
                InvokeResponse.serializer(),
                InvokeResponse(requestId = request.requestId, status = 404, errorMessage = "Endpoint not found")
            )

        return try {
            val bodyJson = request.bodyJson
            val requestBody = if (endpoint.requestType != null && !bodyJson.isNullOrBlank()) {
                PluginIpcJson.instance.decodeFromString(serializer(endpoint.requestType), bodyJson)
            } else {
                null
            }
            val context = object : PluginRequestContext {
                override val pluginId: String = request.rawPath.substringAfter("/api/plugins/").substringBefore('/')
                override val method: String = request.method
                override val rawPath: String = request.rawPath
                override val pathParameters: Map<String, String> = request.pathParameters
                override val queryParameters: Map<String, List<String>> = request.queryParameters
                override val requestHeaders: Map<String, List<String>> = request.headers
                override val requestId: String = request.requestId
            }
            val result = kotlinx.coroutines.runBlocking { endpoint.execute(context, requestBody) }
            PluginIpcJson.instance.encodeToString(
                InvokeResponse.serializer(),
                InvokeResponse(
                    requestId = request.requestId,
                    status = result.status,
                    headers = result.headers,
                    bodyJson = encodeResponseBody(result.body, endpoint.responseType)
                )
            )
        } catch (error: PluginApiException) {
            PluginIpcJson.instance.encodeToString(
                InvokeResponse.serializer(),
                InvokeResponse(requestId = request.requestId, status = error.status, errorMessage = error.message)
            )
        } catch (error: Exception) {
            logger.error("Isolated plugin endpoint failed endpoint=${request.endpointId}", error)
            PluginIpcJson.instance.encodeToString(
                InvokeResponse.serializer(),
                InvokeResponse(requestId = request.requestId, status = 500, errorMessage = error.message ?: "Internal server error")
            )
        }
    }

    private fun handleHealth(payload: String, authToken: String, pluginId: String, startedAt: Long): String {
        val request = PluginIpcJson.instance.decodeFromString(HealthRequest.serializer(), payload)
        if (request.authToken != authToken) {
            return PluginIpcJson.instance.encodeToString(
                HealthResponse.serializer(),
                HealthResponse(pluginId = pluginId, state = PluginProcessState.FAILED.name, startedAtEpochMs = startedAt)
            )
        }
        return PluginIpcJson.instance.encodeToString(
            HealthResponse.serializer(),
            HealthResponse(pluginId = pluginId, state = PluginProcessState.RUNNING.name, startedAtEpochMs = startedAt)
        )
    }

    private fun handleShutdown(payload: String, authToken: String): String {
        val request = PluginIpcJson.instance.decodeFromString(ShutdownRequest.serializer(), payload)
        return PluginIpcJson.instance.encodeToString(
            ShutdownResponse.serializer(),
            ShutdownResponse(accepted = request.authToken == authToken)
        )
    }

    private fun instantiatePlugin(pluginClass: String): KeelPluginV2 {
        val instance = Class.forName(pluginClass).getDeclaredConstructor().newInstance()
        return instance as? KeelPluginV2
            ?: error("Class $pluginClass does not implement KeelPluginV2")
    }
}
