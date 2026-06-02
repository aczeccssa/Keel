package com.keel.samples.aigateway.airelay

import com.keel.contract.ai.ApiKeyVerifier
import com.keel.contract.ai.PoolChainSnapshot
import com.keel.contract.ai.PoolChainSnapshotProvider
import com.keel.contract.ai.RateLimitGate
import com.keel.contract.ai.UsageRecorder
import com.keel.contract.ai.UserDirectory
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
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import org.koin.core.Koin
import org.koin.dsl.module

@KeelApiPlugin(
    pluginId = "airelay",
    title = "AI Gateway Relay Plugin",
    description = "OpenAI/Anthropic compatible AI relay with IR transcoding, key pools, rate limiting, and usage accounting",
    version = "1.0.0"
)
class AIRelayPlugin : StandardKeelPlugin {
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

    override fun modules() = listOf(
        module {
            single { poolChainManager }
        }
    )

    override suspend fun onInit(context: PluginInitContext) {
        kernelKoin = context.kernelKoin
        settings = AIRelaySettings.load()
        poolChainManager = PoolChainManager(settings.chains)
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
            MockableUpstreamHttpClient()
        }
        context.kernelKoin.loadModules(
            listOf(
                module {
                    single<PoolChainSnapshotProvider> { poolChainManager }
                }
            )
        )
    }

    /**
     * Test hook: install a real upstream client and a fresh pool-chain manager built from
     * the supplied chains. The kernel Koin module that exposes [PoolChainSnapshotProvider]
     * is also reloaded so downstream snapshot readers see the new chains.
     */
    fun installRealUpstream(client: UpstreamHttpClient, chains: List<PoolChainConfig>) {
        this.upstreamClient = MockableUpstreamHttpClient(realClient = client)
        this.poolChainManager = PoolChainManager(chains)
    }

    private fun buildService(): AIRelayService {
        val userDirectory = kernelKoin.get<UserDirectory>()
        val apiKeyVerifier = kernelKoin.get<ApiKeyVerifier>()
        val usageRecorder = kernelKoin.get<UsageRecorder>()
        val rateLimitGate = kernelKoin.get<RateLimitGate>()
        return AIRelayService(
            apiKeyVerifier = apiKeyVerifier,
            usageRecorder = usageRecorder,
            rateLimitGate = rateLimitGate,
            userDirectory = userDirectory,
            poolChainManager = poolChainManager,
            transcoder = transcoder,
            upstreamClient = upstreamClient,
            costCalculator = CostCalculator(pricing, userDirectory)
        )
    }

    override fun endpoints(): List<PluginRouteDefinition> = pluginEndpoints(descriptor.pluginId) {
        route("/v1") {
            post<RelayRequest, RelayResponse>(
                "/chat/completions",
                doc = OpenApiDoc(summary = "OpenAI Chat Completions compatible relay", tags = listOf("ai-gateway", "airelay"), errorStatuses = setOf(400, 401, 402, 403, 429, 503)),
                executionPolicy = EndpointExecutionPolicy(timeoutMs = 60_000, maxPayloadBytes = 2_000_000)
            ) { request ->
                val result = buildService().handleBlocking(this, request, WireProtocol.OPENAI_CHAT)
                PluginResult(
                    status = result.status,
                    headers = result.headers,
                    body = RelayResponse(result.body)
                )
            }
            post<RelayRequest, RelayResponse>(
                "/responses",
                doc = OpenApiDoc(summary = "OpenAI Responses compatible relay", tags = listOf("ai-gateway", "airelay"), errorStatuses = setOf(400, 401, 402, 403, 429, 503)),
                executionPolicy = EndpointExecutionPolicy(timeoutMs = 60_000, maxPayloadBytes = 2_000_000)
            ) { request ->
                val result = buildService().handleBlocking(this, request, WireProtocol.OPENAI_RESPONSES)
                PluginResult(
                    status = result.status,
                    headers = result.headers,
                    body = RelayResponse(result.body)
                )
            }
            post<RelayRequest, RelayResponse>(
                "/messages",
                doc = OpenApiDoc(summary = "Anthropic Messages compatible relay", tags = listOf("ai-gateway", "airelay"), errorStatuses = setOf(400, 401, 402, 403, 429, 503)),
                executionPolicy = EndpointExecutionPolicy(timeoutMs = 60_000, maxPayloadBytes = 2_000_000)
            ) { request ->
                val result = buildService().handleBlocking(this, request, WireProtocol.ANTHROPIC_MESSAGES)
                PluginResult(
                    status = result.status,
                    headers = result.headers,
                    body = RelayResponse(result.body)
                )
            }
            get<ModelListResponse>(
                "/models",
                doc = OpenApiDoc(summary = "List AI Gateway models", tags = listOf("ai-gateway", "airelay"))
            ) {
                PluginResult(body = ModelListResponse(settings.chains.flatMap { chain -> chain.modelAliases.map { ModelView(it, chain.chainId) } }))
            }
        }

        route("/admin") {
            get<PoolChainSnapshot>(
                "/pools",
                doc = OpenApiDoc(summary = "List pool-chain health", tags = listOf("ai-gateway", "airelay", "admin"))
            ) {
                PluginResult(body = poolChainManager.snapshot())
            }
            get<PoolChainSnapshot>(
                "/pools/{chainId}/health",
                doc = OpenApiDoc(summary = "Get pool-chain health", tags = listOf("ai-gateway", "airelay", "admin"), errorStatuses = setOf(404))
            ) {
                val chainId = pathParameters["chainId"] ?: throw PluginApiException(400, "Missing chainId")
                val chain = poolChainManager.snapshot().chains.firstOrNull { it.chainId == chainId }
                    ?: throw PluginApiException(404, "Pool chain not found")
                PluginResult(body = PoolChainSnapshot(listOf(chain)))
            }
            post<PoolResetResponse>(
                "/pools/{chainId}/keys/{keyId}/reset",
                doc = OpenApiDoc(summary = "Reset an upstream key state", tags = listOf("ai-gateway", "airelay", "admin"), errorStatuses = setOf(404))
            ) {
                val chainId = pathParameters["chainId"] ?: throw PluginApiException(400, "Missing chainId")
                val keyId = pathParameters["keyId"] ?: throw PluginApiException(400, "Missing keyId")
                if (!poolChainManager.reset(chainId, keyId)) throw PluginApiException(404, "Key not found")
                PluginResult(body = PoolResetResponse("Key reset"))
            }
            get<PoolConfigResponse>(
                "/config",
                doc = OpenApiDoc(summary = "Get pool chain configuration", tags = listOf("ai-gateway", "airelay", "admin"))
            ) {
                PluginResult(body = PoolConfigResponse(
                    chains = settings.chains,
                    pricings = settings.pricings
                ))
            }
            post<AddChainResponse>(
                "/config/chains",
                doc = OpenApiDoc(summary = "Add a new pool chain", tags = listOf("ai-gateway", "airelay", "admin"), errorStatuses = setOf(400, 409))
            ) {
                throw PluginApiException(501, "Runtime chain addition requires restart in Phase 1. Edit airelay/pools.json and restart.")
            }
        }

        staticResources(
            path = "/ui",
            basePackage = "ai-gateway-ui",
            doc = OpenApiDoc(summary = "AI Proxy management UI", tags = listOf("ai-gateway", "airelay")),
            index = "index.html"
        )
    }
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
data class RelayResponse(
    val response: String
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
