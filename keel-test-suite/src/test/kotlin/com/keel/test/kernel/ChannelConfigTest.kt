package com.keel.test.kernel

import com.keel.db.database.DatabaseFactory
import com.keel.samples.aigateway.airelay.config.ChannelRepository
import com.keel.samples.aigateway.airelay.config.ConfigService
import com.keel.samples.aigateway.airelay.config.SecretCipher
import com.keel.samples.aigateway.airelay.config.UpsertChannelRequest
import com.keel.samples.aigateway.airelay.config.UpsertGroupRequest
import com.keel.samples.aigateway.airelay.config.UpsertModelRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit coverage for the runtime provider/model configuration store. Pure in-process (H2 memory),
 * no HTTP, so it runs anywhere. Proves: channels persist, keys round-trip through encryption,
 * the config bridge produces a usable PoolChainManager, and edits hot-apply.
 */
class ChannelConfigTest {

    private fun newRepo(): ChannelRepository {
        val db = DatabaseFactory.h2Memory(name = "channel_test_${System.nanoTime()}").init()
        return ChannelRepository(db, SecretCipher("test-secret")).also { it.initializeSchema() }
    }

    @Test
    fun createListAndMaskKey() {
        val repo = newRepo()
        val created = repo.createChannel(
            UpsertChannelRequest(
                name = "Local Anthropic",
                protocol = "ANTHROPIC_MESSAGES",
                baseUrl = "http://127.0.0.1:15721",
                apiKey = "sk-secret-value-1234",
                models = listOf(UpsertModelRequest(publicModelName = "claude-sonnet-4-20250514"))
            )
        )
        assertTrue(created.channelId.startsWith("ch-"))
        // Key is masked in views, never returned in plaintext.
        assertTrue(created.apiKeyMasked.contains("…"))
        assertTrue(!created.apiKeyMasked.contains("secret-value"))

        val list = repo.listChannels()
        assertEquals(1, list.size)
        assertEquals("Local Anthropic", list[0].name)
        assertEquals(1, list[0].models.size)

        // The decrypted key is recoverable for the live-config bridge.
        assertEquals("sk-secret-value-1234", repo.decryptedKey(created.channelId))
    }

    @Test
    fun updateKeepsKeyWhenBlank() {
        val repo = newRepo()
        val created = repo.createChannel(
            UpsertChannelRequest(name = "C", protocol = "OPENAI_CHAT", baseUrl = "http://x", apiKey = "original-key")
        )
        repo.updateChannel(
            created.channelId,
            UpsertChannelRequest(name = "C-renamed", protocol = "OPENAI_CHAT", baseUrl = "http://x", apiKey = "")
        )
        assertEquals("original-key", repo.decryptedKey(created.channelId))
        assertEquals("C-renamed", repo.getChannel(created.channelId)!!.name)
    }

    @Test
    fun deleteRemovesChannelAndModels() {
        val repo = newRepo()
        val created = repo.createChannel(
            UpsertChannelRequest(
                name = "C", protocol = "OPENAI_CHAT", baseUrl = "http://x", apiKey = "k",
                models = listOf(UpsertModelRequest(publicModelName = "m1"), UpsertModelRequest(publicModelName = "m2"))
            )
        )
        assertTrue(repo.deleteChannel(created.channelId))
        assertNull(repo.getChannel(created.channelId))
        assertEquals(0L, repo.count())
    }

    @Test
    fun newRepositoryCreatesDefaultGroupAndAssignsChannels() {
        val repo = newRepo()
        val groups = repo.listGroups()
        assertTrue(groups.any { it.groupId == "default" && it.enabled })

        val created = repo.createChannel(
            UpsertChannelRequest(
                name = "Anthropic",
                protocol = "ANTHROPIC_MESSAGES",
                baseUrl = "http://127.0.0.1:15721",
                apiKey = "k",
                models = listOf(UpsertModelRequest(publicModelName = "claude-sonnet-4-20250514"))
            )
        )
        assertEquals("default", created.groupId)
        assertEquals("default", repo.getChannel(created.channelId)!!.groupId)
    }

    @Test
    fun channelsCanBeAssignedToGroups() {
        val repo = newRepo()
        repo.createGroup(UpsertGroupRequest(name = "Premium", groupId = "premium", description = "Paid pool", enabled = true))
        val created = repo.createChannel(
            UpsertChannelRequest(
                name = "Premium Anthropic",
                protocol = "ANTHROPIC_MESSAGES",
                baseUrl = "http://127.0.0.1:15721",
                apiKey = "k",
                groupId = "premium",
                priority = 100,
                weight = 50,
                models = listOf(UpsertModelRequest(publicModelName = "claude-premium"))
            )
        )
        assertEquals("premium", created.groupId)
        val premium = repo.listGroups().first { it.groupId == "premium" }
        assertEquals(1, premium.channelCount)
        assertEquals(1, premium.modelCount)
    }

    @Test
    fun deleteGroupRejectsWhenChannelsExist() {
        val repo = newRepo()
        repo.createGroup(UpsertGroupRequest(name = "Premium", groupId = "premium", description = null, enabled = true))
        repo.createChannel(
            UpsertChannelRequest(
                name = "Premium Anthropic",
                protocol = "ANTHROPIC_MESSAGES",
                baseUrl = "http://127.0.0.1:15721",
                apiKey = "k",
                groupId = "premium"
            )
        )
        assertFalse(repo.deleteGroup("premium"), "groups with channels must not be deleted")
    }

    @Test
    fun configServiceBuildsPoolFromEnabledChannels() {
        val repo = newRepo()
        repo.createChannel(
            UpsertChannelRequest(
                name = "Anthropic", protocol = "ANTHROPIC_MESSAGES", baseUrl = "http://127.0.0.1:15721",
                apiKey = "k", enabled = true,
                models = listOf(UpsertModelRequest(publicModelName = "claude-sonnet-4-20250514"))
            )
        )
        // A disabled channel must NOT appear in the live pool.
        repo.createChannel(
            UpsertChannelRequest(
                name = "Disabled", protocol = "OPENAI_CHAT", baseUrl = "http://other", apiKey = "k",
                enabled = false, models = listOf(UpsertModelRequest(publicModelName = "ghost-model"))
            )
        )
        val service = ConfigService(repo)
        service.reload()
        val chains = service.poolChainManager.snapshot().chains
        assertEquals(1, chains.size, "enabled default group should produce one runtime pool")
        assertEquals("default", chains[0].chainId)
        assertTrue(chains[0].modelAliases.contains("claude-sonnet-4-20250514"))
        assertTrue(service.pricings.any { it.model == "claude-sonnet-4-20250514" })
    }

    @Test
    fun configServiceGroupsChannelsIntoPriorityLevels() {
        val repo = newRepo()
        repo.createGroup(UpsertGroupRequest(groupId = "premium", name = "Premium", description = null, enabled = true))
        repo.createChannel(
            UpsertChannelRequest(
                name = "Primary A", protocol = "ANTHROPIC_MESSAGES", baseUrl = "http://a", apiKey = "a",
                groupId = "premium", priority = 100, weight = 100,
                models = listOf(UpsertModelRequest(publicModelName = "claude-premium"))
            )
        )
        repo.createChannel(
            UpsertChannelRequest(
                name = "Primary B", protocol = "ANTHROPIC_MESSAGES", baseUrl = "http://b", apiKey = "b",
                groupId = "premium", priority = 100, weight = 50,
                models = listOf(UpsertModelRequest(publicModelName = "claude-premium"))
            )
        )
        repo.createChannel(
            UpsertChannelRequest(
                name = "Backup", protocol = "OPENAI_CHAT", baseUrl = "http://c", apiKey = "c",
                groupId = "premium", priority = 50, weight = 100,
                models = listOf(UpsertModelRequest(publicModelName = "claude-premium"))
            )
        )

        val service = ConfigService(repo)
        service.reload()
        val chain = service.poolChainManager.snapshot().chains.single { it.chainId == "premium" }
        assertEquals(listOf("claude-premium"), chain.modelAliases)
        assertEquals(2, chain.levels.size)
        assertEquals(2, chain.levels.first().keys.size, "same-priority channels share a level")
        assertEquals(1, chain.levels.last().keys.size, "lower-priority channel becomes backup level")
    }

    @Test
    fun channelCanBelongToMultipleGroups() {
        val repo = newRepo()
        repo.createGroup(UpsertGroupRequest(groupId = "premium", name = "Premium", description = null, enabled = true))
        val created = repo.createChannel(
            UpsertChannelRequest(
                name = "Shared Channel",
                protocol = "ANTHROPIC_MESSAGES",
                baseUrl = "http://shared",
                apiKey = "k",
                models = listOf(UpsertModelRequest(publicModelName = "claude-shared"))
            )
        )
        repo.attachChannelToGroup(created.channelId, "premium", priority = 80, weight = 60, enabled = true)

        val channel = repo.getChannel(created.channelId)!!
        assertEquals(2, channel.memberships.size)
        assertTrue(channel.memberships.any { it.groupId == "default" })
        assertTrue(channel.memberships.any { it.groupId == "premium" })

        val service = ConfigService(repo)
        service.reload()
        val chains = service.poolChainManager.snapshot().chains
        assertTrue(chains.any { it.chainId == "default" })
        assertTrue(chains.any { it.chainId == "premium" })
    }

    @Test
    fun initializeSchemaMigratesLegacyPricingTableWithoutVariantKey() {
        val db = DatabaseFactory.h2Memory(name = "legacy_pricing_${System.nanoTime()}").init()
        db.transaction {
            exec(
                """
                CREATE TABLE IF NOT EXISTS AIRELAY_MODEL_PRICING (
                    CREATED_AT TIMESTAMP(9) DEFAULT CURRENT_TIMESTAMP NOT NULL,
                    UPDATED_AT TIMESTAMP(9) DEFAULT CURRENT_TIMESTAMP NOT NULL,
                    DELETED_AT TIMESTAMP(9) NULL,
                    CREATED_BY VARCHAR(255) NULL,
                    UPDATED_BY VARCHAR(255) NULL,
                    PRICING_ID VARCHAR(32) PRIMARY KEY,
                    MODEL VARCHAR(120) NOT NULL,
                    LABEL VARCHAR(120) NULL,
                    BILLING_UNIT_TOKENS BIGINT DEFAULT 1000000 NOT NULL,
                    INPUT_COST_PER_MTOK DOUBLE PRECISION DEFAULT 0.0 NOT NULL,
                    OUTPUT_COST_PER_MTOK DOUBLE PRECISION DEFAULT 0.0 NOT NULL,
                    CACHE_CREATION_COST_PER_MTOK DOUBLE PRECISION NULL,
                    CACHE_READ_COST_PER_MTOK DOUBLE PRECISION NULL,
                    CACHED_INPUT_DISCOUNT DOUBLE PRECISION NULL,
                    REASONING_OUTPUT_COST_PER_MTOK DOUBLE PRECISION NULL,
                    NOTES VARCHAR(500) NULL
                )
                """.trimIndent()
            )
        }
        val repo = ChannelRepository(db, SecretCipher("test-secret"))
        repo.initializeSchema()
        repo.createChannel(
            UpsertChannelRequest(
                name = "Legacy Pricing",
                protocol = "OPENAI_CHAT",
                baseUrl = "http://legacy",
                apiKey = "k",
                models = listOf(UpsertModelRequest(publicModelName = "gpt-legacy"))
            )
        )

        repo.absorbChannelPricingIntoStandalone()

        val pricing = repo.getPricing("gpt-legacy")
        assertNotNull(pricing)
        assertNull(pricing.variantKey)
    }

    @Test
    fun aliasOnlyGroupExposesAliasAndFallsBackToAvailableTargets() {
        val repo = newRepo()
        repo.createGroup(
            UpsertGroupRequest(
                groupId = "premium",
                name = "Premium",
                description = null,
                enabled = true,
                exposureMode = "ALIAS_ONLY",
                aliasRoutes = listOf()
            )
        )
        repo.createChannel(
            UpsertChannelRequest(
                name = "Fallback Only",
                protocol = "OPENAI_CHAT",
                baseUrl = "http://fallback",
                apiKey = "k",
                groupId = "premium",
                priority = 50,
                weight = 100,
                models = listOf(UpsertModelRequest(publicModelName = "claude-b"))
            )
        )
        repo.replaceGroupAliasesForAdmin(
            "premium",
            listOf(
                com.keel.samples.aigateway.airelay.config.UpsertGroupAliasRequest(
                    aliasName = "smart-claude",
                    targetModels = listOf("claude-a", "claude-b"),
                    enabled = true
                )
            )
        )

        val service = ConfigService(repo)
        service.reload()
        val chain = service.poolChainManager.snapshot().chains.single { it.chainId == "premium" }
        assertEquals(listOf("smart-claude"), chain.modelAliases)
        val candidates = service.poolChainManager.selectCandidates("premium", "smart-claude")
        assertTrue(candidates.isNotEmpty())
        assertEquals("claude-b", candidates.first().resolvedModel)
        assertEquals("claude-b", candidates.first().upstreamModel)
        val direct = runCatching { service.poolChainManager.selectCandidates("premium", "claude-b") }
        assertTrue(direct.isFailure, "direct model access should be blocked for alias-only groups")
    }

    @Test
    fun aliasTargetCanResolveByUpstreamModelName() {
        val repo = newRepo()
        repo.createGroup(
            UpsertGroupRequest(
                groupId = "premium",
                name = "Premium",
                description = null,
                enabled = true,
                exposureMode = "ALIASES_ONLY",
            )
        )
        repo.createChannel(
            UpsertChannelRequest(
                name = "Anthropic Primary",
                protocol = "ANTHROPIC_MESSAGES",
                baseUrl = "http://anthropic",
                apiKey = "k",
                groupId = "premium",
                priority = 100,
                weight = 100,
                models = listOf(
                    UpsertModelRequest(
                        publicModelName = "claude-prod",
                        upstreamModelName = "claude-sonnet-4-20250514"
                    )
                )
            )
        )
        repo.replaceGroupAliasesForAdmin(
            "premium",
            listOf(
                com.keel.samples.aigateway.airelay.config.UpsertGroupAliasRequest(
                    aliasName = "smart-claude",
                    targetModels = listOf("claude-sonnet-4-20250514"),
                    enabled = true
                )
            )
        )

        val service = ConfigService(repo)
        service.reload()
        val chain = service.poolChainManager.resolve("premium", "smart-claude")
        assertEquals(listOf("smart-claude"), chain.modelAliases)
        assertEquals(listOf("claude-sonnet-4-20250514"), chain.aliasRoutes.single().orderedTargets().map { it.model })
        assertEquals(listOf("claude-prod"), chain.levels.first().keys.first().supportedModels)
        assertEquals("claude-sonnet-4-20250514", chain.levels.first().keys.first().modelMap["claude-prod"])
        val candidates = service.poolChainManager.selectCandidates("premium", "smart-claude")
        assertTrue(candidates.isNotEmpty(), "alias should resolve even when target uses upstream model name")
        assertEquals("claude-sonnet-4-20250514", candidates.first().upstreamModel)
    }

    @Test
    fun toggleEnabledHotApplies() {
        val repo = newRepo()
        val ch = repo.createChannel(
            UpsertChannelRequest(
                name = "Anthropic", protocol = "ANTHROPIC_MESSAGES", baseUrl = "http://127.0.0.1:15721",
                apiKey = "k", enabled = true,
                models = listOf(UpsertModelRequest(publicModelName = "claude-only"))
            )
        )
        val service = ConfigService(repo)
        service.reload()
        assertEquals(1, service.poolChainManager.snapshot().chains.size)

        repo.setEnabled(ch.channelId, false)
        service.reload()
        assertEquals(0, service.poolChainManager.snapshot().chains.size, "disabling must remove from pool after reload")
    }

    @Test
    fun secretCipherRoundTrips() {
        val cipher = SecretCipher("master")
        val enc = cipher.encrypt("hello-world")
        assertNotNull(enc)
        assertTrue(enc != "hello-world")
        assertEquals("hello-world", cipher.decrypt(enc))
        assertEquals("", cipher.encrypt(""))
        assertEquals("", cipher.decrypt(""))
    }
}
