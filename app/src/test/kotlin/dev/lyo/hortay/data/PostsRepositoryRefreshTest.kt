package dev.lyo.hortay.data

import dev.lyo.hortay.testutil.PostsRepositoryTestHarness
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
    fun `refresh drains both ChatListMain and ChatListArchive`() = runTest {
        // Post-2026-05 update-pipeline rework: archived channels reach the
        // Archive tab via the same UpdateChatAddedToList listener that feeds
        // Main, but TDLib only emits those updates for lists it has been
        // asked to LoadChats. So the cold-start drain MUST cover both
        // lists — otherwise first-auth users with archived channels see an
        // empty Archive tab until they manually re-trigger sync.
        val harness = PostsRepositoryTestHarness(this)
        val seenLists = mutableListOf<String>()
        harness.td.onAny("LoadChats") { fn ->
            val req = fn as TdApi.LoadChats
            seenLists.add(req.chatList::class.simpleName ?: "?")
            TdApi.Error(404, "no more")
        }

        harness.repo.refresh()
        harness.advanceUntilIdle()

        assertTrue(seenLists.any { it == "ChatListMain" },
            "refresh must drain ChatListMain; seen=$seenLists")
        assertTrue(seenLists.any { it == "ChatListArchive" },
            "refresh must drain ChatListArchive; seen=$seenLists")
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
    fun `UpdateNewChat seeds cursor zero for a never-read channel with unread posts`() = runTest {
        // Never-read channels that actually carry incoming unread
        // (`unreadCount > 0`, `lastReadInboxMessageId == 0`) MUST land in
        // the cursor map at the zero sentinel — otherwise [isUnreadIn]
        // falls into the `cursors[chatId] == null → read` branch and
        // every post silently loses the unread strip.
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
            "never-read channel with unread MUST be in the cursor map")
        assertEquals(0L, cursors[-8000L])
    }

    @Test
    fun `UpdateNewChat skips seeding for outgoing-only admin channels`() = runTest {
        // Admin / outgoing-only channels report `unreadCount == 0` and
        // `lastReadInboxMessageId == 0` — TDLib invariant: outgoing posts
        // don't bump the inbox cursor (tdlib/td#1419). Seeding cursor=0
        // would mark every own broadcast as unread, and the recency-floor
        // boundary picker would then land the user on a fresh self-
        // authored post. The right answer is to leave the slot empty so
        // [isUnreadIn] falls through to "read" — matching the user's
        // mental model ("I wrote that, of course I've seen it"). A real
        // UpdateChatReadInbox arriving later (e.g. someone else's read
        // ack on a discussion-mirror) still seeds normally via the
        // dedicated listener.
        val harness = PostsRepositoryTestHarness(this)
        val adminOwn = harness.fakeChannel(
            id = -8002L,
            lastMessage = harness.fakeChannelMessage(-8002L, 820L),
            unreadCount = 0,
            lastReadInboxMessageId = 0L,
        )
        harness.td.emitUpdate(TdApi.UpdateNewChat(adminOwn))
        harness.advanceUntilIdle()

        assertTrue(-8002L !in harness.repo.chatReadCursors.value,
            "outgoing-only admin channel must NOT seed a cursor (0/0 is ambiguous)")
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
    fun `late UpdateNewChat after triggerInitialSync still ingests`() = runTest {
        // Under the event-driven ingest design, refresh() is a fire-and-forget
        // trigger that drives LoadChats — TDLib then emits UpdateNewChat /
        // UpdateChatLastMessage at its own pace and our listeners catch them.
        // A chat whose UpdateNewChat arrives AFTER refresh() returns (e.g. on
        // slow first-auth networks) must still land in `_posts`; the
        // downstream subscribedPosts filter handles membership.
        val harness = PostsRepositoryTestHarness(this)
        harness.td.onAny("LoadChats") { TdApi.Error(404, "no more") }

        harness.repo.refresh()
        harness.advanceUntilIdle()

        val lateMsg = harness.fakeChannelMessage(-5000L, 500L)
        val chat = harness.fakeChannel(id = -5000L, lastMessage = lateMsg)
        harness.td.emitUpdate(TdApi.UpdateNewChat(chat))
        harness.advanceUntilIdle()

        assertEquals(1, harness.repo.posts.value.size)
        assertEquals(500L, harness.repo.posts.value[0].id)
    }

    @Test
    fun `UpdateChatLastMessage racing UpdateNewChat is buffered and flushed`() = runTest {
        // Race window: UpdateChatLastMessage arrives BEFORE the matching
        // UpdateNewChat seeded chatCache. The previous shape dropped the
        // payload outright; the post-rework buffer captures it in
        // [pendingLastMessages] and [handleNewChat] flushes the buffered
        // message through ingest as soon as the chat lands.
        val harness = PostsRepositoryTestHarness(this)
        val chatId = -9001L
        val racingMsg = harness.fakeChannelMessage(chatId, 901L)

        // Step A: UpdateChatLastMessage with NO prior UpdateNewChat —
        // chatCache miss. The old design would have dropped this; the new
        // one buffers it.
        harness.td.emitUpdate(TdApi.UpdateChatLastMessage(chatId, racingMsg, emptyArray()))
        harness.advanceUntilIdle()
        assertEquals(0, harness.repo.posts.value.size,
            "buffered payload must not surface until UpdateNewChat seeds chatCache")

        // Step B: UpdateNewChat lands. handleNewChat must see the buffered
        // payload via `pendingLastMessages.remove(chat.id)` and ingest it.
        // chat.lastMessage = null here to prove the buffer wins.
        val chat = harness.fakeChannel(id = chatId, lastMessage = null)
        harness.td.emitUpdate(TdApi.UpdateNewChat(chat))
        harness.advanceUntilIdle()

        assertEquals(1, harness.repo.posts.value.size,
            "buffered UpdateChatLastMessage must flush on UpdateNewChat seed")
        assertEquals(901L, harness.repo.posts.value[0].id)
    }

    @Test
    fun `chat positions in UpdateNewChat hydrate _mainChatIds`() = runTest {
        // hydrateChatListMembership reads `chat.positions` as a warm-cache
        // shortcut so [ingest]'s subscription filter can activate sooner —
        // without relying on the UpdateChatAddedToList listener for chats
        // whose positions array is already filled in the cache read.
        val harness = PostsRepositoryTestHarness(this)
        val chatId = -10_001L
        val chat = harness.fakeChannel(
            id = chatId,
            lastMessage = harness.fakeChannelMessage(chatId, 1001L),
        ).apply {
            positions = arrayOf(TdApi.ChatPosition(TdApi.ChatListMain(), 100L, false, null))
        }
        harness.td.emitUpdate(TdApi.UpdateNewChat(chat))
        harness.advanceUntilIdle()

        // archivedChatIds is exposed, _mainChatIds is private; subscribedPosts
        // is the observable that combines both. After UpdateNewChat with a
        // Main position, the post must be in subscribedPosts.
        assertEquals(1, harness.repo.subscribedPosts.value.size,
            "chat with ChatListMain in positions hydrates the main-list filter")
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
        // Register responders FIRST: under the event-driven design,
        // ingest fires at UpdateNewChat time, not at refresh time, so
        // mock setup must precede the update emission.
        harness.td.onAny("LoadChats") { TdApi.Error(404, "no more") }
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
        harness.repo.refresh()
        harness.advanceUntilIdle()

        assertEquals(1, harness.td.rpcCount("GetChatHistory"),
            "warm-cache path: one surround fetch for the one album lastMessage, no rescue pass")
    }
}
