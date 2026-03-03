package com.keel.samples.helloworld

import com.keel.kernel.api.KeelApi
import com.keel.kernel.logging.KeelLoggerService
import com.keel.kernel.plugin.KeelPluginV2
import com.keel.kernel.plugin.PluginDescriptor
import com.keel.kernel.plugin.PluginResult
import com.keel.kernel.plugin.pluginEndpoints
import com.keel.openapi.annotations.KeelApiPlugin

@KeelApiPlugin("helloworld", "Hello World Plugin")
class HelloWorldPlugin : KeelPluginV2 {
    private val logger = KeelLoggerService.getLogger("HelloWorldPlugin")

    override val descriptor: PluginDescriptor = PluginDescriptor("helloworld", "1.0.0", "Hello World Plugin")

    override suspend fun onInit(context: com.keel.kernel.plugin.PluginInitContextV2) {
        logger.info("Initialized hello world plugin in ${context.config.executionMode}")
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
    }

    override suspend fun onStop() {
        logger.info("Hello world plugin stopped")
    }
}
