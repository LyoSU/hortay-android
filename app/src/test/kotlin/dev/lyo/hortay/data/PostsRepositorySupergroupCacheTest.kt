package dev.lyo.hortay.data

import dev.lyo.hortay.testutil.PostsRepositoryTestHarness
import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.TdApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Covers the synchronous subscriber-count read path that drives
 * [ChannelHeaderBar]'s subtitle on channel entry. The user-visible bug this
 * test pins is "subscriber count appears with a delay after the title" — the
 * delay disappears as long as the synchronous read returns the count when
 * both [TdApi.UpdateNewChat] and [TdApi.UpdateSupergroup] have already landed
 * for the channel, which is the common case after the merged feed has
 * surfaced even one post from it.
 */
class PostsRepositorySupergroupCacheTest {

    @Test
    fun `channelSubscribersCached returns null when both updates missing`() = runTest {
        val harness = PostsRepositoryTestHarness(this)

        // Cold cache: nothing populated.
        assertNull(harness.repo.channelSubscribersCached(chatId = -1001L))
    }

    @Test
    fun `channelSubscribersCached returns null when only chat update landed`() = runTest {
        val harness = PostsRepositoryTestHarness(this)

        val chat = harness.fakeChannel(id = -1001L, title = "Test channel")
        harness.td.emitUpdate(TdApi.UpdateNewChat(chat))
        harness.advanceUntilIdle()
        // [ChatTypeSupergroup] in the harness seeds `supergroupId = 1L`. No
        // UpdateSupergroup has fired yet, so the supergroup mirror is empty.
        assertNotNull(harness.repo.chatCacheForTest(chat.id))
        assertNull(harness.repo.supergroupCacheForTest(supergroupId = 1L))

        assertNull(harness.repo.channelSubscribersCached(chatId = chat.id))
    }

    @Test
    fun `channelSubscribersCached returns count once both updates landed`() = runTest {
        val harness = PostsRepositoryTestHarness(this)

        val chat = harness.fakeChannel(id = -1001L, title = "Test channel")
        harness.td.emitUpdate(TdApi.UpdateNewChat(chat))
        val supergroup = TdApi.Supergroup().apply {
            this.id = 1L
            this.memberCount = 12_345
        }
        harness.td.emitUpdate(TdApi.UpdateSupergroup(supergroup))
        harness.advanceUntilIdle()

        assertEquals(12_345, harness.repo.channelSubscribersCached(chatId = chat.id))
    }

    @Test
    fun `channelSubscribersCached reflects live member-count update`() = runTest {
        val harness = PostsRepositoryTestHarness(this)

        val chat = harness.fakeChannel(id = -1001L, title = "Test channel")
        harness.td.emitUpdate(TdApi.UpdateNewChat(chat))
        harness.td.emitUpdate(
            TdApi.UpdateSupergroup(TdApi.Supergroup().apply { id = 1L; memberCount = 100 }),
        )
        harness.advanceUntilIdle()
        assertEquals(100, harness.repo.channelSubscribersCached(chatId = chat.id))

        // Live tick: TDLib re-emits UpdateSupergroup when memberCount changes.
        harness.td.emitUpdate(
            TdApi.UpdateSupergroup(TdApi.Supergroup().apply { id = 1L; memberCount = 250 }),
        )
        harness.advanceUntilIdle()
        assertEquals(250, harness.repo.channelSubscribersCached(chatId = chat.id))
    }

    @Test
    fun `channelSubscribersCached returns null for memberCount of zero`() = runTest {
        val harness = PostsRepositoryTestHarness(this)

        val chat = harness.fakeChannel(id = -1001L, title = "Test channel")
        harness.td.emitUpdate(TdApi.UpdateNewChat(chat))
        harness.td.emitUpdate(
            TdApi.UpdateSupergroup(TdApi.Supergroup().apply { id = 1L; memberCount = 0 }),
        )
        harness.advanceUntilIdle()

        // memberCount = 0 is TDLib's "unknown" sentinel; treat it like a miss
        // so the suspend [channelSubscribers] variant takes the RPC fallback
        // path instead of pinning the subtitle at "0 subscribers".
        assertNull(harness.repo.channelSubscribersCached(chatId = chat.id))
    }

    @Test
    fun `clear wipes supergroup cache so account-switch does not leak counts`() = runTest {
        val harness = PostsRepositoryTestHarness(this)

        val chat = harness.fakeChannel(id = -1001L, title = "Test channel")
        harness.td.emitUpdate(TdApi.UpdateNewChat(chat))
        harness.td.emitUpdate(
            TdApi.UpdateSupergroup(TdApi.Supergroup().apply { id = 1L; memberCount = 500 }),
        )
        harness.advanceUntilIdle()
        assertEquals(500, harness.repo.channelSubscribersCached(chatId = chat.id))

        harness.repo.clear()
        harness.advanceUntilIdle()

        // Both mirrors wiped: cached read fails closed so the next account's
        // channel doesn't paint the previous account's subscriber count.
        assertNull(harness.repo.supergroupCacheForTest(supergroupId = 1L))
        assertNull(harness.repo.channelSubscribersCached(chatId = chat.id))
    }
}
