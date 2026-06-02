package com.keel.samples.aigateway.account

import kotlinx.serialization.Serializable

@Serializable
data class RegisterRequest(
    val email: String,
    val password: String,
    val displayName: String
)

@Serializable
data class LoginRequest(
    val email: String,
    val password: String
)

@Serializable
data class RefreshRequest(
    val refreshToken: String
)

@Serializable
data class AuthResponse(
    val accessToken: String,
    val refreshToken: String,
    val user: AccountUserView
)

@Serializable
data class AccountUserView(
    val userId: String,
    val email: String,
    val displayName: String,
    val role: String,
    val groupId: String,
    val status: String
)

@Serializable
data class AccountUserListResponse(
    val users: List<AccountUserView>,
    val total: Int
)

@Serializable
data class AccountGroupView(
    val groupId: String,
    val name: String,
    val costMultiplier: Double,
    val defaultRpm: Int?,
    val defaultTpm: Int?,
    val defaultBudgetUsd: Double
)

@Serializable
data class AccountGroupListResponse(
    val groups: List<AccountGroupView>,
    val total: Int
)

@Serializable
data class CreateGroupRequest(
    val groupId: String,
    val name: String,
    val costMultiplier: Double = 1.0,
    val defaultRpm: Int? = null,
    val defaultTpm: Int? = null,
    val defaultBudgetUsd: Double = 10.0
)

@Serializable
data class UpdateUserRequest(
    val displayName: String? = null,
    val role: String? = null,
    val groupId: String? = null,
    val status: String? = null
)

data class AccountPrincipal(
    val userId: String,
    val email: String,
    val role: String,
    val groupId: String
)

object AccountRoles {
    const val ADMIN = "admin"
    const val USER = "user"
}

object AccountStatuses {
    const val ACTIVE = "active"
    const val SUSPENDED = "suspended"
}
