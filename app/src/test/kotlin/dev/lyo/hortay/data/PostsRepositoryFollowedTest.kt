package dev.lyo.hortay.data

import dev.lyo.hortay.data.posts.FollowedRefresh
import dev.lyo.hortay.testutil.PostsRepositoryTestHarness
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.TdApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The feed side of "read without joining" in [dev.lyo.hortay.data.posts.PostsRepository]:
 * the subscription gate admits followed channels, the pre-sync bypass is narrowed so an
 * account with no joined channel doesn't leak preview rows, the one-page refresh, the
 * offline cold-start restore, and the local read-cursor paths (merge, view, mark all read).
 */
class PostsRepositoryFollowedTest {

    private val joined = -1001L
    private val followed = -2002L
    private val preview = -3003L

    private suspend fun PostsRepositoryTestHarness.seedChats() {
        td.emitUpdate(TdApi.UpdateNewChat(fakeChannel(joined, lastMessage = fakeChannelMessage(joined, 10L), mainListOrder = 5L)))
        td.emitUpdate(TdApi.UpdateNewChat(fakeChannel(followed, lastMessage = fakeChannelMessage(followed, 20L), unreadCount = 0)))
        td.emitUpdate(TdApi.UpdateNewChat(fakeChannel(preview, lastMessage = fakeChannelMessage(preview, 30L), unreadCount = 0)))
    }

    private suspend fun PostsRepositoryTestHarness.finishSync() {
        td.onAny("LoadChats") { TdApi.Error(404, "no more") }
        repo.triggerInitialSync()
        advanceUntilIdle()
    }

    private fun PostsRepositoryTestHarness.feedChats(): Set<Long> =
        repo.subscribedPosts.value.mapTo(HashSet()) { it.chatId }

    @Test
    fun `followed channels are admitted next to joined ones, previews are not`() = runTest {
        val h = PostsRepositoryTestHarness(this)
        h.followedIds.value = setOf(followed)
        h.seedChats()
        h.finishSync()

        assertEquals(setOf(joined, followed), h.feedChats())
        assertEquals(setOf(joined, followed, preview), h.repo.posts.value.mapTo(HashSet()) { it.chatId })
        assertTrue(followed !in h.repo.archivedChatIds.value, "a followed chat is never in the Archive scope")
    }

    @Test
    fun `an account with no joined channel does not leak preview rows after the sync`() = runTest {
        val h = PostsRepositoryTestHarness(this)
        h.td.emitUpdate(TdApi.UpdateNewChat(h.fakeChannel(preview, lastMessage = h.fakeChannelMessage(preview, 30L))))
        h.advanceUntilIdle()
        assertEquals(setOf(preview), h.feedChats(), "before the sync the bootstrap bypass shows everything")

        h.finishSync()

        assertEquals(emptySet<Long>(), h.feedChats())
    }

    @Test
    fun `followed ids arriving before the chat lists do not hide joined channels at launch`() = runTest {
        val h = PostsRepositoryTestHarness(this)
        h.followedIds.value = setOf(followed)
        // First auth: the joined channel's positions are still empty, so main ids are too.
        h.td.emitUpdate(TdApi.UpdateNewChat(h.fakeChannel(joined, lastMessage = h.fakeChannelMessage(joined, 10L))))
        h.advanceUntilIdle()

        assertTrue(joined in h.feedChats(), "the pre-sync bypass must not end because followed ids are known")
    }

    @Test
    fun `refreshFollowedChannel sends one page, coalesces albums offline and posts no snackbar`() = runTest {
        val h = PostsRepositoryTestHarness(this)
        h.followedIds.value = setOf(followed)
        h.seedChats()
        h.finishSync()
        val messages = mutableListOf<UserMessageBus.UserMessage>()
        val collector = backgroundScope.launch { h.userMessages.messages.collect { messages += it } }
        h.td.onAny("GetMessageLocally") { TdApi.Error(404, "not found") }
        h.td.onAny("GetChatHistory") { req ->
            val q = req as TdApi.GetChatHistory
            assertEquals(false, q.onlyLocal)
            assertEquals(0L, q.fromMessageId)
            TdApi.Messages(2, arrayOf(
                h.fakeChannelMessage(followed, 25L, date = 1_700_000_100, mediaAlbumId = 77L),
                h.fakeChannelMessage(followed, 24L, date = 1_700_000_050),
            ))
        }

        val result = h.repo.refreshFollowedChannel(followed, sinceMessageId = 20L)
        h.advanceUntilIdle()

        assertEquals(FollowedRefresh.NewPosts(25L), result)
        assertEquals(1, h.td.rpcCount("GetChatHistory"), "exactly one networked page, never a walk or a networked coalesce")
        assertTrue(h.td.rpcCount("GetMessageLocally") > 0, "the album fragment is completed offline")
        assertTrue(h.repo.subscribedPosts.value.any { it.chatId == followed && it.id == 24L })
        assertEquals(emptyList<UserMessageBus.UserMessage>(), messages)

        // Same page again: nothing newer than what the feed holds.
        assertEquals(FollowedRefresh.NoChange, h.repo.refreshFollowedChannel(followed, sinceMessageId = 25L))
        collector.cancel()
    }

    @Test
    fun `refreshFollowedChannel classifies errors without surfacing them`() = runTest {
        val h = PostsRepositoryTestHarness(this)
        h.seedChats()
        h.advanceUntilIdle()
        val messages = mutableListOf<UserMessageBus.UserMessage>()
        val collector = backgroundScope.launch { h.userMessages.messages.collect { messages += it } }

        h.td.onNext { TdApi.Error(400, "CHANNEL_PRIVATE") }
        assertEquals(FollowedRefresh.Unavailable, h.repo.refreshFollowedChannel(followed))
        h.td.onNext { TdApi.Error(420, "FLOOD_WAIT_30") }
        assertEquals(FollowedRefresh.FloodWait, h.repo.refreshFollowedChannel(followed))
        h.td.onNext { TdApi.Error(500, "internal") }
        assertEquals(FollowedRefresh.Failed, h.repo.refreshFollowedChannel(followed))

        h.advanceUntilIdle()
        assertEquals(emptyList<UserMessageBus.UserMessage>(), messages)
        collector.cancel()
    }

    @Test
    fun `a logout during the fetch drops the page`() = runTest {
        val h = PostsRepositoryTestHarness(this)
        h.followedIds.value = setOf(followed)
        h.seedChats()
        h.advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        h.td.onAnySuspending("GetChatHistory") {
            gate.await()
            TdApi.Messages(1, arrayOf(h.fakeChannelMessage(followed, 99L)))
        }

        val pending = async { h.repo.refreshFollowedChannel(followed) }
        h.runCurrent()
        h.repo.clear()
        gate.complete(Unit)

        assertEquals(FollowedRefresh.Failed, pending.await())
        assertTrue(h.repo.posts.value.none { it.id == 99L }, "account A's page must not land after the wipe")
    }

    @Test
    fun `restoreFollowedLocally reads the last seen message offline`() = runTest {
        val h = PostsRepositoryTestHarness(this)
        h.followedIds.value = setOf(followed)
        h.td.emitUpdate(TdApi.UpdateNewChat(h.fakeChannel(followed, unreadCount = 0)))
        h.advanceUntilIdle()
        h.td.onAny("GetMessageLocally") { req ->
            val q = req as TdApi.GetMessageLocally
            if (q.messageId == 50L) h.fakeChannelMessage(followed, 50L) else TdApi.Error(404, "not found")
        }

        h.repo.restoreFollowedLocally(mapOf(followed to 50L))
        h.advanceUntilIdle()

        assertTrue(h.repo.subscribedPosts.value.any { it.chatId == followed && it.id == 50L })
        assertEquals(0, h.td.rpcCount("GetChatHistory"))
    }

    @Test
    fun `followed read cursors are max-merged into the cursor map`() = runTest {
        val h = PostsRepositoryTestHarness(this)
        h.followedCursors.value = mapOf(followed to 40L)
        h.advanceUntilIdle()
        assertEquals(40L, h.repo.chatReadCursors.value[followed])

        h.followedCursors.value = mapOf(followed to 30L)
        h.advanceUntilIdle()
        assertEquals(40L, h.repo.chatReadCursors.value[followed], "the cursor never moves back")
    }

    @Test
    fun `viewMessages in a followed channel advances the local cursor and still reports views`() = runTest {
        val h = PostsRepositoryTestHarness(this)
        h.followedIds.value = setOf(followed)
        h.td.onAny("ViewMessages") { TdApi.Ok() }

        h.repo.viewMessages(followed, listOf(21L, 23L))
        h.repo.viewMessages(joined, listOf(11L))

        assertEquals(listOf(followed to 23L), h.followedReads.toList(), "only the followed chat reaches the store")
        assertEquals(2, h.td.rpcCount("ViewMessages"))
    }

    @Test
    fun `mark all read moves a followed channel's local cursor without a server call`() = runTest {
        val h = PostsRepositoryTestHarness(this)
        h.followedIds.value = setOf(followed)
        h.seedChats()
        h.finishSync()
        h.td.onAny("GetMessageLocally") { TdApi.Error(404, "not found") }
        h.td.onAny("GetChatHistory") {
            TdApi.Messages(1, arrayOf(h.fakeChannelMessage(followed, 26L)))
        }
        h.repo.refreshFollowedChannel(followed)

        val acked = h.repo.markChannelsRead(listOf(followed))

        assertEquals(1, acked)
        assertEquals(26L, h.repo.chatReadCursors.value[followed])
        assertEquals(listOf(followed to 26L), h.followedReads.toList())
        assertEquals(0, h.td.rpcCount("ViewMessages"))
        assertEquals(setOf(joined, followed), h.repo.feedChannelIds())
        assertEquals(setOf(joined), h.repo.subscribedChatIds(), "export / recommendations stay joined-only")
    }

    private fun supergroup(member: Boolean) = TdApi.Supergroup().apply {
        id = 1L
        isChannel = true
        status = if (member) TdApi.ChatMemberStatusMember(0) else TdApi.ChatMemberStatusLeft()
    }

    @Test
    fun `a chat lost from TDLib's store is rehydrated by its handle and the retry works`() = runTest {
        val h = PostsRepositoryTestHarness(this)
        h.followedIds.value = setOf(followed)
        // No UpdateNewChat and no local copy: what a cache clear leaves behind.
        h.td.onAny("GetChat") { TdApi.Error(400, "Chat not found") }
        h.td.onAny("GetMessageLocally") { TdApi.Error(404, "not found") }
        h.td.onAny("GetChatHistory") { TdApi.Messages(1, arrayOf(h.fakeChannelMessage(followed, 40L))) }
        assertEquals(FollowedRefresh.Unavailable, h.repo.refreshFollowedChannel(followed))

        h.td.onAny("SearchPublicChat") { h.fakeChannel(followed, unreadCount = 0) }
        h.td.onAny("GetSupergroup") { supergroup(member = false) }
        assertTrue(h.repo.rehydrateFollowedChannel(followed, "fchan"))

        assertEquals(FollowedRefresh.NewPosts(40L), h.repo.refreshFollowedChannel(followed))
    }

    @Test
    fun `a handle now pointing at another channel, or a joined one, is not rehydrated`() = runTest {
        val h = PostsRepositoryTestHarness(this)
        h.td.onAny("GetSupergroup") { supergroup(member = false) }

        h.td.onNext { h.fakeChannel(preview, unreadCount = 0) }
        assertEquals(false, h.repo.rehydrateFollowedChannel(followed, "fchan"), "different chat id")

        h.td.onAny("GetSupergroup") { supergroup(member = true) }
        h.td.onNext { h.fakeChannel(followed, unreadCount = 0) }
        assertEquals(false, h.repo.rehydrateFollowedChannel(followed, "fchan"), "now a member")

        h.td.onNext { TdApi.Error(400, "USERNAME_NOT_OCCUPIED") }
        assertEquals(false, h.repo.rehydrateFollowedChannel(followed, "fchan"), "handle gone")
    }
}
