package com.keel.samples.aigateway.airelay.config

import com.keel.db.database.DatabaseFactory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ChannelRepositoryGroupTest {

    private companion object {
        private var counter = 0
    }

    private lateinit var factory: DatabaseFactory
    private lateinit var repository: ChannelRepository

    @BeforeTest
    fun setUp() {
        factory = DatabaseFactory.h2Memory(name = "channel_repo_test_${counter++}", poolSize = 1)
        repository = ChannelRepository(factory.init(), SecretCipher("test-secret-0123456789"))
        repository.initializeSchema()
    }

    @AfterTest
    fun tearDown() {
        factory.close()
    }

    @Test
    fun defaultGroupCannotBeDeleted() {
        assertEquals(GroupDeleteResult.DEFAULT_PROTECTED, repository.deleteGroupDetailed("default"))
    }

    @Test
    fun unknownGroupReportsNotFound() {
        assertEquals(GroupDeleteResult.NOT_FOUND, repository.deleteGroupDetailed("does-not-exist"))
    }

    @Test
    fun emptyNonDefaultGroupCanBeDeleted() {
        repository.createGroup(UpsertGroupRequest(groupId = "premium", name = "Premium"))
        assertEquals(GroupDeleteResult.DELETED, repository.deleteGroupDetailed("premium"))
        assertEquals(GroupDeleteResult.NOT_FOUND, repository.deleteGroupDetailed("premium"))
    }

    @Test
    fun groupWithChannelsReportsHasChannels() {
        repository.createGroup(UpsertGroupRequest(groupId = "premium", name = "Premium"))
        val channel = repository.createChannel(
            UpsertChannelRequest(
                name = "OpenAI Main",
                protocol = "openai",
                baseUrl = "https://api.openai.com",
                apiKey = "sk-test",
                groupId = "premium",
            )
        )
        repository.attachChannelToGroup(channel.channelId, "premium", priority = 0, weight = 100, enabled = true)

        assertEquals(GroupDeleteResult.HAS_CHANNELS, repository.deleteGroupDetailed("premium"))
    }

    @Test
    fun aliasRoutesPersistAndRenderThroughCanonicalSource() {
        repository.createGroup(UpsertGroupRequest(groupId = "premium", name = "Premium"))
        repository.replaceAliasesForGroupFromAdmin(
            "premium",
            listOf(
                UpsertGroupAliasRequest(
                    aliasName = "smart-claude",
                    targetModels = listOf("claude-sonnet-4-6"),
                )
            )
        )

        val viaList = repository.listGroupAliases("premium")
        assertEquals(1, viaList.size)
        assertEquals("smart-claude", viaList.single().aliasName)

        val group = repository.listGroups().single { it.groupId == "premium" }
        assertTrue(group.aliasRoutes.any { it.aliasName == "smart-claude" }, "GroupView should expose saved alias routes")
    }
}
