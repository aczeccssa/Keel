package com.keel.kernel.api

import io.ktor.server.routing.Route
import io.ktor.util.AttributeKey

@PublishedApi
internal fun Route.resolveBasePathAttribute(key: AttributeKey<String>): String {
    var current: Route? = this
    while (current != null) {
        if (current.attributes.contains(key)) {
            return current.attributes[key]
        }
        current = current.parent
    }
    return ""
}

@PublishedApi
internal fun composeTypedPath(basePath: String, path: String): String {
    val parts = (basePath.trim().split('/') + path.trim().split('/'))
        .map(String::trim)
        .filter { it.isNotEmpty() }
    if (parts.isEmpty()) {
        return "/"
    }
    return "/" + parts.joinToString("/")
}
