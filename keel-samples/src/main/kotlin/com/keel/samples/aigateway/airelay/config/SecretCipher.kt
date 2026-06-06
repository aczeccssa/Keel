package com.keel.samples.aigateway.airelay.config

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest

/**
 * Reversible AES-GCM cipher for upstream API keys at rest. The master key is derived from a
 * secret (env `KEEL_SECRET` or a provided value); upstream keys must be recoverable to forward
 * them to providers, so we cannot one-way hash them like passwords.
 *
 * Format of an encrypted value: base64( iv[12] || ciphertext+tag ).
 */
class SecretCipher(secret: String) {
    private val keyBytes: ByteArray = MessageDigest.getInstance("SHA-256")
        .digest(secret.toByteArray(Charsets.UTF_8)) // 32-byte AES-256 key
    private val random = SecureRandom()

    fun encrypt(plaintext: String): String {
        if (plaintext.isEmpty()) return ""
        val iv = ByteArray(IV_LEN).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(TAG_BITS, iv))
        val ct = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + ct)
    }

    fun decrypt(encoded: String): String {
        if (encoded.isEmpty()) return ""
        val all = Base64.getDecoder().decode(encoded)
        val iv = all.copyOfRange(0, IV_LEN)
        val ct = all.copyOfRange(IV_LEN, all.size)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    companion object {
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val IV_LEN = 12
        private const val TAG_BITS = 128

        fun fromEnv(): SecretCipher {
            val secret = System.getenv("KEEL_SECRET")
                ?: System.getProperty("keel.secret")
                ?: "keel-dev-secret-change-me"
            return SecretCipher(secret)
        }
    }
}
