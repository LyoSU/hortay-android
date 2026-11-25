package dev.lyo.hortay.data

import dev.lyo.hortay.testutil.PostsRepositoryTestHarness
import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.TdApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Covers [ChannelDirectory]'s synchronous subscriber-count read path that drives
 * [ChannelHeaderBar]'s subtitle on channel entry. The user-visible bug this
 * test pins is "subscriber count appears with a delay after the title" — the
 * delay disappears as long as the synchronous read returns the count when
 * both [TdApi.UpdateNewChat] and [TdApi.UpdateSupergroup] have already landed
 * for the channel, which is the common case after the merged feed has
 * surfaced even one post from it.
 */
class ChannelDirectoryTest {

    @Test
    fun `channelSubscribersCached returns null when both updates missing`() = runTest {
        val harness = PostsRepositoryTestHarness(this)

        // Cold cache: nothing populated.
        assertNull(ChannelDirectory(harness.td, harness.registry).channelSubscribersCached(chatId = -1001L))
    }

    @Test
    fun `channelSubscribersCached returns null when only chat update landed`() = runTest {
        val harness = PostsRepositoryTestHarness(this)

        val chat = harness.fakeChannel(id = -1001L, title = "Test channel")
        harness.td.emitUpdate(TdApi.UpdateNewChat(chat))
        harness.advanceUntilIdle()
        // [ChatTypeSupergroup] in the harness seeds `supergroupId = 1L`. No
        // UpdateSupergroup has fired yet, so the supergroup mirror is empty.
        assertNotNull(harness.registry.cachedChat(chat.id))
        assertNull(harness.registry.cachedSupergroup(1L))

        assertNull(ChannelDirectory(harness.td, harness.registry).channelSubscribersCached(chatId = chat.id))
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

        assertEquals(12_345, ChannelDirectory(harness.td, harness.registry).channelSubscribersCached(chatId = chat.id))
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
        assertEquals(100, ChannelDirectory(harness.td, harness.registry).channelSubscribersCached(chatId = chat.id))

        // Live tick: TDLib re-emits UpdateSupergroup when memberCount changes.
        harness.td.emitUpdate(
            TdApi.UpdateSupergroup(TdApi.Supergroup().apply { id = 1L; memberCount = 250 }),
        )
        harness.advanceUntilIdle()
        assertEquals(250, ChannelDirectory(harness.td, harness.registry).channelSubscribersCached(chatId = chat.id))
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
        assertNull(ChannelDirectory(harness.td, harness.registry).channelSubscribersCached(chatId = chat.id))
    }

    @Test
    fun `session end wipes the mirrors so account-switch does not leak counts`() = runTest {
        val harness = PostsRepositoryTestHarness(this)

        val chat = harness.fakeChannel(id = -1001L, title = "Test channel")
        harness.td.emitUpdate(TdApi.UpdateNewChat(chat))
        harness.td.emitUpdate(
            TdApi.UpdateSupergroup(TdApi.Supergroup().apply { id = 1L; memberCount = 500 }),
        )
        harness.advanceUntilIdle()
        assertEquals(500, ChannelDirectory(harness.td, harness.registry).channelSubscribersCached(chatId = chat.id))

        // Logout runs both as AccountSession hooks (see AppGraph).
        harness.repo.clear()
        harness.registry.clear()
        harness.advanceUntilIdle()

        // Both mirrors wiped: cached read fails closed so the next account's
        // channel doesn't paint the previous account's subscriber count.
        assertNull(harness.registry.cachedSupergroup(1L))
        assertNull(ChannelDirectory(harness.td, harness.registry).channelSubscribersCached(chatId = chat.id))
    }

    @Test
    fun `channel rename evicts the cached chat so the next read is fresh`() = runTest {
        val harness = PostsRepositoryTestHarness(this)

        val chat = harness.fakeChannel(id = -1001L, title = "Old")
        harness.td.emitUpdate(TdApi.UpdateNewChat(chat))
        harness.advanceUntilIdle()
        assertNotNull(harness.registry.cachedChat(chat.id))

        harness.td.emitUpdate(TdApi.UpdateChatTitle(chat.id, "New"))
        harness.advanceUntilIdle()

        assertNull(harness.registry.cachedChat(chat.id))
    }

    @Test
    fun `resolveChatKind classifies channels, users and misses`() = runTest {
        val td = FakeTdSender()
        td.onAny("GetChat") { req ->
            when ((req as TdApi.GetChat).chatId) {
                -100L -> TdApi.Chat().apply { id = -100L; type = TdApi.ChatTypeSupergroup(1L, true) }
                42L -> TdApi.Chat().apply { id = 42L; type = TdApi.ChatTypePrivate(42L) }
                else -> TdApi.Error(400, "CHAT_NOT_FOUND")
            }
        }
        val directory = ChannelDirectory(td, ChatRegistry(td))

        assertEquals(PublicHandleResult.Channel(-100L), directory.resolveChatKind(-100L))
        assertEquals(PublicHandleResult.User(42L), directory.resolveChatKind(42L))
        assertEquals(PublicHandleResult.NotFound, directory.resolveChatKind(7L))
    }

    // ---------- lookupPublicChat classification ----------

    private suspend fun lookupWith(answer: (TdApi.Function<*>) -> TdApi.Object): PublicChatLookup {
        val td = FakeTdSender().apply { onAny("SearchPublicChat", answer) }
        // lookupPublicChat never reads the registry; an unbound one is enough.
        return ChannelDirectory(td, ChatRegistry(td)).lookupPublicChat("@durov")
    }

    @Test
    fun `lookupPublicChat returns Found with the chat id`() = runTest {
        assertEquals(
            PublicChatLookup.Found(-1001L),
            lookupWith { TdApi.Chat().apply { id = -1001L } },
        )
    }

    @Test
    fun `lookupPublicChat maps a 400 username error to NotFound`() = runTest {
        assertEquals(PublicChatLookup.NotFound, lookupWith { TdApi.Error(400, "USERNAME_NOT_OCCUPIED") })
    }

    @Test
    fun `lookupPublicChat maps flood wait and timeout to Failed`() = runTest {
        assertEquals(PublicChatLookup.Failed, lookupWith { TdApi.Error(420, "FLOOD_WAIT_30") })
        assertEquals(PublicChatLookup.Failed, lookupWith { TdApi.Error(429, "Too Many Requests: retry after 30") })
        assertEquals(
            PublicChatLookup.Failed,
            lookupWith { TdApi.Error(TdClient.SEND_TIMEOUT_CODE, "timeout") },
        )
    }

    @Test
    fun `lookupPublicChat maps a non-TDLib failure to Failed`() = runTest {
        assertEquals(PublicChatLookup.Failed, lookupWith { throw java.io.IOException("offline") })
    }
}
