package com.keel.test.kernel

import com.keel.kernel.isolation.parseExternalPluginHostArgs
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ExternalPluginHostMainTest {

    @Test
    fun parsesExplicitConfigPathAndSocketArguments() {
        val parsed = parseExternalPluginHostArgs(
            arrayOf(
                "--plugin-class=com.example.DemoPlugin",
                "--invoke-socket-path=/tmp/demo-invoke.sock",
                "--admin-socket-path=/tmp/demo-admin.sock",
                "--event-socket-path=/tmp/demo-event.sock",
                "--auth-token=token-123",
                "--generation=9",
                "--config-path=/tmp/demo-plugin.json"
            )
        )

        assertEquals("com.example.DemoPlugin", parsed.pluginClass)
        assertEquals(Path.of("/tmp/demo-invoke.sock"), parsed.invokeSocketPath)
        assertEquals(Path.of("/tmp/demo-admin.sock"), parsed.adminSocketPath)
        assertEquals(Path.of("/tmp/demo-event.sock"), parsed.eventSocketPath)
        assertEquals("token-123", parsed.authToken)
        assertEquals(9L, parsed.generation)
        assertNotNull(parsed.configPath)
        assertEquals("/tmp/demo-plugin.json", parsed.configPath!!.path)
    }
}
