package com.keel.samples.aigateway.customerportal.auth

import kotlinx.serialization.Serializable

@Serializable
data class CustomerRegisterRequest(
    val email: String,
    val password: String,
    val displayName: String,
)

@Serializable
data class CustomerLoginRequest(
    val email: String,
    val password: String,
)

@Serializable
data class CustomerRefreshRequest(
    val refreshToken: String,
)

@Serializable
data class CustomerOAuthStubRequest(
    val provider: String,
    val email: String,
    val displayName: String,
)

@Serializable
data class CustomerAuthResponse(
    val customerId: String,
    val email: String,
    val displayName: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Long,
)

@Serializable
data class CustomerProfile(
    val customerId: String,
    val email: String,
    val displayName: String,
    val emailVerified: Boolean,
    val oauthProvider: String?,
    val balanceCredits: Long,
    val createdAt: String,
)

data class CustomerPrincipal(
    val customerId: String,
    val email: String,
)
