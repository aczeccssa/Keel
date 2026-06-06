package com.keel.samples.aigateway.customerportal.auth

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * PBKDF2 (HMAC-SHA256) password hashing.
 *
 * The salt is stored alongside the hash on the customer row.
 * `iterations` defaults to 100_000 — strong enough for an MVP single-machine deploy,
 * tunable down to ~1_000 in tests so the suite stays under a few seconds.
 */
class PasswordHasher(
    private val iterations: Int = 100_000,
    private val keyLength: Int = 256,
    private val random: SecureRandom = SecureRandom(),
) {
    fun newSalt(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        return Base64.getEncoder().encodeToString(bytes)
    }

    fun hash(password: String, salt: String): String {
        val saltBytes = Base64.getDecoder().decode(salt)
        val spec = PBEKeySpec(password.toCharArray(), saltBytes, iterations, keyLength)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val hashed = factory.generateSecret(spec).encoded
        return Base64.getEncoder().encodeToString(hashed)
    }

    fun verify(password: String, salt: String, expectedHash: String): Boolean {
        val computed = hash(password, salt)
        return java.security.MessageDigest.isEqual(
            computed.toByteArray(Charsets.UTF_8),
            expectedHash.toByteArray(Charsets.UTF_8),
        )
    }
}
