package com.keel.openapi.runtime

import io.ktor.http.HttpMethod

data class OpenApiDeclaredOperation(
    val method: HttpMethod,
    val path: String,
    val summary: String = "",
    val description: String = "",
    val tags: List<String> = emptyList(),
    val successStatus: Int = 200,
    val errorStatuses: Set<Int> = emptySet(),
    val responseEnvelope: Boolean = false
)

interface OpenApiOperationFragment {
    fun operations(): List<OpenApiDeclaredOperation>
}
