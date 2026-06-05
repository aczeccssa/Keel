package com.keel.samples.aigateway.airelay

import com.keel.contract.ai.ApiKeyVerifier
import com.keel.contract.ai.CostBreakdown
import com.keel.contract.ai.InvalidApiKeyException
import com.keel.contract.ai.QuotaExceededException
import com.keel.contract.ai.RateLimitContext
import com.keel.contract.ai.RateLimitDecision
import com.keel.contract.ai.RateLimitGate
import com.keel.contract.ai.RequestOutcome
import com.keel.contract.ai.TokenUsage
import com.keel.contract.ai.UsageRecordInput
import com.keel.contract.ai.UsageRecorder
import com.keel.contract.ai.UsageSource
import com.keel.contract.ai.UserDirectory
import com.keel.contract.ai.VerifiedApiKey
import com.keel.contract.customer.ChargeResult
import com.keel.contract.customer.CreditLedger
import com.keel.contract.customer.CustomerApiKeyVerifier
import com.keel.contract.customer.CustomerUsageRow
import com.keel.kernel.plugin.KeelRequestContext
import com.keel.kernel.plugin.PluginApiException
import com.keel.samples.aigateway.airelay.pool.PoolChainManager
import com.keel.samples.aigateway.airelay.protocol.IrItem
import com.keel.samples.aigateway.airelay.protocol.IrRequest
import com.keel.samples.aigateway.airelay.protocol.IrStreamEvent
import com.keel.samples.aigateway.airelay.protocol.ProtocolTranscoder
import com.keel.samples.aigateway.airelay.protocol.WireProtocol
import com.keel.samples.aigateway.airelay.protocol.anthropic.AnthropicStreamObserver
import com.keel.samples.aigateway.airelay.protocol.obj
import com.keel.samples.aigateway.airelay.protocol.string
import com.keel.samples.aigateway.airelay.protocol.textOf
import com.keel.samples.aigateway.airelay.upstream.UpstreamHttpClient
import com.keel.samples.aigateway.airelay.upstream.UpstreamHttpException
import com.keel.samples.aigateway.airelay.usage.CostCalculator
import com.keel.samples.aigateway.airelay.usage.TokenEstimator
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
    private val costCalculator: CostCalculator,
    private val customerKeyVerifier: CustomerApiKeyVerifier? = null,
    private val creditLedger: CreditLedger? = null,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val tokenEstimator = TokenEstimator()

    /** Was the request authenticated with a customer key? Stored so we know to charge. */
    private data class KeyContext(
        val verified: VerifiedApiKey,
        val customerId: String?,
        val customerKeyId: String?,
    )

    suspend fun handleBlocking(
        context: KeelRequestContext,
        rawRequest: JsonObject,
        clientProtocol: WireProtocol
    ): RelayResult {
        val started = kotlinx.datetime.Clock.System.now()
        val ir = try {
            transcoder.decodeRequest(clientProtocol, rawRequest)
        } catch (e: Exception) {
            return protocolError(clientProtocol, 400, "invalid_request_error", e.message ?: "Invalid request")
        }

        validateProtocolHeaders(context, clientProtocol)?.let { return it }

        val keyContext = verifyKey(context) ?: return protocolError(clientProtocol, 401, "authentication_error", "Missing or invalid API key")

        if (keyContext.verified.allowedModels.isNotEmpty() && ir.model !in keyContext.verified.allowedModels) {
            return protocolError(clientProtocol, 403, "permission_error", "Model '${ir.model}' is not allowed for this key")
        }

        // Pre-check balance for customer keys
        if (keyContext.customerId != null) {
            val ledger = creditLedger ?: return protocolError(clientProtocol, 500, "api_error", "Credit system unavailable")
            val balance = ledger.snapshotBalance(keyContext.customerId)
            if (balance <= 0) {
                return protocolError(clientProtocol, 402, "insufficient_quota", "Out of credits. Your balance is $balance credits.")
            }
        }

        val rateDecision = rateLimitGate.tryAcquire(rateLimitContext(context, keyContext.verified, ir))
        if (rateDecision is RateLimitDecision.Rejected) {
            usageRecorder.record(rejectionRecord(keyContext.verified, clientProtocol, ir, 429, "rate_limited", started))
            return rateLimitError(clientProtocol, rateDecision)
        }
        val extraHeaders = mutableMapOf<String, List<String>>()
        if (rateDecision is RateLimitDecision.Allowed) {
            extraHeaders["X-RateLimit-Limit"] = listOf(rateDecision.limit.toString())
            extraHeaders["X-RateLimit-Remaining"] = listOf(rateDecision.remaining.toString())
        }

        return try {
            if (ir.stream) {
                handleStream(context, clientProtocol, rawRequest, ir, keyContext, started, extraHeaders)
            } else {
                handleBlockingRelay(context, clientProtocol, rawRequest, ir, keyContext, started, extraHeaders)
            }
        } catch (e: PluginApiException) {
            protocolError(clientProtocol, e.status, "api_error", e.message)
        } catch (e: Exception) {
            protocolError(clientProtocol, 500, "api_error", e.message ?: "Internal error")
        }
    }

    private suspend fun handleBlockingRelay(
        context: KeelRequestContext,
        clientProtocol: WireProtocol,
        rawRequest: JsonObject,
        ir: IrRequest,
        keyContext: KeyContext,
        started: kotlinx.datetime.Instant,
        extraHeaders: Map<String, List<String>>
    ): RelayResult {
        var failoverCount = 0
        var lastError: Throwable? = null
        for (selection in poolChainManager.selectCandidates(keyContext.verified.routingGroupId, ir.model)) {
            val upstreamHeaders = upstreamHeaderOverrides(context, selection.provider.protocol, ir)
            val upstreamRequest = buildUpstreamRequest(rawRequest, clientProtocol, selection.provider.protocol, ir.copy(model = selection.upstreamModel, stream = false))
            selection.keyState.currentConcurrency.incrementAndGet()
            try {
                val upstream = upstreamClient.send(
                    selection,
                    upstreamRequest,
                    upstreamHeaders
                )
                val upstreamIr = transcoder.decodeResponse(selection.provider.protocol, upstream.body)
                val effectiveUsage = normalizeUsage(
                    ir = ir,
                    usage = upstreamIr.usage,
                    completionText = upstreamIr.output.filterIsInstance<IrItem.Message>().joinToString("\n") { textOf(it.content) }
                )
                val responseBody = if (sameProtocolPassThrough(clientProtocol, selection.provider.protocol)) {
                    upstream.body.toString()
                } else {
                    json.encodeToString(transcoder.encodeResponse(clientProtocol, upstreamIr))
                }
                val cost = costCalculator.calculate(upstreamIr.model, effectiveUsage, keyContext.verified.userGroupId, variantKeyFor(ir))

                // Charge customer credits before recording operator-visible success. If local
                // accounting fails, the upstream succeeded but the relay did not complete the
                // request; do not mark the upstream key as failed/cooldown and do not leave a
                // misleading 200 usage row behind.
                val creditHeaders = try {
                    chargeCustomerCredits(
                        keyContext,
                        effectiveUsage,
                        selection.provider.providerId,
                        keyContext.verified.routingGroupId,
                        clientProtocol,
                        ir.model,
                        upstreamIr.id,
                        variantKeyFor(ir)
                    )
                } catch (error: Throwable) {
                    recordLocalAccountingFailure(keyContext.verified, clientProtocol, ir, started, error)
                    poolChainManager.markSuccess(selection)
                    return protocolError(clientProtocol, 500, "api_error", localAccountingMessage(error))
                }

                try {
                    usageRecorder.record(
                        UsageRecordInput(
                            keyId = keyContext.verified.keyId, userId = keyContext.verified.userId, userGroupId = keyContext.verified.userGroupId,
                            clientProtocol = clientProtocol.name, upstreamProtocol = selection.provider.protocol.name,
                            model = ir.model, provider = selection.provider.providerId,
                            poolLevelId = selection.level.levelId, upstreamKeyId = selection.keyState.key.keyId,
                            usage = effectiveUsage, cost = cost, latencyMs = elapsedMs(started),
                            status = upstream.status, errorCode = null, streamed = false, failoverCount = failoverCount,
                            transportStatus = upstream.status,
                            outcome = if (upstream.status >= 400) RequestOutcome.ERROR else RequestOutcome.SUCCESS,
                            usageSource = if (effectiveUsage.totalTokens > 0) com.keel.contract.ai.UsageSource.PROVIDER else com.keel.contract.ai.UsageSource.NONE,
                        )
                    )
                } catch (error: Throwable) {
                    poolChainManager.markSuccess(selection)
                    return protocolError(clientProtocol, 500, "api_error", localAccountingMessage(error))
                }
                poolChainManager.markSuccess(selection)

                val headers = (extraHeaders + creditHeaders).toMutableMap()
                headers["X-Cost-USD"] = listOf(cost.totalCostUsd.toString())
                headers["X-Upstream-Protocol"] = listOf(selection.provider.protocol.name)
                headers["X-Request-Id"] = listOf(upstreamIr.id)
                return RelayResult(status = upstream.status, headers = headers, body = responseBody)
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
        usageRecorder.record(rejectionRecord(keyContext.verified, clientProtocol, ir, 503, shortErrorCode(lastError), started))
        return protocolError(clientProtocol, 503, "api_error", "All upstream pools exhausted")
    }

    private suspend fun handleStream(
        context: KeelRequestContext,
        clientProtocol: WireProtocol,
        rawRequest: JsonObject,
        ir: IrRequest,
        keyContext: KeyContext,
        started: kotlinx.datetime.Instant,
        extraHeaders: Map<String, List<String>>
    ): RelayResult {
        val selection = poolChainManager.selectCandidates(keyContext.verified.routingGroupId, ir.model).firstOrNull()
            ?: return protocolError(clientProtocol, 503, "api_error", "All upstream pools exhausted")
        val upstreamHeaders = upstreamHeaderOverrides(context, selection.provider.protocol, ir)
        val upstreamRequest = buildUpstreamRequest(rawRequest, clientProtocol, selection.provider.protocol, ir.copy(model = selection.upstreamModel, stream = true))
        selection.keyState.currentConcurrency.incrementAndGet()
        val upstreamStream = upstreamClient.stream(selection, upstreamRequest, upstreamHeaders)
        val observer = if (selection.provider.protocol == WireProtocol.ANTHROPIC_MESSAGES) AnthropicStreamObserver() else null
        val rawEvents = upstreamStream.toList()
        rawEvents.forEach { observer?.observe(it) }
        var lastUsage = TokenUsage()
        val outputText = StringBuilder()
        val decodedEvents = transcoder.decodeStream(selection.provider.protocol, rawEvents.asFlow()).map { event ->
            when (event) {
                is IrStreamEvent.UsageUpdate -> lastUsage = event.usage
                is IrStreamEvent.MessageDelta -> lastUsage = event.usage
                is IrStreamEvent.ResponseDone -> lastUsage = event.finalUsage
                is IrStreamEvent.ResponseStart -> if (event.usage.totalTokens > 0) lastUsage = event.usage
                is IrStreamEvent.TextDelta -> outputText.append(event.delta)
                else -> Unit
            }
            event
        }.toList()
        return try {
            val collected = if (sameProtocolPassThrough(clientProtocol, selection.provider.protocol)) {
                renderSse(rawEvents)
            } else {
                val clientEvents = transcoder.encodeStream(clientProtocol, decodedEvents.asFlow())
                renderSse(clientEvents.toList())
            }
            val streamOutcome = observer?.outcome(transportStatus = 200)
            val isSemanticError = streamOutcome?.outcome == RequestOutcome.ERROR
            val effectiveUsage = if (streamOutcome != null && isSemanticError) {
                if (streamOutcome.usageSource != com.keel.contract.ai.UsageSource.NONE) streamOutcome.usage else TokenUsage()
            } else {
                normalizeUsage(ir, lastUsage, outputText.toString())
            }
            val usageSource = when {
                streamOutcome != null -> if (isSemanticError && streamOutcome.usageSource == com.keel.contract.ai.UsageSource.NONE) com.keel.contract.ai.UsageSource.NONE else streamOutcome.usageSource
                effectiveUsage.totalTokens > 0 || effectiveUsage.cacheCreationInputTokens > 0 || effectiveUsage.cacheReadInputTokens > 0 -> com.keel.contract.ai.UsageSource.PROVIDER
                else -> com.keel.contract.ai.UsageSource.NONE
            }
            val semanticStatus = streamOutcome?.semanticStatus ?: 200
            val errorCode = streamOutcome?.errorType
            val effectiveCost = if (isSemanticError && usageSource == com.keel.contract.ai.UsageSource.NONE) {
                CostBreakdown()
            } else {
                costCalculator.calculate(ir.model, effectiveUsage, keyContext.verified.userGroupId, variantKeyFor(ir))
            }

            // Charge customer only for successful streams or streams with real provider usage.
            val creditHeaders = if (isSemanticError && usageSource == com.keel.contract.ai.UsageSource.NONE) {
                emptyMap()
            } else {
                try {
                    chargeCustomerCredits(
                        keyContext,
                        effectiveUsage,
                        selection.provider.providerId,
                        keyContext.verified.routingGroupId,
                        clientProtocol,
                        ir.model,
                        streamOutcome?.requestId ?: "stream-${started.epochSeconds}",
                        variantKeyFor(ir)
                    )
                } catch (error: Throwable) {
                    recordLocalAccountingFailure(keyContext.verified, clientProtocol, ir, started, error)
                    poolChainManager.markSuccess(selection)
                    return protocolError(clientProtocol, 500, "api_error", localAccountingMessage(error))
                }
            }

            poolChainManager.markSuccess(selection)
            try {
                usageRecorder.record(
                    UsageRecordInput(
                        keyId = keyContext.verified.keyId,
                        userId = keyContext.verified.userId,
                        userGroupId = keyContext.verified.userGroupId,
                        clientProtocol = clientProtocol.name,
                        upstreamProtocol = selection.provider.protocol.name,
                        model = ir.model,
                        provider = selection.provider.providerId,
                        poolLevelId = selection.level.levelId,
                        upstreamKeyId = selection.keyState.key.keyId,
                        usage = effectiveUsage,
                        cost = effectiveCost,
                        latencyMs = elapsedMs(started),
                        status = semanticStatus,
                        errorCode = errorCode,
                        streamed = true,
                        failoverCount = 0,
                        transportStatus = streamOutcome?.transportStatus ?: 200,
                        outcome = streamOutcome?.outcome ?: RequestOutcome.SUCCESS,
                        usageSource = usageSource,
                    )
                )
            } catch (error: Throwable) {
                return protocolError(clientProtocol, 500, "api_error", localAccountingMessage(error))
            }

            val headers = (extraHeaders + creditHeaders).toMutableMap()
            headers["X-Upstream-Protocol"] = listOf(selection.provider.protocol.name)
            headers["X-Cost-USD"] = listOf(effectiveCost.totalCostUsd.toString())
            headers["Content-Type"] = listOf("text/event-stream")
            RelayResult(status = semanticStatus, headers = headers, body = collected)
        } catch (error: UpstreamHttpException) {
            poolChainManager.markFailure(selection, error.status, error.message, error.retryAfterSeconds)
            throw error
        } catch (error: Throwable) {
            poolChainManager.markFailure(selection, null, error.message)
            throw error
        } finally {
            selection.keyState.currentConcurrency.decrementAndGet()
        }
    }

    /** Charge the customer for usage, if this was a customer-authenticated request. */
    private suspend fun chargeCustomerCredits(
        keyContext: KeyContext,
        usage: TokenUsage,
        providerId: String,
        groupId: String,
        clientProtocol: WireProtocol,
        model: String,
        requestId: String,
        variantKey: String? = null,
    ): Map<String, List<String>> {
        if (keyContext.customerId == null || keyContext.customerKeyId == null) return emptyMap()
        val ledger = creditLedger ?: return emptyMap()
        val totalTokens = usage.promptTokens + usage.completionTokens + usage.cacheReadInputTokens + usage.cacheCreationInputTokens
        if (totalTokens == 0) return emptyMap()
        val breakdown = costCalculator.calculate(model, usage, "customer", variantKey)
        val creditCost = estimateCredits(breakdown, usage)
        val usdMicros = (breakdown.totalCostUsd * 1_000_000).toLong()
        val row = CustomerUsageRow(
            model = model,
            groupId = groupId,
            providerId = providerId,
            wireProtocol = clientProtocol.name,
            inputTokens = usage.promptTokens.toLong(),
            outputTokens = usage.completionTokens.toLong(),
            cacheReadInputTokens = usage.cacheReadInputTokens.toLong(),
            cacheCreationInputTokens = usage.cacheCreationInputTokens.toLong(),
            cachedPromptTokens = usage.cachedPromptTokens.toLong(),
            reasoningTokens = usage.reasoningTokens.toLong(),
            inputCostMicros = (breakdown.inputCostUsd * 1_000_000).toLong(),
            outputCostMicros = (breakdown.outputCostUsd * 1_000_000).toLong(),
            cacheWriteCostMicros = (breakdown.cacheWriteCostUsd * 1_000_000).toLong(),
            cacheReadCostMicros = (breakdown.cacheReadCostUsd * 1_000_000).toLong(),
            cacheHitRate = breakdown.cacheHitRate,
            requestId = requestId,
        )
        return when (val result = ledger.chargeForUsage(keyContext.customerId, keyContext.customerKeyId, creditCost, usdMicros, row)) {
            is ChargeResult.Ok -> mapOf("X-Credits-Remaining" to listOf(result.newBalanceCredits.toString()))
            is ChargeResult.Failed -> mapOf("X-Credits-Charge-Failed" to listOf(result.message))
            else -> emptyMap()
        }
    }

    private fun estimateCredits(breakdown: CostBreakdown, usage: TokenUsage): Long {
        val usdMicros = (breakdown.totalCostUsd * 1_000_000).toLong().coerceAtLeast(0)
        if (usdMicros > 0) return usdMicros.coerceAtLeast(1)
        val total = usage.promptTokens + usage.completionTokens + usage.cacheReadInputTokens + usage.cacheCreationInputTokens
        return total.coerceAtLeast(1).toLong()
    }

    private fun variantKeyFor(ir: IrRequest): String? =
        ir.metadata["variantKey"]
            ?: ir.metadata["variant_key"]
            ?: ir.metadata["pricing_variant"]
            ?: ir.serviceTier?.takeIf { it.isNotBlank() }
            ?: ir.extras["variantKey"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: ir.extras["variant_key"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: ir.extras["pricing_variant"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: variantKeyForModel(ir.model)

    private fun variantKeyForModel(model: String): String? = modelVariantSemantics(model).variantKey

    private fun normalizeUsage(ir: IrRequest, usage: TokenUsage, completionText: String): TokenUsage {
        if (usage.totalTokens > 0 || usage.cacheCreationInputTokens > 0 || usage.cacheReadInputTokens > 0) {
            return usage
        }
        val promptText = buildString {
            ir.instructions?.let { append(it).append('\n') }
            ir.systemBlocks.forEach { append(textOf(listOf(it))).append('\n') }
            ir.items.forEach { item ->
                when (item) {
                    is IrItem.Message -> append(textOf(item.content)).append('\n')
                    is IrItem.ToolResult -> append(item.content).append('\n')
                }
            }
        }
        return TokenUsage(
            promptTokens = tokenEstimator.estimate(promptText),
            completionTokens = tokenEstimator.estimate(completionText)
        )
    }

    /** Count tokens for an Anthropic Messages request. Forwards to upstream when possible. */
    suspend fun countTokens(context: KeelRequestContext, request: CountTokensRequest): RelayResult {
        validateProtocolHeaders(context, WireProtocol.ANTHROPIC_MESSAGES)?.let { return it }
        val keyContext = verifyKey(context)
            ?: return protocolError(WireProtocol.ANTHROPIC_MESSAGES, 401, "authentication_error", "Missing or invalid API key")
        val selection = poolChainManager.selectCandidates(keyContext.verified.routingGroupId, request.model).firstOrNull()
            ?: return protocolError(WireProtocol.ANTHROPIC_MESSAGES, 404, "not_found_error", "No upstream available for model ${request.model}")
        val countRequest = buildJsonObject {
            put("model", JsonPrimitive(selection.upstreamModel))
            request.messages?.let { put("messages", JsonArray(it)) }
            request.system?.let { put("system", it) }
            request.tools?.let { put("tools", it) }
            request.input?.let { put("input", it) }
        }
        return try {
            if (selection.provider.protocol == WireProtocol.ANTHROPIC_MESSAGES) {
                val result = upstreamClient.countTokens(
                    selection = selection,
                    request = countRequest,
                    extraHeaders = upstreamHeaderOverrides(context, selection.provider.protocol, IrRequest(model = request.model))
                )
                RelayResult(status = result.status, headers = emptyMap(), body = json.encodeToString(result.body))
            } else {
                fallbackCountTokens(request)
            }
        } catch (e: Exception) {
            fallbackCountTokens(request)
        }
    }

    private suspend fun verifyKey(context: KeelRequestContext): KeyContext? {
        val raw = extractGatewayKey(context)
            ?: return null
        // 1. Try customer key first
        val customer = customerKeyVerifier?.verifyCustomerKey(raw)
        if (customer != null) {
            return KeyContext(
                verified = VerifiedApiKey(
                    keyId = customer.keyId,
                    userId = customer.customerId,
                    userGroupId = "customer",
                    routingGroupId = customer.routingGroupId,
                    allowedModels = emptyList(),
                    rpmLimit = null,
                    tpmLimit = null,
                    remainingBudgetUsd = 0.0,
                ),
                customerId = customer.customerId,
                customerKeyId = customer.keyId,
            )
        }
        // 2. Fall back to legacy operator key
        return try {
            val v = apiKeyVerifier.verify(raw, clientIp(context))
            KeyContext(verified = v, customerId = null, customerKeyId = null)
        } catch (_: InvalidApiKeyException) { null }
        catch (_: QuotaExceededException) { null }
    }

    private fun rateLimitContext(context: KeelRequestContext, key: VerifiedApiKey, ir: IrRequest) = RateLimitContext(
        ip = clientIp(context), userId = key.userId, keyId = key.keyId,
        model = ir.model, path = context.rawPath, method = context.method
    )

    private fun clientIp(context: KeelRequestContext): String? =
        context.requestHeaders["X-Forwarded-For"]?.firstOrNull()?.split(',')?.firstOrNull()?.trim()
            ?: context.requestHeaders["X-Real-IP"]?.firstOrNull()

    private fun extractGatewayKey(context: KeelRequestContext): String? {
        val bearer = context.requestHeaders["Authorization"]?.firstOrNull()
            ?.removePrefix("Bearer ")
            ?.takeIf { it.startsWith("sk-keel-") }
        val anthropic = context.requestHeaders["x-api-key"]?.firstOrNull()
            ?.takeIf { it.startsWith("sk-keel-") }
        return bearer ?: anthropic
    }

    private fun validateProtocolHeaders(context: KeelRequestContext, clientProtocol: WireProtocol): RelayResult? {
        if (clientProtocol != WireProtocol.ANTHROPIC_MESSAGES) return null
        val usedAnthropicAuth = context.requestHeaders["x-api-key"]?.firstOrNull() != null
        val version = context.requestHeaders["anthropic-version"]?.firstOrNull()
        return if (usedAnthropicAuth && version.isNullOrBlank()) {
            protocolError(clientProtocol, 400, "invalid_request_error", "anthropic-version header is required")
        } else null
    }

    private fun buildUpstreamRequest(
        rawRequest: JsonObject,
        clientProtocol: WireProtocol,
        upstreamProtocol: WireProtocol,
        ir: IrRequest,
    ): JsonObject {
        if (sameProtocolPassThrough(clientProtocol, upstreamProtocol)) {
            return patchRequestModel(rawRequest, ir.model)
        }
        return transcoder.encodeRequest(upstreamProtocol, ir)
    }

    private fun patchRequestModel(rawRequest: JsonObject, model: String): JsonObject =
        JsonObject(rawRequest.toMutableMap().apply { put("model", JsonPrimitive(model)) })

    private fun sameProtocolPassThrough(clientProtocol: WireProtocol, upstreamProtocol: WireProtocol): Boolean =
        clientProtocol == upstreamProtocol

    private fun upstreamHeaderOverrides(context: KeelRequestContext, protocol: WireProtocol, ir: IrRequest? = null): Map<String, String> {
        if (protocol != WireProtocol.ANTHROPIC_MESSAGES) return emptyMap()
        val overrides = linkedMapOf<String, String>()
        context.requestHeaders["anthropic-beta"]?.firstOrNull()?.takeIf { it.isNotBlank() }?.let {
            overrides["anthropic-beta"] = it
        }
        if ("anthropic-beta" !in overrides) {
            modelVariantSemantics(ir?.model.orEmpty()).anthropicBeta?.let { overrides["anthropic-beta"] = it }
        }
        context.requestHeaders["anthropic-version"]?.firstOrNull()?.takeIf { it.isNotBlank() }?.let {
            overrides["anthropic-version"] = it
        }
        return overrides
    }

    private fun fallbackCountTokens(request: CountTokensRequest): RelayResult {
        val text = buildList {
            request.messages?.forEach { add(it.toString()) }
            request.input?.let { add(it.toString()) }
            request.system?.let { add(it.toString()) }
        }.joinToString("")
        val estimated = (text.length / 4).coerceAtLeast(1)
        return RelayResult(
            status = 200,
            headers = mapOf("X-Token-Count-Estimate" to listOf("true")),
            body = json.encodeToString(buildJsonObject { put("input_tokens", JsonPrimitive(estimated)) })
        )
    }

    private fun rateLimitError(clientProtocol: WireProtocol, rejected: RateLimitDecision.Rejected): RelayResult {
        val headers = mapOf(
            "Retry-After" to listOf(rejected.retryAfterSeconds.toString()),
            "X-RateLimit-Limit" to listOf(rejected.limit.toString()),
            "X-RateLimit-Remaining" to listOf("0")
        )
        return when (clientProtocol) {
            WireProtocol.ANTHROPIC_MESSAGES -> RelayResult(
                status = 429,
                headers = headers,
                body = anthropicErrorBody("rate_limit_error", "Rate limit exceeded")
            )
            else -> RelayResult(
                status = 429,
                headers = headers,
                body = openAiErrorBody("rate_limit_error", "Rate limit exceeded")
            )
        }
    }

    private fun rejectionRecord(
        key: VerifiedApiKey, clientProtocol: WireProtocol, ir: IrRequest,
        status: Int, errorCode: String?, started: kotlinx.datetime.Instant
    ) = UsageRecordInput(
        keyId = key.keyId, userId = key.userId, userGroupId = key.userGroupId,
        clientProtocol = clientProtocol.name, upstreamProtocol = "none",
        model = ir.model, provider = "none", poolLevelId = null, upstreamKeyId = null,
        usage = TokenUsage(), cost = CostBreakdown(), latencyMs = elapsedMs(started),
        status = status, errorCode = errorCode?.take(64), streamed = false, failoverCount = 0
    )

    private suspend fun recordLocalAccountingFailure(
        key: VerifiedApiKey,
        clientProtocol: WireProtocol,
        ir: IrRequest,
        started: kotlinx.datetime.Instant,
        error: Throwable,
    ) {
        runCatching {
            usageRecorder.record(rejectionRecord(key, clientProtocol, ir, 500, "local_accounting_error", started))
        }
    }

    private fun localAccountingMessage(error: Throwable): String =
        "Local accounting failed after upstream success: ${(error.message ?: error::class.simpleName ?: "unknown").take(240)}"

    private fun shortErrorCode(error: Throwable?): String = when (error) {
        null -> "pool_exhausted"
        is UpstreamHttpException -> "upstream_${error.status}"
        else -> error::class.simpleName?.take(64) ?: "upstream_error"
    }

    private fun elapsedMs(started: kotlinx.datetime.Instant): Long =
        (kotlinx.datetime.Clock.System.now() - started).inWholeMilliseconds

    private fun protocolError(clientProtocol: WireProtocol, status: Int, errorType: String, message: String): RelayResult =
        RelayResult(
            status = status,
            headers = mapOf("Content-Type" to listOf("application/json")),
            body = when (clientProtocol) {
                WireProtocol.ANTHROPIC_MESSAGES -> anthropicErrorBody(errorType, message)
                else -> openAiErrorBody(errorType, message)
            }
        )

    private fun anthropicErrorBody(errorType: String, message: String): String =
        json.encodeToString(buildJsonObject {
            put("type", JsonPrimitive("error"))
            put("error", buildJsonObject {
                put("type", JsonPrimitive(errorType))
                put("message", JsonPrimitive(message))
            })
        })

    private fun openAiErrorBody(errorType: String, message: String): String =
        json.encodeToString(buildJsonObject {
            put("error", buildJsonObject {
                put("message", JsonPrimitive(message))
                put("type", JsonPrimitive(errorType))
                put("code", JsonPrimitive(errorType))
            })
        })

    private fun renderSse(events: List<io.ktor.sse.ServerSentEvent>): String = buildString {
        events.forEach { event ->
            event.id?.let { append("id: ").append(it).append('\n') }
            event.event?.let { append("event: ").append(it).append('\n') }
            event.retry?.let { append("retry: ").append(it).append('\n') }
            val data = event.data.orEmpty()
            if (data.isEmpty()) {
                append("data:").append('\n')
            } else {
                data.lineSequence().forEach { line ->
                    append("data: ").append(line).append('\n')
                }
            }
            append('\n')
        }
    }
}
