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
        config.connectionGroupSize?.let { connectionGroupSize = it }
        config.workerGroupSize?.let { workerGroupSize = it }
        config.callGroupSize?.let { callGroupSize = it }
        config.engineConfigBlock?.invoke(this)
    }
}
