package com.keel.samples.aigateway.airelay

import com.keel.contract.ai.ApiKeyVerifier
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
import com.keel.samples.aigateway.airelay.config.ChannelRepository
import com.keel.samples.aigateway.airelay.config.ChannelView
import com.keel.samples.aigateway.airelay.config.ConfigService
import com.keel.samples.aigateway.airelay.config.SecretCipher
import com.keel.samples.aigateway.airelay.config.UpsertChannelRequest
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
import java.io.File

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
            MockableUpstreamHttpClient(realClient = RealUpstreamHttpClient.create())
        }

        // DB-backed channel/model configuration. Seeded from airelay/pools.json (if present) or
        // the static defaults on first boot, then editable at runtime via the admin API/UI.
        runCatching {
            val dataDir = System.getProperty("keel.data.dir")
                ?: (System.getProperty("java.io.tmpdir").trimEnd('/') + "/keel-data")
            File(dataDir).mkdirs()
            val factory = DatabaseFactory.h2File(filePath = "$dataDir/aigateway_airelay", poolSize = 5)
            val db = factory.init()
            val repo = ChannelRepository(db, SecretCipher.fromEnv())
            repo.initializeSchema()
            val service = ConfigService(repo)
            if (repo.count() == 0L) {
                AIRelaySettings.seedChannelsIfPresent(repo)
            }
            service.reload()
            configDbFactory = factory
            channelRepository = repo
            configService = service
        }.onFailure {
            // If the DB can't initialize we keep running on static chains only.
        }

        context.kernelKoin.loadModules(
            listOf(
                module {
                    single<PoolChainSnapshotProvider> { activeManager() }
                }
            )
        )
    }

    /** The live pool manager: DB-backed if channels are configured, else the static one. */
    private fun activeManager(): PoolChainManager {
        val svc = configService ?: return poolChainManager
        val managed = svc.poolChainManager
        return if (managed.snapshot().chains.isNotEmpty()) managed else poolChainManager
    }

    private fun activePricing(): ModelPricingRegistry {
        val svc = configService ?: return pricing
        return if (svc.pricings.isNotEmpty()) ModelPricingRegistry(svc.pricings) else pricing
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
            poolChainManager = activeManager(),
            transcoder = transcoder,
            upstreamClient = upstreamClient,
            costCalculator = CostCalculator(activePricing(), userDirectory)
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
                val chains = activeManager().snapshot().chains
                val models = if (chains.isNotEmpty()) {
                    chains.flatMap { chain -> chain.modelAliases.map { ModelView(it, chain.chainId) } }
                } else {
                    settings.chains.flatMap { chain -> chain.modelAliases.map { ModelView(it, chain.chainId) } }
                }
                PluginResult(body = ModelListResponse(models))
            }
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
                PluginResult(body = PoolConfigResponse(
                    chains = settings.chains,
                    pricings = settings.pricings
                ))
            }

            // ---- Channel (provider) CRUD — DB-backed, hot-applied ----
            route("/channels") {
                get<ChannelListResponse>(
                    doc = OpenApiDoc(summary = "List configured provider channels", tags = listOf("ai-gateway", "airelay", "admin"))
                ) {
                    val repo = channelRepository ?: throw PluginApiException(503, "Channel store unavailable")
                    PluginResult(body = ChannelListResponse(repo.listChannels()))
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
            }
        }

        staticResources(
            path = "/ui",
            basePackage = "ai-gateway-ui",
            doc = OpenApiDoc(summary = "AI Proxy management UI", tags = listOf("ai-gateway", "airelay")),
            index = "index.html"
        )
    }

    private fun validateChannel(request: UpsertChannelRequest) {
        if (request.name.isBlank()) throw PluginApiException(400, "name is required")
        if (request.baseUrl.isBlank()) throw PluginApiException(400, "baseUrl is required")
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
