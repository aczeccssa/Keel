package com.keel.test.kernel

import com.keel.kernel.logging.KeelLoggerService
import com.keel.kernel.routing.logRoutes
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class LogRoutesTest {

    @Test
    fun recentLogsEndpointReturnsOk() = testApplication {
        application {
            install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) {
                json()
            }
            install(SSE)
            routing {
                logRoutes()
            }
        }

        KeelLoggerService.getLogger("LogRoutesTest").info("hello")

        val response = client.get("/api/_system/logs/recent")
        assertEquals(HttpStatusCode.OK, response.status)
    }

    @Test
    fun logLevelCanBeSet() = testApplication {
        application {
            install(io.ktor.server.plugins.contentnegotiation.ContentNegotiation) {
                json()
            }
            install(SSE)
            routing {
                logRoutes()
            }
        }

        val body = Json.encodeToString(mapOf("level" to "DEBUG"))
        val response = client.post("/api/_system/logs/level") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

        assertEquals(HttpStatusCode.OK, response.status)
    }
}
