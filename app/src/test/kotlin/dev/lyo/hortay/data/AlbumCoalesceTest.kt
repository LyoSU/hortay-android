package dev.lyo.hortay.data

import dev.lyo.hortay.testutil.PostsRepositoryTestHarness
import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.TdApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
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

        // Register mocks FIRST: under the event-driven ingest design,
        // handleNewChat fires coalesceAlbumFragments at UpdateNewChat time,
        // so GetChatHistory must already have a responder.
        harness.td.onAny("LoadChats") { TdApi.Error(404, "no more") }
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

        val chat = harness.fakeChannel(id = chatId, lastMessage = members.last())
        harness.td.emitUpdate(TdApi.UpdateNewChat(chat))
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

        // Seed the feed with a complete 5-member album via UpdateNewChat
        // ingest. The GetChatHistory responder must be registered BEFORE
        // emitting the update — under the event-driven design ingest fires
        // at UpdateNewChat time, not at refresh time.
        val full = (1L..5L).map { id ->
            harness.fakeChannelMessage(chatId, id, date = baseDate, mediaAlbumId = albumId)
        }
        harness.td.onAny("LoadChats") { TdApi.Error(404, "no more") }
        harness.td.onAny("GetChatHistory") { TdApi.Messages(full.size, full.toTypedArray()) }
        val chat = harness.fakeChannel(id = chatId, lastMessage = full.last())
        harness.td.emitUpdate(TdApi.UpdateNewChat(chat))
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

    @Test
    fun `album anchor caption edit must not downgrade the card to a single item`() = runTest {
        // Reproduces the user-reported regression: an admin edits the caption
        // of a 5-photo album in the channel. TDLib emits UpdateMessageContent
        // for the anchor message (the lowest-id album member, which is the
        // canonical caption-carrier per tdlib/td#2312). The naive
        // updateOnePost-by-anchor-id path would replace the merged content
        // with MessageContentMapper.map(MessagePhoto), which produces a
        // PhotoAlbum-with-ONE-item — visually collapsing the 5-photo card to
        // a single image, while albumMessageIds still claims 5 siblings
        // (inconsistent state that the UI resolves by rendering content.items).
        //
        // Correct behaviour: any UpdateMessageContent targeting an album
        // member (anchor or sibling) must re-ingest the album so the merged
        // card stays whole with the updated caption.
        val harness = PostsRepositoryTestHarness(this)
        val chatId = -7300L
        val albumId = 666L

        val full = (1L..5L).map { id ->
            harness.fakePhotoAlbumMessage(chatId, id, date = baseDate, mediaAlbumId = albumId)
        }
        // Mocks registered BEFORE UpdateNewChat — handleNewChat ingest
        // fires immediately and triggers coalesceAlbumFragments.
        harness.td.onAny("LoadChats") { TdApi.Error(404, "no more") }
        harness.td.onAny("GetChatHistory") { TdApi.Messages(full.size, full.toTypedArray()) }
        // Anchor re-ingest path (the fix) performs a GetMessage(chatId, M1)
        // before handing the result back through handleNewMessage. Make the
        // fake respond with the post-edit anchor message so the test passes
        // once the fix is in place; on the buggy path this responder simply
        // isn't consulted.
        harness.td.onAny("GetMessage") { req ->
            val q = req as TdApi.GetMessage
            harness.fakePhotoAlbumMessage(
                chatId = q.chatId,
                messageId = q.messageId,
                date = baseDate,
                mediaAlbumId = albumId,
                caption = "edited caption",
            )
        }
        val chat = harness.fakeChannel(id = chatId, lastMessage = full.last())
        harness.td.emitUpdate(TdApi.UpdateNewChat(chat))
        harness.repo.refresh()
        harness.advanceUntilIdle()

        val seeded = harness.repo.posts.value.single()
        assertEquals(5, seeded.albumMessageIds.size, "preflight: 5-photo album seeded")
        assertEquals(
            5,
            (seeded.content as PostContent.PhotoAlbum).items.size,
            "preflight: card content carries all 5 items",
        )

        // Admin edits caption on the anchor. The edited content is a
        // MessagePhoto (single-photo message) — the structure TDLib emits for
        // any individual album member, since each member IS a standalone
        // photo message under the hood.
        val editedAnchorContent = TdApi.MessagePhoto().apply {
            photo = TdApi.Photo(false, null, emptyArray())
            caption = TdApi.FormattedText("edited caption", emptyArray())
        }
        harness.td.emitUpdate(
            TdApi.UpdateMessageContent(chatId, /* messageId */ 1L, editedAnchorContent),
        )
        harness.advanceUntilIdle()

        val survivor = harness.repo.posts.value.single()
        assertEquals(
            5,
            survivor.albumMessageIds.size,
            "anchor edit must not shrink the album's member list",
        )
        val items = (survivor.content as PostContent.PhotoAlbum).items
        assertEquals(
            5,
            items.size,
            "anchor edit must keep the 5 photo items in the merged card",
        )
    }

    @Test
    fun `restoreFromSnapshot upgrades a degraded album using the previous session's saved member ids`() = runTest {
        // Reproduces the user-reported "перезавантажую — лише одна картинка з
        // альбому в стрічці" regression. On a real cold start, TDLib's local
        // message database may not be fully deserialised by the time
        // refreshLocked's per-chat ingest fires its first GetChatHistory
        // surround fetch — the fetch returns empty (cold-cache race), so
        // [coalesceAlbumFragments] can't rescue the album's siblings and the
        // merged card lands with just the anchor message.
        //
        // The previous healthy session persisted every album member id via
        // [saveSnapshotNow]. [restoreFromSnapshotInternal]'s upgrade pass
        // GetMessage's each saved id (which works on a cold chat-history
        // cache because TDLib indexes messages individually), groups by the
        // freshly-observed mediaAlbumId, and routes the rebuilt 5-member
        // batch through [foldRawIntoCurrent]. The degraded card is dropped
        // by the anchor-id-or-any-member-id de-dup and replaced by the
        // PostFilterStrategy-merged full album.
        val harness = PostsRepositoryTestHarness(this)
        val chatId = -7400L
        val albumId = 555L
        val full = (1L..5L).map { id ->
            harness.fakePhotoAlbumMessage(chatId, id, date = baseDate, mediaAlbumId = albumId)
        }
        harness.td.onAny("LoadChats") { TdApi.Error(404, "no more") }
        // GetChatHistory always returns empty here — simulates a TDLib cold
        // local cache that never warms within the refresh window. This is
        // the *worst* case the snapshot upgrade is designed to handle.
        harness.td.onAny("GetChatHistory") { TdApi.Messages(0, emptyArray()) }
        // Seed the snapshot with last session's full 5-member album ids and
        // wire GetMessage to return the real messages — that's what TDLib's
        // per-message local index provides on cold start (sidesteps the
        // chat-history hydration race).
        harness.snapshotStore.seed(full.map { chatId to it.id })
        harness.td.onAny("GetMessage") { req ->
            val q = req as TdApi.GetMessage
            full.firstOrNull { it.id == q.messageId } ?: TdApi.Error(404, "not found")
        }
        val chat = harness.fakeChannel(id = chatId, lastMessage = full.first())
        harness.td.emitUpdate(TdApi.UpdateNewChat(chat))
        harness.repo.refresh()
        harness.advanceUntilIdle()

        // After refresh: degraded 1-photo card (cold surround fetch returned
        // empty, anchor id is in albumMessageIds via the size-1 mergeAlbumMembers
        // shortcut). Pre-condition check so the rest of the test exercises a
        // real degradation, not a false positive.
        val degraded = harness.repo.posts.value.single()
        assertEquals(
            1,
            (degraded.content as PostContent.PhotoAlbum).items.size,
            "pre-condition: refresh against a cold TDLib cache must yield a 1-photo card",
        )

        harness.repo.restoreFromSnapshot()
        harness.advanceUntilIdle()

        val card = harness.repo.posts.value.single()
        assertEquals(
            5,
            card.albumMessageIds.size,
            "snapshot upgrade must rebuild the full 5-member album from the previous session's saved ids",
        )
        val items = (card.content as PostContent.PhotoAlbum).items
        assertEquals(5, items.size, "merged card content must carry 5 items, not 1")
    }

    @Test
    fun `saveSnapshotNow does not poison healthy album membership when current view is degraded`() = runTest {
        // Reproduces the "save-time poisoning" failure mode Codex flagged in
        // audit. Lifecycle:
        //   1. Session A persists healthy snapshot for album 555: ids [1..5].
        //   2. Session B cold-starts. refreshLocked hits a cold TDLib cache;
        //      coalesceAlbumFragments returns empty surround → the merged
        //      card lands with just the anchor (mediaAlbumId set,
        //      albumMessageIds.size == 1).
        //   3. User backgrounds before restoreFromSnapshot's upgrade pass
        //      lands. saveSnapshotNow runs on the foreground→background
        //      transition. Without the anti-poisoning guard it overwrites
        //      the on-disk snapshot with the degraded single id — losing
        //      the last-known-good member set for good.
        //   4. Next cold start: degraded again, but upgradeDegradedAlbums
        //      has nothing to rebuild from. Permanent 1-photo card.
        //
        // The guard reads previous snapshot at save time, GetMessage's any
        // id not already in the new save set (cheap — TDLib's per-message
        // local index), and preserves siblings whose (chatId, mediaAlbumId)
        // matches a currently-degraded album. Snapshot stays healthy
        // through repeated degraded background-transitions.
        val harness = PostsRepositoryTestHarness(this)
        val chatId = -7500L
        val albumId = 556L
        val full = (1L..5L).map { id ->
            harness.fakePhotoAlbumMessage(chatId, id, date = baseDate, mediaAlbumId = albumId)
        }
        harness.td.onAny("LoadChats") { TdApi.Error(404, "no more") }
        // Cold cache: surround fetch returns nothing → degraded card.
        harness.td.onAny("GetChatHistory") { TdApi.Messages(0, emptyArray()) }
        // Previous healthy session saved every member id.
        val previousHealthySnapshot = full.map { chatId to it.id }
        harness.snapshotStore.seed(previousHealthySnapshot)
        // GetMessage works on cold cache (TDLib per-message local index).
        harness.td.onAny("GetMessage") { req ->
            val q = req as TdApi.GetMessage
            full.firstOrNull { it.id == q.messageId } ?: TdApi.Error(404, "not found")
        }
        val chat = harness.fakeChannel(id = chatId, lastMessage = full.first())
        harness.td.emitUpdate(TdApi.UpdateNewChat(chat))
        harness.repo.refresh()
        harness.advanceUntilIdle()

        // Pre-condition: refresh produced a degraded card — a single-member
        // album short-circuits PostFilterStrategy.mergeAlbumMembers and
        // returns the anchor post as-is with `albumMessageIds = emptyList()`
        // but `mediaAlbumId` still set. That combination (mediaAlbumId != 0
        // && albumMessageIds.size <= 1) is exactly what the save-time guard
        // treats as degraded.
        val degraded = harness.repo.posts.value.single()
        assertEquals(
            albumId,
            degraded.mediaAlbumId,
            "pre-condition: anchor's mediaAlbumId must survive the merge",
        )
        assertTrue(
            degraded.albumMessageIds.size <= 1,
            "pre-condition: refresh against cold cache must yield a degraded album",
        )

        // Skip restoreFromSnapshot — simulate user backgrounding before the
        // upgrade pass runs. Trigger save by flipping foreground → background.
        harness.goBackground()
        harness.advanceUntilIdle()

        val saved = harness.snapshotStore.load()
        val savedIds = saved.filter { it.first == chatId }.map { it.second }.toSet()
        assertEquals(
            setOf(1L, 2L, 3L, 4L, 5L),
            savedIds,
            "save-time guard must preserve previously-saved siblings of currently-degraded albums",
        )
    }

    @Test
    fun `restoreFromSnapshot leaves a healthy feed untouched`() = runTest {
        // Sibling invariant for the upgrade path: when [_posts] holds no
        // degraded albums, restore must be a no-op. The snapshot may carry
        // stale top-of-feed entries from yesterday that no longer belong in
        // the live feed; the upgrade path is *only* there to repair
        // partial-album damage from the cold-cache race, not to re-inject
        // older content.
        val harness = PostsRepositoryTestHarness(this)
        val chatId = -8200L
        val solo = harness.fakeChannelMessage(chatId, /* messageId */ 42L, date = baseDate)
        val chat = harness.fakeChannel(id = chatId, lastMessage = solo)
        harness.td.emitUpdate(TdApi.UpdateNewChat(chat))
        harness.advanceUntilIdle()

        harness.td.onAny("LoadChats") { TdApi.Error(404, "no more") }
        harness.td.onAny("GetChats") { TdApi.Chats(1, longArrayOf(chatId)) }

        // Snapshot from a previous session carries an older solo post the
        // user has scrolled past — restore must NOT re-add it.
        val stale = harness.fakeChannelMessage(chatId, /* messageId */ 7L, date = baseDate - 100)
        harness.snapshotStore.seed(listOf(chatId to stale.id))
        harness.td.onAny("GetMessage") { req ->
            val q = req as TdApi.GetMessage
            if (q.messageId == stale.id) stale else TdApi.Error(404, "not found")
        }

        harness.repo.refresh()
        harness.advanceUntilIdle()
        val before = harness.repo.posts.value
        assertEquals(1, before.size, "pre-condition: feed has exactly one fresh post")

        harness.repo.restoreFromSnapshot()
        harness.advanceUntilIdle()
        val after = harness.repo.posts.value
        assertEquals(
            before,
            after,
            "snapshot upgrade must leave a healthy feed untouched — no stale re-injection",
        )
    }
}

/**
 * Build a fake [TdApi.Message] carrying a [TdApi.MessagePhoto] content. Album
 * tests need the items list to be non-empty after PostFilterStrategy merge —
 * the harness's default [PostsRepositoryTestHarness.fakeChannelMessage] uses
 * [TdApi.MessageText], which `MessageContentMapper` maps to `PostContent.Text`,
 * yielding zero items inside the merged `PhotoAlbum`. Tests that assert on
 * `content.items.size` therefore need this MessagePhoto-flavoured variant.
 */
private fun PostsRepositoryTestHarness.fakePhotoAlbumMessage(
    chatId: Long,
    messageId: Long,
    date: Int,
    mediaAlbumId: Long,
    caption: String = "",
): TdApi.Message = TdApi.Message().apply {
    this.id = messageId
    this.chatId = chatId
    this.date = date
    this.mediaAlbumId = mediaAlbumId
    this.senderId = TdApi.MessageSenderChat(chatId)
    this.content = TdApi.MessagePhoto().apply {
        photo = TdApi.Photo(false, null, emptyArray())
        this.caption = TdApi.FormattedText(caption, emptyArray())
    }
}
