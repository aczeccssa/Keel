package com.keel.samples.aigateway.token

import com.keel.contract.ai.CostBreakdown
import com.keel.contract.ai.RequestOutcome
import com.keel.contract.ai.TokenUsage
import com.keel.contract.ai.UsageRecordInput
import com.keel.contract.ai.UserDirectory
import com.keel.contract.ai.UserGroupSummary
import com.keel.contract.ai.UserSummary
import com.keel.db.database.DatabaseFactory
import com.keel.kernel.plugin.PluginApiException
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class TokenRepositoryTest {

    private companion object {
        private var counter = 0
    }

    private lateinit var factory: DatabaseFactory
    private lateinit var repository: TokenRepository

    private val directory = object : UserDirectory {
        override suspend fun findById(userId: String): UserSummary =
            UserSummary(userId, "$userId@example.com", userId, "user", "default", "active")

        override suspend fun findGroup(groupId: String): UserGroupSummary =
            UserGroupSummary(groupId, groupId, 1.0, null, null, 0.0)
    }

    @BeforeTest
    fun setUp() {
        factory = DatabaseFactory.h2Memory(name = "token_repo_test_${counter++}", poolSize = 1)
        repository = TokenRepository(factory.init(), directory)
        repository.initializeSchema()
    }

    @AfterTest
    fun tearDown() {
        factory.close()
    }

    private fun usage(
        userId: String,
        keyId: String,
        model: String,
        status: Int,
        routingGroupId: String?,
        channelId: String?,
        cost: Double = 0.0,
        userGroupId: String = "default",
    ) = runBlocking {
        repository.record(
            UsageRecordInput(
                keyId = keyId,
                userId = userId,
                userGroupId = userGroupId,
                clientProtocol = "anthropic",
                upstreamProtocol = "openai",
                model = model,
                provider = "openai",
                poolLevelId = "$routingGroupId-p0",
                upstreamKeyId = channelId,
                routingGroupId = routingGroupId,
                usage = TokenUsage(promptTokens = 10, completionTokens = 5),
                cost = CostBreakdown(totalCostUsd = cost),
                latencyMs = 42,
                status = status,
                errorCode = if (status >= 400) "upstream_$status" else null,
                streamed = false,
                failoverCount = 0,
            )
        )
    }

    @Test
    fun paginationReturnsHistoricalPagesAndAccurateTotal() {
        repeat(25) { i -> usage("usr-a", "key-a", "gpt-4o", 200, "premium", "ch-1") }

        val page1 = repository.recentRecords(limit = 10, offset = 0)
        assertEquals(25, page1.total)
        assertEquals(10, page1.records.size)
        assertEquals("10", page1.nextCursor)

        val page3 = repository.recentRecords(limit = 10, offset = 20)
        assertEquals(25, page3.total)
        assertEquals(5, page3.records.size)
        assertNull(page3.nextCursor, "last page should have no next cursor")
    }

    @Test
    fun filtersCombineWithAndSemantics() {
        usage("usr-a", "key-a", "gpt-4o", 200, "premium", "ch-1")
        usage("usr-a", "key-a", "gpt-4o", 500, "premium", "ch-1")
        usage("usr-a", "key-a", "claude", 200, "premium", "ch-2")
        usage("usr-b", "key-b", "gpt-4o", 200, "free", "ch-1")

        val result = repository.recentRecords(
            limit = 50,
            routingGroupId = "premium",
            model = "gpt-4o",
            statusFilter = "success",
        )
        assertEquals(1, result.total)
        val record = result.records.single()
        assertEquals("premium", record.routingGroupId)
        assertEquals("gpt-4o", record.model)
        assertTrue(record.status in 200..299)
        assertEquals("premium", result.filtersApplied["routingGroupId"])
        assertEquals("gpt-4o", result.filtersApplied["model"])
        assertEquals("success", result.filtersApplied["statusFilter"])
    }

    @Test
    fun routingGroupFilterUsesLogicalGroupNotPoolLevel() {
        usage("usr-a", "key-a", "gpt-4o", 200, "premium", "ch-1")
        // poolLevelId is "premium-p0"; filtering by routingGroupId must still match.
        val byGroup = repository.recentRecords(limit = 50, routingGroupId = "premium")
        assertEquals(1, byGroup.total)
        // poolLevelId diagnostic filter is distinct.
        val byPool = repository.recentRecords(limit = 50, poolLevelId = "premium-p0")
        assertEquals(1, byPool.total)
        val miss = repository.recentRecords(limit = 50, routingGroupId = "premium-p0")
        assertEquals(0, miss.total)
    }

    @Test
    fun customerIdFilterTargetsCustomerOwnedRecords() {
        usage("cust-a", "key-customer-a", "gpt-4o", 200, "premium", "ch-1", userGroupId = "customer")
        usage("cust-b", "key-customer-b", "gpt-4o", 200, "premium", "ch-1", userGroupId = "customer")
        usage("usr-admin", "key-admin", "gpt-4o", 200, "premium", "ch-1")

        val result = repository.recentRecords(limit = 50, customerId = "cust-a")

        assertEquals(1, result.total)
        assertEquals("cust-a", result.records.single().userId)
    }

    @Test
    fun adminDeleteWorksForKeyOwnedByDifferentUser() {
        val created = repository.createKey("usr-owner", CreateApiKeyRequest(displayName = "Owned"))
        val keyId = created.key.keyId

        // A different admin caller deleting with includeAll must succeed (not 403).
        val deleted = repository.deleteKey("usr-admin", keyId, includeAll = true)
        assertEquals(ApiKeyStatuses.REVOKED, deleted.status)

        // Without includeAll, a non-owner is forbidden.
        val second = repository.createKey("usr-owner", CreateApiKeyRequest(displayName = "Owned2"))
        try {
            repository.deleteKey("usr-admin", second.key.keyId, includeAll = false)
            fail("expected 403 for non-owner without includeAll")
        } catch (e: PluginApiException) {
            assertEquals(403, e.status)
        }
    }

    @Test
    fun adminRevokeWorksForKeyOwnedByDifferentUser() {
        val created = repository.createKey("usr-owner", CreateApiKeyRequest(displayName = "Owned"))
        val revoked = repository.revokeKey("usr-admin", created.key.keyId, includeAll = true)
        assertEquals(ApiKeyStatuses.REVOKED, revoked.status)
    }

    @Test
    fun viewExposesChannelAndRoutingIdentity() {
        usage("usr-a", "key-a", "gpt-4o", 200, "premium", "ch-7")
        val record = repository.recentRecords(limit = 1).records.single()
        assertEquals("ch-7", record.channelId)
        assertEquals("premium", record.routingGroupId)
        assertEquals("premium-p0", record.poolLevelId)
        assertEquals(record.recordId, record.requestId)
    }
}
