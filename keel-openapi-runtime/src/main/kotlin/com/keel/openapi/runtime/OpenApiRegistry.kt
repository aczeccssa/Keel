package com.keel.openapi.runtime

import io.ktor.http.HttpMethod
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.KType

data class OpenApiOperation(
    val method: HttpMethod,
    val path: String,
    val requestBodyType: KType? = null,
    val responseBodyType: KType? = null,
    val typeBound: Boolean = false,
    val summary: String = "",
    val description: String = "",
    val tags: List<String> = emptyList(),
    val responseEnvelope: Boolean = false,
    val successStatus: Int = 200,
    val errorStatuses: Set<Int> = emptySet()
)

object OpenApiRegistry {
    private val operations = ConcurrentHashMap<String, OpenApiOperation>()

    fun register(operation: OpenApiOperation) {
        operations[operation.key()] = operation
    }

    fun operations(): List<OpenApiOperation> {
        return operations.values.sortedWith(
            compareBy<OpenApiOperation>({ it.path }, { it.method.value })
        )
    }

    fun hasOperations(): Boolean = operations.isNotEmpty()

    fun clear() {
        operations.clear()
    }

    private fun OpenApiOperation.key(): String = "${method.value} $path"
}
