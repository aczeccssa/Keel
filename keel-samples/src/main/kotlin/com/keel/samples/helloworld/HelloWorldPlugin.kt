package com.keel.samples.helloworld

import com.keel.kernel.api.KeelApi
import com.keel.kernel.logging.KeelLoggerService
import com.keel.kernel.plugin.KeelPlugin
import com.keel.kernel.plugin.PluginDescriptor
import com.keel.kernel.plugin.PluginEndpointBuilders
import com.keel.kernel.plugin.PluginEndpointBuilders.pluginEndpoints
import com.keel.kernel.plugin.PluginInitContext
import com.keel.kernel.plugin.PluginResult
import com.keel.kernel.plugin.PluginRuntimeContext
import com.keel.openapi.annotations.KeelApiPlugin

@KeelApiPlugin("helloworld", "Hello World Plugin")
class HelloWorldPlugin : KeelPlugin {
    private val logger = KeelLoggerService.getLogger("HelloWorldPlugin")

    override val descriptor: PluginDescriptor = PluginDescriptor("helloworld", "1.0.0", "Hello World Plugin")

    override suspend fun onInit(context: PluginInitContext) {
        logger.info("Initialized hello world plugin in ${context.descriptor.defaultRuntimeMode}")
    }

    override fun endpoints() = pluginEndpoints(descriptor.pluginId) {
        @KeelApi("Hello World greeting", tags = ["helloworld"])
        get<GreetingData> {
            PluginResult(body = GreetingData("Hello from HelloWorldPlugin!"))
        }

        @KeelApi("Get plugin version", tags = ["helloworld"])
        get<VersionData>("/version") {
            PluginResult(body = VersionData(descriptor.version))
        }

        @KeelApi("Get plugin status", tags = ["helloworld"])
        get<StatusData>("/status") {
            PluginResult(body = StatusData("OK"))
        }

        @KeelApi("Get plugin status", tags = ["helloworld"])
        get<StatusData>("/sample") {
            PluginResult(body = StatusData("False"))
        }
    }

    override suspend fun onStop(context: PluginRuntimeContext) {
        logger.info("Hello world plugin stopped")
    }
}
