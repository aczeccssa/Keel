package com.keel.kernel.routing

import com.keel.kernel.api.composeTypedPath
import com.keel.kernel.api.invokeDelete
import com.keel.kernel.api.invokeGet
import com.keel.kernel.api.invokePost
import com.keel.kernel.api.invokePut
import com.keel.kernel.api.resolveBasePathAttribute
import com.keel.kernel.config.KeelConstants
import com.keel.openapi.runtime.OpenApiOperation
import com.keel.openapi.runtime.OpenApiRegistry
import io.ktor.http.HttpMethod
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.route
import io.ktor.util.AttributeKey
import kotlin.jvm.JvmName
import kotlin.reflect.KType
import kotlin.reflect.typeOf

@PublishedApi
internal val RoutingTypedRouteBasePathKey = AttributeKey<String>("keel.routing.typed.basePath")

@PublishedApi
internal fun Route.routingTypedBasePath(): String {
    return resolveBasePathAttribute(RoutingTypedRouteBasePathKey)
}

@PublishedApi
internal fun Route.routingFullTypedPath(path: String): String {
    return composeTypedPath(routingTypedBasePath(), path)
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
    invokeGet(path, body)
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
    invokePost(path, body)
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
    invokePost(path, body)
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
    invokePut(path, body)
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
    invokeDelete(path, body)
}
