package com.keel.test.kernel

import com.keel.kernel.plugin.PluginEndpointBuilders
import com.keel.kernel.plugin.PluginEndpointDefinition
import com.keel.kernel.plugin.PluginResult
import kotlin.test.Test
import kotlin.test.assertEquals

class PluginEndpointDslPathJoinTest {

    @Test
    fun preservesRootPathWhenJoiningRouteAndEndpointPaths() {
        val routes = PluginEndpointBuilders.pluginEndpoints("path-test") {
            route("/") {
                get<String>("/") {
                    PluginResult(body = "ok")
                }
            }
        }

        val endpoint = routes.filterIsInstance<PluginEndpointDefinition<*, *>>().single()
        assertEquals("/", endpoint.path)
        assertEquals("path-test:GET:/", endpoint.endpointId)
    }
}
