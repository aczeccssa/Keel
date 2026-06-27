package com.keel.test.kernel

import com.keel.kernel.plugin.UnifiedPluginManager
import com.keel.openapi.runtime.OpenApiRegistry
import com.keel.samples.aigateway.account.AccountPlugin
import com.keel.samples.aigateway.airelay.AIRelayPlugin
import com.keel.samples.aigateway.riskcontrol.RiskControlPlugin
import com.keel.samples.aigateway.token.TokenPlugin
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AiGatewaySqliteIntegrationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @AfterTest
    fun teardown() {
        OpenApiRegistry.clear()
        runCatching { stopKoin() }
    }

    @Test
    fun loginCreateKeyAndChatCompletionsRoundTripWithSqliteBackend() = testApplication {
        val previousDataDir = System.getProperty("keel.data.dir")
        val previousDbKind = System.getProperty("keel.aigateway.db.kind")
        val testDataDir = Files.createTempDirectory("keel-gateway-sqlite-").toFile()
        System.setProperty("keel.data.dir", testDataDir.absolutePath)
        System.setProperty("keel.aigateway.db.kind", "sqlite")

        OpenApiRegistry.clear()
        val koin = startKoin {}.koin
        val manager = UnifiedPluginManager(koin)
        manager.registerPlugin(AccountPlugin())
        manager.registerPlugin(TokenPlugin())
        manager.registerPlugin(RiskControlPlugin())
        manager.registerPlugin(AIRelayPlugin())

        application {
            install(ContentNegotiation) { json() }
            install(SSE)
            routing { manager.mountRoutes(this) }
            kotlinx.coroutines.runBlocking {
                manager.startPlugin("account")
                manager.startPlugin("token")
                manager.startPlugin("riskcontrol")
                manager.startPlugin("airelay")
            }
        }

        try {
            val loginResponse = client.post("/api/plugins/account/v1/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"email":"user@example.com","password":"user123"}""")
            }
            assertEquals(HttpStatusCode.OK, loginResponse.status)
            val accessToken = json.parseToJsonElement(loginResponse.bodyAsText()).jsonObject["accessToken"]!!.jsonPrimitive.content

            val keyResponse = client.post("/api/plugins/token/v1/keys") {
                header("Authorization", "Bearer $accessToken")
                contentType(ContentType.Application.Json)
                setBody("""{"displayName":"SQLite test key","maxBudgetUsd":100}""")
            }
            assertEquals(HttpStatusCode.OK, keyResponse.status)
            val rawKey = json.parseToJsonElement(keyResponse.bodyAsText()).jsonObject["rawKey"]!!.jsonPrimitive.content

            val chatResponse = client.post("/api/plugins/airelay/v1/chat/completions") {
                header("Authorization", "Bearer $rawKey")
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                      "model": "gpt-4o-mini",
                      "messages": [{"role":"user","content":"Hello from sqlite"}]
                    }
                    """.trimIndent()
                )
            }
            assertEquals(HttpStatusCode.OK, chatResponse.status, chatResponse.bodyAsText())
            val body = json.parseToJsonElement(chatResponse.bodyAsText()).jsonObject
            val choices = body["choices"]?.jsonArray ?: json.parseToJsonElement(body["response"]!!.jsonPrimitive.content).jsonObject["choices"]!!.jsonArray
            val content = choices[0].jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.content
            assertTrue(content.contains("Mock response"))
        } finally {
            if (previousDataDir == null) System.clearProperty("keel.data.dir") else System.setProperty("keel.data.dir", previousDataDir)
            if (previousDbKind == null) System.clearProperty("keel.aigateway.db.kind") else System.setProperty("keel.aigateway.db.kind", previousDbKind)
        }
    }
}
