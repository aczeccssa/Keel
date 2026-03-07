package com.keel.jvm.runtime

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PluginJvmFrameCodecTest {

    @Test
    fun roundTripsPayloadThroughStreamCodec() {
        val output = ByteArrayOutputStream()
        val payload = """{"kind":"health-request","pluginId":"demo"}"""

        PluginJvmFrameCodec.write(output, payload)

        val decoded = PluginJvmFrameCodec.read(ByteArrayInputStream(output.toByteArray()))
        assertEquals(payload, decoded)
    }

    @Test
    fun rejectsOversizedFrames() {
        val output = ByteArrayOutputStream()
        val oversized = "x".repeat(PluginJvmLimits.MAX_FRAME_BYTES + 1)

        assertFailsWith<IllegalArgumentException> {
            PluginJvmFrameCodec.write(output, oversized)
        }
    }
}
