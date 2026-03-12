package com.keel.openapi.runtime

import com.keel.openapi.annotations.KeelApiField
import com.keel.openapi.annotations.KeelApiSchema
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.reflect.KClass
import kotlin.reflect.KType
import kotlin.reflect.KTypeProjection
import kotlin.reflect.full.createType

class OpenApiSchemaGenerator {
    private val components = linkedMapOf<String, JsonElement>()
    private val inProgress = mutableSetOf<String>()

    fun schemaForType(type: KType): JsonElement {
        return schemaForTypeInternal(type)
    }

    fun schemaForResponse(type: KType?, wrapped: Boolean): JsonElement {
        if (!wrapped) {
            return type?.let(::schemaForTypeInternal) ?: nullableObjectSchema()
        }

        val payloadSuffix = type?.let(::schemaSuffixForType) ?: "Unit"
        val schemaName = "KeelResponse_$payloadSuffix"
        if (!components.containsKey(schemaName)) {
            if (!inProgress.add(schemaName)) {
                return ref(schemaName)
            }
            components[schemaName] = JsonObject(emptyMap())
            val payloadSchema = type?.let(::schemaForTypeInternal) ?: nullableObjectSchema()
            components[schemaName] = JsonObject(
                buildMap {
                    put("type", JsonPrimitive("object"))
                    put(
                        "properties",
                        JsonObject(
                            mapOf(
                                "code" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("integer"),
                                        "format" to JsonPrimitive("int32")
                                    )
                                ),
                                "message" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("string"),
                                        "nullable" to JsonPrimitive(true)
                                    )
                                ),
                                "data" to payloadSchema,
                                "timestamp" to JsonObject(
                                    mapOf(
                                        "type" to JsonPrimitive("integer"),
                                        "format" to JsonPrimitive("int64")
                                    )
                                )
                            )
                        )
                    )
                    put("required", JsonArray(listOf(JsonPrimitive("code"), JsonPrimitive("timestamp"))))
                }
            )
            inProgress.remove(schemaName)
        }

        return ref(schemaName)
    }

    fun components(): Map<String, JsonElement> = components

    private fun schemaForTypeInternal(type: KType): JsonElement {
        val classifier = type.classifier as? KClass<*> ?: return nullableObjectSchema()
        val inlineSchema = inlineSchemaFor(type, classifier)
        if (inlineSchema != null) {
            return inlineSchema
        }

        val descriptor = serializerFor(type).descriptor
        val schemaName = schemaNameFor(type, descriptor)
        if (!components.containsKey(schemaName)) {
            if (!inProgress.add(schemaName)) {
                return ref(schemaName)
            }
            components[schemaName] = JsonObject(emptyMap())
            components[schemaName] = buildComponentSchema(type, descriptor)
            inProgress.remove(schemaName)
        }
        return ref(schemaName)
    }

    private fun inlineSchemaFor(type: KType, classifier: KClass<*>): JsonElement? {
        val primitiveSchema = primitiveSchemaFor(classifier)
        if (primitiveSchema != null) {
            return applyNullability(primitiveSchema, type.isMarkedNullable)
        }

        if (classifier == List::class || classifier == MutableList::class) {
            val itemType = type.arguments.firstOrNull()?.type ?: return nullableObjectSchema()
            return JsonObject(
                buildMap {
                    put("type", JsonPrimitive("array"))
                    put("items", schemaForTypeInternal(itemType))
                    if (type.isMarkedNullable) {
                        put("nullable", JsonPrimitive(true))
                    }
                }
            )
        }

        return null
    }

    private fun buildComponentSchema(type: KType, descriptor: SerialDescriptor): JsonElement {
        val classifier = type.classifier as? KClass<*> ?: return nullableObjectSchema()
        if (classifier.java.isEnum) {
            return buildEnumSchema(type, descriptor)
        }

        val schemaAnnotation = descriptor.annotations.filterIsInstance<KeelApiSchema>().firstOrNull()
        val properties = linkedMapOf<String, JsonElement>()
        val required = mutableListOf<JsonElement>()

        for (index in 0 until descriptor.elementsCount) {
            val name = descriptor.getElementName(index)
            val fieldSchema = buildFieldSchema(type, descriptor, index)
            properties[name] = fieldSchema
            if (!descriptor.isElementOptional(index) && !descriptor.getElementDescriptor(index).isNullable) {
                required += JsonPrimitive(name)
            }
        }

        return JsonObject(
            buildMap {
                put("type", JsonPrimitive("object"))
                if (!schemaAnnotation?.description.isNullOrBlank()) {
                    put("description", JsonPrimitive(schemaAnnotation.description))
                }
                put("properties", JsonObject(properties))
                if (required.isNotEmpty()) {
                    put("required", JsonArray(required))
                }
                if (type.isMarkedNullable) {
                    put("nullable", JsonPrimitive(true))
                }
            }
        )
    }

    private fun buildEnumSchema(type: KType, descriptor: SerialDescriptor): JsonElement {
        val schemaAnnotation = descriptor.annotations.filterIsInstance<KeelApiSchema>().firstOrNull()
        return JsonObject(
            buildMap {
                put("type", JsonPrimitive("string"))
                put(
                    "enum",
                    JsonArray((0 until descriptor.elementsCount).map { JsonPrimitive(descriptor.getElementName(it)) })
                )
                if (!schemaAnnotation?.description.isNullOrBlank()) {
                    put("description", JsonPrimitive(schemaAnnotation.description))
                }
                if (type.isMarkedNullable) {
                    put("nullable", JsonPrimitive(true))
                }
            }
        )
    }

    private fun buildFieldSchema(parentType: KType, descriptor: SerialDescriptor, index: Int): JsonElement {
        val fieldType = propertyType(parentType, descriptor.getElementName(index))
        val baseSchema = if (fieldType != null) {
            schemaForTypeInternal(fieldType)
        } else {
            schemaFromDescriptor(descriptor.getElementDescriptor(index))
        }
        val fieldAnnotation = descriptor.getElementAnnotations(index).filterIsInstance<KeelApiField>().firstOrNull()
        return mergeSchema(baseSchema, fieldAnnotation)
    }

    private fun mergeSchema(base: JsonElement, fieldAnnotation: KeelApiField?): JsonElement {
        if (fieldAnnotation == null) {
            return base
        }
        val baseObject = base as? JsonObject ?: return base
        return JsonObject(
            buildMap {
                putAll(baseObject)
                if (fieldAnnotation.description.isNotBlank()) {
                    put("description", JsonPrimitive(fieldAnnotation.description))
                }
                if (fieldAnnotation.example.isNotBlank()) {
                    put("example", JsonPrimitive(fieldAnnotation.example))
                }
                if (fieldAnnotation.format.isNotBlank()) {
                    put("format", JsonPrimitive(fieldAnnotation.format))
                }
            }
        )
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun schemaFromDescriptor(descriptor: SerialDescriptor): JsonElement {
        return when (descriptor.kind) {
            PrimitiveKind.STRING -> JsonObject(mapOf("type" to JsonPrimitive("string")))
            PrimitiveKind.INT -> JsonObject(mapOf("type" to JsonPrimitive("integer"), "format" to JsonPrimitive("int32")))
            PrimitiveKind.LONG -> JsonObject(mapOf("type" to JsonPrimitive("integer"), "format" to JsonPrimitive("int64")))
            PrimitiveKind.BOOLEAN -> JsonObject(mapOf("type" to JsonPrimitive("boolean")))
            PrimitiveKind.FLOAT -> JsonObject(mapOf("type" to JsonPrimitive("number"), "format" to JsonPrimitive("float")))
            PrimitiveKind.DOUBLE -> JsonObject(mapOf("type" to JsonPrimitive("number"), "format" to JsonPrimitive("double")))
            PrimitiveKind.BYTE, PrimitiveKind.SHORT -> JsonObject(mapOf("type" to JsonPrimitive("integer")))
            PrimitiveKind.CHAR -> JsonObject(mapOf("type" to JsonPrimitive("string")))
            StructureKind.LIST -> {
                val items = schemaFromDescriptor(descriptor.getElementDescriptor(0))
                JsonObject(mapOf("type" to JsonPrimitive("array"), "items" to items))
            }
            StructureKind.MAP -> JsonObject(
                mapOf(
                    "type" to JsonPrimitive("object"),
                    "additionalProperties" to schemaFromDescriptor(descriptor.getElementDescriptor(1))
                )
            )
            StructureKind.CLASS, StructureKind.OBJECT -> JsonObject(mapOf("type" to JsonPrimitive("object")))
            SerialKind.ENUM -> JsonObject(
                mapOf(
                    "type" to JsonPrimitive("string"),
                    "enum" to JsonArray((0 until descriptor.elementsCount).map { JsonPrimitive(descriptor.getElementName(it)) })
                )
            )
            else -> JsonObject(mapOf("type" to JsonPrimitive("object")))
        }
    }

    private fun primitiveSchemaFor(classifier: KClass<*>): JsonObject? {
        return when (classifier) {
            String::class, Char::class -> JsonObject(mapOf("type" to JsonPrimitive("string")))
            Int::class, Short::class, Byte::class -> JsonObject(
                mapOf("type" to JsonPrimitive("integer"), "format" to JsonPrimitive("int32"))
            )
            Long::class -> JsonObject(mapOf("type" to JsonPrimitive("integer"), "format" to JsonPrimitive("int64")))
            Float::class -> JsonObject(mapOf("type" to JsonPrimitive("number"), "format" to JsonPrimitive("float")))
            Double::class -> JsonObject(mapOf("type" to JsonPrimitive("number"), "format" to JsonPrimitive("double")))
            Boolean::class -> JsonObject(mapOf("type" to JsonPrimitive("boolean")))
            else -> null
        }
    }

    private fun applyNullability(schema: JsonObject, nullable: Boolean): JsonObject {
        if (!nullable) {
            return schema
        }
        return JsonObject(schema + ("nullable" to JsonPrimitive(true)))
    }

    private fun nullableObjectSchema(): JsonElement {
        return JsonObject(mapOf("nullable" to JsonPrimitive(true)))
    }

    private fun ref(name: String): JsonObject =
        JsonObject(mapOf($$"$ref" to JsonPrimitive($$"#/components/schemas/$$name")))

    private fun schemaNameFor(type: KType, descriptor: SerialDescriptor): String {
        val schemaAnnotation = descriptor.annotations.filterIsInstance<KeelApiSchema>().firstOrNull()
        if (schemaAnnotation != null && schemaAnnotation.name.isNotBlank()) {
            return schemaAnnotation.name
        }
        return schemaSuffixForType(type)
    }

    private fun schemaSuffixForType(type: KType): String {
        val classifier = type.classifier as? KClass<*> ?: return "Anonymous"
        val baseName = classifier.simpleName ?: "Anonymous"
        val args = type.arguments.mapNotNull { it.type }.map(::schemaSuffixForType)
        return if (args.isEmpty()) {
            baseName
        } else {
            (listOf(baseName) + args).joinToString("_")
        }
    }

    private fun propertyType(parentType: KType, propertyName: String): KType? {
        val classifier = parentType.classifier as? KClass<*> ?: return null
        val property = classifier.java.declaredFields.firstOrNull { it.name == propertyName } ?: return null
        return property.genericType.kotlinType()
    }

    private fun serializerFor(type: KType) = try {
        serializer(type)
    } catch (exception: SerializationException) {
        throw IllegalArgumentException("Type $type is not serializable and cannot be documented", exception)
    }
}

private fun java.lang.reflect.Type.kotlinType(): KType? {
    return when (this) {
        is Class<*> -> this.kotlin.createType(nullable = false)
        is java.lang.reflect.ParameterizedType -> {
            val rawType = rawType as? Class<*> ?: return null
            rawType.kotlin.createType(
                arguments = actualTypeArguments.map { argument ->
                    val argumentType = argument.kotlinType() ?: return null
                    KTypeProjection.invariant(argumentType)
                }
            )
        }
        else -> null
    }
}
