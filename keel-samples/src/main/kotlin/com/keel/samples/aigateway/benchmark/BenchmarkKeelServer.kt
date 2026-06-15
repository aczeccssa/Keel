package com.keel.samples.aigateway.benchmark

import com.keel.kernel.plugin.UnifiedPluginManager
import com.keel.openapi.runtime.OpenApiRegistry
import com.keel.samples.aigateway.account.AccountPlugin
import com.keel.samples.aigateway.airelay.AIRelayPlugin
import com.keel.samples.aigateway.airelay.upstream.RealUpstreamHttpClient
import com.keel.samples.aigateway.riskcontrol.RiskControlPlugin
import com.keel.samples.aigateway.token.TokenPlugin
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import java.io.Closeable
import java.net.ServerSocket
import java.nio.file.Files
import kotlin.io.path.writeText

class BenchmarkKeelServer private constructor(
    private val engine: EmbeddedServer<*, *>,
    private val manager: UnifiedPluginManager,
    private val previousDataDir: String?,
    private val previousConfigPath: String?,
    private val client: HttpClient,
    val baseUrl: String,
    val apiKey: String,
) : Closeable {
    override fun close() {
        runCatching { runBlocking { manager.stopAll() } }
        client.close()
        engine.stop(gracePeriodMillis = 500, timeoutMillis = 3_000)
        runCatching { stopKoin() }
        if (previousDataDir == null) {
            System.clearProperty("keel.data.dir")
        } else {
            System.setProperty("keel.data.dir", previousDataDir)
        }
        if (previousConfigPath == null) {
            System.clearProperty("keel.airelay.config")
        } else {
            System.setProperty("keel.airelay.config", previousConfigPath)
        }
        OpenApiRegistry.clear()
    }

    companion object {
        private val jsonCodec = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun start(topology: BenchmarkTopologySetup, port: Int = 0): BenchmarkKeelServer {
            val resolvedPort = if (port == 0) freePort() else port
            val previousDataDir = System.getProperty("keel.data.dir")
            val previousConfigPath = System.getProperty("keel.airelay.config")
            val dataDir = Files.createTempDirectory("keel-ai-relay-benchmark-")
            val configPath = dataDir.resolve("airelay-settings.json")
            configPath.writeText(jsonCodec.encodeToString(topology.settings))
            System.setProperty("keel.data.dir", dataDir.toAbsolutePath().toString())
            System.setProperty("keel.airelay.config", configPath.toAbsolutePath().toString())

            OpenApiRegistry.clear()
            runCatching { stopKoin() }
            val koin: Koin = startKoin {}.koin
            val manager = UnifiedPluginManager(koin)
            val airelayPlugin = AIRelayPlugin()
            manager.registerPlugin(AccountPlugin())
            manager.registerPlugin(TokenPlugin())
            manager.registerPlugin(RiskControlPlugin())
            manager.registerPlugin(airelayPlugin)

            val engine = embeddedServer(ServerCIO, port = resolvedPort, host = "127.0.0.1") {
                install(ContentNegotiation) { json(jsonCodec) }
                install(SSE)
                routing { manager.mountRoutes(this) }
                runBlocking {
                    manager.startPlugin("account")
                    manager.startPlugin("token")
                    manager.startPlugin("riskcontrol")
                    manager.startPlugin("airelay")
                    airelayPlugin.installRealUpstream(RealUpstreamHttpClient.create(), topology.settings.chains)
                }
            }.start(wait = false)

            val client = HttpClient(ClientCIO) { expectSuccess = false }
            val apiKey = runBlocking {
                createApiKey(client, "http://127.0.0.1:$resolvedPort", topology.groupId)
            }
            return BenchmarkKeelServer(
                engine = engine,
                manager = manager,
                previousDataDir = previousDataDir,
                previousConfigPath = previousConfigPath,
                client = client,
                baseUrl = "http://127.0.0.1:$resolvedPort",
                apiKey = apiKey,
            )
        }

        private suspend fun createApiKey(client: HttpClient, baseUrl: String, groupId: String): String {
            val loginText = client.post("$baseUrl/api/plugins/account/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"email":"user@example.com","password":"user123"}""")
            }.bodyAsText()
            val accessToken = jsonCodec.parseToJsonElement(loginText)
                .jsonObject["accessToken"]?.jsonPrimitive?.contentOrNull
                ?: error("Login response did not include accessToken: $loginText")
            val keyText = client.post("$baseUrl/api/plugins/token/v1/keys") {
                header("Authorization", "Bearer $accessToken")
                contentType(ContentType.Application.Json)
                setBody("""{"displayName":"AI relay benchmark key","groupId":"$groupId","maxBudgetUsd":1000000000}""")
            }.bodyAsText()
            return jsonCodec.parseToJsonElement(keyText)
                .jsonObject["rawKey"]?.jsonPrimitive?.contentOrNull
                ?: error("Key response did not include rawKey: $keyText")
        }

        private fun freePort(): Int = ServerSocket(0).use { it.localPort }
    }
}
