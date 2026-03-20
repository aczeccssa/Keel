package com.keel.samples.commerce

data class CommercePrincipal(
    val userId: String,
    val username: String,
    val displayName: String,
    val role: String
)

data class CommerceTokenPair(
    val accessToken: String,
    val refreshToken: String
)

data class CommerceDemoAccount(
    val principal: CommercePrincipal,
    val password: String,
    val tokenPair: CommerceTokenPair
)

object CommerceDemoIdentity {
    private val accounts = listOf(
        CommerceDemoAccount(
            principal = CommercePrincipal(
                userId = "user-1",
                username = "user",
                displayName = "Sample User",
                role = "user"
            ),
            password = "user123",
            tokenPair = CommerceTokenPair(
                accessToken = "user-token",
                refreshToken = "refresh-user"
            )
        ),
        CommerceDemoAccount(
            principal = CommercePrincipal(
                userId = "admin-1",
                username = "admin",
                displayName = "Sample Admin",
                role = "admin"
            ),
            password = "admin123",
            tokenPair = CommerceTokenPair(
                accessToken = "admin-token",
                refreshToken = "refresh-admin"
            )
        )
    )

    private val accountsByUsername = accounts.associateBy { it.principal.username }
    private val principalsByToken = accounts.associateBy({ it.tokenPair.accessToken }, { it.principal })
    private val accountsByRefreshToken = accounts.associateBy { it.tokenPair.refreshToken }
    private val accountsByUserId = accounts.associateBy { it.principal.userId }

    fun authenticate(username: String, password: String): CommerceDemoAccount? {
        val account = accountsByUsername[username] ?: return null
        return account.takeIf { it.password == password }
    }

    fun principalFromAuthorization(authHeader: String?): CommercePrincipal? {
        val token = authHeader?.trim()?.removePrefix("Bearer ")?.trim()
        if (token.isNullOrBlank()) return null
        return principalsByToken[token]
    }

    fun tokenPairFromRefresh(refreshToken: String): CommerceTokenPair? {
        return accountsByRefreshToken[refreshToken]?.tokenPair
    }

    fun principalByUserId(userId: String): CommercePrincipal? = accountsByUserId[userId]?.principal
}
