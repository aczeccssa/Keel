package com.keel.samples.aigateway.customerportal

import com.keel.contract.customer.CustomerUsageRow
import com.keel.samples.aigateway.customerportal.auth.CustomerLoginRequest
import com.keel.samples.aigateway.customerportal.auth.CustomerRegisterRequest
import com.keel.db.database.DatabaseFactory
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CustomerPortalRepositoryTest {

    private companion object {
        private var counter = 0
    }

    private lateinit var factory: DatabaseFactory
    private lateinit var repository: CustomerPortalRepository

    @BeforeTest
    fun setUp() {
        factory = DatabaseFactory.h2Memory(name = "customer_portal_repo_test_${counter++}", poolSize = 1)
        repository = CustomerPortalRepository(factory.init())
        repository.initializeSchema()
    }

    @AfterTest
    fun tearDown() {
        factory.close()
    }

    @Test
    fun customerUsageReturnsHistoricalPagesAndAccurateTotal() {
        val auth = repository.register(
            CustomerRegisterRequest(
                email = "cust@example.com",
                password = "secret-123",
                displayName = "Cust",
            )
        )
        val createdKey = repository.createKey(auth.customerId, CreateCustomerKeyRequest(name = "Primary"))

        repeat(3) { index ->
            runBlocking {
                repository.chargeForUsage(
                    customerId = auth.customerId,
                    keyId = createdKey.key.keyId,
                    usageCreditCost = 10L + index,
                    usageUsdMicrosCost = 25_000L + index,
                    usageRow = CustomerUsageRow(
                        model = "gpt-4o-mini",
                        groupId = "premium",
                        providerId = "openai",
                        wireProtocol = "openai",
                        status = 200,
                        inputTokens = 100,
                        outputTokens = 40,
                        cacheReadInputTokens = 0,
                        cacheCreationInputTokens = 0,
                        requestId = "req-$index",
                    )
                )
            }
            Thread.sleep(2)
        }

        val page1 = repository.customerUsage(auth.customerId, cursor = null, limit = 2)
        assertEquals(3, page1.total)
        assertEquals(2, page1.records.size)
        assertNotNull(page1.nextCursor)
        assertTrue(page1.records.all { it.status == 200 })

        val page2 = repository.customerUsage(auth.customerId, cursor = page1.nextCursor, limit = 2)
        assertEquals(3, page2.total)
        assertEquals(1, page2.records.size)
        assertEquals("req-0", page2.records.single().requestId)
    }

    @Test
    fun customerLedgerReturnsHistoricalPagesAndAccurateTotal() {
        val auth = repository.register(
            CustomerRegisterRequest(
                email = "ledger@example.com",
                password = "secret-123",
                displayName = "Ledger",
            )
        )

        repository.adjustCustomerCredits(auth.customerId, AdjustCustomerCreditsRequest(deltaCredits = 25, reason = "bonus-one"))
        Thread.sleep(2)
        repository.adjustCustomerCredits(auth.customerId, AdjustCustomerCreditsRequest(deltaCredits = 50, reason = "bonus-two"))

        val page1 = repository.customerLedger(auth.customerId, cursor = null, limit = 2)
        assertEquals(3, page1.total, "signup bonus plus two adjustments should be paged")
        assertEquals(2, page1.entries.size)
        assertNotNull(page1.nextCursor)

        val page2 = repository.customerLedger(auth.customerId, cursor = page1.nextCursor, limit = 2)
        assertEquals(3, page2.total)
        assertEquals(1, page2.entries.size)
        assertEquals("signup_bonus", page2.entries.single().reason)
    }

    @Test
    fun softDeletedCustomerHidesFromActiveDirectoryAndCanNoLongerLogin() {
        val auth = repository.register(
            CustomerRegisterRequest(
                email = "deleted@example.com",
                password = "secret-123",
                displayName = "Deleted",
            )
        )
        repository.createKey(auth.customerId, CreateCustomerKeyRequest(name = "To Revoke"))

        val deleted = repository.softDeleteCustomer(auth.customerId)
        assertEquals("deleted", deleted.status)
        assertEquals(0, runBlocking { repository.listAll().count { it.customerId == auth.customerId } })

        val error = runCatching {
            repository.login(CustomerLoginRequest(email = "deleted@example.com", password = "secret-123"))
        }.exceptionOrNull()

        assertNotNull(error)
    }
}
