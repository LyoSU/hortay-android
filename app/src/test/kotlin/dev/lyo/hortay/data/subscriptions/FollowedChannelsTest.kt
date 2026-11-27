package dev.lyo.hortay.data.subscriptions

import dev.lyo.hortay.data.isUnreadAt
import dev.lyo.hortay.testutil.PostsRepositoryTestHarness
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.TdApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [FollowedChannels]: eligibility (public channel, not a member), the seed entering the feed
 * as read, undo keeping the old cursor, the join hand-off pushing the local cursor to
 * Telegram BEFORE the entry is dropped, and title upkeep.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FollowedChannelsTest {

    private val chatId = -4004L

    private class Rig(scope: TestScope) {
        val store = FollowedChannelsStore(FollowedChannelsStoreTest.FakeDisk(), scope.backgroundScope)
        val harness = PostsRepositoryTestHarness(scope, onFollowedRead = store::advanceReadCursor)
        val followed = FollowedChannels(harness.td, store, harness.repo, nowMs = { 123L })

        init {
            // The production wiring hands the repository the store's flows directly; the
            // harness takes mutable ones, so mirror them.
            scope.backgroundScope.launch { store.ids.collect { harness.followedIds.value = it } }
            scope.backgroundScope.launch { store.readCursors.collect { harness.followedCursors.value = it } }
        }
    }

    private fun supergroup(member: Boolean, username: String? = "fchan") = TdApi.Supergroup().apply {
        id = 4004L
        isChannel = true
        status = if (member) TdApi.ChatMemberStatusMember(0) else TdApi.ChatMemberStatusLeft()
        usernames = username?.let { TdApi.Usernames(arrayOf(it), emptyArray(), it, emptyArray()) }
    }

    private suspend fun Rig.knownChannel(member: Boolean = false, username: String? = "fchan") {
        harness.td.emitUpdate(TdApi.UpdateNewChat(harness.fakeChannel(chatId, title = "Followed", unreadCount = 0)))
        harness.td.onAny("GetSupergroup") { supergroup(member, username) }
        harness.td.onAny("GetMessageLocally") { TdApi.Error(404, "not found") }
        harness.advanceUntilIdle()
    }

    private fun Rig.history(vararg ids: Long) {
        harness.td.onAny("GetChatHistory") {
            TdApi.Messages(ids.size, ids.map { harness.fakeChannelMessage(chatId, it) }.toTypedArray())
        }
    }

    @Test
    fun `follow seeds one page and the seeded history enters as read`() = runTest {
        val rig = Rig(this)
        rig.knownChannel()
        rig.history(7L, 6L, 5L)

        val result = rig.followed.follow(chatId)
        rig.harness.advanceUntilIdle()

        assertEquals(FollowedChannels.FollowResult.Followed, result)
        val entry = rig.store.channels.value.getValue(chatId)
        assertEquals(7L, entry.readCursor)
        assertEquals(7L, entry.lastSeenMessageId)
        assertEquals("fchan", entry.username)
        assertEquals(123L, entry.addedAtMs)
        assertEquals(1, rig.harness.td.rpcCount("GetChatHistory"))
        val cursor = rig.harness.repo.chatReadCursors.value[chatId]
        val posts = rig.harness.repo.subscribedPosts.value.filter { it.chatId == chatId }
        assertEquals(3, posts.size)
        assertTrue(posts.none { it.isUnreadAt(cursor) }, "seeded history must not show as unread")
        assertEquals(0, rig.harness.td.rpcCount("JoinChat"))
    }

    @Test
    fun `members, private channels and duplicates are refused`() = runTest {
        val rig = Rig(this)
        rig.knownChannel(member = true)
        assertEquals(FollowedChannels.FollowResult.AlreadyMember, rig.followed.follow(chatId))

        rig.harness.td.onAny("GetSupergroup") { supergroup(member = false, username = null) }
        assertEquals(FollowedChannels.FollowResult.NotPublicChannel, rig.followed.follow(chatId))
        assertEquals(0, rig.harness.td.rpcCount("GetChatHistory"))

        rig.harness.td.onAny("GetSupergroup") { supergroup(member = false) }
        rig.history(1L)
        assertEquals(FollowedChannels.FollowResult.Followed, rig.followed.follow(chatId))
        assertEquals(FollowedChannels.FollowResult.AlreadyFollowed, rig.followed.follow(chatId))
    }

    @Test
    fun `undo keeps the old read cursor so newer posts stay unread`() = runTest {
        val rig = Rig(this)
        rig.knownChannel()
        rig.history(7L, 6L)
        rig.followed.follow(chatId)
        val removed = rig.followed.unfollow(chatId)
        assertTrue(chatId !in rig.store.ids.value)

        rig.history(9L, 8L, 7L)
        rig.followed.follow(chatId, restoring = removed)
        rig.harness.advanceUntilIdle()

        val entry = rig.store.channels.value.getValue(chatId)
        assertEquals(7L, entry.readCursor)
        assertEquals(9L, entry.lastSeenMessageId)
    }

    @Test
    fun `joining hands the local cursor to Telegram before dropping the entry`() = runTest {
        val rig = Rig(this)
        rig.knownChannel()
        rig.history(7L, 6L)
        rig.followed.follow(chatId)
        rig.store.advanceReadCursor(chatId, 7L)
        val acks = mutableListOf<Triple<List<Long>, Boolean, Boolean>>()
        rig.harness.td.onAny("ViewMessages") { req ->
            val q = req as TdApi.ViewMessages
            acks += Triple(q.messageIds.toList(), q.forceRead, chatId in rig.store.ids.value)
            TdApi.Ok()
        }
        rig.followed.start(backgroundScope)
        runCurrent()

        rig.harness.td.emitUpdate(TdApi.UpdateChatAddedToList(chatId, TdApi.ChatListMain()))
        runCurrent()

        assertEquals(listOf(Triple(listOf(7L), true, true)), acks, "pushed with force_read while still followed")
        assertTrue(chatId !in rig.store.ids.value, "then handed off")
        assertEquals(setOf(chatId), rig.harness.repo.feedChannelIds(), "the channel stays in the feed as joined")
    }

    @Test
    fun `a rename reaches the stored title`() = runTest {
        val rig = Rig(this)
        rig.knownChannel()
        rig.history(1L)
        rig.followed.follow(chatId)
        rig.followed.start(backgroundScope)
        runCurrent()

        rig.harness.td.emitUpdate(TdApi.UpdateChatTitle(chatId, "Renamed"))
        runCurrent()

        assertEquals("Renamed", rig.store.channels.value.getValue(chatId).title)
    }
}
