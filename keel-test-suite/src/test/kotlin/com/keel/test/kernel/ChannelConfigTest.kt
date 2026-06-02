package com.keel.test.kernel

import com.keel.db.database.DatabaseFactory
import com.keel.samples.aigateway.airelay.config.ChannelRepository
import com.keel.samples.aigateway.airelay.config.ConfigService
import com.keel.samples.aigateway.airelay.config.SecretCipher
import com.keel.samples.aigateway.airelay.config.UpsertChannelRequest
import com.keel.samples.aigateway.airelay.config.UpsertModelRequest
import kotlin.test.Test
import kotlin.test.assertEquals
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
        assertEquals(1, chains.size, "only the enabled channel should be in the pool")
        assertTrue(chains[0].modelAliases.contains("claude-sonnet-4-20250514"))
        assertTrue(service.pricings.any { it.model == "claude-sonnet-4-20250514" })
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
