package com.keel.kernel.api

import com.keel.openapi.runtime.OpenApiOperation
import com.keel.openapi.runtime.OpenApiRegistry
import io.ktor.http.HttpMethod
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.util.AttributeKey
import kotlin.jvm.JvmName
import kotlin.reflect.KType
import kotlin.reflect.typeOf

internal val TypedRouteBasePathKey = AttributeKey<String>("keel.typed.basePath")

@PublishedApi
internal fun Route.typedBasePath(): String {
    var current: Route? = this
    while (current != null) {
        if (current.attributes.contains(TypedRouteBasePathKey)) {
            return current.attributes[TypedRouteBasePathKey]
        }
        current = current.parent
    }
    return ""
}

@PublishedApi
internal fun Route.fullTypedPath(path: String): String {
    val basePath = typedBasePath().trimEnd('/')
    val normalizedPath = path.takeIf { it.isNotBlank() }?.let {
        if (it.startsWith("/")) it else "/$it"
    } ?: ""
    val combined = "$basePath$normalizedPath"
    return if (combined.isBlank()) "/" else combined
}

@PublishedApi
internal fun Route.registerTypedOperation(
    method: HttpMethod,
    path: String,
    requestType: KType?,
    responseType: KType?,
    summary: String = "",
    description: String = "",
    tags: List<String> = emptyList(),
    successStatus: Int = 200,
    errorStatuses: Set<Int> = emptySet(),
    responseEnvelope: Boolean = false
) {
    OpenApiRegistry.register(
        OpenApiOperation(
            method = method,
            path = fullTypedPath(path),
            requestBodyType = requestType,
            responseBodyType = responseType,
            typeBound = true,
            summary = summary,
            description = description,
            tags = tags,
            successStatus = successStatus,
            errorStatuses = errorStatuses,
            responseEnvelope = responseEnvelope
        )
    )
}

@PublishedApi
internal fun Route.invokeGet(path: String, body: suspend RoutingContext.() -> Unit) {
    if (path.isBlank()) get(body) else get(path, body)
}

@PublishedApi
internal fun Route.invokePost(path: String, body: suspend RoutingContext.() -> Unit) {
    if (path.isBlank()) post(body) else post(path, body)
}

@PublishedApi
internal fun Route.invokePut(path: String, body: suspend RoutingContext.() -> Unit) {
    if (path.isBlank()) put(body) else put(path, body)
}

@PublishedApi
internal fun Route.invokeDelete(path: String, body: suspend RoutingContext.() -> Unit) {
    if (path.isBlank()) delete(body) else delete(path, body)
}

inline fun <reified Res : Any> Route.typedGet(
    path: String = "",
    noinline body: suspend RoutingContext.() -> Unit
) {
    registerTypedOperation(
        method = HttpMethod.Get,
        path = path,
        requestType = null,
        responseType = typeOf<Res>()
    )
    invokeGet(path, body)
}

@JvmName("typedPostWithoutRequest")
inline fun <reified Res : Any> Route.typedPost(
    path: String = "",
    noinline body: suspend RoutingContext.() -> Unit
) {
    registerTypedOperation(
        method = HttpMethod.Post,
        path = path,
        requestType = null,
        responseType = typeOf<Res>()
    )
    invokePost(path, body)
}

inline fun <reified Req : Any, reified Res : Any> Route.typedPost(
    path: String = "",
    noinline body: suspend RoutingContext.() -> Unit
) {
    registerTypedOperation(
        method = HttpMethod.Post,
        path = path,
        requestType = typeOf<Req>(),
        responseType = typeOf<Res>()
    )
    invokePost(path, body)
}

inline fun <reified Req : Any, reified Res : Any> Route.typedPut(
    path: String = "",
    noinline body: suspend RoutingContext.() -> Unit
) {
    registerTypedOperation(
        method = HttpMethod.Put,
        path = path,
        requestType = typeOf<Req>(),
        responseType = typeOf<Res>()
    )
    invokePut(path, body)
}

inline fun <reified Res : Any> Route.typedDelete(
    path: String = "",
    noinline body: suspend RoutingContext.() -> Unit
) {
    registerTypedOperation(
        method = HttpMethod.Delete,
        path = path,
        requestType = null,
        responseType = typeOf<Res>()
    )
    invokeDelete(path, body)
}

@Deprecated(
    message = "Move documentation metadata to @KeelApi; use typedGet only for route registration and type binding.",
    replaceWith = ReplaceWith("typedGet<Res>(path, body)")
)
inline fun <reified Res : Any> Route.documentedGet(
    path: String = "",
    @Suppress("unused") summary: String = "",
    @Suppress("unused") description: String = "",
    @Suppress("unused") tags: List<String> = emptyList(),
    successStatus: Int = 200,
    errorStatuses: Set<Int> = emptySet(),
    responseEnvelope: Boolean = false,
    noinline body: suspend RoutingContext.() -> Unit
) {
    registerTypedOperation(
        method = HttpMethod.Get,
        path = path,
        requestType = null,
        responseType = typeOf<Res>(),
        summary = summary,
        description = description,
        tags = tags,
        successStatus = successStatus,
        errorStatuses = errorStatuses,
        responseEnvelope = responseEnvelope
    )
    invokeGet(path, body)
}

@Deprecated(
    message = "Move documentation metadata to @KeelApi; use typedPost only for route registration and type binding.",
    replaceWith = ReplaceWith("typedPost<Res>(path, body)")
)
@JvmName("documentedPostWithoutRequest")
inline fun <reified Res : Any> Route.documentedPost(
    path: String = "",
    @Suppress("unused") summary: String = "",
    @Suppress("unused") description: String = "",
    @Suppress("unused") tags: List<String> = emptyList(),
    successStatus: Int = 200,
    errorStatuses: Set<Int> = emptySet(),
    responseEnvelope: Boolean = false,
    noinline body: suspend RoutingContext.() -> Unit
) {
    registerTypedOperation(
        method = HttpMethod.Post,
        path = path,
        requestType = null,
        responseType = typeOf<Res>(),
        summary = summary,
        description = description,
        tags = tags,
        successStatus = successStatus,
        errorStatuses = errorStatuses,
        responseEnvelope = responseEnvelope
    )
    invokePost(path, body)
}

@Deprecated(
    message = "Move documentation metadata to @KeelApi; use typedPost only for route registration and type binding.",
    replaceWith = ReplaceWith("typedPost<Req, Res>(path, body)")
)
inline fun <reified Req : Any, reified Res : Any> Route.documentedPost(
    path: String = "",
    @Suppress("unused") summary: String = "",
    @Suppress("unused") description: String = "",
    @Suppress("unused") tags: List<String> = emptyList(),
    successStatus: Int = 200,
    errorStatuses: Set<Int> = emptySet(),
    responseEnvelope: Boolean = false,
    noinline body: suspend RoutingContext.() -> Unit
) {
    registerTypedOperation(
        method = HttpMethod.Post,
        path = path,
        requestType = typeOf<Req>(),
        responseType = typeOf<Res>(),
        summary = summary,
        description = description,
        tags = tags,
        successStatus = successStatus,
        errorStatuses = errorStatuses,
        responseEnvelope = responseEnvelope
    )
    invokePost(path, body)
}

@Deprecated(
    message = "Move documentation metadata to @KeelApi; use typedPut only for route registration and type binding.",
    replaceWith = ReplaceWith("typedPut<Req, Res>(path, body)")
)
inline fun <reified Req : Any, reified Res : Any> Route.documentedPut(
    path: String = "",
    @Suppress("unused") summary: String = "",
    @Suppress("unused") description: String = "",
    @Suppress("unused") tags: List<String> = emptyList(),
    successStatus: Int = 200,
    errorStatuses: Set<Int> = emptySet(),
    responseEnvelope: Boolean = false,
    noinline body: suspend RoutingContext.() -> Unit
) {
    registerTypedOperation(
        method = HttpMethod.Put,
        path = path,
        requestType = typeOf<Req>(),
        responseType = typeOf<Res>(),
        summary = summary,
        description = description,
        tags = tags,
        successStatus = successStatus,
        errorStatuses = errorStatuses,
        responseEnvelope = responseEnvelope
    )
    invokePut(path, body)
}

@Deprecated(
    message = "Move documentation metadata to @KeelApi; use typedDelete only for route registration and type binding.",
    replaceWith = ReplaceWith("typedDelete<Res>(path, body)")
)
inline fun <reified Res : Any> Route.documentedDelete(
    path: String = "",
    @Suppress("unused") summary: String = "",
    @Suppress("unused") description: String = "",
    @Suppress("unused") tags: List<String> = emptyList(),
    successStatus: Int = 200,
    errorStatuses: Set<Int> = emptySet(),
    responseEnvelope: Boolean = false,
    noinline body: suspend RoutingContext.() -> Unit
) {
    registerTypedOperation(
        method = HttpMethod.Delete,
        path = path,
        requestType = null,
        responseType = typeOf<Res>(),
        summary = summary,
        description = description,
        tags = tags,
        successStatus = successStatus,
        errorStatuses = errorStatuses,
        responseEnvelope = responseEnvelope
    )
    invokeDelete(path, body)
}
