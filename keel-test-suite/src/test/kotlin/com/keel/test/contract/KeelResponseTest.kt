package com.keel.test.contract

import com.keel.contract.dto.KeelResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KeelResponseTest {
    @Test
    fun successResponseUsesDefaults() {
        val response = KeelResponse.success(data = "ok")
        assertEquals(200, response.code)
        assertEquals("success", response.message)
        assertEquals("ok", response.data)
        assertTrue(response.timestamp > 0)
    }

    @Test
    fun failureResponseUsesProvidedValues() {
        val response = KeelResponse.failure<String>(404, "Not found")
        assertEquals(404, response.code)
        assertEquals("Not found", response.message)
        assertEquals(null, response.data)
        assertNotNull(response.timestamp)
    }
}
