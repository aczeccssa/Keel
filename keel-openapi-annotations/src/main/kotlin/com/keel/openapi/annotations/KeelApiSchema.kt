package com.keel.openapi.annotations

import kotlinx.serialization.SerialInfo
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * Optional annotation for DTO/data classes to enrich the generated OpenAPI schema.
 * When placed on a @Serializable class, the KSP processor includes
 * the custom name and description in the schema definition.
 *
 * @param name Override the schema name (defaults to the class simple name)
 * @param description Human-readable description of this schema
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@OptIn(ExperimentalSerializationApi::class)
@SerialInfo
annotation class KeelApiSchema(
    val name: String = "",
    val description: String = ""
)
