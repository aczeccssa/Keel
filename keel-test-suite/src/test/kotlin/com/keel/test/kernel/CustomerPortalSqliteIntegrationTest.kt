package com.keel.test.kernel

import com.keel.kernel.plugin.UnifiedPluginManager
import com.keel.openapi.runtime.OpenApiRegistry
import com.keel.samples.aigateway.account.AccountPlugin
import com.keel.samples.aigateway.customerportal.CustomerPortalPlugin
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
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CustomerPortalSqliteIntegrationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @AfterTest
    fun teardown() {
        OpenApiRegistry.clear()
        runCatching { stopKoin() }
    }

    @Test
    fun customerPortalRegisterAndLoginWorksWithSqliteBackend() = testApplication {
        val previousDataDir = System.getProperty("keel.data.dir")
        val previousDbKind = System.getProperty("keel.aigateway.db.kind")
        val testDataDir = Files.createTempDirectory("keel-customer-sqlite-").toFile()
        System.setProperty("keel.data.dir", testDataDir.absolutePath)
        System.setProperty("keel.aigateway.db.kind", "sqlite")

        OpenApiRegistry.clear()
        val koin = startKoin {}.koin
        val manager = UnifiedPluginManager(koin)
        manager.registerPlugin(AccountPlugin())
        manager.registerPlugin(CustomerPortalPlugin())

        application {
            install(ContentNegotiation) { json() }
            routing { manager.mountRoutes(this) }
            kotlinx.coroutines.runBlocking {
                manager.startPlugin("account")
                manager.startPlugin("customer-portal")
            }
        }

        try {
            val registerResponse = client.post("/api/plugins/customer-portal/v1/customer/auth/register") {
                contentType(ContentType.Application.Json)
                setBody("""{"email":"sqlite@example.com","password":"sqlite123","displayName":"SQLite User"}""")
            }
            assertEquals(HttpStatusCode.OK, registerResponse.status, registerResponse.bodyAsText())
            val registerBody = json.parseToJsonElement(registerResponse.bodyAsText()).jsonObject
            assertTrue(registerBody["accessToken"]!!.jsonPrimitive.content.isNotBlank())

            val loginResponse = client.post("/api/plugins/customer-portal/v1/customer/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"email":"sqlite@example.com","password":"sqlite123"}""")
            }
            assertEquals(HttpStatusCode.OK, loginResponse.status, loginResponse.bodyAsText())
            val loginBody = json.parseToJsonElement(loginResponse.bodyAsText()).jsonObject
            assertTrue(loginBody["accessToken"]!!.jsonPrimitive.content.isNotBlank())
        } finally {
            if (previousDataDir == null) System.clearProperty("keel.data.dir") else System.setProperty("keel.data.dir", previousDataDir)
            if (previousDbKind == null) System.clearProperty("keel.aigateway.db.kind") else System.setProperty("keel.aigateway.db.kind", previousDbKind)
        }
    }
}
