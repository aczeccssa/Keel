package com.keel.uds.runtime

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PluginUdsFrameCodecTest {

    @Test
    fun roundTripsPayloadThroughStreamCodec() {
        val output = ByteArrayOutputStream()
        val payload = """{"kind":"health-request","pluginId":"demo"}"""

        PluginUdsFrameCodec.write(output, payload)

        val decoded = PluginUdsFrameCodec.read(ByteArrayInputStream(output.toByteArray()))
        assertEquals(payload, decoded)
    }

    @Test
    fun rejectsOversizedFrames() {
        val output = ByteArrayOutputStream()
        val oversized = "x".repeat(PluginUdsLimits.MAX_FRAME_BYTES + 1)

        assertFailsWith<IllegalArgumentException> {
            PluginUdsFrameCodec.write(output, oversized)
        }
    }
}
