package com.keel.openapi.runtime

import io.ktor.http.HttpMethod
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.reflect.KType

data class OpenApiDoc(
    val summary: String = "",
    val description: String = "",
    val tags: List<String> = emptyList(),
    val successStatus: Int = 200,
    val errorStatuses: Set<Int> = emptySet(),
    val responseEnvelope: Boolean = false
)

data class OpenApiOperation(
    val method: HttpMethod,
    val path: String,
    val requestBodyType: KType? = null,
    val responseBodyType: KType? = null,
    val responseContentTypes: List<String>? = null,
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
    private val topologyVersion = AtomicLong(0)

    fun register(operation: OpenApiOperation) {
        val previous = operations.put(operation.key(), operation)
        if (previous != operation) {
            topologyVersion.incrementAndGet()
        }
    }

    fun operations(): List<OpenApiOperation> {
        return operations.values.sortedWith(
            compareBy<OpenApiOperation>({ it.path }, { it.method.value })
        )
    }

    fun hasOperations(): Boolean = operations.isNotEmpty()

    fun clear() {
        if (operations.isNotEmpty()) {
            operations.clear()
            topologyVersion.incrementAndGet()
        }
    }

    fun topologyVersion(): Long = topologyVersion.get()

    private fun OpenApiOperation.key(): String = "${method.value} $path"
}
