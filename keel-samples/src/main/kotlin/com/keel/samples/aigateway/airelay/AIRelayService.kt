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
import com.keel.samples.aigateway.airelay.protocol.anthropic.AnthropicErrorMapper
import com.keel.samples.aigateway.airelay.protocol.anthropic.AnthropicStreamObserver
import com.keel.samples.aigateway.airelay.protocol.anthropic.AnthropicStreamOutcome
import com.keel.samples.aigateway.airelay.protocol.openai.responses.ResponsesStreamObserver
import com.keel.samples.aigateway.airelay.protocol.obj
import com.keel.samples.aigateway.airelay.protocol.string
import com.keel.samples.aigateway.airelay.protocol.textOf
import com.keel.samples.aigateway.airelay.upstream.OpenedUpstreamStream
import com.keel.samples.aigateway.airelay.upstream.UpstreamHttpClient
import com.keel.samples.aigateway.airelay.upstream.RawProxyRequest
import com.keel.samples.aigateway.airelay.upstream.RawProxyResponse
import com.keel.samples.aigateway.airelay.upstream.UpstreamHttpException
import com.keel.samples.aigateway.airelay.usage.CreditChargeCalculator
import com.keel.samples.aigateway.airelay.usage.CostCalculator
import com.keel.samples.aigateway.airelay.usage.TokenEstimator
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
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
    val body: Any
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
    private val creditChargeCalculator = CreditChargeCalculator()

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
            usageRecorder.record(
                rejectionRecord(
                    key = keyContext.verified,
                    clientProtocol = clientProtocol,
                    ir = ir,
                    status = 429,
                    errorCode = "rate_limited",
                    errorDetail = "ruleId=${rateDecision.ruleId} retryAfterSeconds=${rateDecision.retryAfterSeconds}",
                    started = started
                )
            )
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
            System.err.println("airelay_error model=${ir.model} client=${clientProtocol} upstream=none status=${e.status} error=api_error message=${e.message?.take(180)}")
            protocolError(clientProtocol, e.status, "api_error", e.message)
        } catch (e: Exception) {
            System.err.println("airelay_error model=${ir.model} client=${clientProtocol} upstream=none status=500 error=${e.javaClass.simpleName?.take(64)} message=${(e.message ?: "").take(180)}")
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
        val candidates = poolChainManager.selectCandidates(keyContext.verified.routingGroupId, ir.model)
        if (candidates.isEmpty()) {
            System.err.println(
                "airelay_pool_exhausted model=${ir.model} client=$clientProtocol detail=${
                    poolChainManager.explainAvailability(keyContext.verified.routingGroupId, ir.model)
                }"
            )
            usageRecorder.record(
                rejectionRecord(
                    key = keyContext.verified,
                    clientProtocol = clientProtocol,
                    ir = ir,
                    status = 503,
                    errorCode = "pool_exhausted",
                    errorDetail = poolChainManager.explainAvailability(keyContext.verified.routingGroupId, ir.model),
                    started = started
                )
            )
            return poolExhaustedError(clientProtocol, ir.model, keyContext.verified.routingGroupId)
        }
        for (selection in candidates) {
            val upstreamHeaders = upstreamHeaderOverrides(context, selection.provider.protocol, ir)
            val upstreamRequest = buildUpstreamRequest(rawRequest, clientProtocol, selection.provider.protocol, ir.copy(model = selection.upstreamModel, stream = false))
            selection.keyState.currentConcurrency.incrementAndGet()
            try {
                val upstream = upstreamClient.send(
                    selection,
                    upstreamRequest,
                    upstreamHeaders
                )
                if (upstream.status >= 400) {
                    val errorMsg = upstream.body.obj("error")?.string("message")
                        ?: upstream.body.string("message")
                        ?: "Upstream returned ${upstream.status}"
                    throw UpstreamHttpException(upstream.status, errorMsg)
                }
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
                val resolvedVariantKey = variantKeyFor(ir)
                val cost = costCalculator.calculate(selection.resolvedModel, effectiveUsage, keyContext.verified.userGroupId, resolvedVariantKey)

                // Charge customer credits before recording operator-visible success. If local
                // accounting fails, the upstream succeeded but the relay did not complete the
                // request; do not mark the upstream key as failed/cooldown and do not leave a
                // misleading 200 usage row behind.
                val creditHeaders = try {
                    chargeCustomerCredits(
                        keyContext,
                        selection,
                        effectiveUsage,
                        selection.provider.providerId,
                        keyContext.verified.routingGroupId,
                        clientProtocol,
                        ir.model,
                        upstreamIr.id,
                        resolvedVariantKey
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
                            model = operatorModelLabel(selection), provider = selection.provider.providerId,
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
                headers["Content-Type"] = listOf("application/json")
                headers["X-Cost-USD"] = listOf(cost.totalCostUsd.toString())
                headers["X-Upstream-Protocol"] = listOf(selection.provider.protocol.name)
                headers["X-Request-Id"] = listOf(upstreamIr.id)
                return RelayResult(status = upstream.status, headers = headers, body = responseBody)
            } catch (error: UpstreamHttpException) {
                System.err.println("airelay_error model=${ir.model} client=${clientProtocol} upstream=${selection.provider.protocol} status=${error.status} error=upstream_${error.status} message=${(error.message ?: "").take(180)}")
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
        System.err.println(
            "airelay_pool_exhausted model=${ir.model} client=$clientProtocol detail=${
                poolChainManager.explainAvailability(keyContext.verified.routingGroupId, ir.model)
            }"
        )
        usageRecorder.record(
            rejectionRecord(
                key = keyContext.verified,
                clientProtocol = clientProtocol,
                ir = ir,
                status = 503,
                errorCode = shortErrorCode(lastError),
                errorDetail = poolChainManager.explainAvailability(keyContext.verified.routingGroupId, ir.model),
                started = started,
                failoverCount = failoverCount
            )
        )
        return poolExhaustedError(clientProtocol, ir.model, keyContext.verified.routingGroupId)
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
        val candidates = poolChainManager.selectCandidates(keyContext.verified.routingGroupId, ir.model)
        if (candidates.isEmpty()) {
            val detail = poolChainManager.explainAvailability(keyContext.verified.routingGroupId, ir.model)
            System.err.println("airelay_pool_exhausted model=${ir.model} client=$clientProtocol stream=true detail=$detail")
            usageRecorder.record(
                rejectionRecord(
                    key = keyContext.verified,
                    clientProtocol = clientProtocol,
                    ir = ir,
                    status = 503,
                    errorCode = "pool_exhausted",
                    errorDetail = detail,
                    started = started,
                    streamed = true
                )
            )
            return poolExhaustedError(clientProtocol, ir.model, keyContext.verified.routingGroupId)
        }

        var failoverCount = 0
        var lastError: Throwable? = null
        for (selection in candidates) {
            val upstreamHeaders = upstreamHeaderOverrides(context, selection.provider.protocol, ir)
            val upstreamRequest = buildUpstreamRequest(
                rawRequest,
                clientProtocol,
                selection.provider.protocol,
                ir.copy(model = selection.upstreamModel, stream = true)
            )
            selection.keyState.currentConcurrency.incrementAndGet()
            var handedOff = false
            try {
                val opened = upstreamClient.openStream(selection, upstreamRequest, upstreamHeaders)
                val anthropicObserver = if (selection.provider.protocol == WireProtocol.ANTHROPIC_MESSAGES) AnthropicStreamObserver() else null
                val responsesObserver = if (selection.provider.protocol == WireProtocol.OPENAI_RESPONSES) ResponsesStreamObserver() else null
                var lastUsage = TokenUsage()
                val outputText = StringBuilder()
                val observedRaw = opened.events.onEach { event ->
                    anthropicObserver?.observe(event)
                    responsesObserver?.observe(event)
                }
                val decodedEvents = transcoder.decodeStream(selection.provider.protocol, observedRaw).onEach { event ->
                    when (event) {
                        is IrStreamEvent.UsageUpdate -> lastUsage = event.usage
                        is IrStreamEvent.MessageDelta -> lastUsage = event.usage
                        is IrStreamEvent.ResponseDone -> lastUsage = event.finalUsage
                        is IrStreamEvent.ResponseStart -> if (event.usage.totalTokens > 0) lastUsage = event.usage
                        is IrStreamEvent.TextDelta -> outputText.append(event.delta)
                        else -> Unit
                    }
                }
                val clientEvents = transcoder.encodeStream(clientProtocol, decodedEvents)
                val headers = extraHeaders.toMutableMap()
                headers["X-Upstream-Protocol"] = listOf(selection.provider.protocol.name)
                headers["Content-Type"] = listOf("text/event-stream")
                handedOff = true
                return RelayResult(
                    status = 200,
                    headers = headers,
                    body = RelaySseContent(clientEvents) { completionError ->
                        finalizeStreamRelay(
                            selection = selection,
                            keyContext = keyContext,
                            clientProtocol = clientProtocol,
                            ir = ir,
                            started = started,
                            failoverCount = failoverCount,
                            completionError = completionError,
                            lastUsage = lastUsage,
                            outputText = outputText.toString(),
                            anthropicObserver = anthropicObserver,
                            responsesObserver = responsesObserver,
                        )
                    }
                )
            } catch (error: UpstreamHttpException) {
                System.err.println("airelay_error model=${ir.model} client=${clientProtocol} upstream=${selection.provider.protocol} status=${error.status} error=upstream_${error.status} message=${(error.message ?: "").take(180)}")
                poolChainManager.markFailure(selection, error.status, error.message, error.retryAfterSeconds)
                lastError = error
                failoverCount += 1
                if (error.status in 400..499 && error.status != 429) break
            } catch (error: Throwable) {
                System.err.println("airelay_error model=${ir.model} client=${clientProtocol} upstream=${selection.provider.protocol} status=500 error=${error.javaClass.simpleName?.take(64)} message=${(error.message ?: "").take(180)}")
                poolChainManager.markFailure(selection, null, error.message)
                lastError = error
                failoverCount += 1
            } finally {
                if (!handedOff) {
                    selection.keyState.currentConcurrency.decrementAndGet()
                }
            }
        }

        val detail = poolChainManager.explainAvailability(keyContext.verified.routingGroupId, ir.model)
        System.err.println("airelay_pool_exhausted model=${ir.model} client=$clientProtocol stream=true detail=$detail")
        usageRecorder.record(
            rejectionRecord(
                key = keyContext.verified,
                clientProtocol = clientProtocol,
                ir = ir,
                status = 503,
                errorCode = shortErrorCode(lastError),
                errorDetail = detail,
                started = started,
                streamed = true,
                failoverCount = failoverCount
            )
        )
        return poolExhaustedError(clientProtocol, ir.model, keyContext.verified.routingGroupId)
    }

    private suspend fun finalizeStreamRelay(
        selection: com.keel.samples.aigateway.airelay.pool.PoolSelection,
        keyContext: KeyContext,
        clientProtocol: WireProtocol,
        ir: IrRequest,
        started: kotlinx.datetime.Instant,
        failoverCount: Int,
        completionError: Throwable?,
        lastUsage: TokenUsage,
        outputText: String,
        anthropicObserver: AnthropicStreamObserver?,
        responsesObserver: ResponsesStreamObserver?,
    ) {
        try {
            val streamOutcome = anthropicObserver?.outcome(transportStatus = 200)
                ?: responsesObserver?.outcome(transportStatus = 200)?.let { ro ->
                    AnthropicStreamOutcome(
                        transportStatus = ro.transportStatus,
                        semanticStatus = ro.semanticStatus,
                        outcome = ro.outcome,
                        errorType = ro.errorType,
                        errorMessage = ro.errorMessage,
                        usage = ro.usage,
                        usageSource = ro.usageSource,
                        requestId = ro.requestId,
                        model = ro.model,
                    )
                }
                ?: completionError?.let { error ->
                    val code = if (isClientDisconnect(error)) "client_disconnect" else "stream_transport_error"
                    AnthropicStreamOutcome(
                        transportStatus = 499,
                        semanticStatus = 499,
                        outcome = RequestOutcome.ERROR,
                        errorType = code,
                        errorMessage = error.message ?: error::class.simpleName,
                        usage = TokenUsage(),
                        usageSource = UsageSource.NONE,
                        requestId = null,
                        model = null,
                    )
                }

            val isSemanticError = streamOutcome?.outcome == RequestOutcome.ERROR
            val effectiveUsage = if (streamOutcome != null && isSemanticError) {
                if (streamOutcome.usageSource != UsageSource.NONE) streamOutcome.usage else TokenUsage()
            } else {
                normalizeUsage(ir, lastUsage, outputText)
            }
            val usageSource = when {
                streamOutcome != null -> if (isSemanticError && streamOutcome.usageSource == UsageSource.NONE) UsageSource.NONE else streamOutcome.usageSource
                effectiveUsage.totalTokens > 0 || effectiveUsage.cacheCreationInputTokens > 0 || effectiveUsage.cacheReadInputTokens > 0 -> UsageSource.PROVIDER
                else -> UsageSource.NONE
            }
            val semanticStatus = streamOutcome?.semanticStatus ?: 200
            val errorCode = streamOutcome?.errorType
            val errorDetail = streamOutcome?.errorMessage ?: completionError?.message
            val effectiveCost = if (isSemanticError && usageSource == UsageSource.NONE) {
                CostBreakdown()
            } else {
                costCalculator.calculate(selection.resolvedModel, effectiveUsage, keyContext.verified.userGroupId, variantKeyFor(ir))
            }

            if (!(isSemanticError && usageSource == UsageSource.NONE)) {
                runCatching {
                    chargeCustomerCredits(
                        keyContext,
                        selection,
                        effectiveUsage,
                        selection.provider.providerId,
                        keyContext.verified.routingGroupId,
                        clientProtocol,
                        ir.model,
                        streamOutcome?.requestId ?: "stream-${started.epochSeconds}",
                        variantKeyFor(ir)
                    )
                }.onFailure { error ->
                    System.err.println("airelay_accounting_error model=${ir.model} client=${clientProtocol} message=${(error.message ?: "").take(180)}")
                    recordLocalAccountingFailure(keyContext.verified, clientProtocol, ir, started, error)
                }
            }

            runCatching {
                usageRecorder.record(
                    UsageRecordInput(
                        keyId = keyContext.verified.keyId,
                        userId = keyContext.verified.userId,
                        userGroupId = keyContext.verified.userGroupId,
                        clientProtocol = clientProtocol.name,
                        upstreamProtocol = selection.provider.protocol.name,
                        model = operatorModelLabel(selection),
                        provider = selection.provider.providerId,
                        poolLevelId = selection.level.levelId,
                        upstreamKeyId = selection.keyState.key.keyId,
                        usage = effectiveUsage,
                        cost = effectiveCost,
                        latencyMs = elapsedMs(started),
                        status = semanticStatus,
                        errorCode = errorCode,
                        streamed = true,
                        failoverCount = failoverCount,
                        transportStatus = streamOutcome?.transportStatus ?: 200,
                        outcome = streamOutcome?.outcome ?: RequestOutcome.SUCCESS,
                        usageSource = usageSource,
                        errorDetail = errorDetail?.take(4_000),
                    )
                )
            }

            if (completionError == null || isClientDisconnect(completionError)) {
                poolChainManager.markSuccess(selection)
            } else {
                poolChainManager.markFailure(selection, null, completionError.message)
            }
        } finally {
            selection.keyState.currentConcurrency.decrementAndGet()
        }
    }

    private fun isClientDisconnect(error: Throwable): Boolean {
        val message = error.message?.lowercase().orEmpty()
        return message.contains("broken pipe") || message.contains("connection reset") || message.contains("channel was closed")
    }

    /** Charge the customer for usage, if this was a customer-authenticated request. */
    private suspend fun chargeCustomerCredits(
        keyContext: KeyContext,
        selection: com.keel.samples.aigateway.airelay.pool.PoolSelection,
        usage: TokenUsage,
        providerId: String,
        groupId: String,
        clientProtocol: WireProtocol,
        requestedModel: String,
        requestId: String,
        variantKey: String? = null,
    ): Map<String, List<String>> {
        if (keyContext.customerId == null || keyContext.customerKeyId == null) return emptyMap()
        val ledger = creditLedger ?: return emptyMap()
        val totalTokens = usage.promptTokens + usage.completionTokens + usage.cacheReadInputTokens + usage.cacheCreationInputTokens
        if (totalTokens == 0) return emptyMap()
        val pricingModel = selection.resolvedModel
        val breakdown = costCalculator.calculate(pricingModel, usage, "customer", variantKey)
        val creditCost = creditChargeCalculator.calculate(
            usage = usage,
            modelCreditMultiplier = costCalculator.creditMultiplier(pricingModel, variantKey),
            aliasCreditMultiplier = selection.matchedAliasCreditMultiplier,
        )
        val usdMicros = (breakdown.totalCostUsd * 1_000_000).toLong()
        val row = CustomerUsageRow(
            model = requestedModel,
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

    private fun variantKeyFor(ir: IrRequest): String? =
        ir.metadata["variantKey"]
            ?: ir.metadata["variant_key"]
            ?: ir.metadata["pricing_variant"]
            ?: ir.serviceTier?.takeIf { it.isNotBlank() }
            ?: ir.extras["variantKey"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: ir.extras["variant_key"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: ir.extras["pricing_variant"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: variantKeyForModel(ir.model)

    private fun operatorModelLabel(selection: com.keel.samples.aigateway.airelay.pool.PoolSelection): String {
        val alias = selection.matchedAliasName ?: return selection.requestedModel
        val requestedAlias = selection.requestedModel.takeIf { it.isNotBlank() } ?: alias
        val actual = selection.upstreamModel.takeIf { it.isNotBlank() } ?: selection.resolvedModel
        return if (actual.isBlank() || actual == requestedAlias) requestedAlias else "$requestedAlias -> $actual"
    }

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
        status: Int,
        errorCode: String?,
        started: kotlinx.datetime.Instant,
        errorDetail: String? = null,
        streamed: Boolean = false,
        failoverCount: Int = 0,
        upstreamProtocol: String = "none",
        provider: String = "none",
        poolLevelId: String? = null,
        upstreamKeyId: String? = null,
    ) = UsageRecordInput(
        keyId = key.keyId, userId = key.userId, userGroupId = key.userGroupId,
        clientProtocol = clientProtocol.name, upstreamProtocol = upstreamProtocol,
        model = ir.model, provider = provider, poolLevelId = poolLevelId, upstreamKeyId = upstreamKeyId,
        usage = TokenUsage(), cost = CostBreakdown(), latencyMs = elapsedMs(started),
        status = status,
        errorCode = errorCode?.take(64),
        streamed = streamed,
        failoverCount = failoverCount,
        errorDetail = errorDetail?.take(4_000)
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

    private fun poolExhaustedError(
        clientProtocol: WireProtocol,
        model: String,
        groupId: String,
    ): RelayResult {
        val detail = poolChainManager.explainAvailability(groupId, model)
        return protocolError(
            clientProtocol = clientProtocol,
            status = 503,
            errorType = "api_error",
            message = "All upstream pools exhausted for model $model because the routed upstream capacity is currently saturated.",
            headers = errorHeaders("pool_exhausted", detail)
        )
    }

    private fun errorHeaders(errorCode: String, detail: String? = null): Map<String, List<String>> = buildMap {
        put("Content-Type", listOf("application/json"))
        put("X-Keel-Error-Code", listOf(errorCode))
        detail?.takeIf { it.isNotBlank() }?.let { put("X-Keel-Error-Detail", listOf(it.take(512))) }
    }

    suspend fun proxyAnthropicRaw(
        context: KeelRequestContext,
        raw: com.keel.kernel.plugin.RawPluginRequest,
        upstreamPath: String,
        method: io.ktor.http.HttpMethod,
        requireFilesBeta: Boolean = false,
    ): com.keel.kernel.plugin.RawPluginResponse {
        validateProtocolHeaders(context, WireProtocol.ANTHROPIC_MESSAGES)?.let { error ->
            return com.keel.kernel.plugin.RawPluginResponse(
                status = error.status,
                headers = error.headers,
                contentType = "application/json",
                body = error.body.toString().encodeToByteArray()
            )
        }
        val keyContext = verifyKey(context) ?: return com.keel.kernel.plugin.RawPluginResponse(
            status = 401,
            headers = mapOf("Content-Type" to listOf("application/json")),
            contentType = "application/json",
            body = anthropicErrorBody("authentication_error", "Missing or invalid API key").toByteArray()
        )
        val selection = poolChainManager.selectProtocolCandidates(
            keyContext.verified.routingGroupId, WireProtocol.ANTHROPIC_MESSAGES
        ).firstOrNull() ?: return com.keel.kernel.plugin.RawPluginResponse(
            status = 503,
            headers = mapOf("Content-Type" to listOf("application/json")),
            contentType = "application/json",
            body = anthropicErrorBody("api_error", "No Anthropic upstream available").toByteArray()
        )
        val extraHeaders = upstreamHeaderOverrides(context, WireProtocol.ANTHROPIC_MESSAGES).toMutableMap()
        if (requireFilesBeta) {
            val beta = raw.headers["anthropic-beta"]?.firstOrNull()
                ?: raw.headers["Anthropic-Beta"]?.firstOrNull()
            if (beta.isNullOrBlank()) extraHeaders["anthropic-beta"] = "files-api-2025-04-14"
        }
        return try {
            val response = upstreamClient.proxyRaw(
                selection = selection,
                request = RawProxyRequest(
                    method = method,
                    path = upstreamPath,
                    queryString = raw.query.entries.joinToString("&") { (k, values) ->
                        values.joinToString("&") { v -> "${java.net.URLEncoder.encode(k, "UTF-8")}=${java.net.URLEncoder.encode(v, "UTF-8")}" }
                    },
                    headers = raw.headers,
                    body = raw.body,
                    contentType = raw.headers["Content-Type"]?.firstOrNull()
                        ?: raw.headers["content-type"]?.firstOrNull(),
                ),
                extraHeaders = extraHeaders
            )
            com.keel.kernel.plugin.RawPluginResponse(
                status = response.status,
                headers = response.headers,
                contentType = response.contentType,
                body = response.body
            )
        } catch (error: Throwable) {
            com.keel.kernel.plugin.RawPluginResponse(
                status = 502,
                headers = mapOf("Content-Type" to listOf("application/json")),
                contentType = "application/json",
                body = anthropicErrorBody("api_error", "Raw proxy failed: ${(error.message ?: error::class.simpleName ?: "unknown").take(200)}").toByteArray()
            )
        }
    }

    private fun protocolError(
        clientProtocol: WireProtocol,
        status: Int,
        errorType: String,
        message: String,
        headers: Map<String, List<String>> = mapOf("Content-Type" to listOf("application/json"))
    ): RelayResult =
        RelayResult(
            status = status,
            headers = headers,
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
            append(renderServerSentEvent(event))
        }
    }
}
