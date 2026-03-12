package com.keel.samples.helloworld

import com.keel.kernel.logging.KeelLoggerService
import com.keel.kernel.plugin.KeelPlugin
import com.keel.kernel.plugin.PluginDescriptor
import com.keel.kernel.plugin.PluginEndpointBuilders
import com.keel.kernel.plugin.PluginEndpointBuilders.pluginEndpoints
import com.keel.kernel.plugin.PluginInitContext
import com.keel.kernel.plugin.PluginKtorConfig
import com.keel.kernel.plugin.PluginResult
import com.keel.kernel.plugin.PluginRuntimeContext
import com.keel.openapi.annotations.KeelApiPlugin
import com.keel.openapi.runtime.OpenApiDoc
import io.ktor.server.application.call
import io.ktor.server.application.createRouteScopedPlugin

@KeelApiPlugin("helloworld", "Hello World Plugin")
class HelloWorldPlugin : KeelPlugin {
    private val logger = KeelLoggerService.getLogger("HelloWorldPlugin")

    override val descriptor: PluginDescriptor = PluginDescriptor("helloworld", "1.0.0", "Hello World Plugin")

    override suspend fun onInit(context: PluginInitContext) {
        logger.info("Initialized hello world plugin in ${context.descriptor.defaultRuntimeMode}")
    }

    override fun ktorPlugins(): PluginKtorConfig = PluginKtorConfig().apply {
        service {
            install(sampleServiceScopeHeaderPlugin)
        }
    }

    override fun endpoints() = pluginEndpoints(descriptor.pluginId) {
        get<GreetingData>(doc = OpenApiDoc(summary = "Hello World greeting", tags = listOf("helloworld"))) {
            PluginResult(body = GreetingData("Hello from HelloWorldPlugin!"))
        }

        get<VersionData>("/version", doc = OpenApiDoc(summary = "Get plugin version", tags = listOf("helloworld"))) {
            PluginResult(body = VersionData(descriptor.version))
        }

        get<StatusData>("/status", doc = OpenApiDoc(summary = "Get plugin status", tags = listOf("helloworld"))) {
            PluginResult(body = StatusData("OK"))
        }

        get<StatusData>("/sample", doc = OpenApiDoc(summary = "Get plugin status", tags = listOf("helloworld"))) {
            PluginResult(body = StatusData("False"))
        }
    }

    override suspend fun onStop(context: PluginRuntimeContext) {
        logger.info("Hello world plugin stopped")
    }

    companion object {
        private val sampleServiceScopeHeaderPlugin = createRouteScopedPlugin("helloworld-service-scope") {
            onCall { call ->
                call.response.headers.append("X-HelloWorld-Service-Scope", "enabled")
            }
        }
    }
}
