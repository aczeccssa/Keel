package com.keel.samples.aigateway.riskcontrol

import com.keel.contract.ai.JwtPrincipalVerifier
import com.keel.contract.ai.RateLimitGate
import com.keel.contract.ai.RateLimitSnapshot
import com.keel.contract.ai.RateLimitSnapshotProvider
import com.keel.kernel.plugin.KeelInterceptorResult
import com.keel.kernel.plugin.KeelRequestContext
import com.keel.kernel.plugin.KeelRequestInterceptor
import com.keel.kernel.plugin.PluginApiException
import com.keel.kernel.plugin.PluginDescriptor
import com.keel.kernel.plugin.PluginEndpointBuilders.pluginEndpoints
import com.keel.kernel.plugin.PluginInitContext
import com.keel.kernel.plugin.PluginResult
import com.keel.kernel.plugin.PluginRouteDefinition
import com.keel.kernel.plugin.StandardKeelPlugin
import com.keel.openapi.annotations.KeelApiPlugin
import com.keel.openapi.runtime.OpenApiDoc
import org.koin.dsl.module

@KeelApiPlugin(
    pluginId = "riskcontrol",
    title = "AI Gateway Risk Control Plugin",
    description = "Single-node multi-dimensional token-bucket rate limiting for the AI Gateway sample",
    version = "1.0.0"
)
class RiskControlPlugin : StandardKeelPlugin {
    override val descriptor: PluginDescriptor = PluginDescriptor(
        pluginId = "riskcontrol",
        version = "1.0.0",
        displayName = "AI Gateway Risk Control Plugin"
    )

    lateinit var engine: TokenBucketEngine
        private set
    private lateinit var jwtVerifier: JwtPrincipalVerifier

    override fun modules() = listOf(
        module {
            single { RiskAdminInterceptor(jwtVerifier) }
        }
    )

    override suspend fun onInit(context: PluginInitContext) {
        jwtVerifier = context.kernelKoin.get<JwtPrincipalVerifier>()
        engine = TokenBucketEngine()
        context.kernelKoin.loadModules(
            listOf(
                module {
                    single<RateLimitGate> { engine }
                    single<RateLimitSnapshotProvider> { engine }
                }
            )
        )
    }

    override fun endpoints(): List<PluginRouteDefinition> = pluginEndpoints(descriptor.pluginId) {
        route("/v1") {
            interceptors(RiskAdminInterceptor::class)
            get<RateLimitRuleListResponse>(
                "/rules",
                doc = OpenApiDoc(summary = "List rate-limit rules", tags = listOf("ai-gateway", "riskcontrol"), errorStatuses = setOf(401, 403))
            ) {
                val rules = engine.listRules().map { it.toView() }
                PluginResult(body = RateLimitRuleListResponse(rules, rules.size))
            }
            post<UpsertRateLimitRuleRequest, RateLimitRuleView>(
                "/rules",
                doc = OpenApiDoc(summary = "Create or update rate-limit rule", tags = listOf("ai-gateway", "riskcontrol"), errorStatuses = setOf(400, 401, 403))
            ) { request ->
                PluginResult(body = engine.upsertRule(request).toView())
            }
            put<UpsertRateLimitRuleRequest, RateLimitRuleView>(
                "/rules/{ruleId}",
                doc = OpenApiDoc(summary = "Update rate-limit rule", tags = listOf("ai-gateway", "riskcontrol"), errorStatuses = setOf(400, 401, 403))
            ) { request ->
                val ruleId = pathParameters["ruleId"] ?: throw PluginApiException(400, "Missing ruleId")
                PluginResult(body = engine.upsertRule(request.copy(ruleId = ruleId)).toView())
            }
            delete<ResetRateLimitResponse>(
                "/rules/{ruleId}",
                doc = OpenApiDoc(summary = "Delete rate-limit rule", tags = listOf("ai-gateway", "riskcontrol"), errorStatuses = setOf(401, 403, 404))
            ) {
                val ruleId = pathParameters["ruleId"] ?: throw PluginApiException(400, "Missing ruleId")
                if (!engine.deleteRule(ruleId)) throw PluginApiException(404, "Rule not found")
                PluginResult(body = ResetRateLimitResponse("Rule deleted"))
            }
            get<RateLimitSnapshot>(
                "/snapshot",
                doc = OpenApiDoc(summary = "Get rate-limit snapshot", tags = listOf("ai-gateway", "riskcontrol"), errorStatuses = setOf(401, 403))
            ) {
                PluginResult(body = engine.snapshot())
            }
            get<RateLimitSnapshot>(
                "/buckets",
                doc = OpenApiDoc(summary = "List active rate-limit buckets", tags = listOf("ai-gateway", "riskcontrol"), errorStatuses = setOf(401, 403))
            ) {
                PluginResult(body = engine.snapshot())
            }
            post<ResetRateLimitResponse>(
                "/reset",
                doc = OpenApiDoc(summary = "Reset all rate-limit buckets", tags = listOf("ai-gateway", "riskcontrol"), errorStatuses = setOf(401, 403))
            ) {
                PluginResult(body = ResetRateLimitResponse("Buckets reset", removedBuckets = engine.resetAll()))
            }
        }
    }
}

class RiskAdminInterceptor(
    private val verifier: JwtPrincipalVerifier
) : KeelRequestInterceptor {
    override suspend fun intercept(
        context: KeelRequestContext,
        next: suspend () -> KeelInterceptorResult
    ): KeelInterceptorResult {
        val principal = verifier.verifyAuthorizationHeader(context.requestHeaders["Authorization"]?.firstOrNull())
            ?: return KeelInterceptorResult.reject(401, "Unauthorized")
        if (principal.role != "admin") return KeelInterceptorResult.reject(403, "Forbidden")
        context.principal = principal
        return next()
    }
}
