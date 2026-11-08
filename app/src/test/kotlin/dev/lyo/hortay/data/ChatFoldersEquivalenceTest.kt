package dev.lyo.hortay.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.drinkless.tdlib.TdApi
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChatFoldersEquivalenceTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val repo = ChatFoldersRepository(FakeTdSender(), scope)

    @AfterEach
    fun tearDown() = scope.cancel()

    private fun allChannels(excludeArchived: Boolean) = TdApi.ChatFolder().apply {
        includeChannels = true
        pinnedChatIds = LongArray(0)
        includedChatIds = LongArray(0)
        excludedChatIds = LongArray(0)
        this.excludeArchived = excludeArchived
    }

    @Test
    fun `with archive kept out of All, a folder that includes the archive is a superset and stays`() {
        assertFalse(repo.isEquivalentToAll(allChannels(excludeArchived = false), archiveInAllFeed = false))
        assertTrue(repo.isEquivalentToAll(allChannels(excludeArchived = true), archiveInAllFeed = false))
    }

    @Test
    fun `with archive merged into All, the folder that excludes it is the narrower one`() {
        assertTrue(repo.isEquivalentToAll(allChannels(excludeArchived = false), archiveInAllFeed = true))
        assertFalse(repo.isEquivalentToAll(allChannels(excludeArchived = true), archiveInAllFeed = true))
    }
}
