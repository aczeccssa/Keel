package com.keel.kernel.plugin

import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import kotlin.reflect.typeOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PluginEndpointRuntimeTest {
    @Test
    fun `encodeResponseBody returns raw string for Any response type`() {
        val encoded = encodeResponseBody("""{"ok":true}""", typeOf<Any>())

        assertEquals("""{"ok":true}""", encoded)
    }

    @Test
    fun `encodeResponseBody skips serialization for outgoing content`() {
        val encoded = encodeResponseBody(TextContent("data: ok\n\n", ContentType.Text.EventStream), typeOf<Any>())

        assertNull(encoded)
    }
}
