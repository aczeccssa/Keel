package com.keel.samples.aigateway.airelay

private val BRACKET_VARIANT_SUFFIX = Regex("^(.*)\\[([A-Za-z0-9_-]+)]$")
private val OPUS_DOT_VERSION = Regex("^claude-opus-4\\.([6-9]|[1-9][0-9]+)$")

data class ModelVariantSemantics(
    val requestedModel: String,
    val routingFallbackModel: String? = null,
    val variantKey: String? = null,
    val anthropicBeta: String? = null,
)

fun modelVariantSemantics(model: String): ModelVariantSemantics {
    OPUS_DOT_VERSION.matchEntire(model)?.let { match ->
        val canonical = "claude-opus-4-${match.groupValues[1]}"
        return ModelVariantSemantics(
            requestedModel = model,
            routingFallbackModel = canonical,
            variantKey = "1m",
            anthropicBeta = "context-1m-2025-08-07",
        )
    }
    if (model.startsWith("claude-opus-4-")) {
        val minor = model.substringAfter("claude-opus-4-").toIntOrNull()
        if (minor != null && minor >= 6) {
            return ModelVariantSemantics(
                requestedModel = model,
                routingFallbackModel = model,
                variantKey = "1m",
                anthropicBeta = "context-1m-2025-08-07",
            )
        }
    }
    val colonVariant = model.substringAfter("::", missingDelimiterValue = "")
        .takeIf { it.isNotBlank() }
    if (colonVariant != null) {
        return ModelVariantSemantics(
            requestedModel = model,
            routingFallbackModel = model.substringBefore("::").ifBlank { null },
            variantKey = colonVariant,
        )
    }
    val bracketMatch = BRACKET_VARIANT_SUFFIX.matchEntire(model) ?: return ModelVariantSemantics(requestedModel = model)
    val baseModel = bracketMatch.groupValues[1].trim().ifBlank { return ModelVariantSemantics(requestedModel = model) }
    val variantKey = bracketMatch.groupValues[2].trim().lowercase().ifBlank { return ModelVariantSemantics(requestedModel = model) }
    return ModelVariantSemantics(
        requestedModel = model,
        routingFallbackModel = baseModel,
        variantKey = variantKey,
        anthropicBeta = when (variantKey) {
            "1m" -> "context-1m-2025-08-07"
            else -> null
        },
    )
}
