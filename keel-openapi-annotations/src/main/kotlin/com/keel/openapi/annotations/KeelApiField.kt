package com.keel.openapi.annotations

import kotlinx.serialization.SerialInfo
import kotlinx.serialization.ExperimentalSerializationApi

/**
 * Optional annotation for properties in DTO classes to enrich the generated OpenAPI schema.
 * Provides additional metadata such as descriptions, examples, and format hints.
 *
 * @param description Human-readable description of this field
 * @param example Example value as a string
 * @param format OpenAPI format hint (e.g., "email", "uri", "date-time")
 */
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
@OptIn(ExperimentalSerializationApi::class)
@SerialInfo
annotation class KeelApiField(
    val description: String = "",
    val example: String = "",
    val format: String = ""
)
