package com.keel.samples.aigateway.riskcontrol

import com.keel.contract.ai.RateLimitBucketView
import com.keel.contract.ai.RateLimitContext
import com.keel.contract.ai.RateLimitDecision
import com.keel.contract.ai.RateLimitGate
import com.keel.contract.ai.RateLimitRejectionView
import com.keel.contract.ai.RateLimitSnapshot
import com.keel.contract.ai.RateLimitSnapshotProvider
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.ceil

class TokenBucketEngine(
    initialRules: List<RateLimitRule> = defaultRules()
) : RateLimitGate, RateLimitSnapshotProvider {
    private val rules = AtomicReference(initialRules)
    private val buckets = ConcurrentHashMap<String, Bucket>()
    private val totalAllowed = AtomicLong(0)
    private val totalRejected = AtomicLong(0)
    private val recentRejections = ConcurrentLinkedDeque<RateLimitRejectionView>()

    override suspend fun tryAcquire(context: RateLimitContext): RateLimitDecision {
        val matched = rules.get()
            .asSequence()
            .filter { it.enabled }
            .filter { context.method.uppercase() in it.methods.map(String::uppercase).toSet() }
            .filter { matchesPath(it.pathPattern, context.path) }
            .sortedByDescending { it.priority }
            .toList()

        var strictest: RateLimitDecision.Allowed? = null
        for (rule in matched) {
            val key = buildKey(rule, context) ?: continue
            val bucket = buckets.computeIfAbsent(key.cacheKey()) {
                Bucket(key, rule.capacity, rule.refillRatePerSec)
            }
            val decision = acquire(bucket, rule)
            when (decision) {
                is RateLimitDecision.Rejected -> return decision
                is RateLimitDecision.Allowed -> {
                    if (strictest == null || decision.remaining < strictest.remaining) strictest = decision
                }
            }
        }
        return strictest ?: RateLimitDecision.Allowed(Int.MAX_VALUE, Int.MAX_VALUE, 0L)
    }

    fun listRules(): List<RateLimitRule> = rules.get().sortedWith(compareByDescending<RateLimitRule> { it.priority }.thenBy { it.ruleId })

    fun upsertRule(request: UpsertRateLimitRuleRequest): RateLimitRule {
        require(request.capacity > 0) { "capacity must be > 0" }
        require(request.refillRatePerSec > 0.0) { "refillRatePerSec must be > 0" }
        val rule = RateLimitRule(
            ruleId = request.ruleId?.trim()?.takeIf(String::isNotBlank) ?: "rl-${System.currentTimeMillis()}",
            name = request.name.trim().ifBlank { "Rate limit" },
            dimension = RiskRateLimitDimension.parse(request.dimension),
            pathPattern = request.pathPattern.trim().ifBlank { "/v1/*" },
            methods = request.methods.ifEmpty { setOf("POST") }.map { it.uppercase() }.toSet(),
            capacity = request.capacity,
            refillRatePerSec = request.refillRatePerSec,
            priority = request.priority,
            enabled = request.enabled
        )
        rules.updateAndGet { current ->
            current.filterNot { it.ruleId == rule.ruleId } + rule
        }
        return rule
    }

    fun deleteRule(ruleId: String): Boolean {
        var removed = false
        rules.updateAndGet { current ->
            removed = current.any { it.ruleId == ruleId }
            current.filterNot { it.ruleId == ruleId }
        }
        if (removed) {
            buckets.keys.filter { it.startsWith("$ruleId:") }.forEach(buckets::remove)
        }
        return removed
    }

    fun resetBucket(cacheKey: String): Boolean = buckets.remove(cacheKey) != null

    fun resetAll(): Int {
        val count = buckets.size
        buckets.clear()
        return count
    }

    override fun snapshot(): RateLimitSnapshot {
        val views = buckets.values.map { it.toView() }.sortedByDescending { it.totalRejected }.take(20)
        return RateLimitSnapshot(
            ruleCount = rules.get().size,
            bucketCount = buckets.size,
            totalAllowed = totalAllowed.get(),
            totalRejected = totalRejected.get(),
            topBuckets = views,
            recentRejections = recentRejections.toList().take(20)
        )
    }

    private fun acquire(bucket: Bucket, rule: RateLimitRule): RateLimitDecision {
        synchronized(bucket) {
            val now = System.currentTimeMillis()
            refill(bucket, now)
            return if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0
                bucket.totalAllowed.incrementAndGet()
                totalAllowed.incrementAndGet()
                RateLimitDecision.Allowed(
                    limit = rule.capacity,
                    remaining = bucket.tokens.toInt(),
                    resetAtEpochMs = resetAt(bucket, now)
                )
            } else {
                bucket.totalRejected.incrementAndGet()
                totalRejected.incrementAndGet()
                val retryAfter = ceil((1.0 - bucket.tokens) / rule.refillRatePerSec).toLong().coerceAtLeast(1L)
                RateLimitDecision.Rejected(
                    reason = "rate_limited",
                    limit = rule.capacity,
                    retryAfterSeconds = retryAfter,
                    ruleId = rule.ruleId
                ).also {
                    rememberRejection(
                        RateLimitRejectionView(
                            ruleId = rule.ruleId,
                            dimension = rule.dimension.name,
                            value = bucket.key.value,
                            reason = "rate_limited",
                            retryAfterSeconds = retryAfter,
                            createdAtEpochMs = now
                        )
                    )
                }
            }
        }
    }

    private fun rememberRejection(view: RateLimitRejectionView) {
        recentRejections.addFirst(view)
        while (recentRejections.size > 50) {
            recentRejections.pollLast()
        }
    }

    private fun refill(bucket: Bucket, now: Long) {
        val elapsedSeconds = (now - bucket.lastRefillEpochMs) / 1000.0
        if (elapsedSeconds <= 0.0) return
        bucket.tokens = (bucket.tokens + elapsedSeconds * bucket.refillRatePerSec).coerceAtMost(bucket.capacity.toDouble())
        bucket.lastRefillEpochMs = now
    }

    private fun resetAt(bucket: Bucket, now: Long): Long {
        val missing = bucket.capacity - bucket.tokens
        return now + ((missing / bucket.refillRatePerSec) * 1000).toLong()
    }

    private fun buildKey(rule: RateLimitRule, context: RateLimitContext): BucketKey? {
        val value = when (rule.dimension) {
            RiskRateLimitDimension.IP -> context.ip
            RiskRateLimitDimension.USER -> context.userId
            RiskRateLimitDimension.API_KEY -> context.keyId
            RiskRateLimitDimension.MODEL -> context.model
            RiskRateLimitDimension.GLOBAL -> "global"
        } ?: return null
        return BucketKey(rule.ruleId, rule.dimension, value)
    }

    private fun matchesPath(pattern: String, path: String): Boolean {
        val normalized = path.substringAfter("/api/plugins/airelay", path)
        if (pattern == "*" || pattern == "/*") return true
        return if (pattern.endsWith("*")) {
            normalized.startsWith(pattern.removeSuffix("*"))
        } else {
            normalized == pattern
        }
    }

    private data class Bucket(
        val key: BucketKey,
        val capacity: Int,
        val refillRatePerSec: Double,
        var tokens: Double = capacity.toDouble(),
        var lastRefillEpochMs: Long = System.currentTimeMillis(),
        val totalAllowed: AtomicLong = AtomicLong(0),
        val totalRejected: AtomicLong = AtomicLong(0)
    ) {
        fun toView(): RateLimitBucketView {
            val now = System.currentTimeMillis()
            val resetAt = now + (((capacity - tokens) / refillRatePerSec) * 1000).toLong()
            return RateLimitBucketView(
                ruleId = key.ruleId,
                dimension = key.dimension.name,
                value = key.value,
                capacity = capacity,
                remainingTokens = tokens.toInt(),
                totalAllowed = totalAllowed.get(),
                totalRejected = totalRejected.get(),
                resetAtEpochMs = resetAt
            )
        }
    }

    companion object {
        fun defaultRules(): List<RateLimitRule> = listOf(
            RateLimitRule("ip-default", "Default IP requests", RiskRateLimitDimension.IP, "/v1/*", setOf("POST"), 120, 2.0, 10, true),
            RateLimitRule("user-default", "Default user requests", RiskRateLimitDimension.USER, "/v1/*", setOf("POST"), 240, 4.0, 20, true),
            RateLimitRule("key-default", "Default key requests", RiskRateLimitDimension.API_KEY, "/v1/*", setOf("POST"), 240, 4.0, 30, true),
            RateLimitRule("model-default", "Default model requests", RiskRateLimitDimension.MODEL, "/v1/*", setOf("POST"), 600, 10.0, 5, true)
        )
    }
}
