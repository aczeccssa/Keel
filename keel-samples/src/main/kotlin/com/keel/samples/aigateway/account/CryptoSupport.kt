package com.keel.samples.aigateway.account

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class PasswordHasher(
    private val random: SecureRandom = SecureRandom()
) {
    fun hash(password: String): String {
        val salt = ByteArray(16)
        random.nextBytes(salt)
        val digest = digest(salt, password)
        return listOf("sha256", base64Url(salt), base64Url(digest)).joinToString("\$")
    }

    fun verify(password: String, hash: String): Boolean {
        val parts = hash.split("\$")
        if (parts.size != 3 || parts[0] != "sha256") return false
        val salt = base64UrlDecode(parts[1])
        val expected = base64UrlDecode(parts[2])
        val actual = digest(salt, password)
        return MessageDigest.isEqual(expected, actual)
    }

    private fun digest(salt: ByteArray, password: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        digest.update(password.toByteArray(Charsets.UTF_8))
        return digest.digest()
    }
}

class HmacSigner(
    private val secret: String
) {
    fun sign(value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return base64Url(mac.doFinal(value.toByteArray(Charsets.UTF_8)))
    }

    fun verify(value: String, signature: String): Boolean {
        return MessageDigest.isEqual(sign(value).toByteArray(Charsets.UTF_8), signature.toByteArray(Charsets.UTF_8))
    }
}

fun base64Url(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

fun base64UrlDecode(value: String): ByteArray = Base64.getUrlDecoder().decode(value)
