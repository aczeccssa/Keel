package com.keel.openapi.annotations

import kotlin.reflect.KClass

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class KeelInterceptors(
    vararg val value: KClass<*>
)
