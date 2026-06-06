package com.keel.samples.aigateway.airelay.batches

import kotlinx.serialization.Serializable

@Serializable
data class CreateBatchRequest(val model: String, val groupId: String = "default", val customIds: List<String> = emptyList(), val requests: List<kotlinx.serialization.json.JsonElement>)

@Serializable
data class BatchView(
    val batchId: String, val status: String, val groupId: String,
    val requestCounts: Map<String, Int>, val resultsJsonl: String?,
    val expiresAt: String?, val createdAt: String,
)

@Serializable
data class BatchListResponse(val batches: List<BatchView>, val total: Int)

@Serializable
data class BatchResultsResponse(val jsonlContent: String)
