package dev.lyo.hortay.data

import dev.lyo.hortay.testutil.PostsRepositoryTestHarness
import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.TdApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

/**
 * Pins the three converging holes in album coalescing that let partial
 * (1..9-member) batches replace already-complete merged cards in the feed.
 *
 * Telegram does not ship a "album complete" signal — TDLib maintainer levlam
 * confirms in tdlib/td#1482: "There is no way to know this. You need to use
 * some timeout." Clients have to coalesce on best-effort. Our single-card
 * architecture (one [TimelinePost] carries all album items) makes us strictly
 * more vulnerable than Telegram-Android, which renders each member as its
 * own tile and groups only at render time — so a late member there merely
 * shifts position, while here it replaces the whole card.
 *
 *  - **Bug 1** (filter size==1): [PostsRepository.coalesceAlbumFragments] only
 *    fetched surround context when the batch carried a single fragment of an
 *    album. A 2-9-member partial fell through unaffected, and the merged card
 *    landed with the partial item count. Tested via [coalesce dispatches
 *    surround fetch for partial 2-member batch].
 *
 *  - **Bug 2** (ingest downgrade): [PostsRepository.ingest] prune-then-merge
 *    replaced an existing complete album with whatever the incoming batch
 *    carried, without comparing member counts. Tested via [ingest preserves
 *    complete album when coalesce returns partial].
 *
 *  - **Bug 3** (window too small): surround fetch parameters were
 *    `offset=-5, limit=10`, which cannot cover a 10-member album when the
 *    anchor is the last (highest-id) member — exactly the case for a fresh
 *    album whose `Chat.lastMessage` is M10. Tested via [coalesce surround
 *    fetch window covers full 10-member album].
 */
class AlbumCoalesceTest {

    private val baseDate = 1_700_000_000

    @Test
    fun `coalesce dispatches surround fetch for partial 2-member batch`() = runTest {
        val harness = PostsRepositoryTestHarness(this)
        val chatId = -7000L
        val albumId = 999L

        // Seed the chat without driving refresh, so [_mainChatIds] stays empty
        // and the ingest subscription-gate is permissive. (The CHANGELOG entry
        // for "PostsRepository.ingest() filters by Chat.positions" documents
        // the empty-set bypass: cold-start ingest must work before refresh
        // completes.)
        harness.td.emitUpdate(TdApi.UpdateNewChat(harness.fakeChannel(id = chatId)))
        harness.advanceUntilIdle()

        // Album has 5 members. We feed only M1 and M2 via UpdateNewMessage; the
        // debounce window (whatever the constant is) flushes them as a batch
        // of 2 — a legitimate partial that the old size==1 filter ignored.
        // Surround fetch is the only mechanism that can rescue the missing
        // members, so we assert it was dispatched.
        var surroundCalled = false
        harness.td.onAny("GetChatHistory") { req ->
            val q = req as TdApi.GetChatHistory
            // Distinguish surround fetches (carry a non-zero fromMessageId
            // pointing at an album member) from any other GetChatHistory
            // traffic the harness might pick up.
            if (q.fromMessageId in 1L..5L) surroundCalled = true
            val members = (1L..5L).map { id ->
                harness.fakeChannelMessage(chatId, id, date = baseDate, mediaAlbumId = albumId)
            }
            TdApi.Messages(members.size, members.toTypedArray())
        }

        harness.td.emitUpdate(
            TdApi.UpdateNewMessage(
                harness.fakeChannelMessage(chatId, 1L, date = baseDate, mediaAlbumId = albumId),
            ),
        )
        harness.td.emitUpdate(
            TdApi.UpdateNewMessage(
                harness.fakeChannelMessage(chatId, 2L, date = baseDate, mediaAlbumId = albumId),
            ),
        )
        harness.advanceUntilIdle()

        assertEquals(true, surroundCalled,
            "partial album with 2 members must trigger surround fetch to recover siblings")
        assertEquals(1, harness.repo.posts.value.size,
            "the two partial members must collapse into a single merged album card")
        assertEquals(
            5, harness.repo.posts.value.single().albumMessageIds.size,
            "merged card must carry all 5 album members after coalesce surround fetch",
        )
    }

    @Test
    fun `coalesce surround fetch window covers full 10-member album`() = runTest {
        val harness = PostsRepositoryTestHarness(this)
        val chatId = -7100L
        val albumId = 888L

        // 10-member album, lastMessage = M10 (the canonical case for a fresh
        // album: the newest member is what Chat.lastMessage carries). With
        // the old window (-5/10) only 5 of 10 members fit; with -9/19 the
        // entire 10-member span is reachable from any anchor position.
        val members = (1L..10L).map { id ->
            harness.fakeChannelMessage(chatId, id, date = baseDate, mediaAlbumId = albumId)
        }
        val chat = harness.fakeChannel(id = chatId, lastMessage = members.last())
        harness.td.emitUpdate(TdApi.UpdateNewChat(chat))
        harness.advanceUntilIdle()

        harness.td.onAny("LoadChats") { TdApi.Error(404, "no more") }
        harness.td.onAny("GetChats") { TdApi.Chats(1, longArrayOf(chatId)) }

        var capturedOffset: Int? = null
        var capturedLimit: Int? = null
        harness.td.onAny("GetChatHistory") { req ->
            val q = req as TdApi.GetChatHistory
            if (q.fromMessageId in 1L..10L) {
                capturedOffset = q.offset
                capturedLimit = q.limit
            }
            TdApi.Messages(members.size, members.toTypedArray())
        }

        harness.repo.refresh()
        harness.advanceUntilIdle()

        assertNotNull(capturedOffset, "surround fetch must run for the album-member lastMessage")
        assertEquals(-9, capturedOffset,
            "surround offset must be -(MAX_ALBUM - 1) so a 10-member album with " +
                "anchor at the highest id can still reach M1..M9")
        assertEquals(19, capturedLimit,
            "surround limit must be 2*MAX_ALBUM - 1 to span both directions around any anchor")
    }

    @Test
    fun `ingest preserves complete album when coalesce returns partial`() = runTest {
        val harness = PostsRepositoryTestHarness(this)
        val chatId = -7200L
        val albumId = 777L

        // Seed the feed with a complete 5-member album via refresh — the
        // canonical cold-start path. The first GetChatHistory responder
        // returns all 5 members so the merged card lands whole.
        val full = (1L..5L).map { id ->
            harness.fakeChannelMessage(chatId, id, date = baseDate, mediaAlbumId = albumId)
        }
        val chat = harness.fakeChannel(id = chatId, lastMessage = full.last())
        harness.td.emitUpdate(TdApi.UpdateNewChat(chat))
        harness.advanceUntilIdle()

        harness.td.onAny("LoadChats") { TdApi.Error(404, "no more") }
        harness.td.onAny("GetChats") { TdApi.Chats(1, longArrayOf(chatId)) }
        harness.td.onAny("GetChatHistory") { TdApi.Messages(full.size, full.toTypedArray()) }

        harness.repo.refresh()
        harness.advanceUntilIdle()

        val seeded = harness.repo.posts.value.single()
        assertEquals(5, seeded.albumMessageIds.size,
            "preflight: refresh must seed the feed with a complete 5-photo album")

        // Now: UpdateChatLastMessage arrives with a non-anchor member, and
        // the surround fetch comes up short (transient FLOOD_WAIT, members
        // aged out of TDLib's local store, network blip). The partial batch
        // [M1, M2, M3] must NOT downgrade the merged 5-photo card.
        val partial = (1L..3L).map { id ->
            harness.fakeChannelMessage(chatId, id, date = baseDate, mediaAlbumId = albumId)
        }
        harness.td.onAny("GetChatHistory") { TdApi.Messages(partial.size, partial.toTypedArray()) }
        harness.td.emitUpdate(TdApi.UpdateChatLastMessage(chatId, full[1], emptyArray()))
        harness.advanceUntilIdle()

        val survivor = harness.repo.posts.value.single()
        assertEquals(5, survivor.albumMessageIds.size,
            "partial coalesce result must NOT replace an already-complete merged album")
    }
}
