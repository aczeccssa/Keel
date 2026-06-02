package com.keel.kernel.config

import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.application.Application
import io.ktor.server.application.serverConfig
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.ApplicationEngineFactory
import io.ktor.server.engine.EngineConnectorBuilder
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.embeddedServer
import io.ktor.server.jetty.Jetty
import io.ktor.server.jetty.JettyApplicationEngine
import io.ktor.server.jetty.JettyApplicationEngineBase
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.tomcat.Tomcat
import io.ktor.server.tomcat.TomcatApplicationEngine

object KeelEngineStarter {

    fun start(
        config: KeelServerConfig,
        module: Application.() -> Unit
    ): EmbeddedServer<*, *> {
        return when (val engine = config.engine) {
            is KeelEngine.Netty -> startNetty(config, module)
            is KeelEngine.CIO -> startCio(config, module)
            is KeelEngine.Tomcat -> startTomcat(config, module)
            is KeelEngine.Jetty -> startJetty(config, module)
            is KeelEngine.Custom -> startCustom(engine.factory, config, module)
        }
    }

    private fun startNetty(
        config: KeelServerConfig,
        module: Application.() -> Unit
    ): EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration> {
        return startTyped(Netty, config, module)
    }

    private fun startCio(
        config: KeelServerConfig,
        module: Application.() -> Unit
    ): EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration> {
        return startTyped(CIO, config, module)
    }

    private fun startTomcat(
        config: KeelServerConfig,
        module: Application.() -> Unit
    ): EmbeddedServer<TomcatApplicationEngine, TomcatApplicationEngine.Configuration> {
        return startTyped(Tomcat, config, module)
    }

    private fun startJetty(
        config: KeelServerConfig,
        module: Application.() -> Unit
    ): EmbeddedServer<JettyApplicationEngine, JettyApplicationEngineBase.Configuration> {
        return startTyped(Jetty, config, module)
    }

    private fun <TEngine : ApplicationEngine, TConfiguration : ApplicationEngine.Configuration> startCustom(
        factory: ApplicationEngineFactory<TEngine, TConfiguration>,
        config: KeelServerConfig,
        module: Application.() -> Unit
    ): EmbeddedServer<TEngine, TConfiguration> {
        return startTyped(factory, config, module)
    }

    private fun <TEngine : ApplicationEngine, TConfiguration : ApplicationEngine.Configuration> startTyped(
        factory: ApplicationEngineFactory<TEngine, TConfiguration>,
        config: KeelServerConfig,
        module: Application.() -> Unit
    ): EmbeddedServer<TEngine, TConfiguration> {
        return embeddedServer(
            factory = factory,
            rootConfig = createRootConfig(config, module)
        ) {
            applyEngineConfig(config)
        }
    }

    private fun createRootConfig(
        config: KeelServerConfig,
        module: Application.() -> Unit
    ) = serverConfig(applicationEnvironment()) {
        watchPaths = emptyList()
        module(module)
    }

    private fun ApplicationEngine.Configuration.applyEngineConfig(config: KeelServerConfig) {
        connectors.add(EngineConnectorBuilder().apply {
            port = config.port
            host = config.host
        })
        // Long-lived SSE streams (the observability + AI-gateway dashboards each hold persistent
        // EventSource connections) occupy a call-group worker for their whole lifetime. The engine
        // defaults are tuned for short request/response cycles and are too small once a handful of
        // streams are open, which starves new /api/* and page requests. Give the call group ample
        // headroom unless the host app overrides it explicitly.
        val cpu = Runtime.getRuntime().availableProcessors()
        connectionGroupSize = config.connectionGroupSize ?: max(2, cpu)
        workerGroupSize = config.workerGroupSize ?: max(4, cpu * 2)
        callGroupSize = config.callGroupSize ?: max(64, cpu * 16)
        config.engineConfigBlock?.invoke(this)
    }
}

private fun max(a: Int, b: Int): Int = if (a >= b) a else b
