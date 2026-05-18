package dev.lyo.hortay.data

import dev.lyo.hortay.testutil.PostsRepositoryTestHarness
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.TdApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PostsRepositoryRefreshTest {

    @Test
    fun `refresh issues zero GetChat fan-out calls`() = runTest {
        val harness = PostsRepositoryTestHarness(this)
        repeat(3) { i ->
            val chat = harness.fakeChannel(
                id = -1000L - i,
                lastMessage = harness.fakeChannelMessage(-1000L - i, 100L + i),
            )
            harness.td.emitUpdate(TdApi.UpdateNewChat(chat))
        }
        harness.advanceUntilIdle()

        harness.td.onAny("LoadChats") { TdApi.Error(404, "no more") }
        harness.td.onAny("GetChats") {
            TdApi.Chats(3, longArrayOf(-1000L, -1001L, -1002L))
        }

        harness.repo.refresh()
        harness.advanceUntilIdle()

        assertEquals(0, harness.td.rpcCount("GetChat"),
            "refresh must not fan out GetChat per chat — chatCache is populated by UpdateNewChat")
    }

    @Test
    fun `refresh issues zero GetChatHistory calls when no lastMessage is an album member`() = runTest {
        val harness = PostsRepositoryTestHarness(this)
        repeat(3) { i ->
            val chat = harness.fakeChannel(
                id = -2000L - i,
                lastMessage = harness.fakeChannelMessage(-2000L - i, 200L + i, mediaAlbumId = 0L),
            )
            harness.td.emitUpdate(TdApi.UpdateNewChat(chat))
        }
        harness.advanceUntilIdle()
        harness.td.onAny("LoadChats") { TdApi.Error(404, "no more") }
        harness.td.onAny("GetChats") { TdApi.Chats(3, longArrayOf(-2000L, -2001L, -2002L)) }

        harness.repo.refresh()
        harness.advanceUntilIdle()

        assertEquals(0, harness.td.rpcCount("GetChatHistory"),
            "no album lastMessages → no album coalesce → zero GetChatHistory calls")
    }

    @Test
    fun `refresh does not drain ChatListArchive`() = runTest {
        val harness = PostsRepositoryTestHarness(this)
        val seenLists = mutableListOf<String>()
        harness.td.onAny("LoadChats") { fn ->
            val req = fn as TdApi.LoadChats
            seenLists.add(req.chatList::class.simpleName ?: "?")
            TdApi.Error(404, "no more")
        }
        harness.td.onAny("GetChats") { TdApi.Chats(0, longArrayOf()) }

        harness.repo.refresh()
        harness.advanceUntilIdle()

        assertTrue(seenLists.none { it == "ChatListArchive" },
            "cold-start refresh must skip the archive list entirely; seen=$seenLists")
        assertTrue(seenLists.any { it == "ChatListMain" },
            "cold-start refresh must still drain ChatListMain")
    }

    @Test
    fun `refresh ingests lastMessage from chatCache for every channel chat`() = runTest {
        val harness = PostsRepositoryTestHarness(this)
        val ids = listOf(-3000L, -3001L, -3002L)
        ids.forEachIndexed { i, id ->
            val chat = harness.fakeChannel(
                id = id,
                lastMessage = harness.fakeChannelMessage(id, 300L + i, date = 1_700_000_000 + i),
            )
            harness.td.emitUpdate(TdApi.UpdateNewChat(chat))
        }
        harness.advanceUntilIdle()
        harness.td.onAny("LoadChats") { TdApi.Error(404, "no more") }
        harness.td.onAny("GetChats") { TdApi.Chats(ids.size, ids.toLongArray()) }

        harness.repo.refresh()
        harness.advanceUntilIdle()

        val postIds = harness.repo.posts.value.map { it.id }.toSet()
        assertEquals(setOf(300L, 301L, 302L), postIds,
            "every channel with a non-null lastMessage must contribute one post")
    }

    @Test
    fun `refresh brings every channel into the feed regardless of read state`() = runTest {
        // Cold-start harvest is *complete*: every channel chat with a non-null
        // lastMessage contributes a post, including caught-up chats
        // (`unreadCount == 0`). The previous version skipped caught-up to keep
        // ancient lastMessages out of the OldestUnreadFirst landing pick —
        // that protection moved upstack into [continueReadingIndex]'s recency
        // floor, which keeps dormant unread out of the landing without
        // emptying the feed of read context. The Newest mode in particular
        // DEPENDS on this completeness: a freshly-read channel's lastMessage
        // is part of "what's recent" for the user, not noise.
        val harness = PostsRepositoryTestHarness(this)
        val caughtUp = harness.fakeChannel(
            id = -7000L,
            lastMessage = harness.fakeChannelMessage(-7000L, 700L),
            unreadCount = 0,
        )
        val active = harness.fakeChannel(
            id = -7001L,
            lastMessage = harness.fakeChannelMessage(-7001L, 701L),
            unreadCount = 3,
        )
        harness.td.emitUpdate(TdApi.UpdateNewChat(caughtUp))
        harness.td.emitUpdate(TdApi.UpdateNewChat(active))
        harness.advanceUntilIdle()
        harness.td.onAny("LoadChats") { TdApi.Error(404, "no more") }
        harness.td.onAny("GetChats") { TdApi.Chats(2, longArrayOf(-7000L, -7001L)) }

        harness.repo.refresh()
        harness.advanceUntilIdle()

        val postIds = harness.repo.posts.value.map { it.id }.toSet()
        assertEquals(setOf(700L, 701L), postIds,
            "both caught-up and active channels contribute their lastMessage")
    }

    @Test
    fun `UpdateNewChat seeds cursor even when lastReadInboxMessageId is zero`() = runTest {
        // Never-read channels (lastReadInboxMessageId == 0) MUST land in the
        // cursor map — otherwise [isUnreadIn] falls into the
        // `cursors[chatId] == null → read` branch and every post of the
        // channel silently loses the unread strip. Was visible as a freshly-
        // joined / never-opened channel whose posts never qualified as
        // unread even though no read ack ever crossed them.
        val harness = PostsRepositoryTestHarness(this)
        val neverRead = harness.fakeChannel(
            id = -8000L,
            lastMessage = harness.fakeChannelMessage(-8000L, 800L),
            unreadCount = 5,
            lastReadInboxMessageId = 0L,
        )
        harness.td.emitUpdate(TdApi.UpdateNewChat(neverRead))
        harness.advanceUntilIdle()

        val cursors = harness.repo.chatReadCursors.value
        assertTrue(-8000L in cursors,
            "never-read channel must be present in the cursor map with the zero sentinel")
        assertEquals(0L, cursors[-8000L])
    }

    @Test
    fun `UpdateNewChat does not roll cursor backwards`() = runTest {
        // Monotonic clamp: a stale UpdateNewChat arriving after a fresh
        // UpdateChatReadInbox (or a higher prior seed) must NOT downgrade
        // the cursor, else already-read posts re-appear as unread.
        val harness = PostsRepositoryTestHarness(this)
        val freshlyRead = harness.fakeChannel(
            id = -8001L,
            lastMessage = harness.fakeChannelMessage(-8001L, 810L),
            unreadCount = 0,
            lastReadInboxMessageId = 810L,
        )
        val staleResend = harness.fakeChannel(
            id = -8001L,
            lastMessage = harness.fakeChannelMessage(-8001L, 810L),
            unreadCount = 5,
            lastReadInboxMessageId = 800L, // older than the previous seed
        )
        harness.td.emitUpdate(TdApi.UpdateNewChat(freshlyRead))
        harness.td.emitUpdate(TdApi.UpdateNewChat(staleResend))
        harness.advanceUntilIdle()

        assertEquals(810L, harness.repo.chatReadCursors.value[-8001L],
            "monotonic clamp must preserve the higher cursor")
    }

    @Test
    fun `refresh skips chats whose lastMessage is null and does not crash`() = runTest {
        val harness = PostsRepositoryTestHarness(this)
        val emptyChannel = harness.fakeChannel(id = -4000L, lastMessage = null)
        val activeChannel = harness.fakeChannel(
            id = -4001L,
            lastMessage = harness.fakeChannelMessage(-4001L, 400L),
        )
        harness.td.emitUpdate(TdApi.UpdateNewChat(emptyChannel))
        harness.td.emitUpdate(TdApi.UpdateNewChat(activeChannel))
        harness.advanceUntilIdle()
        harness.td.onAny("LoadChats") { TdApi.Error(404, "no more") }
        harness.td.onAny("GetChats") { TdApi.Chats(2, longArrayOf(-4000L, -4001L)) }

        harness.repo.refresh()
        harness.advanceUntilIdle()

        assertEquals(1, harness.repo.posts.value.size,
            "only the channel with a lastMessage contributes")
        assertEquals(400L, harness.repo.posts.value[0].id)
    }

    @Test
    fun `refresh tolerates UpdateNewChat arriving AFTER GetChats returns its id list`() = runTest {
        val harness = PostsRepositoryTestHarness(this)
        // No UpdateNewChat emitted yet — chatCache is empty.
        harness.td.onAny("LoadChats") { TdApi.Error(404, "no more") }
        harness.td.onAny("GetChats") { TdApi.Chats(1, longArrayOf(-5000L)) }

        // Schedule a late UpdateNewChat: arrives 100 ms into the suspend-until-or-timeout
        // poll window. The 2 s timeout in refreshLocked must absorb this without dropping
        // the chat.
        val lateMsg = harness.fakeChannelMessage(-5000L, 500L)
        launch {
            kotlinx.coroutines.delay(100)
            val chat = harness.fakeChannel(id = -5000L, lastMessage = lateMsg)
            harness.td.emitUpdate(TdApi.UpdateNewChat(chat))
        }

        harness.repo.refresh()
        harness.advanceUntilIdle()

        assertEquals(1, harness.repo.posts.value.size)
        assertEquals(500L, harness.repo.posts.value[0].id)
    }

    @Test
    fun `refresh issues exactly one GetChatHistory per album lastMessage on warm cache`() = runTest {
        // RPC budget contract on the happy warm-cache path: ONE coalesce
        // surround fetch per album lastMessage, zero for solo posts. The
        // cold-cache rescue pass (`COLD_START_ALBUM_RESCUE_DELAY_MS`) does
        // not fire here because the warm-cache responder returns the full
        // album on the first pass, so the resulting merged card carries
        // `albumMessageIds.size = 3 > 1` and is skipped by the rescue
        // filter.
        val harness = PostsRepositoryTestHarness(this)
        val albumChat = harness.fakeChannel(
            id = -6000L,
            lastMessage = harness.fakeChannelMessage(-6000L, 600L, mediaAlbumId = 999L),
        )
        val soloA = harness.fakeChannel(
            id = -6001L,
            lastMessage = harness.fakeChannelMessage(-6001L, 601L, mediaAlbumId = 0L),
        )
        val soloB = harness.fakeChannel(
            id = -6002L,
            lastMessage = harness.fakeChannelMessage(-6002L, 602L, mediaAlbumId = 0L),
        )
        listOf(albumChat, soloA, soloB).forEach { harness.td.emitUpdate(TdApi.UpdateNewChat(it)) }
        harness.advanceUntilIdle()

        harness.td.onAny("LoadChats") { TdApi.Error(404, "no more") }
        harness.td.onAny("GetChats") { TdApi.Chats(3, longArrayOf(-6000L, -6001L, -6002L)) }
        // Warm-cache responder: surround fetch returns all three album
        // members so the merged card lands complete on the first pass.
        harness.td.onAny("GetChatHistory") {
            TdApi.Messages(
                /*totalCount*/ 3,
                arrayOf(
                    harness.fakeChannelMessage(-6000L, 600L, mediaAlbumId = 999L),
                    harness.fakeChannelMessage(-6000L, 601L, mediaAlbumId = 999L),
                    harness.fakeChannelMessage(-6000L, 602L, mediaAlbumId = 999L),
                ),
            )
        }

        harness.repo.refresh()
        harness.advanceUntilIdle()

        assertEquals(1, harness.td.rpcCount("GetChatHistory"),
            "warm-cache path: one surround fetch for the one album lastMessage, no rescue pass")
    }
}
