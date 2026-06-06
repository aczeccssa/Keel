package com.keel.samples.aigateway.airelay.upstream

import io.ktor.http.HttpMethod

data class RawProxyRequest(
    val method: HttpMethod,
    val path: String,
    val queryString: String = "",
    val headers: Map<String, List<String>> = emptyMap(),
    val body: ByteArray = ByteArray(0),
    val contentType: String? = null,
)

data class RawProxyResponse(
    val status: Int,
    val headers: Map<String, List<String>> = emptyMap(),
    val contentType: String? = null,
    val body: ByteArray = ByteArray(0),
)
