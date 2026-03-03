package com.keel.kernel.routing

import com.keel.contract.dto.KeelResponse
import com.keel.kernel.plugin.PluginAvailability
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.path
import io.ktor.server.response.respond
import com.keel.kernel.logging.KeelLoggerService

/**
 * Gateway Interceptor that checks plugin status before routing requests.
 * This implements the "logic switch over physical removal" pattern.
 */
class GatewayInterceptor(
    private val pluginManager: PluginAvailability
) {
    private val logger = KeelLoggerService.getLogger("GatewayInterceptor")

    companion object {
        /** Pre-compiled regex to extract pluginId from path */
        private val PLUGIN_PATH_REGEX = """^/api/plugins/([^/]+)""".toRegex()
    }

    /**
     * Intercept the call and check if the target plugin is enabled.
     * @return true if the call was blocked (plugin disabled), false otherwise.
     */
    suspend fun intercept(call: ApplicationCall): Boolean {
        val path = call.request.path()
        val pluginId = extractPluginId(path)

        if (pluginId != null && !pluginManager.isPluginEnabled(pluginId)) {
            logger.warn("Plugin $pluginId is not enabled, rejecting request to $path")
            call.respond(
                HttpStatusCode.ServiceUnavailable,
                KeelResponse.failure<Unit>(503, "Plugin '$pluginId' is currently unavailable")
            )
            return true
        }
        return false
    }

    private fun extractPluginId(path: String): String? {
        val match = PLUGIN_PATH_REGEX.find(path)
        return match?.groupValues?.get(1)
    }
}
