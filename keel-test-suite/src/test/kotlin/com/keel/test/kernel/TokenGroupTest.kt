package com.keel.test.kernel

import com.keel.contract.ai.UserDirectory
import com.keel.contract.ai.UserGroupSummary
import com.keel.contract.ai.UserSummary
import com.keel.db.database.DatabaseFactory
import com.keel.samples.aigateway.token.CreateApiKeyRequest
import com.keel.samples.aigateway.token.TokenRepository
import com.keel.samples.aigateway.token.UpdateApiKeyRequest
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TokenGroupTest {
    private object Users : UserDirectory {
        override suspend fun findById(userId: String): UserSummary? = UserSummary(
            userId = userId,
            email = "admin@example.com",
            displayName = "Admin",
            role = "admin",
            groupId = "ug-default",
            status = "active"
        )

        override suspend fun findGroup(groupId: String): UserGroupSummary? = UserGroupSummary(
            groupId = groupId,
            name = "Default",
            costMultiplier = 1.0,
            defaultRpm = null,
            defaultTpm = null,
            defaultBudgetUsd = 100.0
        )
    }

    private fun repo(): TokenRepository {
        val db = DatabaseFactory.h2Memory(name = "token_group_${System.nanoTime()}").init()
        return TokenRepository(db, Users).also { it.initializeSchema() }
    }

    @Test
    fun createListUpdateAndVerifyIncludeRoutingGroup() = runBlocking {
        val repo = repo()
        val created = repo.createKey("usr-demo", CreateApiKeyRequest(displayName = "Premium", groupId = "premium"))
        assertEquals("premium", created.key.groupId)
        assertEquals("premium", repo.listKeys("usr-demo", includeAll = false).keys.single().groupId)

        val updated = repo.updateKey("usr-demo", created.key.keyId, UpdateApiKeyRequest(groupId = "default"), includeAll = false)
        assertEquals("default", updated.groupId)

        val verified = repo.verify(created.rawKey, clientIp = null)
        assertEquals("default", verified.routingGroupId)
    }

    @Test
    fun deleteKeyRemovesItFromListsAndVerification() = runBlocking {
        val repo = repo()
        val created = repo.createKey("usr-demo", CreateApiKeyRequest(displayName = "Disposable", groupId = "premium"))

        repo.deleteKey("usr-demo", created.key.keyId, includeAll = false)

        assertTrue(repo.listKeys("usr-demo", includeAll = false).keys.none { it.keyId == created.key.keyId })
        try {
            repo.verify(created.rawKey, clientIp = null)
            kotlin.test.fail("Expected deleted key verification to fail")
        } catch (_: Exception) {
        }
    }
}
