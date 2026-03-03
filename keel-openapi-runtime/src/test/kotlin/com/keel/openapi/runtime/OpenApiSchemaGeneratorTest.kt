package com.keel.openapi.runtime

import com.keel.openapi.annotations.KeelApiField
import com.keel.openapi.annotations.KeelApiSchema
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.reflect.typeOf

class OpenApiSchemaGeneratorTest {

    @Test
    fun `generates annotated nested schemas`() {
        val generator = OpenApiSchemaGenerator()

        generator.schemaForType(typeOf<AnnotatedRequest>())
        val components = generator.components()

        val requestSchema = components["AnnotatedRequest"]?.jsonObject
        assertNotNull(requestSchema)
        assertEquals("object", requestSchema["type"]?.jsonPrimitive?.content)
        assertEquals("Annotated request payload", requestSchema["description"]?.jsonPrimitive?.content)

        val properties = requestSchema["properties"]?.jsonObject
        assertNotNull(properties)

        val emailSchema = properties["email"]?.jsonObject
        assertNotNull(emailSchema)
        assertEquals("Customer email", emailSchema["description"]?.jsonPrimitive?.content)
        assertEquals("test@example.com", emailSchema["example"]?.jsonPrimitive?.content)
        assertEquals("email", emailSchema["format"]?.jsonPrimitive?.content)

        val tagsSchema = properties["tags"]?.jsonObject
        assertNotNull(tagsSchema)
        assertEquals("array", tagsSchema["type"]?.jsonPrimitive?.content)

        val nestedSchema = properties["detail"]?.jsonObject
        assertNotNull(nestedSchema)
        assertEquals("#/components/schemas/AnnotatedDetail", nestedSchema["\$ref"]?.jsonPrimitive?.content)

        val detailSchema = components["AnnotatedDetail"]?.jsonObject
        assertNotNull(detailSchema)
        val detailProperties = detailSchema["properties"]?.jsonObject
        assertNotNull(detailProperties)
        assertEquals("Nested count", detailProperties["count"]?.jsonObject?.get("description")?.jsonPrimitive?.content)
    }

    @Test
    fun `generates enum and wrapped response schemas`() {
        val generator = OpenApiSchemaGenerator()

        val responseSchema = generator.schemaForResponse(typeOf<EnumPayload>(), wrapped = true).jsonObject
        assertEquals("#/components/schemas/KeelResponse_EnumPayload", responseSchema["\$ref"]?.jsonPrimitive?.content)

        val components = generator.components()
        val enumSchema = components["StatusKind"]?.jsonObject
        assertNotNull(enumSchema)
        assertEquals(listOf("READY", "DONE"), enumSchema["enum"]?.jsonArray?.map { it.jsonPrimitive.content })

        val wrappedSchema = components["KeelResponse_EnumPayload"]?.jsonObject
        assertNotNull(wrappedSchema)
        val wrappedProperties = wrappedSchema["properties"]?.jsonObject
        assertNotNull(wrappedProperties)
        val dataSchema = wrappedProperties["data"]?.jsonObject
        assertNotNull(dataSchema)
        assertEquals("#/components/schemas/EnumPayload", dataSchema["\$ref"]?.jsonPrimitive?.content)
    }
}

@Serializable
@KeelApiSchema(description = "Annotated request payload")
private data class AnnotatedRequest(
    @KeelApiField(description = "Customer email", example = "test@example.com", format = "email")
    val email: String,
    @KeelApiField(description = "Optional tags", example = "[]")
    val tags: List<String>,
    val detail: AnnotatedDetail?
)

@Serializable
@KeelApiSchema(name = "AnnotatedDetail", description = "Nested detail object")
private data class AnnotatedDetail(
    @KeelApiField(description = "Nested count", example = "3")
    val count: Int
)

@Serializable
private data class EnumPayload(
    val status: StatusKind
)

@Serializable
private enum class StatusKind {
    READY,
    DONE
}
