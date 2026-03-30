package com.keel.openapi.annotations

import kotlin.reflect.KClass

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
@Repeatable
annotation class KeelRouteInterceptors(
    val method: String,
    val path: String,
    val clearDefaults: Boolean = false,
    vararg val value: KClass<*>
)
