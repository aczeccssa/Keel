package com.keel.samples.helloworld

import com.keel.openapi.annotations.KeelApiField
import com.keel.openapi.annotations.KeelApiSchema
import kotlinx.serialization.Serializable

@KeelApiSchema
@Serializable
data class GreetingData(
    @KeelApiField("The greeting message", "Hello from HelloWorldPlugin!")
    val message: String
)

@KeelApiSchema
@Serializable
data class VersionData(
    @KeelApiField("The plugin version", "1.0.0")
    val version: String
)

@KeelApiSchema
@Serializable
data class StatusData(
    @KeelApiField("The plugin status", "OK")
    val status: String
)
