package com.keel.samples.aigateway.airelay

import com.keel.contract.ai.ApiKeyVerifier
import com.keel.contract.ai.ModelPricingDirectory
import com.keel.contract.ai.ModelPricingSummary
import com.keel.contract.ai.ModelPricingTierSummary
import com.keel.contract.ai.PoolChainSnapshot
import com.keel.contract.ai.PoolChainSnapshotProvider
import com.keel.contract.ai.RateLimitGate
import com.keel.contract.ai.UsageRecorder
import com.keel.contract.ai.UserDirectory
import com.keel.db.database.DatabaseFactory
import com.keel.kernel.plugin.EndpointExecutionPolicy
import com.keel.kernel.plugin.PluginApiException
import com.keel.kernel.plugin.PluginDescriptor
import com.keel.kernel.plugin.PluginEndpointBuilders.pluginEndpoints
import com.keel.kernel.plugin.PluginInitContext
import com.keel.kernel.plugin.PluginResult
import com.keel.kernel.plugin.PluginRouteDefinition
import com.keel.kernel.plugin.PluginRuntimeMode
import com.keel.kernel.plugin.StandardKeelPlugin
import com.keel.openapi.annotations.KeelApiPlugin
import com.keel.openapi.runtime.OpenApiDoc
import com.keel.samples.aigateway.GatewayDataPaths
import com.keel.samples.aigateway.airelay.batches.BatchListResponse
import com.keel.samples.aigateway.airelay.batches.BatchResultsResponse
import com.keel.samples.aigateway.airelay.batches.BatchView
import com.keel.samples.aigateway.airelay.batches.CreateBatchRequest
import com.keel.samples.aigateway.airelay.config.ChannelRepository
import com.keel.samples.aigateway.airelay.config.ChannelView
import com.keel.samples.aigateway.airelay.config.ConfigService
import com.keel.samples.aigateway.airelay.config.DeletePricingResponse
import com.keel.samples.aigateway.airelay.config.GroupView
import com.keel.samples.aigateway.airelay.config.GroupAliasView
import com.keel.samples.aigateway.airelay.config.GroupMembershipAttachRequest
import com.keel.samples.aigateway.airelay.config.GroupMembershipUpdateRequest
import com.keel.samples.aigateway.airelay.config.GroupMembershipView
import com.keel.samples.aigateway.airelay.config.NavCountsResponse
import com.keel.samples.aigateway.airelay.config.PricingListResponse
import com.keel.samples.aigateway.airelay.config.PricingView
import com.keel.samples.aigateway.airelay.config.SecretCipher
import com.keel.samples.aigateway.airelay.config.UpsertPricingRequest
import com.keel.samples.aigateway.airelay.config.UpsertChannelRequest
import com.keel.samples.aigateway.airelay.config.UpsertChannelMembershipRequest
import com.keel.samples.aigateway.airelay.config.UpsertGroupAliasRequest
import com.keel.samples.aigateway.airelay.config.UpsertGroupRequest
import com.keel.samples.aigateway.airelay.pool.PoolChainManager
import com.keel.samples.aigateway.airelay.protocol.ProtocolTranscoder
import com.keel.samples.aigateway.airelay.protocol.WireProtocol
import com.keel.samples.aigateway.airelay.protocol.anthropic.AnthropicMessagesCodec
import com.keel.samples.aigateway.airelay.protocol.openai.chat.OpenAIChatCodec
import com.keel.samples.aigateway.airelay.protocol.openai.responses.OpenAIResponsesCodec
import com.keel.samples.aigateway.airelay.upstream.MockableUpstreamHttpClient
import com.keel.samples.aigateway.airelay.upstream.RealUpstreamHttpClient
import com.keel.samples.aigateway.airelay.upstream.UpstreamHttpClient
import com.keel.samples.aigateway.airelay.usage.CostCalculator
import com.keel.samples.aigateway.airelay.usage.ModelPricingRegistry
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.Json
import io.ktor.sse.ServerSentEvent
import io.ktor.util.cio.ChannelWriteException
import io.ktor.utils.io.ClosedWriteChannelException
import java.io.IOException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import org.koin.core.Koin
import org.koin.dsl.module

@KeelApiPlugin(
    pluginId = "airelay",
    title = "AI Gateway Relay Plugin",
    description = "OpenAI/Anthropic compatible AI relay with IR transcoding, key pools, rate limiting, and usage accounting",
    version = "1.0.0"
)
class AIRelayPlugin : StandardKeelPlugin {
    private val json = Json { encodeDefaults = true }

    override val descriptor: PluginDescriptor = PluginDescriptor(
        pluginId = "airelay",
        version = "1.0.0",
        displayName = "AI Gateway Relay Plugin",
        defaultRuntimeMode = PluginRuntimeMode.IN_PROCESS,
        supportedRuntimeModes = setOf(PluginRuntimeMode.IN_PROCESS),
        callTimeoutMs = 60_000,
        maxConcurrentCalls = 256
    )

    private lateinit var settings: AIRelaySettings
    private lateinit var poolChainManager: PoolChainManager
    lateinit var kernelKoin: Koin
        private set
    private lateinit var transcoder: ProtocolTranscoder
    private lateinit var pricing: ModelPricingRegistry
    lateinit var upstreamClient: MockableUpstreamHttpClient
        private set
    private var installedUpstreamOverride: UpstreamHttpClient? = null
    private var installedPoolChainOverride: PoolChainManager? = null

    // Runtime-configurable provider/model store (DB-backed). When channels exist, the relay
    // routes through them; otherwise it falls back to the static settings.chains. This is what
    // makes "add a provider in the UI and use it immediately" work without a restart.
    private var configService: ConfigService? = null
    private var channelRepository: ChannelRepository? = null
    private var configDbFactory: DatabaseFactory? = null

    override fun modules() = listOf(
        module {
            single { poolChainManager }
        }
    )

    override suspend fun onInit(context: PluginInitContext) {
        kernelKoin = context.kernelKoin
        settings = AIRelaySettings.load()
        poolChainManager = installedPoolChainOverride ?: PoolChainManager(settings.chains)
        transcoder = ProtocolTranscoder(listOf(OpenAIChatCodec(), OpenAIResponsesCodec(), AnthropicMessagesCodec()))
        pricing = ModelPricingRegistry(settings.pricings)
        // When any chain in the configured set targets a non-mock baseUrl we wire a real
        // HttpClient underneath the mockable client. Tests still get a pure-mock harness
        // because the default settings.chains use `mock://` URLs.
        val needsReal = settings.chains.any { chain ->
            chain.levels.any { !it.provider.baseUrl.startsWith("mock://") }
        }
        upstreamClient = if (needsReal) {
            MockableUpstreamHttpClient(realClient = RealUpstreamHttpClient.create())
        } else {
            MockableUpstreamHttpClient(realClient = RealUpstreamHttpClient.create())
        }

        // DB-backed channel/model configuration. Seeded from airelay/pools.json (if present) or
        // the static defaults on first boot, then editable at runtime via the admin API/UI.
        val factory = DatabaseFactory.h2File(
            filePath = GatewayDataPaths.databasePath("aigateway_airelay"),
            poolSize = 5
        )
        val db = factory.init()
        val repo = ChannelRepository(db, SecretCipher.fromEnv())
        repo.initializeSchema()
        val service = ConfigService(repo)
        if (repo.count() == 0L) {
            AIRelaySettings.seedChannelsIfPresent(repo)
        }
        // Absorb channel-embedded pricing into the standalone table so the Pricing
        // tab can show/edit rates for every model the operator has wired.
        repo.absorbChannelPricingIntoStandalone()
        service.reload()
        configDbFactory = factory
        channelRepository = repo
        configService = service

        context.kernelKoin.loadModules(
            listOf(
                module {
                    single<PoolChainSnapshotProvider> { activeManager() }
                    single<ModelPricingDirectory> {
                        object : ModelPricingDirectory {
                            override fun listActive(): List<ModelPricingSummary> {
                                return service.pricings.map { p ->
                                    ModelPricingSummary(
                                        model = p.model,
                                        variantKey = p.variantKey,
                                        label = p.label,
                                        billingUnitTokens = p.billingUnitTokens,
                                        tiers = p.tiers.map { tier ->
                                            ModelPricingTierSummary(
                                                startTokensInclusive = tier.startTokensInclusive,
                                                endTokensExclusive = tier.endTokensExclusive,
                                                billingUnitTokens = tier.billingUnitTokens,
                                                inputCostPerUnit = tier.inputCostPerUnit,
                                                outputCostPerUnit = tier.outputCostPerUnit,
                                                cacheCreationCostPerUnit = tier.cacheCreationCostPerUnit,
                                                cacheReadCostPerUnit = tier.cacheReadCostPerUnit,
                                                reasoningOutputCostPerUnit = tier.reasoningOutputCostPerUnit,
                                            )
                                        },
                                        inputCostPerMTok = p.inputCostPerMTok,
                                        outputCostPerMTok = p.outputCostPerMTok,
                                        cacheCreationCostPerMTok = p.cacheCreationCostPerMTok,
                                        cacheReadCostPerMTok = p.cacheReadCostPerMTok,
                                        cachedInputDiscount = p.cachedInputDiscount,
                                        reasoningOutputCostPerMTok = p.reasoningOutputCostPerMTok,
                                        creditMultiplier = p.creditMultiplier,
                                    )
                                }
                            }
                        }
                    }
                }
            )
        )
    }

    /** The live pool manager: DB-backed if channels are configured, else the static one. */
    private fun activeManager(): PoolChainManager {
        installedPoolChainOverride?.let { return it }
        val svc = configService ?: return poolChainManager
        val managed = svc.poolChainManager
        return if (managed.snapshot().chains.isNotEmpty()) managed else poolChainManager
    }

    private fun activeUpstreamClient(): UpstreamHttpClient = installedUpstreamOverride ?: upstreamClient

    private fun activePricing(): ModelPricingRegistry {
        val svc = configService ?: return pricing
        return if (svc.pricings.isNotEmpty()) ModelPricingRegistry(svc.pricings) else pricing
    }

    private fun currentModels(groupId: String? = null): List<ModelView> {
        val chains = activeManager().snapshot().chains
        val visibleChains = if (groupId != null) chains.filter { it.chainId == groupId } else chains
        return if (visibleChains.isNotEmpty()) {
            visibleChains.flatMap { chain -> chain.modelAliases.map { ModelView(it, chain.chainId) } }
        } else {
            settings.chains.flatMap { chain -> chain.modelAliases.map { ModelView(it, chain.chainId) } }
        }.distinctBy { it.id }
    }

    private fun buildOpenAiModelListJson(models: List<ModelView>): String =
        buildJsonObject {
            put("object", JsonPrimitive("list"))
            put("data", buildJsonArray {
                models.forEach { model -> add(openAiModelJson(model)) }
            })
        }.toString()

    private fun openAiModelJson(model: ModelView) = buildJsonObject {
        put("id", JsonPrimitive(model.id))
        put("object", JsonPrimitive("model"))
        put("owned_by", JsonPrimitive("keel"))
    }

    private fun buildAnthropicModelListJson(models: List<ModelView>): String {
        return buildJsonObject {
            put("data", buildJsonArray {
                models.forEach { model -> add(anthropicModelJson(model)) }
            })
            put("first_id", JsonPrimitive(models.firstOrNull()?.id.orEmpty()))
            put("last_id", JsonPrimitive(models.lastOrNull()?.id.orEmpty()))
            put("has_more", JsonPrimitive(false))
        }.toString()
    }

    private fun anthropicModelJson(model: ModelView) = buildJsonObject {
        put("type", JsonPrimitive("model"))
        put("id", JsonPrimitive(model.id))
        put("display_name", JsonPrimitive(model.id))
        put("created_at", JsonPrimitive("2026-01-01T00:00:00Z"))
    }

    private fun isAnthropicClient(context: com.keel.kernel.plugin.KeelRequestContext): Boolean =
        context.requestHeaders["x-api-key"]?.firstOrNull() != null ||
            context.requestHeaders["anthropic-version"]?.firstOrNull() != null

    private suspend fun resolveVisibleModelsForRequest(context: com.keel.kernel.plugin.KeelRequestContext): List<ModelView> {
        val raw = extractGatewayKey(context)
        if (raw == null) return currentModels()
        val customerVerifier = runCatching { kernelKoin.get<com.keel.contract.customer.CustomerApiKeyVerifier>() }.getOrNull()
        val customer = customerVerifier?.verifyCustomerKey(raw)
        if (customer != null) return currentModels(customer.routingGroupId)
        val verifier = kernelKoin.get<ApiKeyVerifier>()
        val verified = runCatching { verifier.verify(raw, null) }.getOrNull()
        return currentModels(verified?.routingGroupId)
    }

    private suspend fun resolveVisibleModelForRequest(
        context: com.keel.kernel.plugin.KeelRequestContext,
        modelId: String,
    ): ModelView? {
        val visible = resolveVisibleModelsForRequest(context)
        visible.firstOrNull { it.id == modelId }?.let { return it }
        val fallback = modelVariantSemantics(modelId).routingFallbackModel ?: return null
        val baseModel = visible.firstOrNull { it.id == fallback } ?: return null
        return baseModel.copy(id = modelId)
    }

    private fun extractGatewayKey(context: com.keel.kernel.plugin.KeelRequestContext): String? {
        val bearer = context.requestHeaders["Authorization"]?.firstOrNull()
            ?.removePrefix("Bearer ")
            ?.takeIf { it.startsWith("sk-keel-") }
        val anthropic = context.requestHeaders["x-api-key"]?.firstOrNull()
            ?.takeIf { it.startsWith("sk-keel-") }
        return bearer ?: anthropic
    }

    /**
     * Test hook: install a real upstream client and a fresh pool-chain manager built from
     * the supplied chains. The kernel Koin module that exposes [PoolChainSnapshotProvider]
     * is also reloaded so downstream snapshot readers see the new chains.
     */
    fun installRealUpstream(client: UpstreamHttpClient, chains: List<PoolChainConfig>) {
        installedUpstreamOverride = client
        installedPoolChainOverride = PoolChainManager(chains)
        if (this::poolChainManager.isInitialized) {
            poolChainManager = installedPoolChainOverride!!
        }
    }

    override suspend fun onStop(context: com.keel.kernel.plugin.PluginRuntimeContext) {
        configDbFactory?.close()
    }

    private fun buildService(): AIRelayService {
        val userDirectory = kernelKoin.get<UserDirectory>()
        val apiKeyVerifier = kernelKoin.get<ApiKeyVerifier>()
        val usageRecorder = kernelKoin.get<UsageRecorder>()
        val rateLimitGate = kernelKoin.get<RateLimitGate>()
        val customerKeyVerifier = try { kernelKoin.get<com.keel.contract.customer.CustomerApiKeyVerifier>() } catch (_: Exception) { null }
        val creditLedger = try { kernelKoin.get<com.keel.contract.customer.CreditLedger>() } catch (_: Exception) { null }
        return AIRelayService(
            apiKeyVerifier = apiKeyVerifier,
            usageRecorder = usageRecorder,
            rateLimitGate = rateLimitGate,
            userDirectory = userDirectory,
            poolChainManager = activeManager(),
            transcoder = transcoder,
            upstreamClient = activeUpstreamClient(),
            costCalculator = CostCalculator(activePricing(), userDirectory),
            customerKeyVerifier = customerKeyVerifier,
            creditLedger = creditLedger,
        )
    }

    override fun endpoints(): List<PluginRouteDefinition> = pluginEndpoints(descriptor.pluginId) {
        sse(
            "/usage/stream",
            doc = OpenApiDoc(summary = "Subscribe to AI Gateway dashboard usage snapshots", tags = listOf("ai-gateway", "airelay", "usage"))
        ) {
            val intervalMs = request.streamIntervalMs()
            try {
                while (currentCoroutineContext().isActive) {
                    send(ServerSentEvent(data = json.encodeToString(buildUsageStreamPayload())))
                    delay(intervalMs)
                }
            } catch (_: ChannelWriteException) {
                // Client disconnected.
            } catch (_: ClosedWriteChannelException) {
                // Client disconnected.
            } catch (_: IOException) {
                // Connection reset or broken pipe.
            }
        }

        route("/v1") {
            post<JsonObject, OpenAiChatCompletionResponse>(
                "/chat/completions",
                doc = OpenApiDoc(summary = "OpenAI Chat Completions compatible relay", tags = listOf("ai-gateway", "airelay"), errorStatuses = setOf(400, 401, 402, 403, 429, 503)),
                executionPolicy = EndpointExecutionPolicy(timeoutMs = 120_000, maxPayloadBytes = 200_000_000, allowChunkedTransfer = true)
            ) { request ->
                buildService().handleBlocking(this, request, WireProtocol.OPENAI_CHAT).toOpenAiChatCompletionResult()
            }
            post<JsonObject, OpenAiResponseObject>(
                "/responses",
                doc = OpenApiDoc(summary = "OpenAI Responses compatible relay", tags = listOf("ai-gateway", "airelay", "openai-responses"), errorStatuses = setOf(400, 401, 402, 403, 429, 503)),
                executionPolicy = EndpointExecutionPolicy(timeoutMs = 120_000, maxPayloadBytes = 200_000_000, allowChunkedTransfer = true)
            ) { request ->
                buildService().handleBlocking(this, request, WireProtocol.OPENAI_RESPONSES).toOpenAiResponseResult()
            }
            post<JsonObject, AnthropicMessageResponse>(
                "/messages",
                doc = OpenApiDoc(summary = "Anthropic Messages compatible relay", tags = listOf("ai-gateway", "airelay", "anthropic"), errorStatuses = setOf(400, 401, 402, 403, 429, 503)),
                executionPolicy = EndpointExecutionPolicy(timeoutMs = 120_000, maxPayloadBytes = 200_000_000, allowChunkedTransfer = true)
            ) { request ->
                buildService().handleBlocking(this, request, WireProtocol.ANTHROPIC_MESSAGES).toAnthropicMessageResult()
            }
            // ---- Anthropic Files API (raw proxy) ----
            rawPost("/files",
                doc = OpenApiDoc(summary = "Anthropic Files upload proxy", tags = listOf("ai-gateway", "airelay", "anthropic", "files")),
                executionPolicy = EndpointExecutionPolicy(timeoutMs = 120_000, maxPayloadBytes = 200_000_000, allowChunkedTransfer = true)
            ) { raw ->
                val result = buildService().proxyAnthropicRaw(this, raw, "/v1/files", io.ktor.http.HttpMethod.Post, requireFilesBeta = true)
                PluginResult(status = result.status, headers = result.headers, body = result)
            }
            rawGet("/files") { raw ->
                val result = buildService().proxyAnthropicRaw(this, raw, "/v1/files", io.ktor.http.HttpMethod.Get, requireFilesBeta = true)
                PluginResult(status = result.status, headers = result.headers, body = result)
            }
            rawGet("/files/{fileId}") { raw ->
                val fileId = pathParameters["fileId"] ?: throw PluginApiException(400, "Missing fileId")
                val result = buildService().proxyAnthropicRaw(this, raw, "/v1/files/$fileId", io.ktor.http.HttpMethod.Get, requireFilesBeta = true)
                PluginResult(status = result.status, headers = result.headers, body = result)
            }
            rawGet("/files/{fileId}/content") { raw ->
                val fileId = pathParameters["fileId"] ?: throw PluginApiException(400, "Missing fileId")
                val result = buildService().proxyAnthropicRaw(this, raw, "/v1/files/$fileId/content", io.ktor.http.HttpMethod.Get, requireFilesBeta = true)
                PluginResult(status = result.status, headers = result.headers, body = result)
            }
            rawDelete("/files/{fileId}") { raw ->
                val fileId = pathParameters["fileId"] ?: throw PluginApiException(400, "Missing fileId")
                val result = buildService().proxyAnthropicRaw(this, raw, "/v1/files/$fileId", io.ktor.http.HttpMethod.Delete, requireFilesBeta = true)
                PluginResult(status = result.status, headers = result.headers, body = result)
            }
            // ---- Anthropic Message Batches API (raw proxy) ----
            rawPost("/messages/batches") { raw ->
                val result = buildService().proxyAnthropicRaw(this, raw, "/v1/messages/batches", io.ktor.http.HttpMethod.Post)
                PluginResult(status = result.status, headers = result.headers, body = result)
            }
            rawGet("/messages/batches") { raw ->
                val result = buildService().proxyAnthropicRaw(this, raw, "/v1/messages/batches", io.ktor.http.HttpMethod.Get)
                PluginResult(status = result.status, headers = result.headers, body = result)
            }
            rawGet("/messages/batches/{batchId}") { raw ->
                val batchId = pathParameters["batchId"] ?: throw PluginApiException(400, "Missing batchId")
                val result = buildService().proxyAnthropicRaw(this, raw, "/v1/messages/batches/$batchId", io.ktor.http.HttpMethod.Get)
                PluginResult(status = result.status, headers = result.headers, body = result)
            }
            rawPost("/messages/batches/{batchId}/cancel") { raw ->
                val batchId = pathParameters["batchId"] ?: throw PluginApiException(400, "Missing batchId")
                val result = buildService().proxyAnthropicRaw(this, raw, "/v1/messages/batches/$batchId/cancel", io.ktor.http.HttpMethod.Post)
                PluginResult(status = result.status, headers = result.headers, body = result)
            }
            rawGet("/messages/batches/{batchId}/results") { raw ->
                val batchId = pathParameters["batchId"] ?: throw PluginApiException(400, "Missing batchId")
                val result = buildService().proxyAnthropicRaw(this, raw, "/v1/messages/batches/$batchId/results", io.ktor.http.HttpMethod.Get)
                PluginResult(status = result.status, headers = result.headers, body = result)
            }
            // ---- Models ----
            get<String>(
                "/models",
                doc = OpenApiDoc(summary = "List AI Gateway models", tags = listOf("ai-gateway", "airelay", "anthropic", "openai-responses"))
            ) {
                val visibleModels = resolveVisibleModelsForRequest(this)
                PluginResult(
                    headers = mapOf("Content-Type" to listOf("application/json")),
                    body = if (isAnthropicClient(this)) buildAnthropicModelListJson(visibleModels) else buildOpenAiModelListJson(visibleModels)
                )
            }
            get<String>(
                "/models/{modelId}",
                doc = OpenApiDoc(summary = "Get AI Gateway model", tags = listOf("ai-gateway", "airelay", "anthropic", "openai-responses"), errorStatuses = setOf(404))
            ) {
                val modelId = pathParameters["modelId"] ?: throw PluginApiException(400, "Missing modelId")
                val model = resolveVisibleModelForRequest(this, modelId) ?: throw PluginApiException(404, "Model not found")
                PluginResult(
                    headers = mapOf("Content-Type" to listOf("application/json")),
                    body = if (isAnthropicClient(this)) anthropicModelJson(model).toString() else openAiModelJson(model).toString()
                )
            }
            // ---- Token counting ----
            post<CountTokensRequest, String>(
                "/messages/count_tokens",
                doc = OpenApiDoc(summary = "Count tokens in a Messages request", tags = listOf("ai-gateway", "airelay", "anthropic"), errorStatuses = setOf(400, 401)),
                executionPolicy = EndpointExecutionPolicy(timeoutMs = 30_000, maxPayloadBytes = 2_000_000)
            ) { request ->
                val service = buildService()
                val result = service.countTokens(this, request)
                PluginResult(status = result.status, headers = result.headers, body = result.body.toString())
            }
            // ---- Batches ---- (handled by raw proxy routes above)
        }

        route("/admin") {
            get<PoolChainSnapshot>(
                "/pools",
                doc = OpenApiDoc(summary = "List pool-chain health", tags = listOf("ai-gateway", "airelay", "admin"))
            ) {
                PluginResult(body = activeManager().snapshot())
            }
            get<PoolChainSnapshot>(
                "/pools/{chainId}/health",
                doc = OpenApiDoc(summary = "Get pool-chain health", tags = listOf("ai-gateway", "airelay", "admin"), errorStatuses = setOf(404))
            ) {
                val chainId = pathParameters["chainId"] ?: throw PluginApiException(400, "Missing chainId")
                val chain = activeManager().snapshot().chains.firstOrNull { it.chainId == chainId }
                    ?: throw PluginApiException(404, "Pool chain not found")
                PluginResult(body = PoolChainSnapshot(listOf(chain)))
            }
            post<PoolResetResponse>(
                "/pools/{chainId}/keys/{keyId}/reset",
                doc = OpenApiDoc(summary = "Reset an upstream key state", tags = listOf("ai-gateway", "airelay", "admin"), errorStatuses = setOf(404))
            ) {
                val chainId = pathParameters["chainId"] ?: throw PluginApiException(400, "Missing chainId")
                val keyId = pathParameters["keyId"] ?: throw PluginApiException(400, "Missing keyId")
                if (!activeManager().reset(chainId, keyId)) throw PluginApiException(404, "Key not found")
                PluginResult(body = PoolResetResponse("Key reset"))
            }
            get<PoolConfigResponse>(
                "/config",
                doc = OpenApiDoc(summary = "Get pool chain configuration", tags = listOf("ai-gateway", "airelay", "admin"))
            ) {
                val service = configService
                PluginResult(
                    body = PoolConfigResponse(
                        chains = service?.chains ?: settings.chains,
                        pricings = service?.pricings ?: settings.pricings
                    )
                )
            }

            // ---- Routing Group CRUD — NewAPI-style pools ----
            route("/groups") {
                get<GroupListResponse>(
                    doc = OpenApiDoc(summary = "List routing groups", tags = listOf("ai-gateway", "airelay", "admin"))
                ) {
                    val repo = channelRepository ?: throw PluginApiException(503, "Channel store unavailable")
                    PluginResult(body = GroupListResponse(repo.listGroups()))
                }
                post<UpsertGroupRequest, GroupView>(
                    doc = OpenApiDoc(summary = "Create a routing group", tags = listOf("ai-gateway", "airelay", "admin"), errorStatuses = setOf(400, 409, 503))
                ) { request ->
                    val repo = channelRepository ?: throw PluginApiException(503, "Channel store unavailable")
                    val created = repo.createGroup(request)
                    configService?.reload()
                    PluginResult(body = created)
                }
                put<UpsertGroupRequest, GroupView>(
                    "/{groupId}",
                    doc = OpenApiDoc(summary = "Update a routing group", tags = listOf("ai-gateway", "airelay", "admin"), errorStatuses = setOf(400, 404, 503))
                ) { request ->
                    val repo = channelRepository ?: throw PluginApiException(503, "Channel store unavailable")
                    val groupId = pathParameters["groupId"] ?: throw PluginApiException(400, "Missing groupId")
                    val updated = repo.updateGroup(groupId, request) ?: throw PluginApiException(404, "Group not found")
                    configService?.reload()
                    PluginResult(body = updated)
                }
                delete<DeleteGroupResponse>(
                    "/{groupId}",
                    doc = OpenApiDoc(summary = "Delete an empty routing group", tags = listOf("ai-gateway", "airelay", "admin"), errorStatuses = setOf(400, 404, 409, 503))
                ) {
                    val repo = channelRepository ?: throw PluginApiException(503, "Channel store unavailable")
                    val groupId = pathParameters["groupId"] ?: throw PluginApiException(400, "Missing groupId")
                    if (!repo.deleteGroup(groupId)) throw PluginApiException(409, "Group cannot be deleted; remove channels first")
                    configService?.reload()
                    PluginResult(body = DeleteGroupResponse("Group deleted"))
                }
                get<GroupMembershipListResponse>(
                    "/{groupId}/memberships",
                    doc = OpenApiDoc(summary = "List channels attached to a routing group", tags = listOf("ai-gateway", "airelay", "admin"), errorStatuses = setOf(404, 503))
                ) {
                    val repo = channelRepository ?: throw PluginApiException(503, "Channel store unavailable")
                    val groupId = pathParameters["groupId"] ?: throw PluginApiException(400, "Missing groupId")
                    PluginResult(body = GroupMembershipListResponse(repo.listGroupMemberships(groupId)))
                }
                post<GroupMembershipAttachRequest, GroupMembershipView>(
                    "/{groupId}/memberships",
                    doc = OpenApiDoc(summary = "Attach a channel to a routing group", tags = listOf("ai-gateway", "airelay", "admin"), errorStatuses = setOf(400, 404, 503))
                ) { request ->
                    val repo = channelRepository ?: throw PluginApiException(503, "Channel store unavailable")
                    val groupId = pathParameters["groupId"] ?: throw PluginApiException(400, "Missing groupId")
                    val view = repo.attachChannelToGroupFromGroupSide(groupId, request)
                        ?: throw PluginApiException(404, "Membership not found")
                    configService?.reload()
                    PluginResult(body = view)
                }
                put<GroupMembershipUpdateRequest, GroupMembershipView>(
                    "/{groupId}/memberships/{channelId}",
                    doc = OpenApiDoc(summary = "Update a group channel membership", tags = listOf("ai-gateway", "airelay", "admin"), errorStatuses = setOf(404, 503))
                ) { request ->
                    val repo = channelRepository ?: throw PluginApiException(503, "Channel store unavailable")
                    val groupId = pathParameters["groupId"] ?: throw PluginApiException(400, "Missing groupId")
                    val channelId = pathParameters["channelId"] ?: throw PluginApiException(400, "Missing channelId")
                    val view = repo.updateGroupMembershipFromGroupSide(groupId, channelId, request)
                        ?: throw PluginApiException(404, "Membership not found")
                    configService?.reload()
                    PluginResult(body = view)
                }
                delete<DeleteChannelResponse>(
                    "/{groupId}/memberships/{channelId}",
                    doc = OpenApiDoc(summary = "Detach a channel from a routing group", tags = listOf("ai-gateway", "airelay", "admin"), errorStatuses = setOf(404, 503))
                ) {
                    val repo = channelRepository ?: throw PluginApiException(503, "Channel store unavailable")
                    val groupId = pathParameters["groupId"] ?: throw PluginApiException(400, "Missing groupId")
                    val channelId = pathParameters["channelId"] ?: throw PluginApiException(400, "Missing channelId")
                    if (!repo.detachChannelFromGroupFromGroupSide(groupId, channelId)) throw PluginApiException(404, "Membership not found")
                    configService?.reload()
                    PluginResult(body = DeleteChannelResponse("Membership detached"))
                }
                get<GroupAliasListResponse>(
                    "/{groupId}/aliases",
                    doc = OpenApiDoc(summary = "List group alias routes", tags = listOf("ai-gateway", "airelay", "admin"), errorStatuses = setOf(404, 503))
                ) {
                    val repo = channelRepository ?: throw PluginApiException(503, "Channel store unavailable")
                    val groupId = pathParameters["groupId"] ?: throw PluginApiException(400, "Missing groupId")
                    PluginResult(body = GroupAliasListResponse(repo.listGroupAliases(groupId)))
                }
                put<GroupAliasUpsertListRequest, GroupAliasListResponse>(
                    "/{groupId}/aliases",
                    doc = OpenApiDoc(summary = "Replace group alias routes", tags = listOf("ai-gateway", "airelay", "admin"), errorStatuses = setOf(400, 404, 503))
                ) { request ->
                    val repo = channelRepository ?: throw PluginApiException(503, "Channel store unavailable")
                    val groupId = pathParameters["groupId"] ?: throw PluginApiException(400, "Missing groupId")
                    val aliases = repo.replaceAliasesForGroupFromAdmin(groupId, request.aliasRoutes)
                    configService?.reload()
                    PluginResult(body = GroupAliasListResponse(aliases))
                }
            }

            // ---- Channel (provider) CRUD — DB-backed, hot-applied ----
            route("/channels") {
                get<ChannelListResponse>(
                    doc = OpenApiDoc(summary = "List configured provider channels", tags = listOf("ai-gateway", "airelay", "admin"))
                ) {
                    val repo = channelRepository ?: throw PluginApiException(503, "Channel store unavailable")
                    PluginResult(body = ChannelListResponse(repo.listChannels()))
                }
                post<DiscoverModelsRequest, DiscoverModelsResponse>(
                    "/discover-models",
                    doc = OpenApiDoc(summary = "Fetch models for an unsaved channel draft", tags = listOf("ai-gateway", "airelay", "admin"), errorStatuses = setOf(400, 503))
                ) { request ->
                    val protocol = request.protocol.trim().takeIf { it.isNotBlank() }
                        ?: throw PluginApiException(400, "protocol is required")
                    val baseUrl = request.baseUrl.trim().takeIf { it.isNotBlank() }
                        ?: throw PluginApiException(400, "baseUrl is required")
                    val parsed = runCatching { WireProtocol.valueOf(protocol) }
                        .getOrElse { throw PluginApiException(400, "Unknown protocol $protocol") }
                    val result = RealUpstreamHttpClient.create().discoverModels(
                        baseUrl = baseUrl,
                        protocol = parsed,
                        apiKey = request.apiKey,
                        apiKeyEnv = request.apiKeyEnv
                    )
                    PluginResult(body = DiscoverModelsResponse(result.models, result.latencyMs, result.error))
                }
                post<UpsertChannelRequest, ChannelView>(
                    doc = OpenApiDoc(summary = "Create a provider channel", tags = listOf("ai-gateway", "airelay", "admin"), errorStatuses = setOf(400, 503))
                ) { request ->
                    val repo = channelRepository ?: throw PluginApiException(503, "Channel store unavailable")
                    validateChannel(request)
                    val created = repo.createChannel(request)
                    configService?.reload()
                    PluginResult(body = created)
                }
                put<UpsertChannelRequest, ChannelView>(
                    "/{channelId}",
                    doc = OpenApiDoc(summary = "Update a provider channel", tags = listOf("ai-gateway", "airelay", "admin"), errorStatuses = setOf(400, 404, 503))
                ) { request ->
                    val repo = channelRepository ?: throw PluginApiException(503, "Channel store unavailable")
                    val channelId = pathParameters["channelId"] ?: throw PluginApiException(400, "Missing channelId")
                    validateChannel(request)
                    val updated = repo.updateChannel(channelId, request) ?: throw PluginApiException(404, "Channel not found")
                    configService?.reload()
                    PluginResult(body = updated)
                }
                delete<DeleteChannelResponse>(
                    "/{channelId}",
                    doc = OpenApiDoc(summary = "Delete a provider channel", tags = listOf("ai-gateway", "airelay", "admin"), errorStatuses = setOf(404, 503))
                ) {
                    val repo = channelRepository ?: throw PluginApiException(503, "Channel store unavailable")
                    val channelId = pathParameters["channelId"] ?: throw PluginApiException(400, "Missing channelId")
                    if (!repo.deleteChannel(channelId)) throw PluginApiException(404, "Channel not found")
                    configService?.reload()
                    PluginResult(body = DeleteChannelResponse("Channel deleted"))
                }
                post<ToggleChannelResponse>(
                    "/{channelId}/enabled/{enabled}",
                    doc = OpenApiDoc(summary = "Enable or disable a channel", tags = listOf("ai-gateway", "airelay", "admin"), errorStatuses = setOf(404, 503))
                ) {
                    val repo = channelRepository ?: throw PluginApiException(503, "Channel store unavailable")
                    val channelId = pathParameters["channelId"] ?: throw PluginApiException(400, "Missing channelId")
                    val enabled = pathParameters["enabled"]?.toBooleanStrictOrNull()
                        ?: throw PluginApiException(400, "enabled must be true or false")
                    if (!repo.setEnabled(channelId, enabled)) throw PluginApiException(404, "Channel not found")
                    configService?.reload()
                    PluginResult(body = ToggleChannelResponse(channelId, enabled))
                }
                post<ChannelTestResponse>(
                    "/{channelId}/test",
                    doc = OpenApiDoc(summary = "Send a live test request through a channel", tags = listOf("ai-gateway", "airelay", "admin"), errorStatuses = setOf(404, 503)),
                    executionPolicy = EndpointExecutionPolicy(timeoutMs = 30_000)
                ) {
                    val repo = channelRepository ?: throw PluginApiException(503, "Channel store unavailable")
                    val channelId = pathParameters["channelId"] ?: throw PluginApiException(400, "Missing channelId")
                    val channel = repo.getChannel(channelId) ?: throw PluginApiException(404, "Channel not found")
                    val result = testChannel(channel)
                    repo.recordTestResult(channelId, result.latencyMs, result.error)
                    PluginResult(body = result)
                }
                post<DiscoverModelsResponse>(
                    "/{channelId}/discover-models",
                    doc = OpenApiDoc(summary = "Fetch models for a saved channel", tags = listOf("ai-gateway", "airelay", "admin"), errorStatuses = setOf(404, 503))
                ) {
                    val repo = channelRepository ?: throw PluginApiException(503, "Channel store unavailable")
                    val channelId = pathParameters["channelId"] ?: throw PluginApiException(400, "Missing channelId")
                    val channel = repo.getChannel(channelId) ?: throw PluginApiException(404, "Channel not found")
                    val protocol = runCatching { WireProtocol.valueOf(channel.protocol) }
                        .getOrElse { throw PluginApiException(400, "Unknown protocol ${channel.protocol}") }
                    val result = RealUpstreamHttpClient.create().discoverModels(
                        baseUrl = channel.baseUrl,
                        protocol = protocol,
                        apiKey = repo.decryptedKey(channel.channelId).orEmpty(),
                        apiKeyEnv = channel.apiKeyEnv
                    )
                    PluginResult(body = DiscoverModelsResponse(result.models, result.latencyMs, result.error))
                }
                post<UpsertChannelMembershipRequest, ChannelView>(
                    "/{channelId}/memberships",
                    doc = OpenApiDoc(summary = "Attach or update a channel membership", tags = listOf("ai-gateway", "airelay", "admin"), errorStatuses = setOf(400, 404, 503))
                ) { request ->
                    val repo = channelRepository ?: throw PluginApiException(503, "Channel store unavailable")
                    val channelId = pathParameters["channelId"] ?: throw PluginApiException(400, "Missing channelId")
                    val updated = repo.attachChannelToGroup(
                        channelId = channelId,
                        groupId = request.groupId,
                        priority = request.priority,
                        weight = request.weight,
                        enabled = request.enabled
                    ) ?: throw PluginApiException(404, "Channel not found")
                    configService?.reload()
                    PluginResult(body = updated)
                }
                delete<ChannelView>(
                    "/{channelId}/memberships/{groupId}",
                    doc = OpenApiDoc(summary = "Detach a channel from a group", tags = listOf("ai-gateway", "airelay", "admin"), errorStatuses = setOf(404, 503))
                ) {
                    val repo = channelRepository ?: throw PluginApiException(503, "Channel store unavailable")
                    val channelId = pathParameters["channelId"] ?: throw PluginApiException(400, "Missing channelId")
                    val groupId = pathParameters["groupId"] ?: throw PluginApiException(400, "Missing groupId")
                    val updated = repo.detachChannelFromGroup(channelId, groupId) ?: throw PluginApiException(404, "Channel not found")
                    configService?.reload()
                    PluginResult(body = updated)
                }
                post<ChannelModelTestRequest, ChannelTestResponse>(
                    "/{channelId}/test-model",
                    doc = OpenApiDoc(summary = "Test one model mapping on a channel", tags = listOf("ai-gateway", "airelay", "admin"), errorStatuses = setOf(400, 404, 503)),
                    executionPolicy = EndpointExecutionPolicy(timeoutMs = 30_000)
                ) { body ->
                    val repo = channelRepository ?: throw PluginApiException(503, "Channel store unavailable")
                    val channelId = pathParameters["channelId"] ?: throw PluginApiException(400, "Missing channelId")
                    val channel = repo.getChannel(channelId) ?: throw PluginApiException(404, "Channel not found")
                    val protocol = runCatching { WireProtocol.valueOf(channel.protocol) }
                        .getOrElse { throw PluginApiException(400, "Unknown protocol ${channel.protocol}") }
                    val upstreamModel = body.upstreamModelName?.ifBlank { null } ?: body.publicModelName
                    val result = RealUpstreamHttpClient.create().pingChannel(
                        baseUrl = channel.baseUrl,
                        protocol = protocol,
                        apiKey = repo.decryptedKey(channel.channelId).orEmpty(),
                        apiKeyEnv = channel.apiKeyEnv,
                        model = upstreamModel
                    )
                    PluginResult(body = ChannelTestResponse(result.ok, result.latencyMs, result.error, result.sample))
                }
                get<ChannelStatsResponse>(
                    "/{channelId}/stats",
                    doc = OpenApiDoc(summary = "Get channel statistics", tags = listOf("ai-gateway", "airelay", "admin"), errorStatuses = setOf(404, 503))
                ) {
                    val repo = channelRepository ?: throw PluginApiException(503, "Channel store unavailable")
                    val channelId = pathParameters["channelId"] ?: throw PluginApiException(400, "Missing channelId")
                    val channel = repo.getChannel(channelId) ?: throw PluginApiException(404, "Channel not found")
                    val window = queryParameters["window"]?.firstOrNull() ?: "7d"
                    val stats = calculateChannelStats(channelId, window)
                    PluginResult(body = stats)
                }
            }

            // ---- Pricing CRUD — standalone per-model rate cards ----
            route("/pricing") {
                get<PricingListResponse>(
                    doc = OpenApiDoc(summary = "List all model pricing", tags = listOf("ai-gateway", "airelay", "admin"))
                ) {
                    val repo = channelRepository ?: throw PluginApiException(503, "Channel store unavailable")
                    PluginResult(body = PricingListResponse(repo.listPricings()))
                }
                put<UpsertPricingRequest, PricingView>(
                    doc = OpenApiDoc(summary = "Create or update model pricing", tags = listOf("ai-gateway", "airelay", "admin"), errorStatuses = setOf(400, 503))
                ) { request ->
                    val repo = channelRepository ?: throw PluginApiException(503, "Channel store unavailable")
                    if (request.model.isBlank()) throw PluginApiException(400, "model is required")
                    val view = try {
                        repo.upsertPricing(request)
                    } catch (e: IllegalArgumentException) {
                        throw PluginApiException(400, e.message ?: "Invalid pricing request")
                    }
                    configService?.reload()
                    PluginResult(body = view)
                }
                delete<DeletePricingResponse>(
                    "/{model}",
                    doc = OpenApiDoc(summary = "Delete model pricing", tags = listOf("ai-gateway", "airelay", "admin"), errorStatuses = setOf(404, 503))
                ) {
                    val repo = channelRepository ?: throw PluginApiException(503, "Channel store unavailable")
                    val model = pathParameters["model"] ?: throw PluginApiException(400, "Missing model")
                    val variantKey = queryParameters["variantKey"]?.firstOrNull()?.takeIf { it.isNotBlank() }
                    if (!repo.deletePricing(model, variantKey)) throw PluginApiException(404, "Pricing not found")
                    configService?.reload()
                    PluginResult(body = DeletePricingResponse("Pricing deleted"))
                }
            }

            // ---- Dashboard Stats ----
            get<DashboardStatsResponse>(
                "/stats/dashboard",
                doc = OpenApiDoc(summary = "Get dashboard statistics for monitoring", tags = listOf("ai-gateway", "airelay", "admin"))
            ) {
                val window = queryParameters["window"]?.firstOrNull() ?: "24h"
                val stats = calculateDashboardStats(window)
                PluginResult(body = stats)
            }

            // ---- Nav badge counts (customers / codes / keys) ----
            get<NavCountsResponse>(
                "/nav-counts",
                doc = OpenApiDoc(summary = "Aggregate counts for the manager sidebar", tags = listOf("ai-gateway", "airelay", "admin"))
            ) {
                val customerCount = runCatching { kernelKoin.get<com.keel.contract.customer.CustomerDirectory>().count() }.getOrDefault(0L)
                PluginResult(body = NavCountsResponse(customers = customerCount))
            }
        }

        staticResources(
            path = "/ui",
            basePackage = "ui/ai-gateway-ui",
            doc = OpenApiDoc(summary = "AI Proxy management UI", tags = listOf("ai-gateway", "airelay")),
            index = "index.html"
        )
    }

    private fun validateChannel(request: UpsertChannelRequest) {
        if (request.name.isBlank()) throw PluginApiException(400, "name is required")
        if (request.baseUrl.isBlank()) throw PluginApiException(400, "baseUrl is required")
        if (request.groupId.isBlank()) throw PluginApiException(400, "groupId is required")
        runCatching { WireProtocol.valueOf(request.protocol) }
            .getOrElse { throw PluginApiException(400, "protocol must be one of ${WireProtocol.entries.joinToString()}") }
    }

    /** Fire one tiny live request through the channel and report status/latency. */
    private suspend fun testChannel(channel: ChannelView): ChannelTestResponse {
        val repo = channelRepository ?: return ChannelTestResponse(false, null, "Channel store unavailable")
        val protocol = runCatching { WireProtocol.valueOf(channel.protocol) }
            .getOrElse { return ChannelTestResponse(false, null, "Unknown protocol ${channel.protocol}") }
        val model = channel.models.firstOrNull { it.enabled }?.let {
            it.upstreamModelName.ifBlank { it.publicModelName }
        } ?: return ChannelTestResponse(false, null, "Channel has no enabled model to test")
        val key = repo.decryptedKey(channel.channelId).orEmpty()
        val ping = RealUpstreamHttpClient.create().pingChannel(
            baseUrl = channel.baseUrl,
            protocol = protocol,
            apiKey = key,
            apiKeyEnv = channel.apiKeyEnv,
            model = model
        )
        return ChannelTestResponse(
            ok = ping.ok,
            latencyMs = ping.latencyMs,
            error = ping.error,
            sample = ping.sample
        )
    }

    private suspend fun buildUsageStreamPayload(): UsageStreamPayload {
        val snapshot = runCatching { kernelKoin.get<UsageRecorder>().snapshot() }.getOrNull()
        return UsageStreamPayload(
            totalRequests = snapshot?.totalRequests ?: 0L,
            totalCostUsd = snapshot?.totalCostUsd ?: 0.0,
            totalTokens = snapshot?.totalTokens ?: 0L,
            recentRequests = snapshot?.recentRequests.orEmpty(),
            topModels = snapshot?.topModels.orEmpty(),
            topUsers = snapshot?.topUsers.orEmpty()
        )
    }

    private suspend fun calculateChannelStats(channelId: String, window: String): ChannelStatsResponse {
        val repo = channelRepository ?: throw PluginApiException(503, "Channel store unavailable")
        val usageRecorder = kernelKoin.get<UsageRecorder>()
        val snapshot = usageRecorder.snapshot()

        // Filter records for this channel (upstreamKeyId matches channelId)
        val channelRecords = snapshot.recentRequests.filter { it.upstreamKeyId == channelId || it.channelId == channelId }

        // Get test history
        val recentTests = repo.getRecentTests(channelId, 60)

        // Calculate stats
        val successCount = channelRecords.count { it.status < 400 }
        val successRate = if (channelRecords.isNotEmpty()) successCount.toDouble() / channelRecords.size else 0.0
        val avgLatency = if (channelRecords.isNotEmpty()) channelRecords.map { it.latencyMs }.average().toLong() else 0L
        val totalCost = channelRecords.sumOf { it.totalCostUsd }
        val totalTokens = channelRecords.sumOf { it.totalTokens.toLong() }

        return ChannelStatsResponse(
            channelId = channelId,
            successRate7d = successRate,
            totalRequests7d = channelRecords.size.toLong(),
            avgLatencyMs = avgLatency,
            totalCostUsd = totalCost,
            totalTokens = totalTokens,
            recentTests = recentTests
        )
    }

    private suspend fun calculateDashboardStats(window: String): DashboardStatsResponse {
        val usageRecorder = kernelKoin.get<UsageRecorder>()
        val snapshot = usageRecorder.snapshot()
        val records = snapshot.recentRequests

        // Overview calculations
        val totalRequests = records.size.toLong()
        val successCount = records.count { it.status < 400 }
        val successRate = if (records.isNotEmpty()) successCount.toDouble() / records.size else 0.0
        val totalCost = records.sumOf { it.totalCostUsd }
        val totalTokens = records.sumOf { it.totalTokens.toLong() }
        val avgLatency = if (records.isNotEmpty()) records.map { it.latencyMs }.average().toLong() else 0L

        // Percentile latency calculations
        val sortedLatencies = records.map { it.latencyMs }.sorted()
        val p50 = if (sortedLatencies.isNotEmpty()) sortedLatencies[sortedLatencies.size / 2] else 0L
        val p95 = if (sortedLatencies.isNotEmpty()) sortedLatencies[(sortedLatencies.size * 0.95).toInt().coerceAtMost(sortedLatencies.size - 1)] else 0L
        val p99 = if (sortedLatencies.isNotEmpty()) sortedLatencies[(sortedLatencies.size * 0.99).toInt().coerceAtMost(sortedLatencies.size - 1)] else 0L

        // Cache hit rate (placeholder - needs proper implementation)
        val cacheHitRate = 0.0

        // Channel health
        val poolSnapshot = activeManager().snapshot()
        var healthyChannels = 0
        var cooldownChannels = 0
        var disabledChannels = 0
        poolSnapshot.chains.forEach { chain ->
            chain.levels.forEach { level ->
                level.keys.forEach { key ->
                    when (key.status) {
                        "HEALTHY" -> healthyChannels++
                        "COOLDOWN" -> cooldownChannels++
                        "DISABLED" -> disabledChannels++
                    }
                }
            }
        }

        val overview = DashboardOverview(
            totalRequests = totalRequests,
            successRate = successRate,
            totalCostUsd = totalCost,
            totalTokens = totalTokens,
            avgLatencyMs = avgLatency,
            p50LatencyMs = p50,
            p95LatencyMs = p95,
            p99LatencyMs = p99,
            cacheHitRate = cacheHitRate,
            healthyChannels = healthyChannels,
            cooldownChannels = cooldownChannels,
            disabledChannels = disabledChannels
        )

        // Trends - hourly bucketing with complete metrics
        val now = System.currentTimeMillis()
        val hourlyBuckets = (0..23).map { hour ->
            val bucketStart = now - (hour + 1) * 3600_000
            val bucketEnd = now - hour * 3600_000
            val bucketRecords = records.filter { record ->
                val timestamp = record.createdAt.toLongOrNull() ?: 0L
                timestamp in bucketStart..bucketEnd
            }
            TimeSeriesPoint(
                timestamp = bucketEnd,
                requests = bucketRecords.size.toLong(),
                successRate = if (bucketRecords.isNotEmpty()) bucketRecords.count { it.status < 400 }.toDouble() / bucketRecords.size else 0.0
            )
        }.reversed()

        val tokensByHour = (0..23).map { hour ->
            val bucketStart = now - (hour + 1) * 3600_000
            val bucketEnd = now - hour * 3600_000
            val bucketRecords = records.filter { record ->
                val timestamp = record.createdAt.toLongOrNull() ?: 0L
                timestamp in bucketStart..bucketEnd
            }
            TokenTimeSeriesPoint(
                timestamp = bucketEnd,
                promptTokens = bucketRecords.sumOf { it.usage?.promptTokens?.toLong() ?: 0L },
                completionTokens = bucketRecords.sumOf { it.usage?.completionTokens?.toLong() ?: 0L },
                cacheWriteTokens = bucketRecords.sumOf { it.usage?.cacheCreationInputTokens?.toLong() ?: 0L },
                cacheReadTokens = bucketRecords.sumOf { it.usage?.cacheReadInputTokens?.toLong() ?: 0L },
                costUsd = bucketRecords.sumOf { it.cost?.totalCostUsd ?: 0.0 }
            )
        }.reversed()

        val latencyByHour = (0..23).map { hour ->
            val bucketStart = now - (hour + 1) * 3600_000
            val bucketEnd = now - hour * 3600_000
            val bucketRecords = records.filter { record ->
                val timestamp = record.createdAt.toLongOrNull() ?: 0L
                timestamp in bucketStart..bucketEnd
            }
            val latencies = bucketRecords.map { it.latencyMs }.sorted()
            LatencyTimeSeriesPoint(
                timestamp = bucketEnd,
                p50 = if (latencies.isNotEmpty()) latencies[latencies.size / 2] else 0L,
                p95 = if (latencies.isNotEmpty()) latencies[(latencies.size * 0.95).toInt().coerceAtMost(latencies.size - 1)] else 0L,
                p99 = if (latencies.isNotEmpty()) latencies[(latencies.size * 0.99).toInt().coerceAtMost(latencies.size - 1)] else 0L
            )
        }.reversed()

        val trends = DashboardTrends(
            requestsByHour = hourlyBuckets,
            tokensByHour = tokensByHour,
            latencyByHour = latencyByHour
        )

        // Distribution calculations
        val modelDistribution = records.groupBy { it.model }
            .map { (model, modelRecords) ->
                ModelDistribution(
                    model = model,
                    requests = modelRecords.size.toLong(),
                    percentage = modelRecords.size.toDouble() / records.size * 100,
                    totalCostUsd = modelRecords.sumOf { it.totalCostUsd }
                )
            }
            .sortedByDescending { it.requests }
            .take(10)

        val channelDistribution = records.groupBy { it.upstreamKeyId ?: "unknown" }
            .map { (channelId, channelRecords) ->
                val successCount = channelRecords.count { it.status < 400 }
                ChannelDistribution(
                    channelId = channelId,
                    channelName = channelId, // TODO: map to actual name
                    requests = channelRecords.size.toLong(),
                    successRate = if (channelRecords.isNotEmpty()) successCount.toDouble() / channelRecords.size else 0.0,
                    avgLatencyMs = if (channelRecords.isNotEmpty()) channelRecords.map { it.latencyMs }.average().toLong() else 0L
                )
            }
            .sortedByDescending { it.requests }
            .take(10)

        val groupDistribution = records.groupBy { it.poolLevelId ?: "unknown" }
            .map { (groupId, groupRecords) ->
                val topModels = groupRecords.groupBy { it.model }
                    .entries.sortedByDescending { it.value.size }
                    .take(3)
                    .map { it.key }
                GroupDistribution(
                    groupId = groupId,
                    groupName = groupId, // TODO: map to actual name
                    requests = groupRecords.size.toLong(),
                    totalCostUsd = groupRecords.sumOf { it.totalCostUsd },
                    topModels = topModels
                )
            }
            .sortedByDescending { it.requests }
            .take(10)

        val errorRecords = records.filter { it.status >= 400 }
        val errorDistribution = errorRecords.groupBy { it.errorCode ?: "Unknown" }
            .map { (errorType, errorRecords) ->
                ErrorDistribution(
                    errorType = errorType,
                    count = errorRecords.size.toLong(),
                    percentage = if (records.isNotEmpty()) errorRecords.size.toDouble() / records.size * 100 else 0.0
                )
            }
            .sortedByDescending { it.count }
            .take(10)

        val distributions = DashboardDistributions(
            modelDistribution = modelDistribution,
            channelDistribution = channelDistribution,
            groupDistribution = groupDistribution,
            errorDistribution = errorDistribution
        )

        return DashboardStatsResponse(
            overview = overview,
            trends = trends,
            distributions = distributions
        )
    }

    private fun com.keel.kernel.plugin.KeelRequestContext.streamIntervalMs(): Long =
        queryParameters["intervalMs"]?.firstOrNull()?.toLongOrNull()?.coerceIn(1_000L, 60_000L) ?: 5_000L
}

private fun RelayResult.toOpenAiChatCompletionResult(): PluginResult<OpenAiChatCompletionResponse> {
    @Suppress("UNCHECKED_CAST")
    return PluginResult(status = status, headers = headers, body = body) as PluginResult<OpenAiChatCompletionResponse>
}

private fun RelayResult.toOpenAiResponseResult(): PluginResult<OpenAiResponseObject> {
    @Suppress("UNCHECKED_CAST")
    return PluginResult(status = status, headers = headers, body = body) as PluginResult<OpenAiResponseObject>
}

private fun RelayResult.toAnthropicMessageResult(): PluginResult<AnthropicMessageResponse> {
    @Suppress("UNCHECKED_CAST")
    return PluginResult(status = status, headers = headers, body = body) as PluginResult<AnthropicMessageResponse>
}

@Serializable
data class RelayRequest(
    val model: String,
    val messages: List<RelayMessage>? = null,
    val input: JsonElement? = null,
    val instructions: String? = null,
    val max_tokens: Int? = null,
    val max_output_tokens: Int? = null,
    val temperature: Double? = null,
    val top_p: Double? = null,
    val stop: List<String>? = null,
    val stream: Boolean? = null,
    val store: Boolean? = null,
    val reasoning: JsonElement? = null,
    val tools: JsonElement? = null,
    val response_format: JsonElement? = null,
    val text: JsonElement? = null,
    val previous_response_id: String? = null
)

@Serializable
data class RelayMessage(
    val role: String,
    val content: JsonElement
)

@Serializable
data class OpenAiChatCompletionResponse(
    val id: String,
    @SerialName("object") val objectType: String = "chat.completion",
    val created: Long,
    val model: String,
    val choices: List<OpenAiChatCompletionChoice>,
    val usage: OpenAiTokenUsage
)

@Serializable
data class OpenAiChatCompletionChoice(
    val index: Int,
    val message: OpenAiChatCompletionMessage,
    @SerialName("finish_reason") val finishReason: String? = null
)

@Serializable
data class OpenAiChatCompletionMessage(
    val role: String,
    val content: String? = null
)

@Serializable
data class OpenAiResponseObject(
    val id: String,
    @SerialName("object") val objectType: String = "response",
    @SerialName("created_at") val createdAt: Long,
    val status: String,
    val model: String,
    val output: List<OpenAiResponseOutputItem>,
    @SerialName("output_text") val outputText: String,
    val usage: OpenAiTokenUsage
)

@Serializable
data class OpenAiResponseOutputItem(
    val id: String,
    val type: String,
    val role: String,
    val content: List<OpenAiResponseContent>
)

@Serializable
data class OpenAiResponseContent(
    val type: String,
    val text: String
)

@Serializable
data class OpenAiTokenUsage(
    @SerialName("prompt_tokens") val promptTokens: Int,
    @SerialName("completion_tokens") val completionTokens: Int,
    @SerialName("total_tokens") val totalTokens: Int,
    @SerialName("prompt_tokens_details") val promptTokensDetails: OpenAiPromptTokenDetails? = null,
    @SerialName("completion_tokens_details") val completionTokensDetails: OpenAiCompletionTokenDetails? = null
)

@Serializable
data class OpenAiPromptTokenDetails(
    @SerialName("cached_tokens") val cachedTokens: Int = 0
)

@Serializable
data class OpenAiCompletionTokenDetails(
    @SerialName("reasoning_tokens") val reasoningTokens: Int = 0
)

@Serializable
data class AnthropicMessageResponse(
    val id: String,
    val type: String = "message",
    val role: String = "assistant",
    val model: String,
    val content: List<AnthropicContentBlock>,
    @SerialName("stop_reason") val stopReason: String? = null,
    val usage: AnthropicUsage
)

@Serializable
data class AnthropicContentBlock(
    val type: String,
    val text: String? = null,
    val id: String? = null,
    val name: String? = null,
    val input: Map<String, String>? = null,
    @SerialName("tool_use_id") val toolUseId: String? = null,
    val content: String? = null,
    @SerialName("is_error") val isError: Boolean? = null,
    val thinking: String? = null,
    val data: String? = null
)

@Serializable
data class AnthropicUsage(
    @SerialName("input_tokens") val inputTokens: Int,
    @SerialName("output_tokens") val outputTokens: Int,
    @SerialName("cache_creation_input_tokens") val cacheCreationInputTokens: Int = 0,
    @SerialName("cache_read_input_tokens") val cacheReadInputTokens: Int = 0
)

@Serializable
data class ModelListResponse(val data: List<ModelView>)

@Serializable
data class ModelView(val id: String, val chainId: String, val objectType: String = "model")

@Serializable
data class PoolResetResponse(val message: String)

@Serializable
data class PoolConfigResponse(
    val chains: List<PoolChainConfig>,
    val pricings: List<ModelPricing>
)

@Serializable
data class AddChainResponse(val message: String)

@Serializable
data class GroupListResponse(val groups: List<GroupView>)

@Serializable
data class DeleteGroupResponse(val message: String)

@Serializable
data class GroupMembershipListResponse(val memberships: List<GroupMembershipView>)

@Serializable
data class GroupAliasListResponse(val aliasRoutes: List<GroupAliasView>)

@Serializable
data class GroupAliasUpsertListRequest(val aliasRoutes: List<UpsertGroupAliasRequest>)

@Serializable
data class ChannelListResponse(val channels: List<ChannelView>)

@Serializable
data class DeleteChannelResponse(val message: String)

@Serializable
data class ToggleChannelResponse(val channelId: String, val enabled: Boolean)

@Serializable
data class ChannelTestResponse(
    val ok: Boolean,
    val latencyMs: Long?,
    val error: String?,
    val sample: String? = null
)

@Serializable
data class DiscoverModelsRequest(
    val protocol: String,
    val baseUrl: String,
    val apiKey: String = "",
    val apiKeyEnv: String? = null,
)

@Serializable
data class DiscoverModelsResponse(
    val models: List<String>,
    val latencyMs: Long,
    val error: String? = null,
)

@Serializable
data class ChannelModelTestRequest(
    val publicModelName: String,
    val upstreamModelName: String? = null,
)

@Serializable
data class ChannelStatsResponse(
    val channelId: String,
    val successRate7d: Double,
    val totalRequests7d: Long,
    val avgLatencyMs: Long,
    val totalCostUsd: Double,
    val totalTokens: Long,
    val recentTests: List<com.keel.samples.aigateway.airelay.config.TestRecord>
)

@Serializable
data class DashboardStatsResponse(
    val overview: DashboardOverview,
    val trends: DashboardTrends,
    val distributions: DashboardDistributions
)

@Serializable
data class DashboardOverview(
    val totalRequests: Long,
    val successRate: Double,
    val totalCostUsd: Double,
    val totalTokens: Long,
    val avgLatencyMs: Long,
    val p50LatencyMs: Long,
    val p95LatencyMs: Long,
    val p99LatencyMs: Long,
    val cacheHitRate: Double,
    val healthyChannels: Int,
    val cooldownChannels: Int,
    val disabledChannels: Int
)

@Serializable
data class DashboardTrends(
    val requestsByHour: List<TimeSeriesPoint>,
    val tokensByHour: List<TokenTimeSeriesPoint>,
    val latencyByHour: List<LatencyTimeSeriesPoint>
)

@Serializable
data class TimeSeriesPoint(
    val timestamp: Long,
    val requests: Long,
    val successRate: Double
)

@Serializable
data class TokenTimeSeriesPoint(
    val timestamp: Long,
    val promptTokens: Long,
    val completionTokens: Long,
    val cacheWriteTokens: Long,
    val cacheReadTokens: Long,
    val costUsd: Double
)

@Serializable
data class LatencyTimeSeriesPoint(
    val timestamp: Long,
    val p50: Long,
    val p95: Long,
    val p99: Long
)

@Serializable
data class DashboardDistributions(
    val modelDistribution: List<ModelDistribution>,
    val channelDistribution: List<ChannelDistribution>,
    val groupDistribution: List<GroupDistribution>,
    val errorDistribution: List<ErrorDistribution>
)

@Serializable
data class ModelDistribution(
    val model: String,
    val requests: Long,
    val percentage: Double,
    val totalCostUsd: Double
)

@Serializable
data class ChannelDistribution(
    val channelId: String,
    val channelName: String,
    val requests: Long,
    val successRate: Double,
    val avgLatencyMs: Long
)

@Serializable
data class GroupDistribution(
    val groupId: String,
    val groupName: String,
    val requests: Long,
    val totalCostUsd: Double,
    val topModels: List<String>
)

@Serializable
data class ErrorDistribution(
    val errorType: String,
    val count: Long,
    val percentage: Double
)

@Serializable
data class UsageStreamPayload(
    val totalRequests: Long,
    val totalCostUsd: Double,
    val totalTokens: Long,
    val recentRequests: List<com.keel.contract.ai.UsageRecordView>,
    val topModels: List<com.keel.contract.ai.ModelUsageSummary>,
    val topUsers: List<com.keel.contract.ai.UserUsageSummary>
)

// ---- token counting ----

@Serializable
data class CountTokensRequest(
    val model: String,
    val messages: List<JsonElement>? = null,
    val input: JsonElement? = null,
    val system: JsonElement? = null,
    val tools: JsonElement? = null
)
