package com.keel.test.kernel

import com.keel.kernel.plugin.UnifiedPluginManager
import com.keel.openapi.runtime.OpenApiRegistry
import com.keel.samples.aigateway.account.AccountPlugin
import com.keel.samples.aigateway.airelay.AIRelayPlugin
import com.keel.samples.aigateway.customerportal.CustomerPortalPlugin
import com.keel.samples.aigateway.riskcontrol.RiskControlPlugin
import com.keel.samples.aigateway.token.TokenPlugin
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.get
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
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CustomerUsageStreamingIntegrationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @AfterTest
    fun teardown() {
        OpenApiRegistry.clear()
        runCatching { stopKoin() }
    }

    @Test
    fun streamingCustomerRequestProducesUsageAndConsumesCredits() = testApplication {
        val previousDataDir = System.getProperty("keel.data.dir")
        val testDataDir = Files.createTempDirectory("keel-customer-stream-").toFile()
        System.setProperty("keel.data.dir", testDataDir.absolutePath)
        try {
            OpenApiRegistry.clear()
            val koin = startKoin {}.koin
            val manager = UnifiedPluginManager(koin)
            manager.registerPlugin(AccountPlugin())
            manager.registerPlugin(TokenPlugin())
            manager.registerPlugin(RiskControlPlugin())
            manager.registerPlugin(AIRelayPlugin())
            manager.registerPlugin(CustomerPortalPlugin())

            application {
                install(ContentNegotiation) { json() }
                install(SSE)
                routing { manager.mountRoutes(this) }
                kotlinx.coroutines.runBlocking {
                    manager.startPlugin("account")
                    manager.startPlugin("token")
                    manager.startPlugin("riskcontrol")
                    manager.startPlugin("airelay")
                    manager.startPlugin("customer-portal")
                }
            }

            val register = client.post("/api/plugins/customer-portal/v1/customer/auth/register") {
                contentType(ContentType.Application.Json)
                setBody("""{"email":"stream-user@example.com","password":"password123","displayName":"Stream User"}""")
            }
            assertEquals(HttpStatusCode.OK, register.status)
            val auth = json.parseToJsonElement(register.bodyAsText()).jsonObject
            val accessToken = auth["accessToken"]!!.jsonPrimitive.content

            val beforeBalance = client.get("/api/plugins/customer-portal/v1/customer/credits") {
                header("Authorization", "Bearer $accessToken")
            }
            val beforeCredits = json.parseToJsonElement(beforeBalance.bodyAsText()).jsonObject["balanceCredits"]!!.jsonPrimitive.content.toLong()

            val keyResp = client.post("/api/plugins/customer-portal/v1/customer/keys") {
                header("Authorization", "Bearer $accessToken")
                contentType(ContentType.Application.Json)
                setBody("""{"name":"Stream Key","routingGroupId":"default-chain"}""")
            }
            assertEquals(HttpStatusCode.OK, keyResp.status)
            val rawKey = json.parseToJsonElement(keyResp.bodyAsText()).jsonObject["rawKey"]!!.jsonPrimitive.content

            val relay = client.post("/api/plugins/airelay/v1/chat/completions") {
                header("Authorization", "Bearer $rawKey")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"gpt-4o-mini","stream":true,"messages":[{"role":"user","content":"hello"}]}""")
            }
            assertEquals(HttpStatusCode.OK, relay.status)
            assertTrue(relay.bodyAsText().contains("data:"), "stream body should render as SSE text")

            val usageResp = client.get("/api/plugins/customer-portal/v1/customer/usage?limit=10") {
                header("Authorization", "Bearer $accessToken")
            }
            assertEquals(HttpStatusCode.OK, usageResp.status)
            val usage = json.parseToJsonElement(usageResp.bodyAsText()).jsonObject
            val records = usage["records"]!!.jsonArray
            assertTrue(records.isNotEmpty(), "customer usage should include the streamed request")
            val first = records.first().jsonObject
            assertEquals("gpt-4o-mini", first["model"]!!.jsonPrimitive.content)
            assertTrue(first["creditCost"]!!.jsonPrimitive.content.toLong() >= 1L)

            val afterBalance = client.get("/api/plugins/customer-portal/v1/customer/credits") {
                header("Authorization", "Bearer $accessToken")
            }
            val afterCredits = json.parseToJsonElement(afterBalance.bodyAsText()).jsonObject["balanceCredits"]!!.jsonPrimitive.content.toLong()
            assertTrue(afterCredits < beforeCredits, "credits should decrease after the streamed request")
        } finally {
            if (previousDataDir == null) {
                System.clearProperty("keel.data.dir")
            } else {
                System.setProperty("keel.data.dir", previousDataDir)
            }
        }
    }

    @Test
    fun customerKeyCanRouteThroughAliasOnlyPremiumGroup() = testApplication {
        val previousDataDir = System.getProperty("keel.data.dir")
        val testDataDir = Files.createTempDirectory("keel-customer-alias-").toFile()
        System.setProperty("keel.data.dir", testDataDir.absolutePath)
        try {
            OpenApiRegistry.clear()
            val koin = startKoin {}.koin
            val manager = UnifiedPluginManager(koin)
            manager.registerPlugin(AccountPlugin())
            manager.registerPlugin(TokenPlugin())
            manager.registerPlugin(RiskControlPlugin())
            manager.registerPlugin(AIRelayPlugin())
            manager.registerPlugin(CustomerPortalPlugin())

            application {
                install(ContentNegotiation) { json() }
                install(SSE)
                routing { manager.mountRoutes(this) }
                kotlinx.coroutines.runBlocking {
                    manager.startPlugin("account")
                    manager.startPlugin("token")
                    manager.startPlugin("riskcontrol")
                    manager.startPlugin("airelay")
                    manager.startPlugin("customer-portal")
                }
            }

            val groupResp = client.post("/api/plugins/airelay/admin/groups") {
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                      "groupId":"premium",
                      "name":"Premium",
                      "enabled":true,
                      "exposureMode":"ALIAS_ONLY",
                      "aliasRoutes":[
                        {"aliasName":"claude-sonnet-4-6","targetModels":["claude-b"],"enabled":true}
                      ]
                    }
                    """.trimIndent()
                )
            }
            assertEquals(HttpStatusCode.OK, groupResp.status)

            val channelResp = client.post("/api/plugins/airelay/admin/channels") {
                contentType(ContentType.Application.Json)
                setBody(
                    """
                    {
                      "name":"Premium Chat",
                      "protocol":"OPENAI_CHAT",
                      "baseUrl":"mock://premium-chat",
                      "apiKey":"mock-key",
                      "groupId":"premium",
                      "priority":50,
                      "weight":100,
                      "models":[
                        {"publicModelName":"claude-b","upstreamModelName":"claude-b","enabled":true}
                      ]
                    }
                    """.trimIndent()
                )
            }
            assertEquals(HttpStatusCode.OK, channelResp.status)

            val register = client.post("/api/plugins/customer-portal/v1/customer/auth/register") {
                contentType(ContentType.Application.Json)
                setBody("""{"email":"alias-user@example.com","password":"password123","displayName":"Alias User"}""")
            }
            assertEquals(HttpStatusCode.OK, register.status)
            val auth = json.parseToJsonElement(register.bodyAsText()).jsonObject
            val accessToken = auth["accessToken"]!!.jsonPrimitive.content

            val keyResp = client.post("/api/plugins/customer-portal/v1/customer/keys") {
                header("Authorization", "Bearer $accessToken")
                contentType(ContentType.Application.Json)
                setBody("""{"name":"Premium Key","routingGroupId":"premium"}""")
            }
            assertEquals(HttpStatusCode.OK, keyResp.status)
            val rawKey = json.parseToJsonElement(keyResp.bodyAsText()).jsonObject["rawKey"]!!.jsonPrimitive.content

            val relay = client.post("/api/plugins/airelay/v1/chat/completions") {
                header("Authorization", "Bearer $rawKey")
                contentType(ContentType.Application.Json)
                setBody("""{"model":"claude-sonnet-4-6","messages":[{"role":"user","content":"hello"}]}""")
            }
            assertEquals(HttpStatusCode.OK, relay.status, relay.bodyAsText())
            assertTrue(relay.bodyAsText().contains("claude-b"))
        } finally {
            if (previousDataDir == null) {
                System.clearProperty("keel.data.dir")
            } else {
                System.setProperty("keel.data.dir", previousDataDir)
            }
        }
    }
}
