package com.keel.kernel.config

/**
 * Configuration for the Keel Server Engine.
 */
class KeelServerConfig {
    /**
     * The engine to use for the Keel Server. Defaults to Netty.
     */
    var engine: KeelEngine = KeelEngine.Netty

    /**
     * The port to bind the server to. Defaults to 8080.
     */
    var port: Int = 8080

    /**
     * The host to bind the server to. Defaults to "0.0.0.0".
     */
    var host: String = "0.0.0.0"

    /**
     * Size of the event group for accepting connections.
     * Specific behavior depends on the chosen engine.
     */
    var connectionGroupSize: Int? = null

    /**
     * Size of the event group for processing connections, parsing messages and doing engine's internal work.
     * Specific behavior depends on the chosen engine.
     */
    var workerGroupSize: Int? = null

    /**
     * Size of the event group for running application code.
     * Specific behavior depends on the chosen engine.
     */
    var callGroupSize: Int? = null

    /**
     * Allows engine-specific configuration block. This is an escape hatch to configure engine-specific settings.
     * The parameter is the engine configuration class (e.g. NettyApplicationEngine.Configuration).
     */
    var engineConfigBlock: (Any.() -> Unit)? = null

    /**
     * Set a custom engine-specific configuration block.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> configureEngine(block: T.() -> Unit) {
        engineConfigBlock = block as (Any.() -> Unit)
    }
}
