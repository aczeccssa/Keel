package com.keel.uds.runtime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PluginUdsProtocolTest {

    @Test
    fun handshakeResponseSerializesWithRequiredMetadata() {
        val response = HandshakeResponse(
            pluginId = "demo",
            generation = 7,
            timestamp = 1234L,
            messageId = "msg-1",
            correlationId = "req-1",
            descriptorVersion = "1.2.3",
            runtimeMode = "EXTERNAL_JVM",
            endpointInventory = listOf(
                PluginEndpointInventoryItem(
                    endpointId = "demo:GET:/",
                    method = "GET",
                    path = "/"
                )
            ),
            accepted = true
        )

        val encoded = PluginUdsJson.instance.encodeToString(HandshakeResponse.serializer(), response)
        val decoded = PluginUdsJson.instance.decodeFromString(HandshakeResponse.serializer(), encoded)

        assertEquals("handshake-response", decoded.kind)
        assertEquals(PLUGIN_UDS_PROTOCOL_VERSION, decoded.protocolVersion)
        assertEquals("demo", decoded.pluginId)
        assertEquals(7, decoded.generation)
        assertEquals("req-1", decoded.correlationId)
        assertTrue(decoded.endpointInventory.isNotEmpty())
    }
}
