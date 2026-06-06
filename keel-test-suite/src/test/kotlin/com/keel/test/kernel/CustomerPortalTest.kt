package com.keel.test.kernel

import com.keel.db.database.DatabaseFactory
import com.keel.contract.customer.ChargeResult
import com.keel.contract.customer.CustomerUsageRow
import com.keel.samples.aigateway.customerportal.CustomerPortalRepository
import com.keel.samples.aigateway.customerportal.CreateCustomerKeyRequest
import com.keel.samples.aigateway.customerportal.AdjustCustomerCreditsRequest
import com.keel.samples.aigateway.customerportal.RedeemCodeRequest
import com.keel.samples.aigateway.customerportal.CreateRedemptionCodeRequest
import com.keel.samples.aigateway.customerportal.UpdateCustomerRequest
import com.keel.samples.aigateway.customerportal.UpdateCustomerKeyRequest
import com.keel.samples.aigateway.customerportal.auth.CustomerLoginRequest
import com.keel.samples.aigateway.customerportal.auth.CustomerRegisterRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.*

/**
 * Exercises customer auth, key CRUD, credit ledger, and redemption codes.
 * All tests run against H2 in-memory — no HTTP, no upstream.
 */
class CustomerPortalTest {

    private fun newRepo(): CustomerPortalRepository {
        val db = DatabaseFactory.h2Memory(name = "cp_test_${System.nanoTime()}").init()
        return CustomerPortalRepository(db).also { it.initializeSchema() }
    }

    // ---- auth ----

    @Test
    fun signupCreatesCustomerAndGrantsBonus() {
        val repo = newRepo()
        val auth = repo.register(CustomerRegisterRequest("test@example.com", "password123", "Test User"))
        assertEquals("test@example.com", auth.email)
        assertTrue(auth.accessToken.isNotBlank())
        assertTrue(auth.refreshToken.isNotBlank())

        val profile = repo.me(com.keel.samples.aigateway.customerportal.auth.CustomerPrincipal(auth.customerId, auth.email))
        val balance = repo.customerBalance(auth.customerId)
        assertEquals(CustomerPortalRepository.SIGNUP_BONUS_CREDITS, balance.balanceCredits)
        assertEquals(auth.customerId, profile.customerId)
    }

    @Test
    fun loginWithWrongPasswordFails() {
        val repo = newRepo()
        repo.register(CustomerRegisterRequest("u@x.com", "correct1", "U"))
        try {
            repo.login(CustomerLoginRequest("u@x.com", "wrongpass"))
            assertTrue(false, "Expected 401")
        } catch (e: com.keel.kernel.plugin.PluginApiException) {
            assertEquals(401, e.status)
        }
    }

    @Test
    fun refreshTokenWorks() {
        val repo = newRepo()
        val auth = repo.register(CustomerRegisterRequest("u@x.com", "correct1", "U"))
        val refreshed = repo.refresh(com.keel.samples.aigateway.customerportal.auth.CustomerRefreshRequest(auth.refreshToken))
        assertTrue(refreshed.accessToken.isNotBlank())
    }

    @Test
    fun oauthStubCreatesOrReusesCustomer() {
        val repo = newRepo()
        val oauth = repo.oauthStub(com.keel.samples.aigateway.customerportal.auth.CustomerOAuthStubRequest("github", "gh@example.com", "GH User"))
        assertTrue(oauth.accessToken.isNotBlank())
        assertEquals("github", repo.me(com.keel.samples.aigateway.customerportal.auth.CustomerPrincipal(oauth.customerId, oauth.email)).oauthProvider)
        // Re-login with same email
        val oauth2 = repo.oauthStub(com.keel.samples.aigateway.customerportal.auth.CustomerOAuthStubRequest("github", "gh@example.com", "GH"))
        assertEquals(oauth.customerId, oauth2.customerId)
    }

    // ---- keys ----

    @Test
    fun createAndListKeys() {
        val repo = newRepo()
        val auth = repo.register(CustomerRegisterRequest("u@x.com", "correct1", "U"))
        val key = repo.createKey(auth.customerId, CreateCustomerKeyRequest("My Key", "default"))
        assertTrue(key.rawKey.startsWith("sk-keel-cust-"))
        assertTrue(key.key.name == "My Key")

        val list = repo.listKeys(auth.customerId)
        assertEquals(1, list.total)
        assertEquals("My Key", list.keys[0].name)
        // Raw key is never in the view
        assertTrue(!list.keys[0].prefix.contains(key.rawKey))
    }

    @Test
    fun updateKeyChangesFields() {
        val repo = newRepo()
        val auth = repo.register(CustomerRegisterRequest("u@x.com", "correct1", "U"))
        val key = repo.createKey(auth.customerId, CreateCustomerKeyRequest("K1"))
        val updated = repo.updateKey(auth.customerId, key.key.keyId, UpdateCustomerKeyRequest(name = "Renamed"))
        assertEquals("Renamed", updated.name)
    }

    @Test
    fun revokeKeyPreventsVerification() = runBlocking {
        val repo = newRepo()
        val auth = repo.register(CustomerRegisterRequest("u@x.com", "correct1", "U"))
        val key = repo.createKey(auth.customerId, CreateCustomerKeyRequest("K1"))
        val verified = repo.verifyCustomerKey(key.rawKey)
        assertNotNull(verified)
        assertEquals(auth.customerId, verified.customerId)

        repo.revokeKey(auth.customerId, key.key.keyId)
        assertNull(repo.verifyCustomerKey(key.rawKey))
    }

    @Test
    fun deleteKeyRemovesItFromListAndVerification() = runBlocking {
        val repo = newRepo()
        val auth = repo.register(CustomerRegisterRequest("u@x.com", "correct1", "U"))
        val key = repo.createKey(auth.customerId, CreateCustomerKeyRequest("K1"))
        assertNotNull(repo.verifyCustomerKey(key.rawKey))

        repo.deleteKey(auth.customerId, key.key.keyId)

        assertNull(repo.verifyCustomerKey(key.rawKey))
        assertTrue(repo.listKeys(auth.customerId).keys.none { it.keyId == key.key.keyId })
    }

    @Test
    fun customerKeyRoutingGroupIsNormalizedAndLegacyChainIdsCollapseToDefault() = runBlocking {
        val repo = newRepo()
        val auth = repo.register(CustomerRegisterRequest("u@x.com", "correct1", "U"))

        val premium = repo.createKey(auth.customerId, CreateCustomerKeyRequest("Premium", "Premium "))
        assertEquals("premium", premium.key.routingGroupId)
        assertEquals("premium", repo.verifyCustomerKey(premium.rawKey)?.routingGroupId)

        val legacy = repo.createKey(auth.customerId, CreateCustomerKeyRequest("Legacy", "default-chain"))
        assertEquals("default", legacy.key.routingGroupId)
        assertEquals("default", repo.verifyCustomerKey(legacy.rawKey)?.routingGroupId)
    }

    // ---- credits ----

    @Test
    fun chargeForUsageDeductsAndReturnsOk() = runBlocking {
        val repo = newRepo()
        val auth = repo.register(CustomerRegisterRequest("u@x.com", "correct1", "U"))
        val key = repo.createKey(auth.customerId, CreateCustomerKeyRequest("K1"))
        val balanceBefore = repo.snapshotBalance(auth.customerId)

        val result = repo.chargeForUsage(auth.customerId, key.key.keyId, 100L, 50L,
            CustomerUsageRow("gpt-4o", "default", "p1", "ANTHROPIC_MESSAGES", 100, 50, 0, 0, requestId = "req-1"))
        assertTrue(result is ChargeResult.Ok)
        val ok = result as ChargeResult.Ok
        assertEquals(balanceBefore - 100L, ok.newBalanceCredits)

        val balanceAfter = repo.customerBalance(auth.customerId)
        assertEquals(balanceBefore - 100L, balanceAfter.balanceCredits)

        // Usage record visible
        val usage = repo.customerUsage(auth.customerId, null, 10)
        assertEquals(1, usage.total)
        assertEquals(100L, usage.records[0].creditCost)
    }

    @Test
    fun chargeForUsageHandlesZeroTokens() = runBlocking {
        val repo = newRepo()
        val auth = repo.register(CustomerRegisterRequest("u@x.com", "correct1", "U"))
        val key = repo.createKey(auth.customerId, CreateCustomerKeyRequest("K1"))
        val result = repo.chargeForUsage(auth.customerId, key.key.keyId, 0L, 0L,
            CustomerUsageRow("m1", "default", "p1", "ANTHROPIC_MESSAGES", 0, 0, 0, 0, requestId = "req-1"))
        assertTrue(result is ChargeResult.Ok)
    }

    @Test
    fun balanceGoesNegativeForLargeCharge() = runBlocking {
        val repo = newRepo()
        val auth = repo.register(CustomerRegisterRequest("u@x.com", "correct1", "U"))
        val key = repo.createKey(auth.customerId, CreateCustomerKeyRequest("K1"))
        // Charge more than the signup bonus
        val result = repo.chargeForUsage(auth.customerId, key.key.keyId,
            CustomerPortalRepository.SIGNUP_BONUS_CREDITS + 5000L, 0L,
            CustomerUsageRow("m1", "default", "p1", "ANTHROPIC_MESSAGES", 1000, 500, 0, 0, requestId = "req-1"))
        assertTrue(result is ChargeResult.Ok)
        val ok = result as ChargeResult.Ok
        assertTrue(ok.newBalanceCredits < 0)
    }

    // ---- redemption ----

    @Test
    fun redeemValidCodeAddsCredits() {
        val repo = newRepo()
        val user = repo.register(CustomerRegisterRequest("u@x.com", "correct1", "U"))
        val code = repo.createRedemptionCode(CreateRedemptionCodeRequest(5000L), "admin-1")
        assertEquals("active", code.status)

        val balanceBefore = repo.customerBalance(user.customerId).balanceCredits
        val result = repo.redeemCode(user.customerId, RedeemCodeRequest(code.code))
        assertEquals(5000L, result.amountCredits)
        assertEquals(balanceBefore + 5000L, result.newBalanceCredits)
        assertEquals("redeemed", repo.listRedemptionCodes().codes[0].status)
    }

    @Test
    fun redeemAlreadyRedeemedCodeFails() {
        val repo = newRepo()
        val user = repo.register(CustomerRegisterRequest("u@x.com", "correct1", "U"))
        val code = repo.createRedemptionCode(CreateRedemptionCodeRequest(1000L), "admin-1")
        repo.redeemCode(user.customerId, RedeemCodeRequest(code.code))
        try {
            repo.redeemCode(user.customerId, RedeemCodeRequest(code.code))
            assertTrue(false, "Expected 409")
        } catch (e: com.keel.kernel.plugin.PluginApiException) {
            assertEquals(409, e.status)
        }
    }

    @Test
    fun redeemNonexistentCodeFails() {
        val repo = newRepo()
        val user = repo.register(CustomerRegisterRequest("u@x.com", "correct1", "U"))
        try {
            repo.redeemCode(user.customerId, RedeemCodeRequest("NONEXISTENT"))
            assertTrue(false, "Expected 404")
        } catch (e: com.keel.kernel.plugin.PluginApiException) {
            assertEquals(404, e.status)
        }
    }

    @Test
    fun revokeCodePreventsRedemption() {
        val repo = newRepo()
        val user = repo.register(CustomerRegisterRequest("u@x.com", "correct1", "U"))
        val code = repo.createRedemptionCode(CreateRedemptionCodeRequest(1000L), "admin-1")
        repo.revokeRedemptionCode(code.code)
        try {
            repo.redeemCode(user.customerId, RedeemCodeRequest(code.code))
            assertTrue(false, "Expected 410")
        } catch (e: com.keel.kernel.plugin.PluginApiException) {
            assertEquals(410, e.status)
        }
    }

    @Test
    fun ledgerRecordsAllTransactions() {
        val repo = newRepo()
        val user = repo.register(CustomerRegisterRequest("u@x.com", "correct1", "U"))
        val code = repo.createRedemptionCode(CreateRedemptionCodeRequest(2000L), "admin-1")
        repo.redeemCode(user.customerId, RedeemCodeRequest(code.code))
        // Charge some usage
        runBlocking {
            val key = repo.createKey(user.customerId, CreateCustomerKeyRequest("K1"))
            repo.chargeForUsage(user.customerId, key.key.keyId, 300L, 10L,
                CustomerUsageRow("m1", "default", "p1", "ANTHROPIC_MESSAGES", 100, 50, 0, 0, requestId = "r1"))
        }
        val ledger = repo.customerLedger(user.customerId, null, 50)
        assertTrue(ledger.entries.size >= 3) // signup_bonus + redemption + usage
        assertTrue(ledger.entries.any { it.reason == "signup_bonus" })
        assertTrue(ledger.entries.any { it.reason == "redemption" })
        assertTrue(ledger.entries.any { it.reason == "usage" })
    }

    // ---- CustomerDirectory ----

    @Test
    fun customerDirectoryListsAll() = runBlocking {
        val repo = newRepo()
        repo.register(CustomerRegisterRequest("a@x.com", "password1", "A"))
        repo.register(CustomerRegisterRequest("b@x.com", "password2", "B"))
        val list = repo.listAll()
        assertEquals(2, list.size)
        assertEquals(2L, repo.count())
    }

    @Test
    fun adminCanUpdateCustomerLockAndAdjustCredits() = runBlocking {
        val repo = newRepo()
        val user = repo.register(CustomerRegisterRequest("a@x.com", "password1", "A"))

        val updated = repo.updateCustomer(user.customerId, UpdateCustomerRequest(displayName = "Alice", status = "locked"))
        assertEquals("Alice", updated.displayName)
        assertEquals("locked", updated.status)

        val adjusted = repo.adjustCustomerCredits(user.customerId, AdjustCustomerCreditsRequest(deltaCredits = 2500L, reason = "manual_topup"))
        assertEquals(CustomerPortalRepository.SIGNUP_BONUS_CREDITS + 2500L, adjusted.balanceCredits)

        val detail = repo.adminCustomerDetail(user.customerId)
        assertEquals("Alice", detail.displayName)
        assertEquals("locked", detail.status)
        assertEquals(CustomerPortalRepository.SIGNUP_BONUS_CREDITS + 2500L, detail.balanceCredits)
    }

    @Test
    fun softDeletedCustomerDisappearsFromDirectory() = runBlocking {
        val repo = newRepo()
        val user = repo.register(CustomerRegisterRequest("gone@x.com", "password1", "Gone"))
        repo.softDeleteCustomer(user.customerId)
        assertTrue(repo.listAll().none { it.customerId == user.customerId })
    }

    @Test
    fun adminCanRevokeCustomerKey() = runBlocking {
        val repo = newRepo()
        val user = repo.register(CustomerRegisterRequest("u@x.com", "password1", "U"))
        val key = repo.createKey(user.customerId, CreateCustomerKeyRequest("K1"))
        assertNotNull(repo.verifyCustomerKey(key.rawKey))
        repo.adminRevokeCustomerKey(user.customerId, key.key.keyId)
        assertNull(repo.verifyCustomerKey(key.rawKey))
    }

    // ---- key cross-customer access ----

    @Test
    fun customerCannotAccessOtherCustomerKey() {
        val repo = newRepo()
        val userA = repo.register(CustomerRegisterRequest("a@x.com", "password1", "A"))
        val userB = repo.register(CustomerRegisterRequest("b@x.com", "password2", "B"))
        val keyB = repo.createKey(userB.customerId, CreateCustomerKeyRequest("KB"))
        try {
            repo.getKey(userA.customerId, keyB.key.keyId)
            assertTrue(false, "Expected 403")
        } catch (e: com.keel.kernel.plugin.PluginApiException) {
            assertEquals(403, e.status)
        }
    }
}
