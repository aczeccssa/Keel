package com.keel.samples.aigateway.airelay

import com.keel.contract.ai.ApiKeyVerifier
import com.keel.contract.ai.CostBreakdown
import com.keel.contract.ai.InvalidApiKeyException
import com.keel.contract.ai.QuotaExceededException
import com.keel.contract.ai.RateLimitContext
import com.keel.contract.ai.RateLimitDecision
import com.keel.contract.ai.RateLimitGate
import com.keel.contract.ai.TokenUsage
import com.keel.contract.ai.UsageRecordInput
import com.keel.contract.ai.UsageRecorder
import com.keel.contract.ai.UserDirectory
import com.keel.contract.ai.VerifiedApiKey
import com.keel.kernel.plugin.KeelRequestContext
import com.keel.kernel.plugin.PluginApiException
import com.keel.samples.aigateway.airelay.pool.PoolChainManager
import com.keel.samples.aigateway.airelay.protocol.IrRequest
import com.keel.samples.aigateway.airelay.protocol.ProtocolTranscoder
import com.keel.samples.aigateway.airelay.protocol.WireProtocol
import com.keel.samples.aigateway.airelay.upstream.UpstreamHttpClient
import com.keel.samples.aigateway.airelay.upstream.UpstreamHttpException
import com.keel.samples.aigateway.airelay.usage.CostCalculator
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

data class RelayResult(
    val status: Int = 200,
    val headers: Map<String, List<String>> = emptyMap(),
    val body: String
)

class AIRelayService(
    private val apiKeyVerifier: ApiKeyVerifier,
    private val usageRecorder: UsageRecorder,
    private val rateLimitGate: RateLimitGate,
    private val userDirectory: UserDirectory,
    private val poolChainManager: PoolChainManager,
    private val transcoder: ProtocolTranscoder,
    private val upstreamClient: UpstreamHttpClient,
    private val costCalculator: CostCalculator
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun handleBlocking(
        context: KeelRequestContext,
        request: RelayRequest,
        clientProtocol: WireProtocol
    ): RelayResult {
        val started = kotlinx.datetime.Clock.System.now()

        if (request.previous_response_id != null) {
            return errorResult(400, "previous_response_id is not supported in Phase 1; use store:false and provide full history")
        }

        val rawJson = json.encodeToString(request)
        val ir = try {
            transcoder.decodeRequest(clientProtocol, json.parseToJsonElement(rawJson).jsonObject)
        } catch (e: Exception) {
            return errorResult(400, e.message ?: "Invalid request")
        }

        val verified = verifyKey(context) ?: return errorResult(401, "Missing or invalid virtual API key")

        if (verified.allowedModels.isNotEmpty() && ir.model !in verified.allowedModels) {
            return errorResult(403, "Model '${ir.model}' is not allowed for this key")
        }

        val rateDecision = rateLimitGate.tryAcquire(rateLimitContext(context, verified, ir))
        if (rateDecision is RateLimitDecision.Rejected) {
            usageRecorder.record(rejectionRecord(verified, clientProtocol, ir, 429, "rate_limited", started))
            return RelayResult(
                status = 429,
                headers = mapOf(
                    "Retry-After" to listOf(rateDecision.retryAfterSeconds.toString()),
                    "X-RateLimit-Limit" to listOf(rateDecision.limit.toString()),
                    "X-RateLimit-Remaining" to listOf("0")
                ),
                body = json.encodeToString(buildJsonObject { put("error", JsonPrimitive("Rate limit exceeded")) })
            )
        }
        val extraHeaders = mutableMapOf<String, List<String>>()
        if (rateDecision is RateLimitDecision.Allowed) {
            extraHeaders["X-RateLimit-Limit"] = listOf(rateDecision.limit.toString())
            extraHeaders["X-RateLimit-Remaining"] = listOf(rateDecision.remaining.toString())
        }

        return try {
            if (ir.stream) {
                handleStream(context, clientProtocol, ir, verified, started, extraHeaders)
            } else {
                handleBlockingRelay(context, clientProtocol, ir, verified, started, extraHeaders)
            }
        } catch (e: PluginApiException) {
            errorResult(e.status, e.message)
        } catch (e: Exception) {
            errorResult(500, e.message ?: "Internal error")
        }
    }

    private suspend fun handleBlockingRelay(
        context: KeelRequestContext,
        clientProtocol: WireProtocol,
        ir: IrRequest,
        verified: VerifiedApiKey,
        started: kotlinx.datetime.Instant,
        extraHeaders: Map<String, List<String>>
    ): RelayResult {
        var failoverCount = 0
        var lastError: Throwable? = null
        for (selection in poolChainManager.selectCandidates(ir.model)) {
            val upstreamRequest = transcoder.encodeRequest(selection.level.provider.protocol, ir.copy(stream = false))
            selection.keyState.currentConcurrency.incrementAndGet()
            try {
                val upstream = upstreamClient.send(selection, upstreamRequest)
                val upstreamIr = transcoder.decodeResponse(selection.level.provider.protocol, upstream.body)
                val clientResponse = transcoder.encodeResponse(clientProtocol, upstreamIr)
                val cost = costCalculator.calculate(upstreamIr.model, upstreamIr.usage, verified.userGroupId)
                usageRecorder.record(
                    UsageRecordInput(
                        keyId = verified.keyId, userId = verified.userId, userGroupId = verified.userGroupId,
                        clientProtocol = clientProtocol.name, upstreamProtocol = selection.level.provider.protocol.name,
                        model = ir.model, provider = selection.level.provider.providerId,
                        poolLevelId = selection.level.levelId, upstreamKeyId = selection.keyState.key.keyId,
                        usage = upstreamIr.usage, cost = cost, latencyMs = elapsedMs(started),
                        status = upstream.status, errorCode = null, streamed = false, failoverCount = failoverCount
                    )
                )
                poolChainManager.markSuccess(selection)
                val headers = extraHeaders.toMutableMap()
                headers["X-Cost-USD"] = listOf(cost.totalCostUsd.toString())
                headers["X-Upstream-Protocol"] = listOf(selection.level.provider.protocol.name)
                return RelayResult(status = upstream.status, headers = headers, body = json.encodeToString(clientResponse))
            } catch (error: UpstreamHttpException) {
                poolChainManager.markFailure(selection, error.status, error.message, error.retryAfterSeconds)
                lastError = error
                failoverCount += 1
                if (error.status in 400..499 && error.status != 429) break
            } catch (error: Throwable) {
                poolChainManager.markFailure(selection, null, error.message)
                lastError = error
                failoverCount += 1
            } finally {
                selection.keyState.currentConcurrency.decrementAndGet()
            }
        }
        usageRecorder.record(rejectionRecord(verified, clientProtocol, ir, 503, lastError?.message ?: "pool_exhausted", started))
        return errorResult(503, "All upstream pools exhausted")
    }

    private suspend fun handleStream(
        context: KeelRequestContext,
        clientProtocol: WireProtocol,
        ir: IrRequest,
        verified: VerifiedApiKey,
        started: kotlinx.datetime.Instant,
        extraHeaders: Map<String, List<String>>
    ): RelayResult {
        val selection = poolChainManager.selectCandidates(ir.model).firstOrNull()
            ?: return errorResult(503, "All upstream pools exhausted")
        val upstreamRequest = transcoder.encodeRequest(selection.level.provider.protocol, ir.copy(stream = true))
        val upstreamStream = upstreamClient.stream(selection, upstreamRequest)
        val clientEvents = transcoder.transcodeStream(selection.level.provider.protocol, clientProtocol, upstreamStream)
        val collected = clientEvents.map { it.data ?: "" }.toList().joinToString("\n")
        poolChainManager.markSuccess(selection)
        usageRecorder.record(rejectionRecord(verified, clientProtocol, ir, 200, null, started).copy(streamed = true))
        val headers = extraHeaders.toMutableMap()
        headers["X-Upstream-Protocol"] = listOf(selection.level.provider.protocol.name)
        headers["Content-Type"] = listOf("text/event-stream")
        return RelayResult(status = 200, headers = headers, body = collected)
    }

    private suspend fun verifyKey(context: KeelRequestContext): VerifiedApiKey? {
        val raw = context.requestHeaders["Authorization"]?.firstOrNull()
            ?.removePrefix("Bearer ")
            ?.takeIf { it.startsWith("sk-keel-") }
            ?: return null
        return try {
            apiKeyVerifier.verify(raw, clientIp(context))
        } catch (_: InvalidApiKeyException) {
            null
        } catch (_: QuotaExceededException) {
            null
        }
    }

    private fun rateLimitContext(context: KeelRequestContext, key: VerifiedApiKey, ir: IrRequest) = RateLimitContext(
        ip = clientIp(context), userId = key.userId, keyId = key.keyId,
        model = ir.model, path = context.rawPath, method = context.method
    )

    private fun clientIp(context: KeelRequestContext): String? =
        context.requestHeaders["X-Forwarded-For"]?.firstOrNull()?.split(',')?.firstOrNull()?.trim()
            ?: context.requestHeaders["X-Real-IP"]?.firstOrNull()

    private fun errorResult(status: Int, message: String) = RelayResult(
        status = status, body = json.encodeToString(buildJsonObject { put("error", JsonPrimitive(message)) })
    )

    private fun rejectionRecord(
        key: VerifiedApiKey, clientProtocol: WireProtocol, ir: IrRequest,
        status: Int, errorCode: String?, started: kotlinx.datetime.Instant
    ) = UsageRecordInput(
        keyId = key.keyId, userId = key.userId, userGroupId = key.userGroupId,
        clientProtocol = clientProtocol.name, upstreamProtocol = "none",
        model = ir.model, provider = "none", poolLevelId = null, upstreamKeyId = null,
        usage = TokenUsage(), cost = CostBreakdown(), latencyMs = elapsedMs(started),
        status = status, errorCode = errorCode, streamed = false, failoverCount = 0
    )

    private fun elapsedMs(started: kotlinx.datetime.Instant): Long =
        (kotlinx.datetime.Clock.System.now() - started).inWholeMilliseconds
}
