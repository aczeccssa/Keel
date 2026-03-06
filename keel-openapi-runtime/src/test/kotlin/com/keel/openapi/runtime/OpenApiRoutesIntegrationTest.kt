package com.keel.openapi.runtime

import com.keel.contract.dto.KeelResponse
import com.keel.kernel.api.KeelApi
import com.keel.kernel.api.pluginApi
import com.keel.kernel.api.systemApi
import com.keel.kernel.api.typedGet
import com.keel.kernel.api.typedPost
import com.keel.kernel.api.typedRoute
import com.keel.kernel.routing.docRoutes
import com.keel.samples.dbdemo.CreateNoteRequest
import com.keel.samples.dbdemo.Note
import com.keel.samples.dbdemo.NoteListData
import com.keel.samples.helloworld.GreetingData
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenApiRoutesIntegrationTest {
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeTest
    fun setUp() {
        OpenApiRegistry.clear()
    }

    @AfterTest
    fun tearDown() {
        OpenApiRegistry.clear()
    }

    @Test
    fun `docs json contains system and sample plugin routes`() = testApplication {
        application {
            install(ContentNegotiation) {
                json()
            }
            routing {
                systemApi {
                    typedRoute("/plugins") {
                        typedGet<String> {
                            call.respond(KeelResponse.success("ok"))
                        }
                    }
                }
                pluginApi("helloworld") {
                    typedGet<GreetingData> {
                        call.respond(GreetingData("Hello"))
                    }
                }
                pluginApi("dbdemo") {
                    typedRoute("/notes") {
                        typedGet<NoteListData> {
                            call.respond(KeelResponse.success(NoteListData(emptyList(), 0)))
                        }
                        typedPost<CreateNoteRequest, Note> {
                            call.respond(
                                KeelResponse.success(
                                    Note(
                                        id = 1,
                                        title = "Example",
                                        content = "content"
                                    )
                                )
                            )
                        }
                    }
                }
                docRoutes()
            }
        }

        val response = client.get("/api/_system/docs/openapi.json")
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.headers[HttpHeaders.ContentType]?.startsWith(ContentType.Application.Json.toString()) == true)

        val spec = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val paths = spec["paths"]!!.jsonObject
        assertTrue("/api/_system/plugins" in paths)
        assertTrue("/api/plugins/helloworld" in paths)
        assertTrue("/api/plugins/dbdemo/notes" in paths)

        val tags = spec["tags"]!!.jsonArray.map { it.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
        assertTrue("system" in tags)
        assertTrue("helloworld" in tags)
        assertTrue("dbdemo" in tags)

        val schemas = spec["components"]!!.jsonObject["schemas"]!!.jsonObject
        assertTrue("GreetingData" in schemas)
        assertTrue("CreateNoteRequest" in schemas)
        assertTrue("Note" in schemas)
    }

    @Test
    fun `typed routes generate docs`() = testApplication {
        application {
            routing {
                pluginApi("compat") {
                    @KeelApi(summary = "Legacy route", tags = ["compat"], responseEnvelope = false)
                    typedGet<GreetingData>(path = "/legacy") {
                        call.respond(GreetingData("legacy"))
                    }
                }
                docRoutes()
            }
        }

        val response = client.get("/api/_system/docs/openapi.json")
        assertEquals(HttpStatusCode.OK, response.status)
        val spec = json.parseToJsonElement(response.bodyAsText()).jsonObject
        val operation = spec["paths"]!!
            .jsonObject["/api/plugins/compat/legacy"]!!
            .jsonObject["get"]!!
            .jsonObject
        assertTrue(operation.isNotEmpty())
    }

    @Test
    fun `docs html points to openapi json`() = testApplication {
        application {
            routing {
                docRoutes()
            }
        }

        val response = client.get("/api/_system/docs/")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue("SwaggerUIBundle" in body)
        assertTrue("/api/_system/docs/openapi.json" in body)
    }
}
