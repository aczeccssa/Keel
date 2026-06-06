package com.keel.samples.aigateway.account

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

class JwtTokenService(
    secret: String = System.getenv("JWT_SECRET") ?: "keel-ai-gateway-demo-secret-change-me"
) {
    private val signer = HmacSigner(secret)
    private val json = Json { encodeDefaults = true }

    fun issue(principal: AccountPrincipal, ttl: Duration = 1.hours): String {
        val header = base64Url(json.encodeToString(JwtHeader()).toByteArray(Charsets.UTF_8))
        val now = Clock.System.now()
        val payload = base64Url(
            json.encodeToString(
                JwtPayload(
                    sub = principal.userId,
                    email = principal.email,
                    role = principal.role,
                    groupId = principal.groupId,
                    iat = now.epochSeconds,
                    exp = now.plus(ttl).epochSeconds
                )
            ).toByteArray(Charsets.UTF_8)
        )
        val unsigned = "$header.$payload"
        return "$unsigned.${signer.sign(unsigned)}"
    }

    fun verify(token: String): AccountPrincipal? {
        val parts = token.split('.')
        if (parts.size != 3) return null
        val unsigned = parts.take(2).joinToString(".")
        if (!signer.verify(unsigned, parts[2])) return null
        val payload = runCatching {
            json.decodeFromString<JwtPayload>(base64UrlDecode(parts[1]).toString(Charsets.UTF_8))
        }.getOrNull() ?: return null
        if (Clock.System.now() >= Instant.fromEpochSeconds(payload.exp)) return null
        return AccountPrincipal(
            userId = payload.sub,
            email = payload.email,
            role = payload.role,
            groupId = payload.groupId
        )
    }

    @Serializable
    private data class JwtHeader(
        val alg: String = "HS256",
        val typ: String = "JWT"
    )

    @Serializable
    private data class JwtPayload(
        val sub: String,
        val email: String,
        val role: String,
        val groupId: String,
        val iat: Long,
        val exp: Long
    )
}
