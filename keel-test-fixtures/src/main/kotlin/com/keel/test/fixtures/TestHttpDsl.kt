package com.keel.test.fixtures

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType

class TestHttpDsl(
    private val client: HttpClient,
    private val context: KeelTestContext
) {
    suspend fun get(path: String): TestHttpResponse {
        val response = client.get(path)
        return TestHttpResponse("GET", path, response, context)
    }

    suspend fun postJson(path: String, body: Any): TestHttpResponse {
        val response = client.post(path) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        return TestHttpResponse("POST", path, response, context)
    }

    suspend fun putJson(path: String, body: Any): TestHttpResponse {
        val response = client.put(path) {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        return TestHttpResponse("PUT", path, response, context)
    }

    suspend fun delete(path: String): TestHttpResponse {
        val response = client.delete(path)
        return TestHttpResponse("DELETE", path, response, context)
    }
}

class TestHttpResponse(
    private val method: String,
    private val path: String,
    val raw: HttpResponse,
    private val context: KeelTestContext
) {
    val status: Int get() = raw.status.value

    suspend fun expectStatus(expectedCode: Int): TestHttpResponse {
        if (status != expectedCode) {
            val body = runCatching { raw.bodyAsText() }.getOrNull()?.take(400)
            val headers = raw.headers.entries().associate { it.key to it.value.joinToString(",") }
            val diagnostic = HttpFailureDiagnostic(
                method = method,
                path = path,
                expectedStatus = expectedCode,
                actualStatus = status,
                responseSnippet = body,
                responseHeaders = headers
            )
            context.recordHttpFailure(diagnostic)
            error(
                "Expected HTTP status $expectedCode but got $status for $method $path. " +
                    "ResponseBody=${body ?: "(empty)"}"
            )
        }
        return this
    }

    suspend inline fun <reified T> expectBody(block: (T) -> Unit = {}): T {
        val body: T = raw.body()
        block(body)
        return body
    }

    fun expectHeader(name: String, expected: String): TestHttpResponse {
        val actual = raw.headers[name]
        if (actual != expected) {
            context.recordHttpFailure(
                HttpFailureDiagnostic(
                    method = method,
                    path = path,
                    expectedStatus = null,
                    actualStatus = status,
                    responseSnippet = null,
                    responseHeaders = mapOf(name to (actual ?: "(missing)"))
                )
            )
            error("Expected header '$name' to be '$expected' but was '$actual' for $method $path")
        }
        return this
    }

    suspend fun bodyAsText(): String = raw.bodyAsText()
}

suspend fun TestKeelPluginScope.http(block: suspend TestHttpDsl.() -> Unit) {
    val dsl = TestHttpDsl(client, context)
    dsl.block()
}
