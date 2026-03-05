package com.keel.kernel.routing

import com.keel.kernel.config.KeelConstants
import com.keel.openapi.runtime.OpenApiOperation
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

@PublishedApi
internal val RoutingTypedRouteBasePathKey = AttributeKey<String>("keel.routing.typed.basePath")

@PublishedApi
internal fun Route.routingTypedBasePath(): String {
    var current: Route? = this
    while (current != null) {
        if (current.attributes.contains(RoutingTypedRouteBasePathKey)) {
            return current.attributes[RoutingTypedRouteBasePathKey]
        }
        current = current.parent
    }
    return ""
}

@PublishedApi
internal fun Route.routingFullTypedPath(path: String): String {
    val basePath = routingTypedBasePath().trimEnd('/')
    val normalizedPath = path.takeIf { it.isNotBlank() }?.let {
        if (it.startsWith("/")) it else "/$it"
    } ?: ""
    val combined = "$basePath$normalizedPath"
    return if (combined.isBlank()) "/" else combined
}

@PublishedApi
internal fun Route.registerRoutingTypedOperation(
    method: HttpMethod,
    path: String,
    requestType: KType?,
    responseType: KType?
) {
    OpenApiRegistry.register(
        OpenApiOperation(
            method = method,
            path = routingFullTypedPath(path),
            requestBodyType = requestType,
            responseBodyType = responseType,
            typeBound = true
        )
    )
}

@PublishedApi
internal fun Route.invokeCompatGet(path: String, body: suspend RoutingContext.() -> Unit) {
    if (path.isBlank()) get(body) else get(path, body)
}

@PublishedApi
internal fun Route.invokeCompatPost(path: String, body: suspend RoutingContext.() -> Unit) {
    if (path.isBlank()) post(body) else post(path, body)
}

@PublishedApi
internal fun Route.invokeCompatPut(path: String, body: suspend RoutingContext.() -> Unit) {
    if (path.isBlank()) put(body) else put(path, body)
}

@PublishedApi
internal fun Route.invokeCompatDelete(path: String, body: suspend RoutingContext.() -> Unit) {
    if (path.isBlank()) delete(body) else delete(path, body)
}

fun Route.systemApi(block: Route.() -> Unit) {
    route(KeelConstants.SYSTEM_API_PREFIX) {
        attributes.put(RoutingTypedRouteBasePathKey, KeelConstants.SYSTEM_API_PREFIX)
        block()
    }
}

fun Route.typedRoute(path: String, block: Route.() -> Unit) {
    val resolvedPath = routingFullTypedPath(path)
    route(path) {
        attributes.put(RoutingTypedRouteBasePathKey, resolvedPath)
        block()
    }
}

inline fun <reified Res : Any> Route.typedGet(
    path: String = "",
    noinline body: suspend RoutingContext.() -> Unit
) {
    registerRoutingTypedOperation(
        method = HttpMethod.Get,
        path = path,
        requestType = null,
        responseType = typeOf<Res>()
    )
    invokeCompatGet(path, body)
}

@JvmName("routingTypedPostWithoutRequest")
inline fun <reified Res : Any> Route.typedPost(
    path: String = "",
    noinline body: suspend RoutingContext.() -> Unit
) {
    registerRoutingTypedOperation(
        method = HttpMethod.Post,
        path = path,
        requestType = null,
        responseType = typeOf<Res>()
    )
    invokeCompatPost(path, body)
}

inline fun <reified Req : Any, reified Res : Any> Route.typedPost(
    path: String = "",
    noinline body: suspend RoutingContext.() -> Unit
) {
    registerRoutingTypedOperation(
        method = HttpMethod.Post,
        path = path,
        requestType = typeOf<Req>(),
        responseType = typeOf<Res>()
    )
    invokeCompatPost(path, body)
}

inline fun <reified Req : Any, reified Res : Any> Route.typedPut(
    path: String = "",
    noinline body: suspend RoutingContext.() -> Unit
) {
    registerRoutingTypedOperation(
        method = HttpMethod.Put,
        path = path,
        requestType = typeOf<Req>(),
        responseType = typeOf<Res>()
    )
    invokeCompatPut(path, body)
}

inline fun <reified Res : Any> Route.typedDelete(
    path: String = "",
    noinline body: suspend RoutingContext.() -> Unit
) {
    registerRoutingTypedOperation(
        method = HttpMethod.Delete,
        path = path,
        requestType = null,
        responseType = typeOf<Res>()
    )
    invokeCompatDelete(path, body)
}
