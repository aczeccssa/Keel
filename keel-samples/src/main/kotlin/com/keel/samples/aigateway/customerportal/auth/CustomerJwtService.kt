package com.keel.samples.aigateway.customerportal.auth

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/**
 * HMAC-SHA256 JWT for customer-portal access tokens. Mirrors the structure of
 * com.keel.samples.aigateway.account.JwtTokenService but uses its own secret so
 * a leaked operator JWT cannot impersonate a customer.
 */
class CustomerJwtService(
    private val secret: String = System.getenv("CUSTOMER_JWT_SECRET")
        ?: "keel-customer-portal-demo-secret-change-me",
) {
    private val json = Json { encodeDefaults = true }

    fun issue(principal: CustomerPrincipal, ttl: Duration = 1.hours): Pair<String, Long> {
        val now = Clock.System.now()
        val exp = now.plus(ttl).epochSeconds
        val header = base64Url(json.encodeToString(JwtHeader()).toByteArray(Charsets.UTF_8))
        val payload = base64Url(
            json.encodeToString(
                JwtPayload(sub = principal.customerId, email = principal.email, iat = now.epochSeconds, exp = exp),
            ).toByteArray(Charsets.UTF_8),
        )
        val unsigned = "$header.$payload"
        return "$unsigned.${sign(unsigned)}" to (exp - now.epochSeconds)
    }

    fun verify(token: String): CustomerPrincipal? {
        val parts = token.split('.')
        if (parts.size != 3) return null
        if (sign("${parts[0]}.${parts[1]}") != parts[2]) return null
        val payload = runCatching {
            json.decodeFromString<JwtPayload>(base64UrlDecode(parts[1]).toString(Charsets.UTF_8))
        }.getOrNull() ?: return null
        if (Clock.System.now() >= Instant.fromEpochSeconds(payload.exp)) return null
        return CustomerPrincipal(customerId = payload.sub, email = payload.email)
    }

    private fun sign(data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return base64Url(mac.doFinal(data.toByteArray(Charsets.UTF_8)))
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun base64UrlDecode(s: String): ByteArray = Base64.getUrlDecoder().decode(s)

    @Serializable
    private data class JwtHeader(val alg: String = "HS256", val typ: String = "JWT")

    @Serializable
    private data class JwtPayload(val sub: String, val email: String, val iat: Long, val exp: Long)
}
