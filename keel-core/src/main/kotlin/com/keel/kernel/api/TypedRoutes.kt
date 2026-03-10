package com.keel.kernel.api

import com.keel.openapi.runtime.OpenApiOperation
import com.keel.openapi.runtime.OpenApiDoc
import com.keel.openapi.runtime.OpenApiRegistry
import io.ktor.http.HttpMethod
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.util.AttributeKey
import kotlin.jvm.JvmName
import kotlin.reflect.KType
import kotlin.reflect.typeOf

internal val TypedRouteBasePathKey = AttributeKey<String>("keel.typed.basePath")

@PublishedApi
internal fun Route.typedBasePath(): String {
    return resolveBasePathAttribute(TypedRouteBasePathKey)
}

@PublishedApi
internal fun Route.fullTypedPath(path: String): String {
    return composeTypedPath(typedBasePath(), path)
}

@PublishedApi
internal fun Route.registerTypedOperation(
    method: HttpMethod,
    path: String,
    requestType: KType?,
    responseType: KType?,
    doc: OpenApiDoc = OpenApiDoc()
) {
    OpenApiRegistry.register(
        OpenApiOperation(
            method = method,
            path = fullTypedPath(path),
            requestBodyType = requestType,
            responseBodyType = responseType,
            typeBound = true,
            summary = doc.summary,
            description = doc.description,
            tags = doc.tags,
            successStatus = doc.successStatus,
            errorStatuses = doc.errorStatuses,
            responseEnvelope = doc.responseEnvelope
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

fun Route.typedRoute(path: String, block: Route.() -> Unit) {
    val resolvedPath = fullTypedPath(path)
    route(path) {
        attributes.put(TypedRouteBasePathKey, resolvedPath)
        block()
    }
}

inline fun <reified Res : Any> Route.typedGet(
    path: String = "",
    doc: OpenApiDoc = OpenApiDoc(),
    noinline body: suspend RoutingContext.() -> Unit
) {
    registerTypedOperation(
        method = HttpMethod.Get,
        path = path,
        requestType = null,
        responseType = typeOf<Res>(),
        doc = doc
    )
    invokeGet(path, body)
}

@JvmName("typedPostWithoutRequest")
inline fun <reified Res : Any> Route.typedPost(
    path: String = "",
    doc: OpenApiDoc = OpenApiDoc(),
    noinline body: suspend RoutingContext.() -> Unit
) {
    registerTypedOperation(
        method = HttpMethod.Post,
        path = path,
        requestType = null,
        responseType = typeOf<Res>(),
        doc = doc
    )
    invokePost(path, body)
}

inline fun <reified Req : Any, reified Res : Any> Route.typedPost(
    path: String = "",
    doc: OpenApiDoc = OpenApiDoc(),
    noinline body: suspend RoutingContext.() -> Unit
) {
    registerTypedOperation(
        method = HttpMethod.Post,
        path = path,
        requestType = typeOf<Req>(),
        responseType = typeOf<Res>(),
        doc = doc
    )
    invokePost(path, body)
}

inline fun <reified Req : Any, reified Res : Any> Route.typedPut(
    path: String = "",
    doc: OpenApiDoc = OpenApiDoc(),
    noinline body: suspend RoutingContext.() -> Unit
) {
    registerTypedOperation(
        method = HttpMethod.Put,
        path = path,
        requestType = typeOf<Req>(),
        responseType = typeOf<Res>(),
        doc = doc
    )
    invokePut(path, body)
}

inline fun <reified Res : Any> Route.typedDelete(
    path: String = "",
    doc: OpenApiDoc = OpenApiDoc(),
    noinline body: suspend RoutingContext.() -> Unit
) {
    registerTypedOperation(
        method = HttpMethod.Delete,
        path = path,
        requestType = null,
        responseType = typeOf<Res>(),
        doc = doc
    )
    invokeDelete(path, body)
}

@Deprecated(
    message = "Use typedGet with doc = OpenApiDoc(...).",
    replaceWith = ReplaceWith("typedGet<Res>(path = path, doc = OpenApiDoc(summary, description, tags, successStatus, errorStatuses, responseEnvelope), body = body)")
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
        doc = OpenApiDoc(
            summary = summary,
            description = description,
            tags = tags,
            successStatus = successStatus,
            errorStatuses = errorStatuses,
            responseEnvelope = responseEnvelope
        )
    )
    invokeGet(path, body)
}

@Deprecated(
    message = "Use typedPost with doc = OpenApiDoc(...).",
    replaceWith = ReplaceWith("typedPost<Res>(path = path, doc = OpenApiDoc(summary, description, tags, successStatus, errorStatuses, responseEnvelope), body = body)")
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
        doc = OpenApiDoc(
            summary = summary,
            description = description,
            tags = tags,
            successStatus = successStatus,
            errorStatuses = errorStatuses,
            responseEnvelope = responseEnvelope
        )
    )
    invokePost(path, body)
}

@Deprecated(
    message = "Use typedPost with doc = OpenApiDoc(...).",
    replaceWith = ReplaceWith("typedPost<Req, Res>(path = path, doc = OpenApiDoc(summary, description, tags, successStatus, errorStatuses, responseEnvelope), body = body)")
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
        doc = OpenApiDoc(
            summary = summary,
            description = description,
            tags = tags,
            successStatus = successStatus,
            errorStatuses = errorStatuses,
            responseEnvelope = responseEnvelope
        )
    )
    invokePost(path, body)
}

@Deprecated(
    message = "Use typedPut with doc = OpenApiDoc(...).",
    replaceWith = ReplaceWith("typedPut<Req, Res>(path = path, doc = OpenApiDoc(summary, description, tags, successStatus, errorStatuses, responseEnvelope), body = body)")
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
        doc = OpenApiDoc(
            summary = summary,
            description = description,
            tags = tags,
            successStatus = successStatus,
            errorStatuses = errorStatuses,
            responseEnvelope = responseEnvelope
        )
    )
    invokePut(path, body)
}

@Deprecated(
    message = "Use typedDelete with doc = OpenApiDoc(...).",
    replaceWith = ReplaceWith("typedDelete<Res>(path = path, doc = OpenApiDoc(summary, description, tags, successStatus, errorStatuses, responseEnvelope), body = body)")
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
        doc = OpenApiDoc(
            summary = summary,
            description = description,
            tags = tags,
            successStatus = successStatus,
            errorStatuses = errorStatuses,
            responseEnvelope = responseEnvelope
        )
    )
    invokeDelete(path, body)
}
