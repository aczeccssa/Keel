package com.keel.openapi.runtime

import io.ktor.http.HttpMethod
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.reflect.typeOf

class OpenApiAggregatorTest {
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeTest
    fun setUp() {
        OpenApiRegistry.clear()
    }

    @AfterTest
    fun tearDown() {
        OpenApiRegistry.clear()
    }

    @Test
    fun `buildSpec includes registered paths schemas and path parameters`() {
        OpenApiRegistry.register(
            OpenApiOperation(
                method = HttpMethod.Get,
                path = "/api/_system/plugins/{pluginId}",
                responseBodyType = typeOf<TestPayload>(),
                typeBound = true,
                responseEnvelope = true,
                errorStatuses = setOf(404)
            )
        )

        val spec = json.parseToJsonElement(OpenApiAggregator.buildSpec()).jsonObject

        assertTrue(spec["paths"]?.jsonObject?.containsKey("/api/_system/plugins/{pluginId}") == true)
        val getOperation = spec["paths"]!!.jsonObject["/api/_system/plugins/{pluginId}"]!!.jsonObject["get"]!!.jsonObject
        val parameters = getOperation["parameters"]!!.jsonArray
        assertEquals("pluginId", parameters.first().jsonObject["name"]?.jsonPrimitive?.content)
        assertEquals(
            listOf("system", "plugins"),
            getOperation["tags"]!!.jsonArray.map { it.jsonPrimitive.content }
        )

        val tags = spec["tags"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
        assertTrue("system" in tags)
        assertTrue("plugins" in tags)

        val schemas = spec["components"]!!.jsonObject["schemas"]!!.jsonObject
        assertTrue("TestPayload" in schemas)
        assertTrue("KeelResponse_TestPayload" in schemas)
    }

    @Test
    fun `buildSpec is minimal when registry is empty`() {
        val spec = json.parseToJsonElement(OpenApiAggregator.buildSpec()).jsonObject
        val paths = spec["paths"]?.jsonObject
        assertTrue(paths != null && paths.isEmpty())
    }

    @Test
    fun `service loader discovers generated sample fragments`() {
        val pluginIds = OpenApiAggregator.discoverFragments().map { it.pluginId }.toSet()
        assertTrue("helloworld" in pluginIds)
        assertTrue("dbdemo" in pluginIds)
    }

    @Test
    fun `runtime doc metadata merges with typed route schema metadata`() {
        OpenApiRegistry.register(
            OpenApiOperation(
                method = HttpMethod.Get,
                path = "/api/plugins/runtime-doc",
                responseBodyType = typeOf<TestPayload>(),
                typeBound = true,
                summary = "Runtime doc",
                tags = listOf("runtime")
            )
        )

        val spec = json.parseToJsonElement(OpenApiAggregator.buildSpec()).jsonObject
        val getOperation = spec["paths"]!!
            .jsonObject["/api/plugins/runtime-doc"]!!
            .jsonObject["get"]!!
            .jsonObject

        assertEquals("Runtime doc", getOperation["summary"]?.jsonPrimitive?.content)
        assertEquals(listOf("runtime"), getOperation["tags"]?.jsonArray?.map { it.jsonPrimitive.content })

        val schemas = spec["components"]!!.jsonObject["schemas"]!!.jsonObject
        assertTrue("TestPayload" in schemas)
    }

    @Test
    fun `runtime doc metadata controls success status and envelope`() {
        OpenApiRegistry.register(
            OpenApiOperation(
                method = HttpMethod.Get,
                path = "/api/_system/custom",
                responseBodyType = typeOf<TestPayload>(),
                typeBound = true,
                successStatus = 202,
                responseEnvelope = true
            )
        )
        val spec = json.parseToJsonElement(OpenApiAggregator.buildSpec()).jsonObject
        val operation = spec["paths"]!!.jsonObject["/api/_system/custom"]!!.jsonObject["get"]!!.jsonObject
        val responses = operation["responses"]!!.jsonObject
        assertTrue("202" in responses)
    }

    @Test
    fun `typed only route uses default doc metadata`() {
        OpenApiRegistry.register(
            OpenApiOperation(
                method = HttpMethod.Get,
                path = "/typed-only",
                responseBodyType = typeOf<TestPayload>(),
                typeBound = true
            )
        )

        val spec = json.parseToJsonElement(OpenApiAggregator.buildSpec()).jsonObject
        val operation = spec["paths"]!!.jsonObject["/typed-only"]!!.jsonObject["get"]!!.jsonObject
        assertEquals("", operation["summary"]?.jsonPrimitive?.content)
        val schemas = spec["components"]!!.jsonObject["schemas"]!!.jsonObject
        assertTrue("TestPayload" in schemas)
    }

    @Test
    fun `system hotreload route falls back to system and domain tags`() {
        OpenApiRegistry.register(
            OpenApiOperation(
                method = HttpMethod.Get,
                path = "/api/_system/hotreload/status",
                responseBodyType = typeOf<TestPayload>(),
                typeBound = true
            )
        )

        val spec = json.parseToJsonElement(OpenApiAggregator.buildSpec()).jsonObject
        val operation = spec["paths"]!!
            .jsonObject["/api/_system/hotreload/status"]!!
            .jsonObject["get"]!!
            .jsonObject

        assertEquals(
            listOf("system", "hotreload"),
            operation["tags"]!!.jsonArray.map { it.jsonPrimitive.content }
        )
    }

    @Test
    fun `runtime plugin routes fall back to plugin tag when annotation metadata is unavailable`() {
        OpenApiRegistry.register(
            OpenApiOperation(
                method = HttpMethod.Get,
                path = "/api/plugins/observability/runtime-only",
                responseBodyType = typeOf<TestPayload>(),
                typeBound = true
            )
        )

        val spec = json.parseToJsonElement(OpenApiAggregator.buildSpec()).jsonObject
        val operation = spec["paths"]!!.jsonObject["/api/plugins/observability/runtime-only"]!!
            .jsonObject["get"]!!
            .jsonObject

        assertEquals(listOf("observability"), operation["tags"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `buildSpec cache invalidates when runtime topology changes`() {
        val initialSpec = json.parseToJsonElement(OpenApiAggregator.buildSpec()).jsonObject
        assertTrue("/cache-check" !in initialSpec["paths"]!!.jsonObject)

        OpenApiRegistry.register(
            OpenApiOperation(
                method = HttpMethod.Get,
                path = "/cache-check",
                responseBodyType = typeOf<TestPayload>(),
                typeBound = true
            )
        )

        val updatedSpec = json.parseToJsonElement(OpenApiAggregator.buildSpec()).jsonObject
        assertTrue("/cache-check" in updatedSpec["paths"]!!.jsonObject)
    }

    @Test
    fun `buildSpec supports non json content types`() {
        OpenApiRegistry.register(
            OpenApiOperation(
                method = HttpMethod.Get,
                path = "/sse",
                responseContentTypes = listOf("text/event-stream"),
                typeBound = false
            )
        )
        OpenApiRegistry.register(
            OpenApiOperation(
                method = HttpMethod.Get,
                path = "/static",
                responseContentTypes = listOf("application/octet-stream", "text/html"),
                typeBound = false
            )
        )

        val spec = json.parseToJsonElement(OpenApiAggregator.buildSpec()).jsonObject
        val sseContent = spec["paths"]!!.jsonObject["/sse"]!!.jsonObject["get"]!!
            .jsonObject["responses"]!!.jsonObject["200"]!!.jsonObject["content"]!!.jsonObject
        assertTrue("text/event-stream" in sseContent)
        assertTrue("application/json" !in sseContent)
        assertTrue(sseContent["text/event-stream"]!!.jsonObject.isEmpty())

        val staticContent = spec["paths"]!!.jsonObject["/static"]!!.jsonObject["get"]!!
            .jsonObject["responses"]!!.jsonObject["200"]!!.jsonObject["content"]!!.jsonObject
        assertTrue("application/octet-stream" in staticContent)
        assertTrue("text/html" in staticContent)
        assertTrue("application/json" !in staticContent)
    }
}

@kotlinx.serialization.Serializable
private data class TestPayload(
    val value: String
)
