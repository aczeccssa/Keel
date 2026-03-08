package com.keel.kernel.config

import io.ktor.server.engine.ApplicationEngineFactory
import io.ktor.server.engine.ApplicationEngine
import kotlin.reflect.KClass

/**
 * Keel Engine Abstraction.
 * Limits and abstracts the natively supported Ktor engines.
 */
sealed class KeelEngine {
    
    /**
     * Represents the Netty engine, the default engine in Keel.
     */
    object Netty : KeelEngine()

    /**
     * Represents the CIO (Coroutine-based I/O) engine.
     */
    object CIO : KeelEngine()

    /**
     * Represents the Tomcat engine.
     */
    object Tomcat : KeelEngine()

    /**
     * Represents the Jetty engine.
     */
    object Jetty : KeelEngine()

    /**
     * Allows custom engines if developers want to bring their own Ktor ApplicationEngineFactory.
     */
    class Custom(val factory: ApplicationEngineFactory<ApplicationEngine, *>) : KeelEngine()
}
