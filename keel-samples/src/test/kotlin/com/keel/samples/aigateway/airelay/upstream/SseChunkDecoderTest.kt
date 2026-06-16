package com.keel.samples.aigateway.airelay.upstream

import com.keel.samples.aigateway.airelay.SseChunkDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SseChunkDecoderTest {
    @Test
    fun `emits event only after full block arrives across chunk boundaries`() {
        val decoder = SseChunkDecoder()

        assertNull(decoder.append("event: response.output_text.delta\ndata: {\"delta\":\"hel"))

        val event = decoder.append("lo\"}\n\n")

        assertNotNull(event)
        assertEquals("response.output_text.delta", event.event)
        assertEquals("{\"delta\":\"hello\"}", event.data)
    }

    @Test
    fun `preserves multi line data fields`() {
        val decoder = SseChunkDecoder()

        val event = decoder.append("data: first line\ndata: second line\n\n")

        assertEquals("first line\nsecond line", event?.data)
    }
}
