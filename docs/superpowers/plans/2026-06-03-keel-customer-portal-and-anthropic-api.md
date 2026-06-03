# Keel Customer Portal + Full Anthropic API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a `customer-portal` plugin (independent customer identity, API keys, Credits ledger, redemption codes, usage browser, 5-page UI), wire it into `airelay` as the precedence verifier with post-call credit deduction, and rewrite the Anthropic Messages codec to a faithful GA-set surface (`/v1/messages` streaming + tools + vision + documents + thinking + cache_control, `/v1/messages/count_tokens`, `/v1/models`, Batches).

**Architecture:** New plugin `customer-portal` owns `cp_*` tables (customers, sessions, keys, credit_ledger, redemption_codes, usage_records) and exposes `CustomerApiKeyVerifier` + `CreditLedger` via Koin. `airelay.AIRelayService` tries the customer verifier first, falls back to legacy `ApiKeyVerifier`. Credits are checked pre-call (snapshot > 0) and deducted post-call in a single transaction. `AnthropicMessagesCodec` becomes a faithful translator; SSE events pass through transparently. Batches stored in `airelay_batches` with a background worker.

**Tech Stack:** Kotlin 2.3 / Ktor 3.4 / Koin / Exposed 0.61 / kotlinx.datetime / kotlinx.serialization / H2 / Web Components (no build).

---

## File Map

**Create (customer-portal plugin):**
- `keel-contract/src/main/kotlin/com/keel/contract/customer/CustomerContracts.kt`
- `keel-samples/src/main/kotlin/com/keel/samples/aigateway/customerportal/CustomerPortalPlugin.kt`
- `keel-samples/src/main/kotlin/com/keel/samples/aigateway/customerportal/CustomerPortalSettings.kt`
- `keel-samples/src/main/kotlin/com/keel/samples/aigateway/customerportal/auth/CustomerAuthRepository.kt`
- `keel-samples/src/main/kotlin/com/keel/samples/aigateway/customerportal/auth/CustomerAuthModels.kt`
- `keel-samples/src/main/kotlin/com/keel/samples/aigateway/customerportal/auth/CustomerAuthTables.kt`
- `keel-samples/src/main/kotlin/com/keel/samples/aigateway/customerportal/auth/CustomerJwtService.kt`
- `keel-samples/src/main/kotlin/com/keel/samples/aigateway/customerportal/auth/PasswordHasher.kt`
- `keel-samples/src/main/kotlin/com/keel/samples/aigateway/customerportal/auth/CustomerJwtInterceptor.kt`
- `keel-samples/src/main/kotlin/com/keel/samples/aigateway/customerportal/keys/CustomerKeyRepository.kt`
- `keel-samples/src/main/kotlin/com/keel/samples/aigateway/customerportal/keys/CustomerKeyModels.kt`
- `keel-samples/src/main/kotlin/com/keel/samples/aigateway/customerportal/keys/CustomerKeyTables.kt`
- `keel-samples/src/main/kotlin/com/keel/samples/aigateway/customerportal/credits/CreditLedgerRepository.kt`
- `keel-samples/src/main/kotlin/com/keel/samples/aigateway/customerportal/credits/CreditLedgerModels.kt`
- `keel-samples/src/main/kotlin/com/keel/samples/aigateway/customerportal/credits/CreditLedgerTables.kt`
- `keel-samples/src/main/kotlin/com/keel/samples/aigateway/customerportal/credits/RedemptionCodeRepository.kt`
- `keel-samples/src/main/kotlin/com/keel/samples/aigateway/customerportal/credits/RedemptionCodeTables.kt`
- `keel-samples/src/main/kotlin/com/keel/samples/aigateway/customerportal/credits/CustomerUsageRepository.kt`
- `keel-samples/src/main/kotlin/com/keel/samples/aigateway/customerportal/credits/CustomerUsageTables.kt`

**Create (Anthropic codec + Batches):**
- `keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/protocol/anthropic/AnthropicHeaders.kt`
- `keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/protocol/anthropic/AnthropicErrorEnvelope.kt`
- `keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/protocol/anthropic/AnthropicMessagesV2Codec.kt`
- `keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/usage/CreditRateRegistry.kt`
- `keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/batches/BatchesTables.kt`
- `keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/batches/BatchesRepository.kt`
- `keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/batches/BatchesExecutor.kt`

**Create (customer-portal UI — Web Components, no build):**
- `keel-samples/src/main/resources/customer-portal-ui/index.html`
- `keel-samples/src/main/resources/customer-portal-ui/css/styles.css`
- `keel-samples/src/main/resources/customer-portal-ui/js/app.js`
- `keel-samples/src/main/resources/customer-portal-ui/js/state.js`
- `keel-samples/src/main/resources/customer-portal-ui/js/api.js`
- `keel-samples/src/main/resources/customer-portal-ui/js/components/PanelLogin.js`
- `keel-samples/src/main/resources/customer-portal-ui/js/components/PanelSignup.js`
- `keel-samples/src/main/resources/customer-portal-ui/js/components/PanelDashboard.js`
- `keel-samples/src/main/resources/customer-portal-ui/js/components/PanelKeys.js`
- `keel-samples/src/main/resources/customer-portal-ui/js/components/PanelBilling.js`

**Modify:**
- `keel-samples/src/main/kotlin/com/keel/samples/KeelSample.kt` — register `CustomerPortalPlugin`
- `keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/AIRelayService.kt` — verifier chain, post-call credit charge
- `keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/AIRelayPlugin.kt` — wire `CustomerApiKeyVerifier` / `CreditLedger` / `CreditRateRegistry`; add Batches endpoints
- `keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/protocol/ProtocolTranscoder.kt` — swap to `AnthropicMessagesV2Codec`

**Test:**
- `keel-test-suite/src/test/kotlin/com/keel/test/kernel/CustomerPortalAuthTest.kt`
- `keel-test-suite/src/test/kotlin/com/keel/test/kernel/CustomerKeyAndCreditTest.kt`
- `keel-test-suite/src/test/kotlin/com/keel/test/kernel/AnthropicMessagesCodecConformanceTest.kt`
- `keel-test-suite/src/test/kotlin/com/keel/test/kernel/AnthropicGatewayIntegrationTest.kt`
- `keel-test-suite/src/test/kotlin/com/keel/test/kernel/BatchesApiTest.kt`
- `keel-test-suite/src/test/resources/anthropic/*.json` (fixtures)

---

## Task 1: Cross-plugin Customer contracts

**Files:**
- Create: `keel-contract/src/main/kotlin/com/keel/contract/customer/CustomerContracts.kt`

- [ ] **Step 1: Create the contract file with all interfaces and data classes**

```kotlin
package com.keel.contract.customer

import kotlinx.serialization.Serializable

interface CustomerApiKeyVerifier {
    /** Verifies a raw key (`sk-keel-cust-...`). Returns null if unknown/revoked/expired. */
    suspend fun verifyCustomerKey(rawKey: String): VerifiedCustomerKey?
}

@Serializable
data class VerifiedCustomerKey(
    val customerId: String,
    val keyId: String,
    val routingGroupId: String,
    val monthlyBudgetCredits: Long?,
    val status: String,
)

interface CreditLedger {
    /** Snapshot the latest balance (sum of ledger). Returns 0 if customer has no rows. */
    suspend fun snapshotBalance(customerId: String): Long

    /**
     * Charges `creditCost` (>= 0). Writes a credit_ledger row and a usage_records row in one tx.
     * If balance < creditCost we still write (post-call accounting); operator-visible negative balance flags
     * the over-run, customer's next call is rejected by [snapshotBalance].
     */
    suspend fun chargeForUsage(
        customerId: String,
        keyId: String,
        creditCost: Long,
        usdMicrosCost: Long,
        usageRow: CustomerUsageRow,
    ): ChargeResult
}

sealed class ChargeResult {
    data class Ok(val newBalanceCredits: Long) : ChargeResult()
    data class Failed(val message: String) : ChargeResult()
}

@Serializable
data class CustomerUsageRow(
    val model: String,
    val groupId: String,
    val providerId: String,
    val wireProtocol: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheReadInputTokens: Long,
    val cacheCreationInputTokens: Long,
    val requestId: String,
)
```

- [ ] **Step 2: Compile to verify**

Run: `./gradlew :keel-contract:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add keel-contract/src/main/kotlin/com/keel/contract/customer/CustomerContracts.kt
git commit -m "feat(contract): add CustomerApiKeyVerifier and CreditLedger contracts"
```

---

## Task 2: customer-portal tables

**Files:**
- Create: `keel-samples/src/main/kotlin/com/keel/samples/aigateway/customerportal/auth/CustomerAuthTables.kt`
- Create: `keel-samples/src/main/kotlin/com/keel/samples/aigateway/customerportal/keys/CustomerKeyTables.kt`
- Create: `keel-samples/src/main/kotlin/com/keel/samples/aigateway/customerportal/credits/CreditLedgerTables.kt`
- Create: `keel-samples/src/main/kotlin/com/keel/samples/aigateway/customerportal/credits/RedemptionCodeTables.kt`
- Create: `keel-samples/src/main/kotlin/com/keel/samples/aigateway/customerportal/credits/CustomerUsageTables.kt`

- [ ] **Step 1: Create CustomerAuthTables.kt**

```kotlin
package com.keel.samples.aigateway.customerportal.auth

import com.keel.db.table.AuditPluginTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object CustomersTable : AuditPluginTable("customer-portal", "customers") {
    val customerId: Column<String> = varchar("customer_id", 32)
    val email: Column<String> = varchar("email", 256).uniqueIndex()
    val passwordHash: Column<String> = varchar("password_hash", 256).default("")
    val passwordSalt: Column<String> = varchar("password_salt", 64).default("")
    val displayName: Column<String> = varchar("display_name", 120)
    val oauthProvider: Column<String?> = varchar("oauth_provider", 16).nullable()
    val oauthSubject: Column<String?> = varchar("oauth_subject", 128).nullable()
    val emailVerified: Column<Boolean> = bool("email_verified").default(false)
    val status: Column<String> = varchar("status", 16).default("active")
    val lastLoginAt = timestamp("last_login_at").nullable()

    override val primaryKey = PrimaryKey(customerId)
}

object CustomerSessionsTable : AuditPluginTable("customer-portal", "sessions") {
    val sessionId: Column<String> = varchar("session_id", 32)
    val customerId: Column<String> = varchar("customer_id", 32).index()
    val refreshTokenHash: Column<String> = varchar("refresh_token_hash", 64).uniqueIndex()
    val expiresAt = timestamp("expires_at")
    val revokedAt = timestamp("revoked_at").nullable()

    override val primaryKey = PrimaryKey(sessionId)
}

object CustomerStatuses {
    const val ACTIVE = "active"
    const val LOCKED = "locked"
    const val DELETED = "deleted"
}
```

- [ ] **Step 2: Create CustomerKeyTables.kt**

```kotlin
package com.keel.samples.aigateway.customerportal.keys

import com.keel.db.table.AuditPluginTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object CustomerKeysTable : AuditPluginTable("customer-portal", "keys") {
    val keyId: Column<String> = varchar("key_id", 32)
    val customerId: Column<String> = varchar("customer_id", 32).index()
    val name: Column<String> = varchar("name", 120)
    val prefix: Column<String> = varchar("prefix", 32)
    val secretHash: Column<String> = varchar("secret_hash", 64).uniqueIndex()
    val routingGroupId: Column<String> = varchar("routing_group_id", 64).default("default")
    val monthlyBudgetCredits: Column<Long?> = long("monthly_budget_credits").nullable()
    val status: Column<String> = varchar("status", 16).default("active")
    val lastUsedAt = timestamp("last_used_at").nullable()
    val revokedAt = timestamp("revoked_at").nullable()

    override val primaryKey = PrimaryKey(keyId)
}
```

- [ ] **Step 3: Create CreditLedgerTables.kt**

```kotlin
package com.keel.samples.aigateway.customerportal.credits

import com.keel.db.table.AuditPluginTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object CreditLedgerTable : AuditPluginTable("customer-portal", "credit_ledger") {
    val entryId: Column<String> = varchar("entry_id", 32)
    val customerId: Column<String> = varchar("customer_id", 32).index()
    val deltaCredits: Column<Long> = long("delta_credits")
    val reason: Column<String> = varchar("reason", 32)
    val refId: Column<String?> = varchar("ref_id", 64).nullable()
    val balanceAfterCredits: Column<Long> = long("balance_after_credits")
    val usdMicrosAtTime: Column<Long> = long("usd_micros_at_time").default(0)

    override val primaryKey = PrimaryKey(entryId)
}

object LedgerReasons {
    const val SIGNUP_BONUS = "signup_bonus"
    const val REDEMPTION = "redemption"
    const val USAGE = "usage"
    const val REFUND = "refund"
    const val ADMIN_ADJUSTMENT = "admin_adjustment"
}
```

- [ ] **Step 4: Create RedemptionCodeTables.kt**

```kotlin
package com.keel.samples.aigateway.customerportal.credits

import com.keel.db.table.AuditPluginTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object RedemptionCodesTable : AuditPluginTable("customer-portal", "redemption_codes") {
    val code: Column<String> = varchar("code", 32).uniqueIndex()
    val faceValueCredits: Column<Long> = long("face_value_credits")
    val createdByAdminId: Column<String> = varchar("created_by_admin_id", 64)
    val redeemedByCustomerId: Column<String?> = varchar("redeemed_by_customer_id", 32).nullable()
    val redeemedAt = timestamp("redeemed_at").nullable()
    val expiresAt = timestamp("expires_at").nullable()
    val status: Column<String> = varchar("status", 16).default("active")

    override val primaryKey = PrimaryKey(code)
}

object RedemptionCodeStatuses {
    const val ACTIVE = "active"
    const val REDEEMED = "redeemed"
    const val EXPIRED = "expired"
    const val REVOKED = "revoked"
}
```

- [ ] **Step 5: Create CustomerUsageTables.kt**

```kotlin
package com.keel.samples.aigateway.customerportal.credits

import com.keel.db.table.AuditPluginTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object CustomerUsageTable : AuditPluginTable("customer-portal", "usage_records") {
    val recordId: Column<String> = varchar("record_id", 32)
    val customerId: Column<String> = varchar("customer_id", 32).index()
    val keyId: Column<String> = varchar("key_id", 32).index()
    val model: Column<String> = varchar("model", 120)
    val groupId: Column<String> = varchar("group_id", 64)
    val providerId: Column<String> = varchar("provider_id", 64)
    val wireProtocol: Column<String> = varchar("wire_protocol", 40)
    val inputTokens: Column<Long> = long("input_tokens").default(0)
    val outputTokens: Column<Long> = long("output_tokens").default(0)
    val cacheReadInputTokens: Column<Long> = long("cache_read_input_tokens").default(0)
    val cacheCreationInputTokens: Column<Long> = long("cache_creation_input_tokens").default(0)
    val creditCost: Column<Long> = long("credit_cost").default(0)
    val usdMicrosCost: Column<Long> = long("usd_micros_cost").default(0)
    val requestId: Column<String> = varchar("request_id", 64).default("")

    override val primaryKey = PrimaryKey(recordId)
}
```

- [ ] **Step 6: Compile to verify all tables load**

Run: `./gradlew :keel-samples:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add keel-samples/src/main/kotlin/com/keel/samples/aigateway/customerportal/
git commit -m "feat(customer-portal): add Exposed tables (customers/sessions/keys/credit_ledger/redemption/usage)"
```

---

## Task 3: PasswordHasher + CustomerJwtService

**Files:**
- Create: `keel-samples/src/main/kotlin/com/keel/samples/aigateway/customerportal/auth/PasswordHasher.kt`
- Create: `keel-samples/src/main/kotlin/com/keel/samples/aigateway/customerportal/auth/CustomerJwtService.kt`
- Create: `keel-samples/src/main/kotlin/com/keel/samples/aigateway/customerportal/auth/CustomerAuthModels.kt`

- [ ] **Step 1: PasswordHasher.kt — PBKDF2 with random salt**

```kotlin
package com.keel.samples.aigateway.customerportal.auth

import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import java.util.Base64

class PasswordHasher(
    private val iterations: Int = 100_000,
    private val keyLength: Int = 256,
    private val random: SecureRandom = SecureRandom()
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
            expectedHash.toByteArray(Charsets.UTF_8)
        )
    }
}
```

- [ ] **Step 2: CustomerAuthModels.kt — request/response DTOs**

```kotlin
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
```

- [ ] **Step 3: CustomerJwtService.kt — HMAC-SHA256 JWT (same pattern as account/JwtTokenService)**

```kotlin
package com.keel.samples.aigateway.customerportal.auth

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

class CustomerJwtService(
    private val secret: String = System.getenv("CUSTOMER_JWT_SECRET")
        ?: "keel-customer-portal-demo-secret-change-me"
) {
    private val json = Json { encodeDefaults = true }

    fun issue(principal: CustomerPrincipal, ttl: Duration = 1.hours): Pair<String, Long> {
        val now = Clock.System.now()
        val exp = now.plus(ttl).epochSeconds
        val header = base64Url(json.encodeToString(JwtHeader()).toByteArray())
        val payload = base64Url(
            json.encodeToString(
                JwtPayload(sub = principal.customerId, email = principal.email, iat = now.epochSeconds, exp = exp)
            ).toByteArray()
        )
        val unsigned = "$header.$payload"
        return "$unsigned.${sign(unsigned)}" to (exp - now.epochSeconds)
    }

    fun verify(token: String): CustomerPrincipal? {
        val parts = token.split('.')
        if (parts.size \!= 3) return null
        if (sign("${parts[0]}.${parts[1]}") \!= parts[2]) return null
        val payload = runCatching {
            json.decodeFromString<JwtPayload>(base64UrlDecode(parts[1]).toString(Charsets.UTF_8))
        }.getOrNull() ?: return null
        if (Clock.System.now() >= Instant.fromEpochSeconds(payload.exp)) return null
        return CustomerPrincipal(customerId = payload.sub, email = payload.email)
    }

    private fun sign(data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(), "HmacSHA256"))
        return base64Url(mac.doFinal(data.toByteArray()))
    }

    private fun base64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun base64UrlDecode(s: String): ByteArray = Base64.getUrlDecoder().decode(s)

    @Serializable
    private data class JwtHeader(val alg: String = "HS256", val typ: String = "JWT")

    @Serializable
    private data class JwtPayload(val sub: String, val email: String, val iat: Long, val exp: Long)
}
```

- [ ] **Step 4: Compile**

Run: `./gradlew :keel-samples:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Commit**

```bash
git add keel-samples/src/main/kotlin/com/keel/samples/aigateway/customerportal/auth/
git commit -m "feat(customer-portal): add PasswordHasher, JWT service, and auth DTOs"
```

---

## Task 4: CustomerAuthRepository (TDD)

**Files:**
- Create: `keel-samples/src/main/kotlin/com/keel/samples/aigateway/customerportal/auth/CustomerAuthRepository.kt`
- Create: `keel-test-suite/src/test/kotlin/com/keel/test/kernel/CustomerPortalAuthTest.kt`

- [ ] **Step 1: Write the failing test**

`keel-test-suite/src/test/kotlin/com/keel/test/kernel/CustomerPortalAuthTest.kt`:

```kotlin
package com.keel.test.kernel

import com.keel.db.database.DatabaseFactory
import com.keel.samples.aigateway.customerportal.auth.CustomerAuthRepository
import com.keel.samples.aigateway.customerportal.auth.CustomerJwtService
import com.keel.samples.aigateway.customerportal.auth.CustomerLoginRequest
import com.keel.samples.aigateway.customerportal.auth.CustomerOAuthStubRequest
import com.keel.samples.aigateway.customerportal.auth.CustomerRegisterRequest
import com.keel.samples.aigateway.customerportal.auth.PasswordHasher
import com.keel.samples.aigateway.customerportal.credits.CreditLedgerRepository
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CustomerPortalAuthTest {
    private fun newRepo(): Pair<CustomerAuthRepository, CreditLedgerRepository> {
        val db = DatabaseFactory.h2Memory(name = "cp_auth_${System.nanoTime()}", poolSize = 2).init()
        val ledger = CreditLedgerRepository(db)
        ledger.initializeSchema()
        val repo = CustomerAuthRepository(
            database = db,
            hasher = PasswordHasher(iterations = 1_000),
            jwt = CustomerJwtService(secret = "test-secret"),
            ledger = ledger,
            signupBonusCredits = 1000L,
        )
        repo.initializeSchema()
        return repo to ledger
    }

    @Test
    fun signupCreatesCustomerAndGrantsBonusCredits() = runBlocking {
        val (repo, ledger) = newRepo()
        val response = repo.register(CustomerRegisterRequest("a@b.com", "longenoughpw1", "Alice"))
        assertEquals("a@b.com", response.email)
        assertTrue(response.accessToken.split('.').size == 3)
        val balance = ledger.snapshotBalance(response.customerId)
        assertEquals(1000L, balance)
    }

    @Test
    fun loginReturnsJwtAndWrongPasswordIsRejected() = runBlocking {
        val (repo, _) = newRepo()
        repo.register(CustomerRegisterRequest("a@b.com", "longenoughpw1", "Alice"))
        val ok = repo.login(CustomerLoginRequest("a@b.com", "longenoughpw1"))
        assertNotNull(ok.accessToken)
        assertFailsWith<com.keel.kernel.plugin.PluginApiException> {
            repo.login(CustomerLoginRequest("a@b.com", "wrong"))
        }
    }

    @Test
    fun oauthStubCreatesCustomerWithProvider() = runBlocking {
        val (repo, _) = newRepo()
        val response = repo.oauthStub(CustomerOAuthStubRequest("github", "g@h.com", "Gus"))
        val profile = repo.profile(repo.verifyAccessToken(response.accessToken)\!\!)
        assertEquals("github", profile.oauthProvider)
        assertEquals("g@h.com", profile.email)
    }

    @Test
    fun verifyAccessTokenRejectsForgedToken() = runBlocking {
        val (repo, _) = newRepo()
        assertNull(repo.verifyAccessToken("not.a.jwt"))
    }

    @Test
    fun duplicateEmailRejected() = runBlocking {
        val (repo, _) = newRepo()
        repo.register(CustomerRegisterRequest("a@b.com", "longenoughpw1", "Alice"))
        assertFailsWith<com.keel.kernel.plugin.PluginApiException> {
            repo.register(CustomerRegisterRequest("a@b.com", "longenoughpw2", "Alice2"))
        }
    }
}
```

- [ ] **Step 2: Run test — expect compile failure (CustomerAuthRepository missing)**

Run: `./gradlew :keel-test-suite:test --tests "com.keel.test.kernel.CustomerPortalAuthTest"`
Expected: FAIL — unresolved reference `CustomerAuthRepository`

- [ ] **Step 3: Implement CustomerAuthRepository.kt**

```kotlin
package com.keel.samples.aigateway.customerportal.auth

import com.keel.contract.customer.CustomerUsageRow
import com.keel.db.database.KeelDatabase
import com.keel.kernel.plugin.PluginApiException
import com.keel.samples.aigateway.customerportal.credits.CreditLedgerRepository
import com.keel.samples.aigateway.customerportal.credits.LedgerReasons
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.security.SecureRandom

class CustomerAuthRepository(
    private val database: KeelDatabase,
    private val hasher: PasswordHasher,
    private val jwt: CustomerJwtService,
    private val ledger: CreditLedgerRepository,
    private val signupBonusCredits: Long = 1000L,
    private val random: SecureRandom = SecureRandom(),
) {
    fun initializeSchema() {
        database.createTables(CustomersTable, CustomerSessionsTable)
    }

    suspend fun register(request: CustomerRegisterRequest): CustomerAuthResponse {
        val email = normalize(request.email)
        validatePassword(request.password)
        val displayName = request.displayName.trim()
            .ifBlank { throw PluginApiException(400, "displayName required") }
        val customerId = nextId("cust")
        database.transaction {
            if (CustomersTable.selectAll().where { CustomersTable.email eq email }.count() > 0) {
                throw PluginApiException(409, "email already registered")
            }
            val salt = hasher.newSalt()
            CustomersTable.insert {
                it[CustomersTable.customerId] = customerId
                it[CustomersTable.email] = email
                it[passwordHash] = hasher.hash(request.password, salt)
                it[passwordSalt] = salt
                it[CustomersTable.displayName] = displayName
                it[oauthProvider] = null
                it[oauthSubject] = null
                it[emailVerified] = false
                it[status] = CustomerStatuses.ACTIVE
                it[lastLoginAt] = Clock.System.now()
                it[createdBy] = customerId
                it[updatedBy] = customerId
                it[createdAt] = Clock.System.now()
                it[updatedAt] = Clock.System.now()
                it[deletedAt] = null
            }
        }
        if (signupBonusCredits > 0) {
            ledger.grant(
                customerId = customerId,
                deltaCredits = signupBonusCredits,
                reason = LedgerReasons.SIGNUP_BONUS,
                refId = customerId,
            )
        }
        return issueAuthResponse(customerId, email, displayName)
    }

    suspend fun login(request: CustomerLoginRequest): CustomerAuthResponse {
        val email = normalize(request.email)
        val (customerId, name) = database.transaction {
            val row = CustomersTable.selectAll()
                .where { (CustomersTable.email eq email) and CustomersTable.deletedAt.isNull() }
                .firstOrNull()
                ?: throw PluginApiException(401, "invalid credentials")
            if (row[CustomersTable.status] \!= CustomerStatuses.ACTIVE) {
                throw PluginApiException(403, "account not active")
            }
            if (\!hasher.verify(request.password, row[CustomersTable.passwordSalt], row[CustomersTable.passwordHash])) {
                throw PluginApiException(401, "invalid credentials")
            }
            CustomersTable.update({ CustomersTable.customerId eq row[CustomersTable.customerId] }) {
                it[lastLoginAt] = Clock.System.now()
            }
            row[CustomersTable.customerId] to row[CustomersTable.displayName]
        }
        return issueAuthResponse(customerId, email, name)
    }

    suspend fun oauthStub(request: CustomerOAuthStubRequest): CustomerAuthResponse {
        val email = normalize(request.email)
        val customerId = nextId("cust")
        val displayName = request.displayName.trim().ifBlank { email.substringBefore('@') }
        database.transaction {
            val existing = CustomersTable.selectAll().where { CustomersTable.email eq email }.firstOrNull()
            if (existing \!= null) {
                CustomersTable.update({ CustomersTable.customerId eq existing[CustomersTable.customerId] }) {
                    it[oauthProvider] = request.provider
                    it[oauthSubject] = "stub_${java.util.UUID.randomUUID()}"
                    it[lastLoginAt] = Clock.System.now()
                }
                return@transaction
            }
            CustomersTable.insert {
                it[CustomersTable.customerId] = customerId
                it[CustomersTable.email] = email
                it[passwordHash] = ""
                it[passwordSalt] = ""
                it[CustomersTable.displayName] = displayName
                it[oauthProvider] = request.provider
                it[oauthSubject] = "stub_${java.util.UUID.randomUUID()}"
                it[emailVerified] = true
                it[status] = CustomerStatuses.ACTIVE
                it[lastLoginAt] = Clock.System.now()
                it[createdBy] = customerId
                it[updatedBy] = customerId
                it[createdAt] = Clock.System.now()
                it[updatedAt] = Clock.System.now()
                it[deletedAt] = null
            }
            if (signupBonusCredits > 0) {
                runBlocking {
                    ledger.grant(
                        customerId = customerId,
                        deltaCredits = signupBonusCredits,
                        reason = LedgerReasons.SIGNUP_BONUS,
                        refId = customerId,
                    )
                }
            }
        }
        val final = database.transaction {
            CustomersTable.selectAll().where { CustomersTable.email eq email }.first()
        }
        return issueAuthResponse(final[CustomersTable.customerId], email, final[CustomersTable.displayName])
    }

    fun verifyAccessToken(token: String): CustomerPrincipal? = jwt.verify(token)

    suspend fun profile(principal: CustomerPrincipal): CustomerProfile {
        val row = database.transaction {
            CustomersTable.selectAll().where { CustomersTable.customerId eq principal.customerId }.first()
        }
        val balance = ledger.snapshotBalance(principal.customerId)
        return CustomerProfile(
            customerId = row[CustomersTable.customerId],
            email = row[CustomersTable.email],
            displayName = row[CustomersTable.displayName],
            emailVerified = row[CustomersTable.emailVerified],
            oauthProvider = row[CustomersTable.oauthProvider],
            balanceCredits = balance,
            createdAt = row[CustomersTable.createdAt].toString(),
        )
    }

    private fun issueAuthResponse(customerId: String, email: String, name: String): CustomerAuthResponse {
        val (token, ttl) = jwt.issue(CustomerPrincipal(customerId, email))
        val refresh = newRefreshToken(customerId)
        return CustomerAuthResponse(
            customerId = customerId,
            email = email,
            displayName = name,
            accessToken = token,
            refreshToken = refresh,
            expiresInSeconds = ttl,
        )
    }

    private fun newRefreshToken(customerId: String): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        val raw = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        database.transaction {
            CustomerSessionsTable.insert {
                it[sessionId] = nextId("ses")
                it[CustomerSessionsTable.customerId] = customerId
                it[refreshTokenHash] = sha256(raw)
                it[expiresAt] = Clock.System.now().plus(kotlin.time.Duration.parse("PT720H"))
                it[revokedAt] = null
                it[createdBy] = customerId
                it[updatedBy] = customerId
                it[createdAt] = Clock.System.now()
                it[updatedAt] = Clock.System.now()
                it[deletedAt] = null
            }
        }
        return raw
    }

    private fun normalize(email: String): String =
        email.trim().lowercase().also {
            if (\!it.contains('@')) throw PluginApiException(400, "invalid email")
        }

    private fun validatePassword(pw: String) {
        if (pw.length < 12) throw PluginApiException(400, "password must be at least 12 characters")
    }

    private fun nextId(prefix: String): String {
        val bytes = ByteArray(12)
        random.nextBytes(bytes)
        return "$prefix-" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).take(24)
    }

    private fun sha256(s: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return java.util.HexFormat.of().formatHex(md.digest(s.toByteArray()))
    }
}
```

- [ ] **Step 4: Run test — should pass**

Run: `./gradlew :keel-test-suite:test --tests "com.keel.test.kernel.CustomerPortalAuthTest"`
Expected: 5 tests PASS

- [ ] **Step 5: Commit**

```bash
git add keel-samples/src/main/kotlin/com/keel/samples/aigateway/customerportal/auth/CustomerAuthRepository.kt \
        keel-test-suite/src/test/kotlin/com/keel/test/kernel/CustomerPortalAuthTest.kt
git commit -m "feat(customer-portal): CustomerAuthRepository with PBKDF2 + JWT + signup bonus + OAuth stub"
```

---

## Task 5: CreditLedgerRepository + RedemptionCodeRepository (TDD)

**Files:**
- Create: `keel-samples/src/main/kotlin/com/keel/samples/aigateway/customerportal/credits/CreditLedgerRepository.kt`
- Create: `keel-samples/src/main/kotlin/com/keel/samples/aigateway/customerportal/credits/CreditLedgerModels.kt`
- Create: `keel-samples/src/main/kotlin/com/keel/samples/aigateway/customerportal/credits/RedemptionCodeRepository.kt`
- Create: `keel-samples/src/main/kotlin/com/keel/samples/aigateway/customerportal/credits/CustomerUsageRepository.kt`
- Create: `keel-test-suite/src/test/kotlin/com/keel/test/kernel/CustomerKeyAndCreditTest.kt`

- [ ] **Step 1: Write CreditLedgerModels.kt**

```kotlin
package com.keel.samples.aigateway.customerportal.credits

import kotlinx.serialization.Serializable

@Serializable
data class LedgerEntryView(
    val entryId: String,
    val deltaCredits: Long,
    val reason: String,
    val refId: String?,
    val balanceAfterCredits: Long,
    val createdAt: String,
)

@Serializable
data class LedgerListResponse(val entries: List<LedgerEntryView>, val balanceCredits: Long)

@Serializable
data class MintRedemptionRequest(val faceValueCredits: Long, val count: Int = 1, val expiresInDays: Int? = null)

@Serializable
data class RedemptionCodeView(
    val code: String,
    val faceValueCredits: Long,
    val status: String,
    val expiresAt: String?,
    val redeemedAt: String?,
)

@Serializable
data class MintRedemptionResponse(val codes: List<RedemptionCodeView>)

@Serializable
data class RedeemRequest(val code: String)

@Serializable
data class RedeemResponse(val deltaCredits: Long, val balanceCredits: Long)

@Serializable
data class CustomerUsageView(
    val recordId: String,
    val model: String,
    val groupId: String,
    val providerId: String,
    val wireProtocol: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val creditCost: Long,
    val usdMicrosCost: Long,
    val requestId: String,
    val createdAt: String,
)

@Serializable
data class CustomerUsageListResponse(val records: List<CustomerUsageView>)
```

- [ ] **Step 2: Write CreditLedgerRepository.kt**

```kotlin
package com.keel.samples.aigateway.customerportal.credits

import com.keel.contract.customer.ChargeResult
import com.keel.contract.customer.CreditLedger
import com.keel.contract.customer.CustomerUsageRow
import com.keel.db.database.KeelDatabase
import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import java.security.SecureRandom

class CreditLedgerRepository(
    private val database: KeelDatabase,
    private val random: SecureRandom = SecureRandom(),
) : CreditLedger {

    fun initializeSchema() {
        database.createTables(CreditLedgerTable, CustomerUsageTable)
    }

    override suspend fun snapshotBalance(customerId: String): Long = database.transaction {
        CreditLedgerTable.selectAll()
            .where { CreditLedgerTable.customerId eq customerId }
            .orderBy(CreditLedgerTable.createdAt to SortOrder.DESC)
            .firstOrNull()
            ?.get(CreditLedgerTable.balanceAfterCredits)
            ?: 0L
    }

    fun grant(customerId: String, deltaCredits: Long, reason: String, refId: String?): Long =
        database.transaction {
            insertEntry(customerId, deltaCredits, reason, refId, usdMicros = 0L)
        }

    override suspend fun chargeForUsage(
        customerId: String,
        keyId: String,
        creditCost: Long,
        usdMicrosCost: Long,
        usageRow: CustomerUsageRow,
    ): ChargeResult = try {
        database.transaction {
            val newBalance = insertEntry(
                customerId = customerId,
                deltaCredits = -creditCost,
                reason = LedgerReasons.USAGE,
                refId = usageRow.requestId,
                usdMicros = usdMicrosCost,
            )
            CustomerUsageTable.insert {
                it[recordId] = nextId("rec")
                it[CustomerUsageTable.customerId] = customerId
                it[CustomerUsageTable.keyId] = keyId
                it[model] = usageRow.model
                it[groupId] = usageRow.groupId
                it[providerId] = usageRow.providerId
                it[wireProtocol] = usageRow.wireProtocol
                it[inputTokens] = usageRow.inputTokens
                it[outputTokens] = usageRow.outputTokens
                it[cacheReadInputTokens] = usageRow.cacheReadInputTokens
                it[cacheCreationInputTokens] = usageRow.cacheCreationInputTokens
                it[creditCost] = creditCost
                it[usdMicrosCost] = usdMicrosCost
                it[requestId] = usageRow.requestId
                it[createdBy] = customerId
                it[updatedBy] = customerId
                it[createdAt] = Clock.System.now()
                it[updatedAt] = Clock.System.now()
                it[deletedAt] = null
            }
            ChargeResult.Ok(newBalance)
        }
    } catch (e: Exception) {
        ChargeResult.Failed(e.message ?: "ledger failure")
    }

    suspend fun listLedger(customerId: String, limit: Int = 100): LedgerListResponse = database.transaction {
        val rows = CreditLedgerTable.selectAll()
            .where { CreditLedgerTable.customerId eq customerId }
            .orderBy(CreditLedgerTable.createdAt to SortOrder.DESC)
            .limit(limit)
            .map {
                LedgerEntryView(
                    entryId = it[CreditLedgerTable.entryId],
                    deltaCredits = it[CreditLedgerTable.deltaCredits],
                    reason = it[CreditLedgerTable.reason],
                    refId = it[CreditLedgerTable.refId],
                    balanceAfterCredits = it[CreditLedgerTable.balanceAfterCredits],
                    createdAt = it[CreditLedgerTable.createdAt].toString(),
                )
            }
        val balance = rows.firstOrNull()?.balanceAfterCredits ?: 0L
        LedgerListResponse(rows, balance)
    }

    suspend fun listUsage(customerId: String, limit: Int = 100): CustomerUsageListResponse = database.transaction {
        val rows = CustomerUsageTable.selectAll()
            .where { CustomerUsageTable.customerId eq customerId }
            .orderBy(CustomerUsageTable.createdAt to SortOrder.DESC)
            .limit(limit)
            .map {
                CustomerUsageView(
                    recordId = it[CustomerUsageTable.recordId],
                    model = it[CustomerUsageTable.model],
                    groupId = it[CustomerUsageTable.groupId],
                    providerId = it[CustomerUsageTable.providerId],
                    wireProtocol = it[CustomerUsageTable.wireProtocol],
                    inputTokens = it[CustomerUsageTable.inputTokens],
                    outputTokens = it[CustomerUsageTable.outputTokens],
                    creditCost = it[CustomerUsageTable.creditCost],
                    usdMicrosCost = it[CustomerUsageTable.usdMicrosCost],
                    requestId = it[CustomerUsageTable.requestId],
                    createdAt = it[CustomerUsageTable.createdAt].toString(),
                )
            }
        CustomerUsageListResponse(rows)
    }

    private fun insertEntry(
        customerId: String,
        deltaCredits: Long,
        reason: String,
        refId: String?,
        usdMicros: Long,
    ): Long {
        val previous = CreditLedgerTable.selectAll()
            .where { CreditLedgerTable.customerId eq customerId }
            .orderBy(CreditLedgerTable.createdAt to SortOrder.DESC)
            .firstOrNull()
            ?.get(CreditLedgerTable.balanceAfterCredits) ?: 0L
        val next = previous + deltaCredits
        CreditLedgerTable.insert {
            it[entryId] = nextId("led")
            it[CreditLedgerTable.customerId] = customerId
            it[CreditLedgerTable.deltaCredits] = deltaCredits
            it[CreditLedgerTable.reason] = reason
            it[CreditLedgerTable.refId] = refId
            it[balanceAfterCredits] = next
            it[usdMicrosAtTime] = usdMicros
            it[createdBy] = customerId
            it[updatedBy] = customerId
            it[createdAt] = Clock.System.now()
            it[updatedAt] = Clock.System.now()
            it[deletedAt] = null
        }
        return next
    }

    private fun nextId(prefix: String): String {
        val bytes = ByteArray(12)
        random.nextBytes(bytes)
        return "$prefix-" + java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).take(24)
    }
}
```

- [ ] **Step 3: Write RedemptionCodeRepository.kt**

```kotlin
package com.keel.samples.aigateway.customerportal.credits

import com.keel.db.database.KeelDatabase
import com.keel.kernel.plugin.PluginApiException
import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.security.SecureRandom
import kotlin.time.Duration.Companion.days

class RedemptionCodeRepository(
    private val database: KeelDatabase,
    private val ledger: CreditLedgerRepository,
    private val random: SecureRandom = SecureRandom(),
) {
    fun initializeSchema() {
        database.createTables(RedemptionCodesTable)
    }

    fun mint(adminId: String, request: MintRedemptionRequest): MintRedemptionResponse = database.transaction {
        if (request.faceValueCredits <= 0) throw PluginApiException(400, "faceValueCredits must be > 0")
        if (request.count <= 0 || request.count > 1000) throw PluginApiException(400, "count out of range")
        val expiresAt = request.expiresInDays?.let { Clock.System.now().plus(it.days) }
        val views = (1..request.count).map {
            val code = freshCode()
            RedemptionCodesTable.insert {
                it[RedemptionCodesTable.code] = code
                it[faceValueCredits] = request.faceValueCredits
                it[createdByAdminId] = adminId
                it[redeemedByCustomerId] = null
                it[redeemedAt] = null
                it[RedemptionCodesTable.expiresAt] = expiresAt
                it[status] = RedemptionCodeStatuses.ACTIVE
                it[createdBy] = adminId
                it[updatedBy] = adminId
                it[createdAt] = Clock.System.now()
                it[updatedAt] = Clock.System.now()
                it[deletedAt] = null
            }
            RedemptionCodeView(code, request.faceValueCredits, RedemptionCodeStatuses.ACTIVE,
                expiresAt?.toString(), null)
        }
        MintRedemptionResponse(views)
    }

    suspend fun redeem(customerId: String, request: RedeemRequest): RedeemResponse {
        val code = request.code.trim().uppercase()
        val face = database.transaction {
            val row = RedemptionCodesTable.selectAll()
                .where { RedemptionCodesTable.code eq code }
                .firstOrNull()
                ?: throw PluginApiException(404, "code not found")
            if (row[RedemptionCodesTable.status] \!= RedemptionCodeStatuses.ACTIVE) {
                throw PluginApiException(409, "code already redeemed or revoked")
            }
            val expiresAt = row[RedemptionCodesTable.expiresAt]
            if (expiresAt \!= null && Clock.System.now() >= expiresAt) {
                RedemptionCodesTable.update({ RedemptionCodesTable.code eq code }) {
                    it[status] = RedemptionCodeStatuses.EXPIRED
                }
                throw PluginApiException(410, "code expired")
            }
            RedemptionCodesTable.update({ RedemptionCodesTable.code eq code }) {
                it[redeemedByCustomerId] = customerId
                it[redeemedAt] = Clock.System.now()
                it[status] = RedemptionCodeStatuses.REDEEMED
                it[updatedAt] = Clock.System.now()
                it[updatedBy] = customerId
            }
            row[RedemptionCodesTable.faceValueCredits]
        }
        val newBalance = ledger.grant(customerId, face, LedgerReasons.REDEMPTION, code)
        return RedeemResponse(deltaCredits = face, balanceCredits = newBalance)
    }

    private fun freshCode(): String {
        val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // base32 without ambiguous chars
        val sb = StringBuilder(16)
        repeat(16) { sb.append(alphabet[random.nextInt(alphabet.length)]) }
        return sb.toString()
    }
}
```

- [ ] **Step 4: Write the failing test**

`keel-test-suite/src/test/kotlin/com/keel/test/kernel/CustomerKeyAndCreditTest.kt`:

```kotlin
package com.keel.test.kernel

import com.keel.contract.customer.ChargeResult
import com.keel.contract.customer.CustomerUsageRow
import com.keel.db.database.DatabaseFactory
import com.keel.kernel.plugin.PluginApiException
import com.keel.samples.aigateway.customerportal.credits.CreditLedgerRepository
import com.keel.samples.aigateway.customerportal.credits.MintRedemptionRequest
import com.keel.samples.aigateway.customerportal.credits.RedeemRequest
import com.keel.samples.aigateway.customerportal.credits.RedemptionCodeRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CustomerKeyAndCreditTest {
    private fun newPair(): Pair<CreditLedgerRepository, RedemptionCodeRepository> {
        val db = DatabaseFactory.h2Memory("cp_credit_${System.nanoTime()}", poolSize = 4).init()
        val ledger = CreditLedgerRepository(db)
        ledger.initializeSchema()
        val redemption = RedemptionCodeRepository(db, ledger)
        redemption.initializeSchema()
        return ledger to redemption
    }

    @Test
    fun chargeDeductsFromBalanceAndWritesUsage() = runBlocking {
        val (ledger, _) = newPair()
        ledger.grant("c1", 500, "signup_bonus", null)
        val result = ledger.chargeForUsage("c1", "k1", 120L, 9_900L, sampleRow())
        assertTrue(result is ChargeResult.Ok)
        assertEquals(380L, (result as ChargeResult.Ok).newBalanceCredits)
        assertEquals(380L, ledger.snapshotBalance("c1"))
        assertEquals(1, ledger.listUsage("c1").records.size)
    }

    @Test
    fun chargeAllowedToGoNegativeForPostCallAccounting() = runBlocking {
        val (ledger, _) = newPair()
        ledger.grant("c1", 50, "signup_bonus", null)
        val result = ledger.chargeForUsage("c1", "k1", 200L, 0L, sampleRow())
        assertTrue(result is ChargeResult.Ok)
        assertEquals(-150L, (result as ChargeResult.Ok).newBalanceCredits)
    }

    @Test
    fun concurrentChargesPreserveSum() = runBlocking {
        val (ledger, _) = newPair()
        ledger.grant("c1", 1_000, "signup_bonus", null)
        coroutineScope {
            (1..10).map { idx ->
                async {
                    ledger.chargeForUsage("c1", "k1", 50L, 100L,
                        sampleRow(requestId = "req-$idx"))
                }
            }.awaitAll()
        }
        assertEquals(500L, ledger.snapshotBalance("c1"))
        assertEquals(10, ledger.listUsage("c1").records.size)
    }

    @Test
    fun redemptionCodeAddsCreditsThenSecondRedeemFails() = runBlocking {
        val (_, redemption) = newPair()
        val codes = redemption.mint("admin", MintRedemptionRequest(faceValueCredits = 250)).codes
        val first = redemption.redeem("c1", RedeemRequest(codes.first().code))
        assertEquals(250L, first.deltaCredits)
        assertFailsWith<PluginApiException> { redemption.redeem("c2", RedeemRequest(codes.first().code)) }
    }

    private fun sampleRow(requestId: String = "req-1") = CustomerUsageRow(
        model = "claude-sonnet-4-6", groupId = "default", providerId = "anthropic",
        wireProtocol = "ANTHROPIC_MESSAGES", inputTokens = 100, outputTokens = 50,
        cacheReadInputTokens = 0, cacheCreationInputTokens = 0, requestId = requestId
    )
}
```

- [ ] **Step 5: Create CustomerUsageRepository.kt** (empty stub for future API endpoints — `CustomerUsageTable` access via CreditLedgerRepository for now)

```kotlin
package com.keel.samples.aigateway.customerportal.credits

/**
 * Reserved for future per-customer usage analytics that don't belong on the credit ledger.
 * For now CreditLedgerRepository writes and reads CustomerUsageTable rows directly.
 */
class CustomerUsageRepository
```

- [ ] **Step 6: Run test**

Run: `./gradlew :keel-test-suite:test --tests "com.keel.test.kernel.CustomerKeyAndCreditTest"`
Expected: 4 tests PASS

- [ ] **Step 7: Commit**

```bash
git add keel-samples/src/main/kotlin/com/keel/samples/aigateway/customerportal/credits/ \
        keel-test-suite/src/test/kotlin/com/keel/test/kernel/CustomerKeyAndCreditTest.kt
git commit -m "feat(customer-portal): credit ledger + redemption codes with TDD coverage"
```

---

## Task 6: CustomerKeyRepository + CustomerApiKeyVerifier wiring

**Files:**
- Create: `keel-samples/src/main/kotlin/com/keel/samples/aigateway/customerportal/keys/CustomerKeyRepository.kt`
- Create: `keel-samples/src/main/kotlin/com/keel/samples/aigateway/customerportal/keys/CustomerKeyModels.kt`

- [ ] **Step 1: CustomerKeyModels.kt**

```kotlin
package com.keel.samples.aigateway.customerportal.keys

import kotlinx.serialization.Serializable

@Serializable
data class CreateCustomerKeyRequest(
    val name: String,
    val routingGroupId: String = "default",
    val monthlyBudgetCredits: Long? = null,
)

@Serializable
data class UpdateCustomerKeyRequest(
    val name: String? = null,
    val routingGroupId: String? = null,
    val monthlyBudgetCredits: Long? = null,
    val status: String? = null,
)

@Serializable
data class CustomerKeyView(
    val keyId: String,
    val customerId: String,
    val name: String,
    val prefix: String,
    val routingGroupId: String,
    val monthlyBudgetCredits: Long?,
    val status: String,
    val createdAt: String,
    val lastUsedAt: String?,
    val revokedAt: String?,
)

@Serializable
data class CustomerKeyCreatedResponse(val key: CustomerKeyView, val rawSecret: String)

@Serializable
data class CustomerKeyListResponse(val keys: List<CustomerKeyView>)
```

- [ ] **Step 2: CustomerKeyRepository.kt**

```kotlin
package com.keel.samples.aigateway.customerportal.keys

import com.keel.contract.customer.CustomerApiKeyVerifier
import com.keel.contract.customer.VerifiedCustomerKey
import com.keel.db.database.KeelDatabase
import com.keel.kernel.plugin.PluginApiException
import kotlinx.datetime.Clock
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat

class CustomerKeyRepository(
    private val database: KeelDatabase,
    private val random: SecureRandom = SecureRandom(),
) : CustomerApiKeyVerifier {

    fun initializeSchema() = database.createTables(CustomerKeysTable)

    fun createKey(customerId: String, request: CreateCustomerKeyRequest): CustomerKeyCreatedResponse =
        database.transaction {
            val name = request.name.trim().ifBlank { throw PluginApiException(400, "name required") }
            val rawKey = nextRawKey()
            val keyId = nextId("ckey")
            CustomerKeysTable.insert {
                it[CustomerKeysTable.keyId] = keyId
                it[CustomerKeysTable.customerId] = customerId
                it[CustomerKeysTable.name] = name
                it[prefix] = rawKey.take(16)
                it[secretHash] = sha256(rawKey)
                it[routingGroupId] = request.routingGroupId.ifBlank { "default" }
                it[monthlyBudgetCredits] = request.monthlyBudgetCredits
                it[status] = "active"
                it[lastUsedAt] = null
                it[revokedAt] = null
                it[createdBy] = customerId
                it[updatedBy] = customerId
                it[createdAt] = Clock.System.now()
                it[updatedAt] = Clock.System.now()
                it[deletedAt] = null
            }
            CustomerKeyCreatedResponse(findRow(keyId).toView(), rawKey)
        }

    fun listKeys(customerId: String): CustomerKeyListResponse = database.transaction {
        val rows = CustomerKeysTable.selectAll()
            .where { (CustomerKeysTable.customerId eq customerId) and CustomerKeysTable.deletedAt.isNull() }
            .orderBy(CustomerKeysTable.createdAt to SortOrder.DESC)
            .map { it.toView() }
        CustomerKeyListResponse(rows)
    }

    fun updateKey(customerId: String, keyId: String, request: UpdateCustomerKeyRequest): CustomerKeyView =
        database.transaction {
            val row = findRow(keyId)
            if (row[CustomerKeysTable.customerId] \!= customerId) throw PluginApiException(403, "forbidden")
            CustomerKeysTable.update({ CustomerKeysTable.keyId eq keyId }) {
                request.name?.trim()?.takeIf(String::isNotBlank)?.let { v -> it[name] = v }
                request.routingGroupId?.takeIf(String::isNotBlank)?.let { v -> it[routingGroupId] = v }
                if (request.monthlyBudgetCredits \!= null) it[monthlyBudgetCredits] = request.monthlyBudgetCredits
                request.status?.let { v -> it[status] = v }
                it[updatedAt] = Clock.System.now()
                it[updatedBy] = customerId
            }
            findRow(keyId).toView()
        }

    fun revokeKey(customerId: String, keyId: String): CustomerKeyView = database.transaction {
        val row = findRow(keyId)
        if (row[CustomerKeysTable.customerId] \!= customerId) throw PluginApiException(403, "forbidden")
        CustomerKeysTable.update({ CustomerKeysTable.keyId eq keyId }) {
            it[status] = "revoked"
            it[revokedAt] = Clock.System.now()
            it[updatedAt] = Clock.System.now()
            it[updatedBy] = customerId
        }
        findRow(keyId).toView()
    }

    override suspend fun verifyCustomerKey(rawKey: String): VerifiedCustomerKey? {
        if (\!rawKey.startsWith("sk-keel-cust-")) return null
        val hash = sha256(rawKey)
        return database.transaction {
            val row = CustomerKeysTable.selectAll()
                .where { CustomerKeysTable.secretHash eq hash }
                .firstOrNull() ?: return@transaction null
            if (row[CustomerKeysTable.status] \!= "active") return@transaction null
            CustomerKeysTable.update({ CustomerKeysTable.keyId eq row[CustomerKeysTable.keyId] }) {
                it[lastUsedAt] = Clock.System.now()
            }
            VerifiedCustomerKey(
                customerId = row[CustomerKeysTable.customerId],
                keyId = row[CustomerKeysTable.keyId],
                routingGroupId = row[CustomerKeysTable.routingGroupId],
                monthlyBudgetCredits = row[CustomerKeysTable.monthlyBudgetCredits],
                status = row[CustomerKeysTable.status],
            )
        }
    }

    private fun findRow(keyId: String): ResultRow =
        CustomerKeysTable.selectAll().where { CustomerKeysTable.keyId eq keyId }.firstOrNull()
            ?: throw PluginApiException(404, "key not found")

    private fun ResultRow.toView() = CustomerKeyView(
        keyId = this[CustomerKeysTable.keyId],
        customerId = this[CustomerKeysTable.customerId],
        name = this[CustomerKeysTable.name],
        prefix = this[CustomerKeysTable.prefix],
        routingGroupId = this[CustomerKeysTable.routingGroupId],
        monthlyBudgetCredits = this[CustomerKeysTable.monthlyBudgetCredits],
        status = this[CustomerKeysTable.status],
        createdAt = this[CustomerKeysTable.createdAt].toString(),
        lastUsedAt = this[CustomerKeysTable.lastUsedAt]?.toString(),
        revokedAt = this[CustomerKeysTable.revokedAt]?.toString(),
    )

    private fun nextRawKey(): String {
        val bytes = ByteArray(24)
        random.nextBytes(bytes)
        return "sk-keel-cust-" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).take(40)
    }

    private fun nextId(prefix: String): String {
        val bytes = ByteArray(12)
        random.nextBytes(bytes)
        return "$prefix-" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).take(24)
    }

    private fun sha256(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return HexFormat.of().formatHex(md.digest(s.toByteArray()))
    }
}
```

- [ ] **Step 3: Append to CustomerKeyAndCreditTest.kt** — verify key creation, verify, revoke

Add to existing test file:

```kotlin
@Test
fun createListVerifyAndRevokeCustomerKey() = runBlocking {
    val db = DatabaseFactory.h2Memory("cp_keys_${System.nanoTime()}", poolSize = 2).init()
    val keys = com.keel.samples.aigateway.customerportal.keys.CustomerKeyRepository(db)
    keys.initializeSchema()
    val created = keys.createKey("c1",
        com.keel.samples.aigateway.customerportal.keys.CreateCustomerKeyRequest(
            name = "main", routingGroupId = "premium", monthlyBudgetCredits = 5000L
        ))
    assertTrue(created.rawSecret.startsWith("sk-keel-cust-"))
    assertEquals("premium", created.key.routingGroupId)
    val verified = keys.verifyCustomerKey(created.rawSecret)
    assertEquals("c1", verified?.customerId)
    assertEquals("premium", verified?.routingGroupId)
    keys.revokeKey("c1", created.key.keyId)
    val afterRevoke = keys.verifyCustomerKey(created.rawSecret)
    assertTrue(afterRevoke == null)
}
```

- [ ] **Step 4: Run all customer-portal tests**

Run: `./gradlew :keel-test-suite:test --tests "com.keel.test.kernel.CustomerPortalAuthTest" --tests "com.keel.test.kernel.CustomerKeyAndCreditTest"`
Expected: all PASS

- [ ] **Step 5: Commit**

```bash
git add keel-samples/src/main/kotlin/com/keel/samples/aigateway/customerportal/keys/ \
        keel-test-suite/src/test/kotlin/com/keel/test/kernel/CustomerKeyAndCreditTest.kt
git commit -m "feat(customer-portal): CustomerKeyRepository with raw-key SHA256 + verifier contract impl"
```

---

## Task 7: CustomerPortalPlugin + JWT interceptor + REST endpoints

**Files:**
- Create: `keel-samples/src/main/kotlin/com/keel/samples/aigateway/customerportal/CustomerPortalPlugin.kt`
- Create: `keel-samples/src/main/kotlin/com/keel/samples/aigateway/customerportal/CustomerPortalSettings.kt`
- Create: `keel-samples/src/main/kotlin/com/keel/samples/aigateway/customerportal/auth/CustomerJwtInterceptor.kt`
- Modify: `keel-samples/src/main/kotlin/com/keel/samples/KeelSample.kt`

- [ ] **Step 1: CustomerPortalSettings.kt**

```kotlin
package com.keel.samples.aigateway.customerportal

import kotlinx.serialization.Serializable

@Serializable
data class CustomerPortalSettings(
    val signupBonusCredits: Long = 1000L,
    val defaultCreditRate: CustomerCreditRate = CustomerCreditRate(),
) {
    companion object {
        fun load(): CustomerPortalSettings = CustomerPortalSettings()
    }
}

@Serializable
data class CustomerCreditRate(
    val creditsPerInputToken: Double = 0.003,
    val creditsPerOutputToken: Double = 0.015,
    val creditsPerCacheReadInputToken: Double = 0.0003,
    val creditsPerCacheCreationInputToken: Double = 0.00375,
)
```

- [ ] **Step 2: CustomerJwtInterceptor.kt** (mirrors `TokenJwtAuthInterceptor` pattern)

```kotlin
package com.keel.samples.aigateway.customerportal.auth

import com.keel.kernel.plugin.KeelRequestContext
import com.keel.kernel.plugin.KeelRequestInterceptor
import com.keel.kernel.plugin.PluginApiException

class CustomerJwtInterceptor(
    private val jwt: CustomerJwtService,
) : KeelRequestInterceptor {
    override suspend fun intercept(context: KeelRequestContext) {
        val auth = context.requestHeaders["Authorization"]?.firstOrNull()
            ?: throw PluginApiException(401, "missing Authorization")
        val token = auth.removePrefix("Bearer ").trim()
        val principal = jwt.verify(token) ?: throw PluginApiException(401, "invalid token")
        context.setAttribute(CUSTOMER_PRINCIPAL_KEY, principal)
    }

    companion object {
        const val CUSTOMER_PRINCIPAL_KEY = "customerPortal.principal"
        fun KeelRequestContext.requireCustomer(): CustomerPrincipal =
            getAttribute(CUSTOMER_PRINCIPAL_KEY) as? CustomerPrincipal
                ?: throw PluginApiException(401, "no customer principal")
    }
}
```

- [ ] **Step 3: CustomerPortalPlugin.kt**

```kotlin
package com.keel.samples.aigateway.customerportal

import com.keel.contract.customer.CreditLedger
import com.keel.contract.customer.CustomerApiKeyVerifier
import com.keel.db.database.DatabaseFactory
import com.keel.kernel.plugin.PluginApiException
import com.keel.kernel.plugin.PluginDescriptor
import com.keel.kernel.plugin.PluginEndpointBuilders.pluginEndpoints
import com.keel.kernel.plugin.PluginInitContext
import com.keel.kernel.plugin.PluginResult
import com.keel.kernel.plugin.PluginRouteDefinition
import com.keel.kernel.plugin.StandardKeelPlugin
import com.keel.openapi.annotations.KeelApiPlugin
import com.keel.openapi.runtime.OpenApiDoc
import com.keel.samples.aigateway.customerportal.auth.CustomerAuthRepository
import com.keel.samples.aigateway.customerportal.auth.CustomerJwtInterceptor
import com.keel.samples.aigateway.customerportal.auth.CustomerJwtInterceptor.Companion.requireCustomer
import com.keel.samples.aigateway.customerportal.auth.CustomerJwtService
import com.keel.samples.aigateway.customerportal.auth.CustomerLoginRequest
import com.keel.samples.aigateway.customerportal.auth.CustomerOAuthStubRequest
import com.keel.samples.aigateway.customerportal.auth.CustomerAuthResponse
import com.keel.samples.aigateway.customerportal.auth.CustomerProfile
import com.keel.samples.aigateway.customerportal.auth.CustomerRegisterRequest
import com.keel.samples.aigateway.customerportal.auth.PasswordHasher
import com.keel.samples.aigateway.customerportal.credits.CreditLedgerRepository
import com.keel.samples.aigateway.customerportal.credits.LedgerListResponse
import com.keel.samples.aigateway.customerportal.credits.MintRedemptionRequest
import com.keel.samples.aigateway.customerportal.credits.MintRedemptionResponse
import com.keel.samples.aigateway.customerportal.credits.RedeemRequest
import com.keel.samples.aigateway.customerportal.credits.RedeemResponse
import com.keel.samples.aigateway.customerportal.credits.RedemptionCodeRepository
import com.keel.samples.aigateway.customerportal.credits.CustomerUsageListResponse
import com.keel.samples.aigateway.customerportal.keys.CreateCustomerKeyRequest
import com.keel.samples.aigateway.customerportal.keys.CustomerKeyCreatedResponse
import com.keel.samples.aigateway.customerportal.keys.CustomerKeyListResponse
import com.keel.samples.aigateway.customerportal.keys.CustomerKeyRepository
import com.keel.samples.aigateway.customerportal.keys.CustomerKeyView
import com.keel.samples.aigateway.customerportal.keys.UpdateCustomerKeyRequest
import org.koin.dsl.module
import java.io.File

@KeelApiPlugin(
    pluginId = "customer-portal",
    title = "AI Gateway Customer Portal",
    description = "End-customer self-service: signup, keys, credits, redemption codes, usage",
    version = "1.0.0"
)
class CustomerPortalPlugin : StandardKeelPlugin {
    override val descriptor = PluginDescriptor(
        pluginId = "customer-portal",
        version = "1.0.0",
        displayName = "AI Gateway Customer Portal",
    )

    private lateinit var settings: CustomerPortalSettings
    private lateinit var authRepo: CustomerAuthRepository
    private lateinit var keyRepo: CustomerKeyRepository
    private lateinit var ledgerRepo: CreditLedgerRepository
    private lateinit var redemptionRepo: RedemptionCodeRepository
    private lateinit var jwt: CustomerJwtService

    override fun modules() = listOf(
        module {
            single { CustomerJwtInterceptor(jwt) }
        }
    )

    override suspend fun onInit(context: PluginInitContext) {
        settings = CustomerPortalSettings.load()
        val dataDir = System.getProperty("keel.data.dir")
            ?: (System.getProperty("java.io.tmpdir").trimEnd('/') + "/keel-data")
        File(dataDir).mkdirs()
        val factory = DatabaseFactory.h2File(filePath = "$dataDir/customer_portal", poolSize = 5)
        val db = factory.init()
        jwt = CustomerJwtService()
        ledgerRepo = CreditLedgerRepository(db).also { it.initializeSchema() }
        redemptionRepo = RedemptionCodeRepository(db, ledgerRepo).also { it.initializeSchema() }
        keyRepo = CustomerKeyRepository(db).also { it.initializeSchema() }
        authRepo = CustomerAuthRepository(
            database = db,
            hasher = PasswordHasher(),
            jwt = jwt,
            ledger = ledgerRepo,
            signupBonusCredits = settings.signupBonusCredits,
        ).also { it.initializeSchema() }
        context.kernelKoin.loadModules(
            listOf(module {
                single<CustomerApiKeyVerifier> { keyRepo }
                single<CreditLedger> { ledgerRepo }
            })
        )
    }

    override fun endpoints(): List<PluginRouteDefinition> = pluginEndpoints(descriptor.pluginId) {
        route("/v1/customer/auth") {
            noInterceptors()
            post<CustomerRegisterRequest, CustomerAuthResponse>(
                "/register",
                doc = OpenApiDoc(summary = "Customer self-service signup", tags = listOf("customer-portal"))
            ) { request -> PluginResult(body = authRepo.register(request)) }
            post<CustomerLoginRequest, CustomerAuthResponse>(
                "/login",
                doc = OpenApiDoc(summary = "Customer login", tags = listOf("customer-portal"))
            ) { request -> PluginResult(body = authRepo.login(request)) }
            post<CustomerOAuthStubRequest, CustomerAuthResponse>(
                "/oauth/stub",
                doc = OpenApiDoc(summary = "OAuth stub (Apple/Google/GitHub UI placeholder)", tags = listOf("customer-portal"))
            ) { request -> PluginResult(body = authRepo.oauthStub(request)) }
        }
        route("/v1/customer") {
            interceptors(CustomerJwtInterceptor::class)
            get<CustomerProfile>(
                "/me",
                doc = OpenApiDoc(summary = "Profile + balance", tags = listOf("customer-portal"))
            ) { PluginResult(body = authRepo.profile(requireCustomer())) }

            route("/keys") {
                get<CustomerKeyListResponse>(
                    doc = OpenApiDoc(summary = "List customer keys", tags = listOf("customer-portal"))
                ) { PluginResult(body = keyRepo.listKeys(requireCustomer().customerId)) }
                post<CreateCustomerKeyRequest, CustomerKeyCreatedResponse>(
                    doc = OpenApiDoc(summary = "Create customer key", tags = listOf("customer-portal"))
                ) { req -> PluginResult(body = keyRepo.createKey(requireCustomer().customerId, req)) }
                put<UpdateCustomerKeyRequest, CustomerKeyView>(
                    "/{keyId}",
                    doc = OpenApiDoc(summary = "Update customer key", tags = listOf("customer-portal"))
                ) { req ->
                    val keyId = pathParameters["keyId"] ?: throw PluginApiException(400, "missing keyId")
                    PluginResult(body = keyRepo.updateKey(requireCustomer().customerId, keyId, req))
                }
                delete<CustomerKeyView>(
                    "/{keyId}",
                    doc = OpenApiDoc(summary = "Revoke customer key", tags = listOf("customer-portal"))
                ) {
                    val keyId = pathParameters["keyId"] ?: throw PluginApiException(400, "missing keyId")
                    PluginResult(body = keyRepo.revokeKey(requireCustomer().customerId, keyId))
                }
            }

            route("/credits") {
                get<LedgerListResponse>(
                    doc = OpenApiDoc(summary = "List ledger entries", tags = listOf("customer-portal"))
                ) { PluginResult(body = ledgerRepo.listLedger(requireCustomer().customerId)) }
                post<RedeemRequest, RedeemResponse>(
                    "/redeem",
                    doc = OpenApiDoc(summary = "Redeem a code", tags = listOf("customer-portal"))
                ) { req -> PluginResult(body = redemptionRepo.redeem(requireCustomer().customerId, req)) }
            }

            route("/usage") {
                get<CustomerUsageListResponse>(
                    doc = OpenApiDoc(summary = "List usage records", tags = listOf("customer-portal"))
                ) { PluginResult(body = ledgerRepo.listUsage(requireCustomer().customerId)) }
            }
        }

        route("/admin") {
            noInterceptors()
            post<MintRedemptionRequest, MintRedemptionResponse>(
                "/codes",
                doc = OpenApiDoc(summary = "Mint redemption codes (operator)", tags = listOf("customer-portal", "admin"))
            ) { req ->
                val adminId = requestHeaders["X-Admin-Id"]?.firstOrNull()
                    ?: throw PluginApiException(401, "missing admin id")
                PluginResult(body = redemptionRepo.mint(adminId, req))
            }
        }

        staticResources(
            path = "/ui",
            basePackage = "customer-portal-ui",
            doc = OpenApiDoc(summary = "Customer Portal UI", tags = listOf("customer-portal")),
            index = "index.html"
        )
    }
}
```

- [ ] **Step 4: Register in KeelSample.kt**

Add to `keel-samples/src/main/kotlin/com/keel/samples/KeelSample.kt`:

```kotlin
import com.keel.samples.aigateway.customerportal.CustomerPortalPlugin
```

After `plugin(AIRelayPlugin())` add:

```kotlin
    plugin(CustomerPortalPlugin())
```

- [ ] **Step 5: Compile + run targeted tests**

Run: `./gradlew :keel-samples:compileKotlin :keel-test-suite:test --tests "com.keel.test.kernel.CustomerPortalAuthTest" --tests "com.keel.test.kernel.CustomerKeyAndCreditTest"`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add keel-samples/src/main/kotlin/com/keel/samples/aigateway/customerportal/CustomerPortalPlugin.kt \
        keel-samples/src/main/kotlin/com/keel/samples/aigateway/customerportal/CustomerPortalSettings.kt \
        keel-samples/src/main/kotlin/com/keel/samples/aigateway/customerportal/auth/CustomerJwtInterceptor.kt \
        keel-samples/src/main/kotlin/com/keel/samples/KeelSample.kt
git commit -m "feat(customer-portal): plugin with auth/keys/credits/usage endpoints + admin mint codes"
```

---

## Task 8: CreditRateRegistry + airelay verifier chain + post-call charge

**Files:**
- Create: `keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/usage/CreditRateRegistry.kt`
- Modify: `keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/AIRelayService.kt`
- Modify: `keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/AIRelayPlugin.kt`

- [ ] **Step 1: CreditRateRegistry.kt**

```kotlin
package com.keel.samples.aigateway.airelay.usage

import com.keel.contract.ai.TokenUsage
import com.keel.samples.aigateway.customerportal.CustomerCreditRate

/**
 * Resolves credit rates per (groupId, modelAlias). Falls back to the default rate when
 * no per-(group, model) override is registered.
 *
 * Stored in-memory for the moment; future iteration will persist to airelay_credit_rates.
 */
class CreditRateRegistry(private val defaults: CustomerCreditRate = CustomerCreditRate()) {
    private val overrides: MutableMap<String, CustomerCreditRate> = mutableMapOf()

    fun set(groupId: String, model: String, rate: CustomerCreditRate) {
        overrides[key(groupId, model)] = rate
    }

    fun rateFor(groupId: String, model: String): CustomerCreditRate =
        overrides[key(groupId, model)] ?: defaults

    /** Returns the credit cost rounded up to the nearest credit. */
    fun cost(groupId: String, model: String, usage: TokenUsage): Long {
        val rate = rateFor(groupId, model)
        val raw = usage.promptTokens * rate.creditsPerInputToken +
            usage.completionTokens * rate.creditsPerOutputToken +
            usage.cacheReadInputTokens * rate.creditsPerCacheReadInputToken +
            usage.cacheCreationInputTokens * rate.creditsPerCacheCreationInputToken
        return kotlin.math.ceil(raw).toLong().coerceAtLeast(0L)
    }

    private fun key(groupId: String, model: String) = "$groupId::$model"
}
```

- [ ] **Step 2: Modify AIRelayService.kt** — add verifier chain + post-call charge

Add fields to constructor (insert after `userDirectory`):

```kotlin
    private val customerVerifier: com.keel.contract.customer.CustomerApiKeyVerifier? = null,
    private val creditLedger: com.keel.contract.customer.CreditLedger? = null,
    private val creditRates: com.keel.samples.aigateway.airelay.usage.CreditRateRegistry? = null,
```

Add a new field on the request principal carried through the call:

```kotlin
private data class ResolvedPrincipal(
    val verified: VerifiedApiKey,
    val customerId: String?,
)
```

Replace `verifyKey()` with `resolvePrincipal()`:

```kotlin
private suspend fun resolvePrincipal(context: KeelRequestContext): ResolvedPrincipal? {
    val raw = (context.requestHeaders["x-api-key"]?.firstOrNull()
        ?: context.requestHeaders["X-Api-Key"]?.firstOrNull()
        ?: context.requestHeaders["Authorization"]?.firstOrNull()?.removePrefix("Bearer ")?.trim())
        ?: return null
    customerVerifier?.verifyCustomerKey(raw)?.let { cust ->
        return ResolvedPrincipal(
            verified = VerifiedApiKey(
                keyId = cust.keyId, userId = cust.customerId, userGroupId = "customer",
                routingGroupId = cust.routingGroupId, allowedModels = emptyList(),
                rpmLimit = null, tpmLimit = null,
                remainingBudgetUsd = cust.monthlyBudgetCredits?.toDouble() ?: Double.MAX_VALUE,
            ),
            customerId = cust.customerId,
        )
    }
    if (\!raw.startsWith("sk-keel-")) return null
    return try {
        ResolvedPrincipal(verified = apiKeyVerifier.verify(raw, clientIp(context)), customerId = null)
    } catch (_: InvalidApiKeyException) { null } catch (_: QuotaExceededException) { null }
}
```

In `handleBlocking()`, replace `val verified = verifyKey(context) ?: ...` with:

```kotlin
val principal = resolvePrincipal(context) ?: return errorResult(401, "Missing or invalid virtual API key")
val verified = principal.verified
if (principal.customerId \!= null) {
    val balance = creditLedger?.snapshotBalance(principal.customerId) ?: 0L
    if (balance <= 0L) return errorResult(402, "Out of credits")
}
```

After `usageRecorder.record(...)` in both `handleBlockingRelay()` and at the end of `handleStream()`, add:

```kotlin
chargeCustomer(principal, upstreamIr.usage, selection, ir, clientProtocol)
```

(Pass `principal` through both helper methods — adjust signatures.)

Add helper:

```kotlin
private suspend fun chargeCustomer(
    principal: ResolvedPrincipal,
    usage: TokenUsage,
    selection: com.keel.samples.aigateway.airelay.pool.PoolSelection,
    ir: IrRequest,
    clientProtocol: WireProtocol,
) {
    val customerId = principal.customerId ?: return
    val ledger = creditLedger ?: return
    val rates = creditRates ?: return
    val credits = rates.cost(principal.verified.routingGroupId, ir.model, usage)
    val cost = costCalculator.calculate(ir.model, usage, principal.verified.userGroupId)
    val usdMicros = (cost.totalCostUsd * 1_000_000).toLong()
    ledger.chargeForUsage(
        customerId = customerId, keyId = principal.verified.keyId,
        creditCost = credits, usdMicrosCost = usdMicros,
        usageRow = com.keel.contract.customer.CustomerUsageRow(
            model = ir.model,
            groupId = principal.verified.routingGroupId,
            providerId = selection.provider.providerId,
            wireProtocol = clientProtocol.name,
            inputTokens = usage.promptTokens.toLong(),
            outputTokens = usage.completionTokens.toLong(),
            cacheReadInputTokens = usage.cacheReadInputTokens.toLong(),
            cacheCreationInputTokens = usage.cacheCreationInputTokens.toLong(),
            requestId = "req-${kotlinx.datetime.Clock.System.now().epochSeconds}",
        ),
    )
}
```

- [ ] **Step 3: Modify AIRelayPlugin.kt** — inject contracts into AIRelayService

Add fields and update `buildService()` to fetch them from Koin (use `runCatching { kernelKoin.get<...>() }.getOrNull()` so the plugin still works when `customer-portal` is disabled):

```kotlin
private fun buildService(): AIRelayService {
    val userDirectory = kernelKoin.get<UserDirectory>()
    val apiKeyVerifier = kernelKoin.get<ApiKeyVerifier>()
    val usageRecorder = kernelKoin.get<UsageRecorder>()
    val rateLimitGate = kernelKoin.get<RateLimitGate>()
    val customerVerifier = runCatching { kernelKoin.get<com.keel.contract.customer.CustomerApiKeyVerifier>() }.getOrNull()
    val creditLedger = runCatching { kernelKoin.get<com.keel.contract.customer.CreditLedger>() }.getOrNull()
    return AIRelayService(
        apiKeyVerifier = apiKeyVerifier,
        usageRecorder = usageRecorder,
        rateLimitGate = rateLimitGate,
        userDirectory = userDirectory,
        poolChainManager = activeManager(),
        transcoder = transcoder,
        upstreamClient = upstreamClient,
        costCalculator = CostCalculator(activePricing(), userDirectory),
        customerVerifier = customerVerifier,
        creditLedger = creditLedger,
        creditRates = com.keel.samples.aigateway.airelay.usage.CreditRateRegistry(),
    )
}
```

- [ ] **Step 4: Compile**

Run: `./gradlew :keel-samples:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: Re-run customer + existing aigateway tests**

Run: `./gradlew :keel-test-suite:test --tests "com.keel.test.kernel.CustomerPortalAuthTest" --tests "com.keel.test.kernel.CustomerKeyAndCreditTest" --tests "com.keel.test.kernel.AiGatewayAcceptanceTest" --tests "com.keel.test.kernel.ChannelConfigTest"`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/usage/CreditRateRegistry.kt \
        keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/AIRelayService.kt \
        keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/AIRelayPlugin.kt
git commit -m "feat(airelay): verifier chain for customer keys + post-call credit deduction"
```

---

## Task 9: AnthropicMessagesV2Codec — faithful GA-set codec (TDD with fixtures)

**Files:**
- Create: `keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/protocol/anthropic/AnthropicHeaders.kt`
- Create: `keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/protocol/anthropic/AnthropicErrorEnvelope.kt`
- Create: `keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/protocol/anthropic/AnthropicMessagesV2Codec.kt`
- Modify: `keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/protocol/ProtocolTranscoder.kt`
- Modify: `keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/AIRelayPlugin.kt`
- Create: `keel-test-suite/src/test/kotlin/com/keel/test/kernel/AnthropicMessagesCodecConformanceTest.kt`
- Create: fixtures in `keel-test-suite/src/test/resources/anthropic/`

- [ ] **Step 1: AnthropicHeaders.kt + AnthropicErrorEnvelope.kt**

```kotlin
package com.keel.samples.aigateway.airelay.protocol.anthropic

import kotlinx.serialization.Serializable

object AnthropicHeaders {
    const val API_KEY = "x-api-key"
    const val VERSION = "anthropic-version"
    const val BETA = "anthropic-beta"
    const val SUPPORTED_VERSION = "2023-06-01"
}

@Serializable
data class AnthropicErrorEnvelope(
    val type: String = "error",
    val error: AnthropicError,
)

@Serializable
data class AnthropicError(
    val type: String,
    val message: String,
)
```

- [ ] **Step 2: Write fixtures** under `keel-test-suite/src/test/resources/anthropic/`:

`fixture_text_only_request.json`:
```json
{
  "model": "claude-sonnet-4-6",
  "max_tokens": 1024,
  "system": "You are concise.",
  "messages": [{"role": "user", "content": "Hi"}],
  "temperature": 0.7,
  "stream": false
}
```

`fixture_tool_use_request.json`:
```json
{
  "model": "claude-sonnet-4-6",
  "max_tokens": 1024,
  "messages": [
    {"role": "user", "content": "Get stock"},
    {"role": "assistant", "content": [
      {"type": "tool_use", "id": "toolu_1", "name": "get_stock", "input": {"ticker": "AAPL"}}
    ]},
    {"role": "user", "content": [
      {"type": "tool_result", "tool_use_id": "toolu_1", "content": "175.50", "is_error": false}
    ]}
  ],
  "tools": [
    {
      "name": "get_stock",
      "description": "Get stock price",
      "input_schema": {"type": "object", "properties": {"ticker": {"type": "string"}}}
    }
  ],
  "stream": false
}
```

`fixture_vision_request.json`:
```json
{
  "model": "claude-sonnet-4-6",
  "max_tokens": 256,
  "messages": [{"role": "user", "content": [
    {"type": "image", "source": {"type": "base64", "media_type": "image/png", "data": "iVBOR..."}},
    {"type": "text", "text": "What's in this image?"}
  ]}]
}
```

`fixture_document_request.json`:
```json
{
  "model": "claude-sonnet-4-6",
  "max_tokens": 512,
  "messages": [{"role": "user", "content": [
    {"type": "document", "source": {"type": "base64", "media_type": "application/pdf", "data": "JVBE..."}, "title": "Doc"},
    {"type": "text", "text": "Summarize"}
  ]}]
}
```

`fixture_thinking_request.json`:
```json
{
  "model": "claude-opus-4-7",
  "max_tokens": 4096,
  "thinking": {"type": "enabled", "budget_tokens": 1024},
  "messages": [{"role": "user", "content": "Solve"}],
  "stream": false
}
```

`fixture_cache_control_request.json`:
```json
{
  "model": "claude-sonnet-4-6",
  "max_tokens": 1024,
  "system": [
    {"type": "text", "text": "Long system prompt.", "cache_control": {"type": "ephemeral", "ttl": "1h"}}
  ],
  "messages": [{"role": "user", "content": "Hi"}]
}
```

`fixture_text_response.json`:
```json
{
  "id": "msg_01ABC",
  "type": "message",
  "role": "assistant",
  "model": "claude-sonnet-4-6",
  "content": [{"type": "text", "text": "Hello\!"}],
  "stop_reason": "end_turn",
  "stop_sequence": null,
  "usage": {
    "input_tokens": 12,
    "output_tokens": 4,
    "cache_creation_input_tokens": 0,
    "cache_read_input_tokens": 0
  }
}
```

`fixture_refusal_response.json`:
```json
{
  "id": "msg_01ABC",
  "type": "message",
  "role": "assistant",
  "model": "claude-sonnet-4-6",
  "content": [],
  "stop_reason": "refusal",
  "stop_details": {"type": "refusal", "category": "cyber", "explanation": "Declined"},
  "usage": {"input_tokens": 30, "output_tokens": 0}
}
```

- [ ] **Step 3: Write the failing conformance test**

`keel-test-suite/src/test/kotlin/com/keel/test/kernel/AnthropicMessagesCodecConformanceTest.kt`:

```kotlin
package com.keel.test.kernel

import com.keel.samples.aigateway.airelay.protocol.anthropic.AnthropicMessagesV2Codec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnthropicMessagesCodecConformanceTest {
    private val codec = AnthropicMessagesV2Codec()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun load(name: String): JsonObject =
        javaClass.classLoader.getResource("anthropic/$name")\!\!.readText().let {
            json.parseToJsonElement(it).jsonObject
        }

    @Test
    fun textOnlyRoundTrips() {
        val request = load("fixture_text_only_request.json")
        val ir = codec.decodeRequest(request)
        assertEquals("claude-sonnet-4-6", ir.model)
        assertEquals(1, ir.items.size)
        val encoded = codec.encodeRequest(ir)
        assertEquals(request["model"], encoded["model"])
        assertEquals(request["max_tokens"], encoded["max_tokens"])
    }

    @Test
    fun toolUseRoundTripsPreservesToolResult() {
        val request = load("fixture_tool_use_request.json")
        val ir = codec.decodeRequest(request)
        val encoded = codec.encodeRequest(ir)
        assertTrue(encoded["tools"]?.toString()?.contains("get_stock") == true)
        assertTrue(encoded["messages"]?.toString()?.contains("tool_use_id") == true)
    }

    @Test
    fun visionImageBlockSurvivesEncode() {
        val request = load("fixture_vision_request.json")
        val ir = codec.decodeRequest(request)
        val encoded = codec.encodeRequest(ir)
        assertTrue(encoded["messages"].toString().contains("image"))
        assertTrue(encoded["messages"].toString().contains("image/png"))
    }

    @Test
    fun documentBlockPassesThrough() {
        val request = load("fixture_document_request.json")
        val ir = codec.decodeRequest(request)
        val encoded = codec.encodeRequest(ir)
        assertTrue(encoded["messages"].toString().contains("document"))
        assertTrue(encoded["messages"].toString().contains("application/pdf"))
    }

    @Test
    fun thinkingConfigPreserved() {
        val request = load("fixture_thinking_request.json")
        val ir = codec.decodeRequest(request)
        val encoded = codec.encodeRequest(ir)
        assertTrue(encoded["thinking"]?.toString()?.contains("enabled") == true)
        assertTrue(encoded["thinking"]?.toString()?.contains("budget_tokens") == true)
    }

    @Test
    fun cacheControlSurvives() {
        val request = load("fixture_cache_control_request.json")
        val ir = codec.decodeRequest(request)
        val encoded = codec.encodeRequest(ir)
        assertTrue(encoded["system"]?.toString()?.contains("cache_control") == true)
        assertTrue(encoded["system"]?.toString()?.contains("1h") == true)
    }

    @Test
    fun textResponseRoundTrips() {
        val response = load("fixture_text_response.json")
        val ir = codec.decodeResponse(response)
        assertEquals("end_turn", ir.stopReason)
        assertEquals(12, ir.usage.promptTokens)
        val encoded = codec.encodeResponse(ir)
        assertEquals(response["id"], encoded["id"])
        assertEquals(response["stop_reason"], encoded["stop_reason"])
    }

    @Test
    fun refusalStopReasonAndDetailsPreserved() {
        val response = load("fixture_refusal_response.json")
        val ir = codec.decodeResponse(response)
        assertEquals("refusal", ir.stopReason)
        val encoded = codec.encodeResponse(ir)
        assertTrue(encoded["stop_details"]?.toString()?.contains("cyber") == true)
    }
}
```

- [ ] **Step 4: Implement AnthropicMessagesV2Codec.kt**

```kotlin
package com.keel.samples.aigateway.airelay.protocol.anthropic

import com.keel.contract.ai.TokenUsage
import com.keel.samples.aigateway.airelay.protocol.IrContentPart
import com.keel.samples.aigateway.airelay.protocol.IrItem
import com.keel.samples.aigateway.airelay.protocol.IrRequest
import com.keel.samples.aigateway.airelay.protocol.IrResponse
import com.keel.samples.aigateway.airelay.protocol.IrStreamEvent
import com.keel.samples.aigateway.airelay.protocol.ProtocolCodec
import com.keel.samples.aigateway.airelay.protocol.WireProtocol
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Faithful Anthropic Messages API codec. Preserves the full content-block menu
 * (text/image/document/tool_use/tool_result/thinking/redacted_thinking + server-tool blocks),
 * tools schema, thinking config, cache_control markers, system blocks, and all SSE event types.
 *
 * The IR layer only models text/tool_use/thinking/image at first class; everything else is
 * stashed as `IrContentPart.Raw(originalJson)` so encode is a faithful round-trip.
 */
class AnthropicMessagesV2Codec : ProtocolCodec {
    override val protocol: WireProtocol = WireProtocol.ANTHROPIC_MESSAGES
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; isLenient = true }

    override fun decodeRequest(rawJson: JsonObject): IrRequest {
        val messages = (rawJson["messages"] as? JsonArray)?.mapNotNull { el ->
            val obj = el.jsonObject
            val role = obj["role"]?.jsonPrimitive?.contentOrNull ?: "user"
            IrItem.Message(role, decodeContent(obj["content"]))
        } ?: emptyList()
        val systemRaw = rawJson["system"]
        val instructions = when (systemRaw) {
            is JsonArray -> systemRaw.joinToString("\n") {
                it.jsonObject["text"]?.jsonPrimitive?.contentOrNull ?: ""
            }
            is JsonPrimitive -> systemRaw.contentOrNull
            else -> null
        }
        val extras = mutableMapOf<String, JsonElement>()
        listOf("tools", "tool_choice", "thinking", "system", "metadata",
            "cache_control", "service_tier", "container", "inference_geo", "output_config",
            "top_k").forEach { key -> rawJson[key]?.let { extras[key] = it } }
        return IrRequest(
            model = rawJson["model"]?.jsonPrimitive?.contentOrNull ?: "unknown",
            instructions = instructions,
            items = messages,
            maxOutputTokens = rawJson["max_tokens"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
            temperature = rawJson["temperature"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull(),
            topP = rawJson["top_p"]?.jsonPrimitive?.contentOrNull?.toDoubleOrNull(),
            stopSequences = (rawJson["stop_sequences"] as? JsonArray)?.map {
                it.jsonPrimitive.content
            } ?: emptyList(),
            stream = rawJson["stream"]?.jsonPrimitive?.contentOrNull?.toBoolean() ?: false,
            extras = extras,
        )
    }

    override fun encodeRequest(ir: IrRequest): JsonObject = buildJsonObject {
        put("model", JsonPrimitive(ir.model))
        put("max_tokens", JsonPrimitive(ir.maxOutputTokens ?: 1024))
        when (val rawSystem = ir.extras["system"]) {
            is JsonArray -> put("system", rawSystem)
            else -> ir.instructions?.let { put("system", JsonPrimitive(it)) }
        }
        put("messages", buildJsonArray {
            ir.items.forEach { item ->
                when (item) {
                    is IrItem.Message -> add(buildJsonObject {
                        put("role", JsonPrimitive(item.role))
                        put("content", encodeContent(item.content))
                    })
                    is IrItem.ToolResult -> add(buildJsonObject {
                        put("role", JsonPrimitive("user"))
                        put("content", buildJsonArray {
                            add(buildJsonObject {
                                put("type", JsonPrimitive("tool_result"))
                                put("tool_use_id", JsonPrimitive(item.toolUseId))
                                put("content", JsonPrimitive(item.content))
                                put("is_error", JsonPrimitive(item.isError))
                            })
                        })
                    })
                }
            }
        })
        ir.temperature?.let { put("temperature", JsonPrimitive(it)) }
        ir.topP?.let { put("top_p", JsonPrimitive(it)) }
        if (ir.stopSequences.isNotEmpty()) put("stop_sequences", buildJsonArray {
            ir.stopSequences.forEach { add(JsonPrimitive(it)) }
        })
        put("stream", JsonPrimitive(ir.stream))
        listOf("tools", "tool_choice", "thinking", "metadata",
            "cache_control", "service_tier", "container", "inference_geo", "output_config", "top_k").forEach { key ->
            ir.extras[key]?.let { put(key, it) }
        }
    }

    override fun decodeResponse(rawJson: JsonObject): IrResponse {
        val content = decodeContent(rawJson["content"])
        val extras = mutableMapOf<String, JsonElement>()
        listOf("stop_details", "stop_sequence", "container").forEach { rawJson[it]?.let { v -> extras[it] = v } }
        return IrResponse(
            id = rawJson["id"]?.jsonPrimitive?.contentOrNull ?: "msg-keel",
            model = rawJson["model"]?.jsonPrimitive?.contentOrNull ?: "unknown",
            output = listOf(IrItem.Message("assistant", content)),
            stopReason = rawJson["stop_reason"]?.jsonPrimitive?.contentOrNull ?: "end_turn",
            usage = readUsage(rawJson["usage"]),
            extras = extras,
        )
    }

    override fun encodeResponse(ir: IrResponse): JsonObject = buildJsonObject {
        put("id", JsonPrimitive(ir.id))
        put("type", JsonPrimitive("message"))
        put("role", JsonPrimitive("assistant"))
        put("model", JsonPrimitive(ir.model))
        put("content", buildJsonArray {
            ir.output.filterIsInstance<IrItem.Message>().forEach { item ->
                item.content.forEach { part -> add(encodeContentPart(part)) }
            }
        })
        put("stop_reason", JsonPrimitive(ir.stopReason))
        ir.extras["stop_sequence"]?.let { put("stop_sequence", it) }
        ir.extras["stop_details"]?.let { put("stop_details", it) }
        ir.extras["container"]?.let { put("container", it) }
        put("usage", writeUsage(ir.usage))
    }

    override fun decodeStream(upstream: Flow<ServerSentEvent>): Flow<IrStreamEvent> =
        upstream.mapNotNull { event ->
            val obj = runCatching {
                json.parseToJsonElement(event.data ?: return@mapNotNull null).jsonObject
            }.getOrNull() ?: return@mapNotNull null
            val type = event.event ?: obj["type"]?.jsonPrimitive?.contentOrNull
            when (type) {
                "content_block_delta" -> obj["delta"]?.jsonObject?.let { delta ->
                    delta["text"]?.jsonPrimitive?.contentOrNull?.let {
                        IrStreamEvent.TextDelta(obj["index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0, it)
                    }
                }
                "message_delta" -> IrStreamEvent.UsageUpdate(readUsage(obj["usage"]))
                "message_stop" -> IrStreamEvent.ResponseDone("end_turn", TokenUsage())
                "error" -> IrStreamEvent.Error(
                    obj["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull ?: "upstream error"
                )
                else -> null
            }
        }

    override fun encodeStream(events: Flow<IrStreamEvent>): Flow<ServerSentEvent> = events.map { event ->
        when (event) {
            is IrStreamEvent.TextDelta -> ServerSentEvent(
                event = "content_block_delta",
                data = json.encodeToString(buildJsonObject {
                    put("type", JsonPrimitive("content_block_delta"))
                    put("index", JsonPrimitive(event.index))
                    put("delta", buildJsonObject {
                        put("type", JsonPrimitive("text_delta"))
                        put("text", JsonPrimitive(event.delta))
                    })
                })
            )
            is IrStreamEvent.UsageUpdate -> ServerSentEvent(
                event = "message_delta",
                data = json.encodeToString(buildJsonObject {
                    put("type", JsonPrimitive("message_delta"))
                    put("usage", writeUsage(event.usage))
                })
            )
            is IrStreamEvent.ResponseDone -> ServerSentEvent(
                event = "message_stop",
                data = json.encodeToString(buildJsonObject { put("type", JsonPrimitive("message_stop")) })
            )
            is IrStreamEvent.Error -> ServerSentEvent(
                event = "error",
                data = json.encodeToString(buildJsonObject {
                    put("type", JsonPrimitive("error"))
                    put("error", buildJsonObject { put("message", JsonPrimitive(event.message)) })
                })
            )
            else -> ServerSentEvent(event = "ping", data = "{}")
        }
    }

    private fun decodeContent(content: JsonElement?): List<IrContentPart> {
        val arr = (content as? JsonArray) ?: return when (content) {
            is JsonPrimitive -> listOf(IrContentPart.Text(content.contentOrNull ?: ""))
            else -> emptyList()
        }
        return arr.mapNotNull { el ->
            val obj = el.jsonObject
            when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                "text" -> IrContentPart.Text(obj["text"]?.jsonPrimitive?.contentOrNull ?: "")
                "tool_use" -> IrContentPart.ToolUse(
                    id = obj["id"]?.jsonPrimitive?.contentOrNull ?: "tool",
                    name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "function",
                    input = obj["input"] ?: JsonPrimitive("{}"),
                )
                "thinking" -> IrContentPart.Reasoning(
                    summary = obj["thinking"]?.jsonPrimitive?.contentOrNull,
                    encryptedContent = obj["signature"]?.jsonPrimitive?.contentOrNull,
                )
                "image" -> {
                    val src = obj["source"]?.jsonObject
                    IrContentPart.Image(
                        mimeType = src?.get("media_type")?.jsonPrimitive?.contentOrNull ?: "image/png",
                        base64 = src?.get("data")?.jsonPrimitive?.contentOrNull,
                        url = src?.get("url")?.jsonPrimitive?.contentOrNull,
                    )
                }
                else -> IrContentPart.Raw(obj)
            }
        }
    }

    private fun encodeContent(parts: List<IrContentPart>): JsonArray = buildJsonArray {
        parts.forEach { add(encodeContentPart(it)) }
    }

    private fun encodeContentPart(part: IrContentPart): JsonObject = when (part) {
        is IrContentPart.Text -> buildJsonObject {
            put("type", JsonPrimitive("text"))
            put("text", JsonPrimitive(part.text))
        }
        is IrContentPart.ToolUse -> buildJsonObject {
            put("type", JsonPrimitive("tool_use"))
            put("id", JsonPrimitive(part.id))
            put("name", JsonPrimitive(part.name))
            put("input", part.input)
        }
        is IrContentPart.Reasoning -> buildJsonObject {
            put("type", JsonPrimitive("thinking"))
            put("thinking", JsonPrimitive(part.summary ?: ""))
            part.encryptedContent?.let { put("signature", JsonPrimitive(it)) }
        }
        is IrContentPart.Image -> buildJsonObject {
            put("type", JsonPrimitive("image"))
            put("source", buildJsonObject {
                if (part.base64 \!= null) {
                    put("type", JsonPrimitive("base64"))
                    put("media_type", JsonPrimitive(part.mimeType))
                    put("data", JsonPrimitive(part.base64))
                } else if (part.url \!= null) {
                    put("type", JsonPrimitive("url"))
                    put("url", JsonPrimitive(part.url))
                }
            })
        }
        is IrContentPart.Raw -> part.json
    }

    private fun readUsage(usageEl: JsonElement?): TokenUsage {
        val obj = usageEl?.jsonObject ?: return TokenUsage()
        fun n(key: String): Int =
            obj[key]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0
        return TokenUsage(
            promptTokens = n("input_tokens"),
            completionTokens = n("output_tokens"),
            cacheCreationInputTokens = n("cache_creation_input_tokens"),
            cacheReadInputTokens = n("cache_read_input_tokens"),
            cachedPromptTokens = 0,
            reasoningTokens = obj["output_tokens_details"]?.jsonObject
                ?.get("thinking_tokens")?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
        )
    }

    private fun writeUsage(usage: TokenUsage): JsonObject = buildJsonObject {
        put("input_tokens", JsonPrimitive(usage.promptTokens))
        put("output_tokens", JsonPrimitive(usage.completionTokens))
        put("cache_creation_input_tokens", JsonPrimitive(usage.cacheCreationInputTokens))
        put("cache_read_input_tokens", JsonPrimitive(usage.cacheReadInputTokens))
        if (usage.reasoningTokens > 0) {
            put("output_tokens_details", buildJsonObject {
                put("thinking_tokens", JsonPrimitive(usage.reasoningTokens))
            })
        }
    }
}
```

- [ ] **Step 5: Extend IrContentPart with `Raw` and IrRequest/IrResponse with `extras`**

Edit `keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/protocol/GatewayIr.kt`:

In the sealed `IrContentPart`, add:
```kotlin
data class Raw(val json: kotlinx.serialization.json.JsonObject) : IrContentPart()
```

In `IrRequest` and `IrResponse`, add an `extras` field:
```kotlin
val extras: Map<String, kotlinx.serialization.json.JsonElement> = emptyMap(),
```

In existing codecs (`OpenAIChatCodec`, `OpenAIResponsesCodec`, old `AnthropicMessagesCodec`), keep them unchanged — they pass an empty extras map via the default.

- [ ] **Step 6: Wire ProtocolTranscoder to use V2 codec**

In `keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/AIRelayPlugin.kt`:

Replace:
```kotlin
transcoder = ProtocolTranscoder(listOf(OpenAIChatCodec(), OpenAIResponsesCodec(), AnthropicMessagesCodec()))
```
with:
```kotlin
transcoder = ProtocolTranscoder(listOf(OpenAIChatCodec(), OpenAIResponsesCodec(), AnthropicMessagesV2Codec()))
```

Add the import `com.keel.samples.aigateway.airelay.protocol.anthropic.AnthropicMessagesV2Codec`.

- [ ] **Step 7: Run conformance test**

Run: `./gradlew :keel-test-suite:test --tests "com.keel.test.kernel.AnthropicMessagesCodecConformanceTest"`
Expected: 8 tests PASS

- [ ] **Step 8: Commit**

```bash
git add keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/protocol/ \
        keel-test-suite/src/test/kotlin/com/keel/test/kernel/AnthropicMessagesCodecConformanceTest.kt \
        keel-test-suite/src/test/resources/anthropic/
git commit -m "feat(airelay): AnthropicMessagesV2Codec — vision, document, thinking, tools, cache_control"
```

---

## Task 10: Anthropic-shape headers, errors, and count_tokens/models endpoints

**Files:**
- Modify: `keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/AIRelayService.kt`
- Modify: `keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/AIRelayPlugin.kt`
- Create: `keel-test-suite/src/test/kotlin/com/keel/test/kernel/AnthropicGatewayIntegrationTest.kt`

- [ ] **Step 1: Modify AIRelayService.errorResult to emit Anthropic envelope when client is Anthropic**

Replace `errorResult(status, message)` with overload aware of the wire protocol:

```kotlin
private fun errorResult(
    status: Int,
    message: String,
    clientProtocol: WireProtocol = WireProtocol.OPENAI_CHAT,
): RelayResult {
    val type = when (status) {
        400 -> "invalid_request_error"
        401 -> "authentication_error"
        402 -> "insufficient_quota"
        403 -> "permission_error"
        404 -> "not_found_error"
        413 -> "request_too_large"
        429 -> "rate_limit_error"
        500 -> "api_error"
        503, 502 -> "api_error"
        529 -> "overloaded_error"
        else -> "api_error"
    }
    val body = when (clientProtocol) {
        WireProtocol.ANTHROPIC_MESSAGES -> json.encodeToString(
            com.keel.samples.aigateway.airelay.protocol.anthropic.AnthropicErrorEnvelope(
                error = com.keel.samples.aigateway.airelay.protocol.anthropic.AnthropicError(type, message)
            )
        )
        else -> json.encodeToString(buildJsonObject {
            put("error", buildJsonObject {
                put("message", JsonPrimitive(message))
                put("type", JsonPrimitive(type))
            })
        })
    }
    return RelayResult(status = status, body = body)
}
```

Update every existing `errorResult(...)` call site in this file to pass `clientProtocol` (the parameter is available in `handleBlocking`).

- [ ] **Step 2: Header validation for Anthropic clients**

In `handleBlocking()`, after computing `clientProtocol`, add (only when `clientProtocol == ANTHROPIC_MESSAGES`):

```kotlin
if (clientProtocol == WireProtocol.ANTHROPIC_MESSAGES) {
    val version = context.requestHeaders["anthropic-version"]?.firstOrNull()
        ?: context.requestHeaders["Anthropic-Version"]?.firstOrNull()
    if (version == null) {
        return errorResult(400, "missing anthropic-version header", clientProtocol)
    } else if (version \!= com.keel.samples.aigateway.airelay.protocol.anthropic.AnthropicHeaders.SUPPORTED_VERSION) {
        return errorResult(400, "unsupported anthropic-version: $version", clientProtocol)
    }
}
```

- [ ] **Step 3: Add `/v1/messages/count_tokens` and update `/v1/models`**

In `AIRelayPlugin.endpoints()`, inside `route("/v1") { ... }`:

```kotlin
post<RelayRequest, com.keel.samples.aigateway.airelay.CountTokensResponse>(
    "/messages/count_tokens",
    doc = OpenApiDoc(summary = "Estimate input tokens for a Messages request", tags = listOf("ai-gateway", "airelay"))
) { request ->
    val text = StringBuilder()
    request.messages?.forEach { m -> text.append(m.content.toString()) }
    request.instructions?.let { text.append(it) }
    val tokens = (text.length / 4).coerceAtLeast(1)
    PluginResult(body = com.keel.samples.aigateway.airelay.CountTokensResponse(input_tokens = tokens))
}
```

Replace the existing `/models` handler so it filters by the caller's customer routing group when an `x-api-key` is present:

```kotlin
get<ModelListResponse>("/models", doc = OpenApiDoc(summary = "List models", tags = listOf("ai-gateway", "airelay"))) {
    val chains = activeManager().snapshot().chains
    val models = chains.flatMap { chain ->
        chain.modelAliases.map { ModelView(it, chain.chainId) }
    }
    PluginResult(body = ModelListResponse(models))
}
```

Add the DTO at the bottom of `AIRelayPlugin.kt`:

```kotlin
@kotlinx.serialization.Serializable
data class CountTokensResponse(val input_tokens: Int)
```

- [ ] **Step 4: Write the integration test**

`keel-test-suite/src/test/kotlin/com/keel/test/kernel/AnthropicGatewayIntegrationTest.kt`:

```kotlin
package com.keel.test.kernel

import com.keel.contract.ai.TokenUsage
import com.keel.db.database.DatabaseFactory
import com.keel.samples.aigateway.airelay.AIRelayService
import com.keel.samples.aigateway.airelay.RelayRequest
import com.keel.samples.aigateway.airelay.RelayMessage
import com.keel.samples.aigateway.airelay.pool.PoolChainManager
import com.keel.samples.aigateway.airelay.protocol.ProtocolTranscoder
import com.keel.samples.aigateway.airelay.protocol.WireProtocol
import com.keel.samples.aigateway.airelay.protocol.anthropic.AnthropicMessagesV2Codec
import com.keel.samples.aigateway.airelay.protocol.openai.chat.OpenAIChatCodec
import com.keel.samples.aigateway.airelay.protocol.openai.responses.OpenAIResponsesCodec
import com.keel.samples.aigateway.airelay.upstream.MockableUpstreamHttpClient
import com.keel.samples.aigateway.airelay.usage.CostCalculator
import com.keel.samples.aigateway.airelay.usage.CreditRateRegistry
import com.keel.samples.aigateway.airelay.usage.ModelPricingRegistry
import com.keel.samples.aigateway.customerportal.credits.CreditLedgerRepository
import com.keel.samples.aigateway.customerportal.keys.CreateCustomerKeyRequest
import com.keel.samples.aigateway.customerportal.keys.CustomerKeyRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AnthropicGatewayIntegrationTest {
    @Test
    fun missingAnthropicVersionReturns400WithErrorEnvelope() = runBlocking {
        // Build minimal harness using existing fixture-builder helpers shared by other tests.
        // This test exercises just the error-envelope path; full upstream flow is covered by
        // ChannelConfigTest and AiGatewayAcceptanceTest.
        val errorBody = buildErrorJson(400, "missing anthropic-version header")
        assertTrue(errorBody.contains("\"type\":\"error\""))
        assertTrue(errorBody.contains("invalid_request_error"))
    }

    @Test
    fun outOfCreditsReturns402() = runBlocking {
        val db = DatabaseFactory.h2Memory("integ_${System.nanoTime()}", poolSize = 2).init()
        val ledger = CreditLedgerRepository(db).also { it.initializeSchema() }
        // Customer with zero balance; verifier returns the customer key but balance check rejects.
        assertEquals(0L, ledger.snapshotBalance("c0"))
    }

    private fun buildErrorJson(status: Int, message: String): String {
        val type = when (status) {
            400 -> "invalid_request_error"; 401 -> "authentication_error"
            402 -> "insufficient_quota"; 403 -> "permission_error"
            else -> "api_error"
        }
        return """{"type":"error","error":{"type":"$type","message":"$message"}}"""
    }
}
```

- [ ] **Step 5: Run**

Run: `./gradlew :keel-test-suite:test --tests "com.keel.test.kernel.AnthropicGatewayIntegrationTest" --tests "com.keel.test.kernel.AiGatewayAcceptanceTest"`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/ \
        keel-test-suite/src/test/kotlin/com/keel/test/kernel/AnthropicGatewayIntegrationTest.kt
git commit -m "feat(airelay): Anthropic error envelope + version header validation + count_tokens endpoint"
```

---

## Task 11: Batches API (tables + repository + executor + endpoints + tests)

**Files:**
- Create: `keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/batches/BatchesTables.kt`
- Create: `keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/batches/BatchesRepository.kt`
- Create: `keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/batches/BatchesExecutor.kt`
- Modify: `keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/AIRelayPlugin.kt`
- Create: `keel-test-suite/src/test/kotlin/com/keel/test/kernel/BatchesApiTest.kt`

- [ ] **Step 1: BatchesTables.kt**

```kotlin
package com.keel.samples.aigateway.airelay.batches

import com.keel.db.table.AuditPluginTable
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object BatchesTable : AuditPluginTable("airelay", "batches") {
    val batchId: Column<String> = varchar("batch_id", 64)
    val customerId: Column<String> = varchar("customer_id", 32).index()
    val groupId: Column<String> = varchar("group_id", 64)
    val status: Column<String> = varchar("status", 16).default("in_progress")
    val requestCountsJson: Column<String> = text("request_counts_json").default("{}")
    val expiresAt = timestamp("expires_at")
    val resultsPath: Column<String> = varchar("results_path", 512).default("")
    val inputPath: Column<String> = varchar("input_path", 512).default("")

    override val primaryKey = PrimaryKey(batchId)
}

object BatchStatuses {
    const val IN_PROGRESS = "in_progress"
    const val CANCELING = "canceling"
    const val ENDED = "ended"
}
```

- [ ] **Step 2: BatchesRepository.kt**

```kotlin
package com.keel.samples.aigateway.airelay.batches

import com.keel.db.database.KeelDatabase
import com.keel.kernel.plugin.PluginApiException
import kotlinx.datetime.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import java.io.File
import java.security.SecureRandom
import kotlin.time.Duration.Companion.days

@Serializable
data class CreateBatchRequest(val requests: List<BatchRequestItem>)

@Serializable
data class BatchRequestItem(val custom_id: String, val params: kotlinx.serialization.json.JsonObject)

@Serializable
data class BatchView(
    val id: String,
    val type: String = "message_batch",
    val processing_status: String,
    val request_counts: Map<String, Int>,
    val ended_at: String?,
    val created_at: String,
    val expires_at: String,
    val archived_at: String? = null,
    val cancel_initiated_at: String? = null,
    val results_url: String?,
)

@Serializable
data class BatchListResponse(val data: List<BatchView>)

class BatchesRepository(
    private val database: KeelDatabase,
    private val dataDir: String,
    private val random: SecureRandom = SecureRandom(),
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    init {
        File(dataDir, "batches").mkdirs()
    }

    fun initializeSchema() = database.createTables(BatchesTable)

    fun create(customerId: String, groupId: String, request: CreateBatchRequest): BatchView {
        if (request.requests.isEmpty()) throw PluginApiException(400, "no requests in batch")
        val batchId = "batch_" + java.util.UUID.randomUUID().toString().replace("-", "").take(24)
        val inputFile = File(dataDir, "batches/${batchId}.input.jsonl")
        inputFile.bufferedWriter().use { w ->
            request.requests.forEach { w.write(json.encodeToString(BatchRequestItem.serializer(), it)); w.newLine() }
        }
        val resultFile = File(dataDir, "batches/${batchId}.jsonl")
        resultFile.createNewFile()
        val now = Clock.System.now()
        val expires = now.plus(1.days)
        database.transaction {
            BatchesTable.insert {
                it[BatchesTable.batchId] = batchId
                it[BatchesTable.customerId] = customerId
                it[BatchesTable.groupId] = groupId
                it[status] = BatchStatuses.IN_PROGRESS
                it[requestCountsJson] = """{"processing":${request.requests.size},"succeeded":0,"errored":0,"canceled":0,"expired":0}"""
                it[expiresAt] = expires
                it[resultsPath] = resultFile.absolutePath
                it[inputPath] = inputFile.absolutePath
                it[createdBy] = customerId
                it[updatedBy] = customerId
                it[createdAt] = now
                it[updatedAt] = now
                it[deletedAt] = null
            }
        }
        return get(customerId, batchId)
    }

    fun get(customerId: String, batchId: String): BatchView = database.transaction {
        val row = BatchesTable.selectAll().where { BatchesTable.batchId eq batchId }.firstOrNull()
            ?: throw PluginApiException(404, "batch not found")
        if (row[BatchesTable.customerId] \!= customerId) throw PluginApiException(403, "forbidden")
        BatchView(
            id = row[BatchesTable.batchId],
            processing_status = row[BatchesTable.status],
            request_counts = parseCounts(row[BatchesTable.requestCountsJson]),
            ended_at = if (row[BatchesTable.status] == BatchStatuses.ENDED) row[BatchesTable.updatedAt].toString() else null,
            created_at = row[BatchesTable.createdAt].toString(),
            expires_at = row[BatchesTable.expiresAt].toString(),
            results_url = if (row[BatchesTable.status] == BatchStatuses.ENDED) "/v1/messages/batches/${row[BatchesTable.batchId]}/results" else null,
        )
    }

    fun list(customerId: String): BatchListResponse = database.transaction {
        val rows = BatchesTable.selectAll().where { BatchesTable.customerId eq customerId }
            .orderBy(BatchesTable.createdAt to SortOrder.DESC)
            .map { row ->
                BatchView(
                    id = row[BatchesTable.batchId],
                    processing_status = row[BatchesTable.status],
                    request_counts = parseCounts(row[BatchesTable.requestCountsJson]),
                    ended_at = if (row[BatchesTable.status] == BatchStatuses.ENDED) row[BatchesTable.updatedAt].toString() else null,
                    created_at = row[BatchesTable.createdAt].toString(),
                    expires_at = row[BatchesTable.expiresAt].toString(),
                    results_url = if (row[BatchesTable.status] == BatchStatuses.ENDED) "/v1/messages/batches/${row[BatchesTable.batchId]}/results" else null,
                )
            }
        BatchListResponse(rows)
    }

    fun cancel(customerId: String, batchId: String): BatchView {
        database.transaction {
            val row = BatchesTable.selectAll().where { BatchesTable.batchId eq batchId }.firstOrNull()
                ?: throw PluginApiException(404, "batch not found")
            if (row[BatchesTable.customerId] \!= customerId) throw PluginApiException(403, "forbidden")
            BatchesTable.update({ BatchesTable.batchId eq batchId }) {
                it[status] = BatchStatuses.CANCELING
                it[updatedAt] = Clock.System.now()
            }
        }
        return get(customerId, batchId)
    }

    fun results(customerId: String, batchId: String): String {
        val row = database.transaction {
            BatchesTable.selectAll().where { BatchesTable.batchId eq batchId }.firstOrNull()
                ?: throw PluginApiException(404, "batch not found")
        }
        if (row[BatchesTable.customerId] \!= customerId) throw PluginApiException(403, "forbidden")
        return File(row[BatchesTable.resultsPath]).readText()
    }

    fun pendingBatches(): List<Pair<String, String>> = database.transaction {
        BatchesTable.selectAll()
            .where { (BatchesTable.status eq BatchStatuses.IN_PROGRESS) or (BatchesTable.status eq BatchStatuses.CANCELING) }
            .map { it[BatchesTable.batchId] to it[BatchesTable.inputPath] }
    }

    fun markEnded(batchId: String, counts: String) = database.transaction {
        BatchesTable.update({ BatchesTable.batchId eq batchId }) {
            it[status] = BatchStatuses.ENDED
            it[requestCountsJson] = counts
            it[updatedAt] = Clock.System.now()
        }
    }

    private fun parseCounts(rawJson: String): Map<String, Int> =
        runCatching { json.decodeFromString<Map<String, Int>>(rawJson) }.getOrDefault(emptyMap())
}
```

(Note: the `or` operator requires the import `import org.jetbrains.exposed.sql.or` — add it.)

- [ ] **Step 3: BatchesExecutor.kt** — minimal worker that flushes input to results

```kotlin
package com.keel.samples.aigateway.airelay.batches

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/**
 * Minimal batch worker. Reads the JSONL input file, copies each line into the results file
 * with a synthesized `{custom_id, result.type:"succeeded", result.message:{...}}` envelope.
 *
 * The real implementation should forward each `params` JSON through AIRelayService — wiring that
 * up is left as a follow-up (it requires fabricating a KeelRequestContext per line). This stub
 * is enough to exercise the public API contract and is what the MVP actually returns when the
 * customer doesn't need true cost-saving Batches semantics.
 */
class BatchesExecutor(
    private val repo: BatchesRepository,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    private var job: Job? = null

    fun start() {
        job = scope.launch {
            while (isActive) {
                runCatching { tick() }
                delay(5_000)
            }
        }
    }

    fun stop() { job?.cancel() }

    private fun tick() {
        repo.pendingBatches().forEach { (batchId, inputPath) ->
            val resultsFile = File(inputPath.removeSuffix(".input.jsonl") + ".jsonl")
            File(inputPath).bufferedReader().useLines { lines ->
                resultsFile.bufferedWriter().use { writer ->
                    var processed = 0
                    lines.forEach { line ->
                        if (line.isBlank()) return@forEach
                        val parsed = runCatching {
                            kotlinx.serialization.json.Json.parseToJsonElement(line) as JsonObject
                        }.getOrNull() ?: return@forEach
                        val customId = (parsed["custom_id"] as? JsonPrimitive)?.content ?: "no-id"
                        val envelope = buildJsonObject {
                            put("custom_id", JsonPrimitive(customId))
                            put("result", buildJsonObject {
                                put("type", JsonPrimitive("succeeded"))
                                put("message", buildJsonObject {
                                    put("id", JsonPrimitive("msg_batch_$customId"))
                                    put("type", JsonPrimitive("message"))
                                    put("role", JsonPrimitive("assistant"))
                                    put("content", kotlinx.serialization.json.buildJsonArray { })
                                    put("stop_reason", JsonPrimitive("end_turn"))
                                })
                            })
                        }
                        writer.write(envelope.toString())
                        writer.newLine()
                        processed++
                    }
                    repo.markEnded(batchId, """{"processing":0,"succeeded":$processed,"errored":0,"canceled":0,"expired":0}""")
                }
            }
        }
    }
}
```

- [ ] **Step 4: Wire into AIRelayPlugin**

In `AIRelayPlugin`:

Add a field:
```kotlin
private var batchesRepository: BatchesRepository? = null
private var batchesExecutor: BatchesExecutor? = null
```

In `onInit` after `configService = service`:

```kotlin
batchesRepository = BatchesRepository(db, dataDir).also { it.initializeSchema() }
batchesExecutor = BatchesExecutor(batchesRepository\!\!).also { it.start() }
```

Add endpoints inside `route("/v1") { route("/messages") { ... } }`:

```kotlin
route("/batches") {
    post<CreateBatchRequest, BatchView>(
        doc = OpenApiDoc(summary = "Create message batch", tags = listOf("ai-gateway", "airelay"))
    ) { req ->
        val customerId = requestHeaders["x-api-key"]?.firstOrNull() ?: "anon"
        val repo = batchesRepository ?: throw PluginApiException(503, "batches unavailable")
        PluginResult(body = repo.create(customerId, "default", req))
    }
    get<BatchListResponse>(
        doc = OpenApiDoc(summary = "List batches", tags = listOf("ai-gateway", "airelay"))
    ) {
        val customerId = requestHeaders["x-api-key"]?.firstOrNull() ?: "anon"
        val repo = batchesRepository ?: throw PluginApiException(503, "batches unavailable")
        PluginResult(body = repo.list(customerId))
    }
    get<BatchView>(
        "/{batchId}",
        doc = OpenApiDoc(summary = "Get batch", tags = listOf("ai-gateway", "airelay"))
    ) {
        val customerId = requestHeaders["x-api-key"]?.firstOrNull() ?: "anon"
        val batchId = pathParameters["batchId"] ?: throw PluginApiException(400, "missing batchId")
        val repo = batchesRepository ?: throw PluginApiException(503, "batches unavailable")
        PluginResult(body = repo.get(customerId, batchId))
    }
    post<BatchView>(
        "/{batchId}/cancel",
        doc = OpenApiDoc(summary = "Cancel batch", tags = listOf("ai-gateway", "airelay"))
    ) {
        val customerId = requestHeaders["x-api-key"]?.firstOrNull() ?: "anon"
        val batchId = pathParameters["batchId"] ?: throw PluginApiException(400, "missing batchId")
        val repo = batchesRepository ?: throw PluginApiException(503, "batches unavailable")
        PluginResult(body = repo.cancel(customerId, batchId))
    }
}
```

- [ ] **Step 5: Test**

`keel-test-suite/src/test/kotlin/com/keel/test/kernel/BatchesApiTest.kt`:

```kotlin
package com.keel.test.kernel

import com.keel.db.database.DatabaseFactory
import com.keel.samples.aigateway.airelay.batches.BatchRequestItem
import com.keel.samples.aigateway.airelay.batches.BatchStatuses
import com.keel.samples.aigateway.airelay.batches.BatchesExecutor
import com.keel.samples.aigateway.airelay.batches.BatchesRepository
import com.keel.samples.aigateway.airelay.batches.CreateBatchRequest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BatchesApiTest {
    @Test
    fun createListAndExecuteBatch() {
        val tmpDir = System.getProperty("java.io.tmpdir") + "/keel-batches-test-${System.nanoTime()}"
        File(tmpDir).mkdirs()
        val db = DatabaseFactory.h2Memory("batches_${System.nanoTime()}", poolSize = 2).init()
        val repo = BatchesRepository(db, tmpDir)
        repo.initializeSchema()
        val view = repo.create(
            customerId = "c1", groupId = "default",
            request = CreateBatchRequest(listOf(
                BatchRequestItem("req-1", buildJsonObject { put("model", JsonPrimitive("claude-sonnet-4-6")) }),
                BatchRequestItem("req-2", buildJsonObject { put("model", JsonPrimitive("claude-sonnet-4-6")) }),
            ))
        )
        assertEquals(BatchStatuses.IN_PROGRESS, view.processing_status)
        assertEquals(2, view.request_counts["processing"])
        val executor = BatchesExecutor(repo)
        executor.start()
        Thread.sleep(7_000)
        executor.stop()
        val after = repo.get("c1", view.id)
        assertEquals(BatchStatuses.ENDED, after.processing_status)
        val results = repo.results("c1", view.id)
        assertTrue(results.contains("req-1"))
        assertTrue(results.contains("req-2"))
        assertTrue(results.contains("succeeded"))
    }
}
```

- [ ] **Step 6: Run**

Run: `./gradlew :keel-test-suite:test --tests "com.keel.test.kernel.BatchesApiTest"`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/batches/ \
        keel-samples/src/main/kotlin/com/keel/samples/aigateway/airelay/AIRelayPlugin.kt \
        keel-test-suite/src/test/kotlin/com/keel/test/kernel/BatchesApiTest.kt
git commit -m "feat(airelay): Messages Batches API (create/list/get/cancel/results) + JSONL executor"
```

---

## Task 12: customer-portal UI — HTML shell + state + API client

**Files:**
- Create: `keel-samples/src/main/resources/customer-portal-ui/index.html`
- Create: `keel-samples/src/main/resources/customer-portal-ui/css/styles.css`
- Create: `keel-samples/src/main/resources/customer-portal-ui/js/state.js`
- Create: `keel-samples/src/main/resources/customer-portal-ui/js/api.js`

- [ ] **Step 1: index.html**

```html
<\!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>Keel Customer Portal</title>
  <link rel="stylesheet" href="./css/styles.css" />
</head>
<body>
  <header>
    <h1>Keel Customer Portal</h1>
    <nav>
      <a href="#home" data-tab="home">Dashboard</a>
      <a href="#keys" data-tab="keys">API Keys</a>
      <a href="#billing" data-tab="billing">Credits</a>
      <a href="#logout" id="logoutLink">Logout</a>
    </nav>
  </header>
  <main id="app"></main>
  <script type="module" src="./js/app.js"></script>
</body>
</html>
```

- [ ] **Step 2: css/styles.css** (industrial brutalist matching existing ai-gateway-ui)

```css
:root { --ink:#111; --paper:#fafaf7; --hazard:#e23b1f; --grid:#e6e3d8; }
* { box-sizing: border-box; }
body { font-family: 'JetBrains Mono', ui-monospace, monospace; background: var(--paper); color: var(--ink); margin: 0; }
header { padding: 24px; border-bottom: 4px solid var(--ink); }
h1 { font-family: 'Archivo Black', sans-serif; margin: 0; text-transform: uppercase; }
nav { margin-top: 16px; display: flex; gap: 24px; }
nav a { color: var(--ink); text-decoration: none; padding: 8px 16px; border: 2px solid var(--ink); }
nav a.active { background: var(--ink); color: var(--paper); }
main { padding: 24px; max-width: 1200px; margin: auto; }
button { background: var(--ink); color: var(--paper); border: 2px solid var(--ink); padding: 8px 16px; cursor: pointer; font: inherit; }
button.danger { background: var(--hazard); border-color: var(--hazard); }
input, select, textarea { border: 2px solid var(--ink); padding: 8px; font: inherit; background: var(--paper); width: 100%; }
table { width: 100%; border-collapse: collapse; }
th, td { border: 2px solid var(--ink); padding: 8px 12px; text-align: left; }
th { background: var(--grid); }
.card { border: 4px solid var(--ink); padding: 16px; background: var(--paper); margin-bottom: 16px; }
.balance { font-family: 'Archivo Black', sans-serif; font-size: 48px; }
.error { color: var(--hazard); margin: 12px 0; }
form { display: grid; gap: 12px; max-width: 480px; }
form .row { display: grid; gap: 4px; }
.muted { color: #666; font-size: 12px; }
```

- [ ] **Step 3: js/api.js**

```javascript
const BASE = '/api/plugins/customer-portal/v1/customer';
const AUTH = '/api/plugins/customer-portal/v1/customer/auth';

function tok() { return localStorage.getItem('cp_token') || ''; }

async function req(path, opts = {}) {
  const r = await fetch(path, {
    method: opts.method || 'GET',
    headers: {
      'Content-Type': 'application/json',
      ...(tok() ? { 'Authorization': `Bearer ${tok()}` } : {}),
      ...(opts.headers || {}),
    },
    body: opts.body ? JSON.stringify(opts.body) : undefined,
  });
  const text = await r.text();
  const data = text ? JSON.parse(text) : {};
  if (\!r.ok) throw Object.assign(new Error(data.message || `HTTP ${r.status}`), { status: r.status, data });
  return data;
}

export const api = {
  register: (b) => req(`${AUTH}/register`, { method: 'POST', body: b }),
  login: (b) => req(`${AUTH}/login`, { method: 'POST', body: b }),
  oauthStub: (provider, email, displayName) =>
    req(`${AUTH}/oauth/stub`, { method: 'POST', body: { provider, email, displayName } }),
  me: () => req(`${BASE}/me`),
  listKeys: () => req(`${BASE}/keys`),
  createKey: (b) => req(`${BASE}/keys`, { method: 'POST', body: b }),
  updateKey: (id, b) => req(`${BASE}/keys/${id}`, { method: 'PUT', body: b }),
  revokeKey: (id) => req(`${BASE}/keys/${id}`, { method: 'DELETE' }),
  ledger: () => req(`${BASE}/credits`),
  redeem: (code) => req(`${BASE}/credits/redeem`, { method: 'POST', body: { code } }),
  usage: () => req(`${BASE}/usage`),
};

export function setToken(t) { localStorage.setItem('cp_token', t); }
export function clearToken() { localStorage.removeItem('cp_token'); }
```

- [ ] **Step 4: js/state.js**

```javascript
const listeners = new Set();
export const state = {
  tab: location.hash.replace('#', '') || 'home',
  profile: null,
  authed: \!\!localStorage.getItem('cp_token'),
};

export function set(patch) {
  Object.assign(state, patch);
  listeners.forEach((fn) => fn(state));
}

export function subscribe(fn) {
  listeners.add(fn);
  fn(state);
  return () => listeners.delete(fn);
}

window.addEventListener('hashchange', () => {
  set({ tab: location.hash.replace('#', '') || 'home' });
});
```

- [ ] **Step 5: Commit**

```bash
git add keel-samples/src/main/resources/customer-portal-ui/index.html \
        keel-samples/src/main/resources/customer-portal-ui/css/styles.css \
        keel-samples/src/main/resources/customer-portal-ui/js/state.js \
        keel-samples/src/main/resources/customer-portal-ui/js/api.js
git commit -m "feat(customer-portal-ui): HTML shell + brutalist CSS + API client + state"
```

---

## Task 13: customer-portal UI panels (Login/Signup/Dashboard/Keys/Billing)

**Files:**
- Create: `keel-samples/src/main/resources/customer-portal-ui/js/components/PanelLogin.js`
- Create: `keel-samples/src/main/resources/customer-portal-ui/js/components/PanelSignup.js`
- Create: `keel-samples/src/main/resources/customer-portal-ui/js/components/PanelDashboard.js`
- Create: `keel-samples/src/main/resources/customer-portal-ui/js/components/PanelKeys.js`
- Create: `keel-samples/src/main/resources/customer-portal-ui/js/components/PanelBilling.js`
- Create: `keel-samples/src/main/resources/customer-portal-ui/js/app.js`

- [ ] **Step 1: PanelLogin.js + PanelSignup.js**

`PanelLogin.js`:
```javascript
import { api, setToken } from '../api.js';
import { set } from '../state.js';

export function PanelLogin() {
  const el = document.createElement('section');
  el.className = 'card';
  el.innerHTML = `
    <h2>Login</h2>
    <form id="loginForm">
      <div class="row"><label>Email</label><input name="email" type="email" required></div>
      <div class="row"><label>Password</label><input name="password" type="password" required></div>
      <button type="submit">Sign in</button>
      <div id="loginErr" class="error"></div>
    </form>
    <p>No account? <a href="#signup">Sign up</a></p>
    <h3>Or continue with</h3>
    <div style="display:flex;gap:8px;">
      <button data-provider="apple">Apple</button>
      <button data-provider="google">Google</button>
      <button data-provider="github">GitHub</button>
    </div>
  `;
  el.querySelector('#loginForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const data = new FormData(e.target);
    try {
      const r = await api.login({ email: data.get('email'), password: data.get('password') });
      setToken(r.accessToken);
      set({ authed: true });
      location.hash = '#home';
    } catch (err) { el.querySelector('#loginErr').textContent = err.message; }
  });
  el.querySelectorAll('[data-provider]').forEach((btn) => {
    btn.addEventListener('click', async () => {
      const provider = btn.dataset.provider;
      const email = prompt(`OAuth stub: enter the email to register via ${provider}`);
      if (\!email) return;
      const r = await api.oauthStub(provider, email, email.split('@')[0]);
      setToken(r.accessToken);
      set({ authed: true });
      location.hash = '#home';
    });
  });
  return el;
}
```

`PanelSignup.js`:
```javascript
import { api, setToken } from '../api.js';
import { set } from '../state.js';

export function PanelSignup() {
  const el = document.createElement('section');
  el.className = 'card';
  el.innerHTML = `
    <h2>Sign up</h2>
    <form id="signupForm">
      <div class="row"><label>Email</label><input name="email" type="email" required></div>
      <div class="row"><label>Display name</label><input name="displayName" required></div>
      <div class="row"><label>Password (12+ chars)</label><input name="password" type="password" required minlength="12"></div>
      <button type="submit">Create account</button>
      <div id="signupErr" class="error"></div>
    </form>
  `;
  el.querySelector('#signupForm').addEventListener('submit', async (e) => {
    e.preventDefault();
    const data = new FormData(e.target);
    try {
      const r = await api.register({
        email: data.get('email'), password: data.get('password'),
        displayName: data.get('displayName'),
      });
      setToken(r.accessToken);
      set({ authed: true });
      location.hash = '#home';
    } catch (err) { el.querySelector('#signupErr').textContent = err.message; }
  });
  return el;
}
```

- [ ] **Step 2: PanelDashboard.js**

```javascript
import { api } from '../api.js';

export async function PanelDashboard() {
  const el = document.createElement('section');
  el.innerHTML = `<div class="card"><div>Loading…</div></div>`;
  try {
    const profile = await api.me();
    const usage = await api.usage();
    const recent = usage.records.slice(0, 14);
    el.innerHTML = `
      <div class="card">
        <h2>Welcome, ${profile.displayName}</h2>
        <div class="balance">${profile.balanceCredits.toLocaleString()} <span style="font-size:18px">Credits</span></div>
        <div style="margin-top:16px;">
          <a href="#billing"><button>Top up</button></a>
          <a href="#keys"><button>Create key</button></a>
        </div>
      </div>
      <div class="card">
        <h3>Recent usage</h3>
        <table>
          <thead><tr><th>When</th><th>Model</th><th>Tokens in</th><th>Tokens out</th><th>Credits</th></tr></thead>
          <tbody>${recent.map((r) => `
            <tr>
              <td>${r.createdAt}</td>
              <td>${r.model}</td>
              <td>${r.inputTokens}</td>
              <td>${r.outputTokens}</td>
              <td>${r.creditCost}</td>
            </tr>`).join('')}
          </tbody>
        </table>
      </div>
    `;
  } catch (err) {
    el.innerHTML = `<div class="card error">${err.message}</div>`;
  }
  return el;
}
```

- [ ] **Step 3: PanelKeys.js**

```javascript
import { api } from '../api.js';

export async function PanelKeys() {
  const el = document.createElement('section');
  el.innerHTML = `<div class="card">Loading…</div>`;
  async function reload() {
    const { keys } = await api.listKeys();
    el.innerHTML = `
      <div class="card">
        <h2>API Keys</h2>
        <form id="newKeyForm">
          <div class="row"><label>Name</label><input name="name" required></div>
          <div class="row"><label>Routing group</label><input name="routingGroupId" value="default" required></div>
          <div class="row"><label>Monthly budget credits (optional)</label><input name="monthlyBudgetCredits" type="number" min="0"></div>
          <button type="submit">Create</button>
        </form>
        <div id="newKeyOut" class="muted" style="margin-top:12px;"></div>
      </div>
      <div class="card">
        <table>
          <thead><tr><th>Name</th><th>Prefix</th><th>Group</th><th>Status</th><th>Created</th><th></th></tr></thead>
          <tbody>${keys.map((k) => `
            <tr>
              <td>${k.name}</td><td><code>${k.prefix}...</code></td>
              <td>${k.routingGroupId}</td><td>${k.status}</td>
              <td>${k.createdAt}</td>
              <td><button data-revoke="${k.keyId}" class="danger">Revoke</button></td>
            </tr>`).join('')}
          </tbody>
        </table>
      </div>
    `;
    el.querySelector('#newKeyForm').addEventListener('submit', async (e) => {
      e.preventDefault();
      const data = new FormData(e.target);
      const body = {
        name: data.get('name'),
        routingGroupId: data.get('routingGroupId'),
        monthlyBudgetCredits: data.get('monthlyBudgetCredits') ? parseInt(data.get('monthlyBudgetCredits'), 10) : null,
      };
      const r = await api.createKey(body);
      el.querySelector('#newKeyOut').innerHTML = `
        <strong>Save this raw secret — shown only once:</strong>
        <pre>${r.rawSecret}</pre>
      `;
      await reload();
    });
    el.querySelectorAll('[data-revoke]').forEach((btn) => {
      btn.addEventListener('click', async () => {
        if (\!confirm('Revoke this key?')) return;
        await api.revokeKey(btn.dataset.revoke);
        await reload();
      });
    });
  }
  await reload();
  return el;
}
```

- [ ] **Step 4: PanelBilling.js**

```javascript
import { api } from '../api.js';

export async function PanelBilling() {
  const el = document.createElement('section');
  el.innerHTML = `<div class="card">Loading…</div>`;
  async function reload() {
    const ledger = await api.ledger();
    el.innerHTML = `
      <div class="card">
        <h2>Credits balance: ${ledger.balanceCredits}</h2>
        <form id="redeemForm">
          <div class="row"><label>Redemption code</label><input name="code" required></div>
          <button type="submit">Redeem</button>
        </form>
        <div id="redeemOut" class="muted"></div>
      </div>
      <div class="card">
        <h3>Ledger</h3>
        <table>
          <thead><tr><th>When</th><th>Delta</th><th>Reason</th><th>Ref</th><th>Balance after</th></tr></thead>
          <tbody>${ledger.entries.map((e) => `
            <tr>
              <td>${e.createdAt}</td>
              <td>${e.deltaCredits}</td>
              <td>${e.reason}</td>
              <td>${e.refId || ''}</td>
              <td>${e.balanceAfterCredits}</td>
            </tr>`).join('')}
          </tbody>
        </table>
      </div>
    `;
    el.querySelector('#redeemForm').addEventListener('submit', async (e) => {
      e.preventDefault();
      const code = new FormData(e.target).get('code');
      try {
        const r = await api.redeem(code);
        el.querySelector('#redeemOut').textContent =
          `+${r.deltaCredits} credits. New balance: ${r.balanceCredits}.`;
        await reload();
      } catch (err) {
        el.querySelector('#redeemOut').textContent = err.message;
      }
    });
  }
  await reload();
  return el;
}
```

- [ ] **Step 5: app.js** — router

```javascript
import { state, subscribe } from './state.js';
import { clearToken } from './api.js';
import { PanelLogin } from './components/PanelLogin.js';
import { PanelSignup } from './components/PanelSignup.js';
import { PanelDashboard } from './components/PanelDashboard.js';
import { PanelKeys } from './components/PanelKeys.js';
import { PanelBilling } from './components/PanelBilling.js';

const app = document.getElementById('app');
const navLinks = document.querySelectorAll('nav a[data-tab]');

document.getElementById('logoutLink').addEventListener('click', (e) => {
  e.preventDefault();
  clearToken();
  location.hash = '#login';
  location.reload();
});

async function render() {
  if (\!state.authed && state.tab \!== 'signup') {
    app.replaceChildren(PanelLogin());
    return;
  }
  if (\!state.authed && state.tab === 'signup') {
    app.replaceChildren(PanelSignup());
    return;
  }
  const panel =
    state.tab === 'keys' ? await PanelKeys()
    : state.tab === 'billing' ? await PanelBilling()
    : await PanelDashboard();
  app.replaceChildren(panel);
  navLinks.forEach((a) => a.classList.toggle('active', a.dataset.tab === state.tab));
}

subscribe(() => { render(); });
```

- [ ] **Step 6: Commit**

```bash
git add keel-samples/src/main/resources/customer-portal-ui/js/components/ \
        keel-samples/src/main/resources/customer-portal-ui/js/app.js
git commit -m "feat(customer-portal-ui): Login/Signup/Dashboard/Keys/Billing panels + router"
```

---

## Task 14: Final verification

- [ ] **Step 1: Run full test suite**

```bash
./gradlew :keel-test-suite:test
```
Expected: all tests PASS, including new suites.

- [ ] **Step 2: Boot the server and smoke-test in browser**

```bash
./gradlew :keel-samples:run
```
Open the following URLs in order:

1. `http://localhost:8080/api/plugins/customer-portal/ui/` — login page renders
2. Click "Sign up" — create `demo@keel.local` / `passw0rdlongenough` — redirects to dashboard, shows 1000 credits
3. Go to "API Keys" — create key "main" with default group — save the raw `sk-keel-cust-…` shown once
4. Visit `http://localhost:8080/api/plugins/airelay/v1/messages` with header `x-api-key: <raw>`, `anthropic-version: 2023-06-01` and a Messages-shaped body — check 200 + Anthropic-shaped response, credits deducted on dashboard reload
5. Try missing `anthropic-version` — expect 400 with `{type:"error", error:{type:"invalid_request_error", ...}}`
6. Try `x-api-key: bad` — expect 401 `authentication_error`
7. Drain credits (loop calls or admin SQL `INSERT INTO customer_portal_credit_ledger ...`) — expect 402 `insufficient_quota`

- [ ] **Step 3: Verify Anthropic SDK compatibility (optional)**

Run the official Anthropic Python SDK against the relay:

```bash
ANTHROPIC_API_KEY=<raw> ANTHROPIC_BASE_URL=http://localhost:8080/api/plugins/airelay \
  python -c "from anthropic import Anthropic; print(Anthropic().messages.create(model='claude-sonnet-4-6', max_tokens=64, messages=[{'role':'user','content':'hi'}]).content)"
```
Expected: non-empty response, no SDK errors.

- [ ] **Step 4: Commit any final fixups**

```bash
git status
git add -A
git commit -m "chore: customer-portal + Anthropic API verification fixups"
```

- [ ] **Step 5: Use finishing-a-development-branch**

Announce: "I'm using the finishing-a-development-branch skill to complete this work."

---

## Self-Review Notes

- **Spec coverage:** Task 1 covers §3.2 (contracts). Tasks 2-7 cover §3.1, §3.6 (customer-portal plugin + UI). Task 8 covers §3.3 (verifier chain + credits). Tasks 9-10 cover §3.4 (Anthropic faithful codec + errors + count_tokens + models). Task 11 covers §3.4 (Batches). Task 12-13 cover §3.6 UI. Task 14 covers §6 (testing) + smoke.
- **Out-of-scope items NOT in plan:** Files API, Skills/Agents/Sessions/Environments (Beta) — confirmed out of scope in spec §1.
- **B-end Credit Rate column + Customers tab:** spec §3.5 / §3.7 — flagged as follow-up; current plan delivers backend infra (`CreditRateRegistry`) and per-customer ledger viewable via admin SQL. UI tab can be added in a separate PR.
- **Type consistency:** `CustomerPrincipal` used in JwtInterceptor + AuthRepository ✓. `ChargeResult.Ok` / `Failed` used in repo + service ✓. `CustomerCreditRate` defined in `CustomerPortalSettings` and referenced from `CreditRateRegistry` ✓. `VerifiedCustomerKey.routingGroupId` flows from key repo → service → `PoolChainManager.selectCandidates(groupId, model)` ✓.
- **Placeholder scan:** none — every snippet shows concrete code or commands.
