package dev.lyo.hortay.data

import dev.lyo.hortay.data.posts.ALBUM_ID_STRIDE
import dev.lyo.hortay.data.posts.TELEGRAM_MAX_ALBUM_SIZE
import dev.lyo.hortay.data.posts.albumCandidateIds
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
    fun `cold-start album rehydrates from local per-message index with zero GetChatHistory`() = runTest {
        val harness = PostsRepositoryTestHarness(this)
        val chatId = -7600L
        val albumId = 555L
        val stride = 1L shl 20
        val firstId = 50L * stride
        val memberIds = (0L..4L).map { firstId + it * stride } // 5 consecutive stride-aligned ids
        val lastMember = harness.fakeChannelMessage(chatId, memberIds.last(), date = baseDate, mediaAlbumId = albumId)

        harness.td.onAny("GetMessageLocally") { req ->
            val q = req as TdApi.GetMessageLocally
            if (q.chatId == chatId && q.messageId in memberIds && q.messageId != memberIds.last()) {
                harness.fakeChannelMessage(chatId, q.messageId, date = baseDate, mediaAlbumId = albumId)
            } else {
                TdApi.Error(404, "message not found locally")
            }
        }

        harness.td.emitUpdate(TdApi.UpdateNewChat(harness.fakeChannel(id = chatId, lastMessage = lastMember)))
        harness.advanceUntilIdle()

        assertEquals(1, harness.repo.posts.value.size, "the single lastMessage rehydrates into one merged card")
        assertEquals(5, harness.repo.posts.value.single().albumMessageIds.size, "all 5 members recovered from the local index")
        assertEquals(0, harness.td.rpcCount("GetChatHistory"), "cold-start local path must not touch GetChatHistory")
    }

    @Test
    fun `cold-start local recovery ignores siblings from a different album`() = runTest {
        val harness = PostsRepositoryTestHarness(this)
        val chatId = -7700L
        val albumId = 555L
        val otherAlbumId = 556L
        val stride = 1L shl 20
        val firstId = 80L * stride
        val memberIds = (0L..4L).map { firstId + it * stride }
        val lastMember = harness.fakeChannelMessage(chatId, memberIds.last(), date = baseDate, mediaAlbumId = albumId)

        // The local index answers for the candidate ids, but the two members just
        // below the album belong to a DIFFERENT album (a back-to-back post). They
        // must be filtered out by the mediaAlbumId guard.
        harness.td.onAny("GetMessageLocally") { req ->
            val q = req as TdApi.GetMessageLocally
            when (q.messageId) {
                in memberIds -> harness.fakeChannelMessage(chatId, q.messageId, date = baseDate, mediaAlbumId = albumId)
                firstId - stride, firstId - 2 * stride ->
                    harness.fakeChannelMessage(chatId, q.messageId, date = baseDate, mediaAlbumId = otherAlbumId)
                else -> TdApi.Error(404, "not local")
            }
        }

        harness.td.emitUpdate(TdApi.UpdateNewChat(harness.fakeChannel(id = chatId, lastMessage = lastMember)))
        harness.advanceUntilIdle()

        assertEquals(1, harness.repo.posts.value.size)
        assertEquals(
            5, harness.repo.posts.value.single().albumMessageIds.size,
            "only same-mediaAlbumId siblings merge; the neighbouring album's members are excluded",
        )
    }

    @Test
    fun `cold-start partial album batch recovers siblings from the local index`() = runTest {
        val harness = PostsRepositoryTestHarness(this)
        val chatId = -7000L
        val albumId = 999L
        val stride = 1L shl 20
        val memberIds = (1L..5L).map { it * stride } // stride-aligned

        harness.td.emitUpdate(TdApi.UpdateNewChat(harness.fakeChannel(id = chatId)))
        harness.advanceUntilIdle()

        harness.td.onAny("GetMessageLocally") { req ->
            val q = req as TdApi.GetMessageLocally
            if (q.chatId == chatId && q.messageId in memberIds) {
                harness.fakeChannelMessage(chatId, q.messageId, date = baseDate, mediaAlbumId = albumId)
            } else {
                TdApi.Error(404, "not local")
            }
        }

        // Two members arrive live via UpdateNewMessage (debounced into one batch);
        // the other three are recovered from the per-message local index.
        harness.td.emitUpdate(TdApi.UpdateNewMessage(harness.fakeChannelMessage(chatId, memberIds[0], date = baseDate, mediaAlbumId = albumId)))
        harness.td.emitUpdate(TdApi.UpdateNewMessage(harness.fakeChannelMessage(chatId, memberIds[1], date = baseDate, mediaAlbumId = albumId)))
        harness.advanceUntilIdle()

        assertEquals(1, harness.repo.posts.value.size, "partial members collapse into one merged album card")
        assertEquals(5, harness.repo.posts.value.single().albumMessageIds.size, "all 5 members recovered from the local index")
        assertEquals(0, harness.td.rpcCount("GetChatHistory"), "cold-start path must not touch GetChatHistory")
    }

    @Test
    fun `networked surround fetch window covers full 10-member album`() = runTest {
        val harness = PostsRepositoryTestHarness(this)
        val chatId = -7100L
        val albumId = 888L
        val stride = 1L shl 20
        val members = (1L..10L).map { id -> harness.fakeChannelMessage(chatId, id * stride, date = baseDate, mediaAlbumId = albumId) }

        harness.td.emitUpdate(TdApi.UpdateNewChat(harness.fakeChannel(id = chatId)))
        harness.advanceUntilIdle()

        var capturedOffset: Int? = null
        var capturedLimit: Int? = null
        harness.td.onAny("GetChatHistory") { req ->
            val q = req as TdApi.GetChatHistory
            when {
                q.fromMessageId == 0L -> TdApi.Messages(1, arrayOf(members.last())) // head load: just M10 (partial → triggers surround)
                else -> {
                    capturedOffset = q.offset
                    capturedLimit = q.limit
                    TdApi.Messages(members.size, members.toTypedArray())
                }
            }
        }

        harness.repo.loadChannelHistory(chatId, limit = 80)
        harness.advanceUntilIdle()

        assertNotNull(capturedOffset, "surround fetch must run for the partial album head load")
        assertEquals(-9, capturedOffset, "surround offset must be -(MAX-1) to reach M1..M9 from a top-anchored album")
        assertEquals(19, capturedLimit, "surround limit must be 2*MAX-1 to span both directions around any anchor")
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
        val stride = 1L shl 20
        val full = (1L..5L).map { id ->
            harness.fakeChannelMessage(chatId, id * stride, date = baseDate, mediaAlbumId = albumId)
        }
        harness.td.onAny("LoadChats") { TdApi.Error(404, "no more") }
        // Cold-start ingest recovers siblings via the per-message local index.
        harness.td.onAny("GetMessageLocally") { req ->
            val q = req as TdApi.GetMessageLocally
            full.firstOrNull { it.id == q.messageId } ?: TdApi.Error(404, "not local")
        }
        val chat = harness.fakeChannel(id = chatId, lastMessage = full.last())
        harness.td.emitUpdate(TdApi.UpdateNewChat(chat))
        harness.repo.refresh()
        harness.advanceUntilIdle()

        val seeded = harness.repo.posts.value.single()
        assertEquals(5, seeded.albumMessageIds.size,
            "preflight: refresh must seed the feed with a complete 5-photo album")

        // Now: UpdateChatLastMessage arrives with a non-anchor member. After
        // refresh() initialSyncDone is true, so this ingest takes the NETWORKED
        // path; its surround fetch comes up short (transient FLOOD_WAIT, members
        // aged out of TDLib's local store, network blip). The partial batch
        // [M1, M2, M3] must NOT downgrade the merged 5-photo card.
        val partial = (1L..3L).map { id ->
            harness.fakeChannelMessage(chatId, id * stride, date = baseDate, mediaAlbumId = albumId)
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

        val stride = 1L shl 20
        val full = (1L..5L).map { id ->
            harness.fakePhotoAlbumMessage(chatId, id * stride, date = baseDate, mediaAlbumId = albumId)
        }
        // Mocks registered BEFORE UpdateNewChat — handleNewChat ingest
        // fires immediately and triggers coalesceAlbumFragments.
        harness.td.onAny("LoadChats") { TdApi.Error(404, "no more") }
        // Cold-start ingest recovers siblings via the per-message local index.
        harness.td.onAny("GetMessageLocally") { req ->
            val q = req as TdApi.GetMessageLocally
            full.firstOrNull { it.id == q.messageId } ?: TdApi.Error(404, "not local")
        }
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
            TdApi.UpdateMessageContent(chatId, /* messageId */ 1L * stride, editedAnchorContent),
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
        harness.advanceUntilIdle()

        // After cold-start ingest (no refresh → [initialSyncDone] still false, so
        // the reactive post-drain upgrade has NOT fired): degraded 1-photo card —
        // the cold surround fetch returned empty and the size-1 mergeAlbumMembers
        // shortcut leaves the anchor alone. Pre-condition so the rest of the test
        // exercises the MANUAL restoreFromSnapshot upgrade path in isolation.
        val degraded = harness.repo.posts.value.single()
        assertEquals(
            1,
            (degraded.content as PostContent.PhotoAlbum).items.size,
            "pre-condition: cold-cache ingest must yield a 1-photo card",
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
        harness.advanceUntilIdle()

        // Pre-condition: cold-start ingest produced a degraded card (no refresh →
        // [initialSyncDone] still false → the reactive post-drain upgrade has not
        // run, so this faithfully models "user backgrounds before any upgrade
        // pass"). A single-member album short-circuits
        // PostFilterStrategy.mergeAlbumMembers and returns the anchor post as-is
        // with `albumMessageIds = emptyList()` but `mediaAlbumId` still set. That
        // combination (mediaAlbumId != 0 && albumMessageIds.size <= 1) is exactly
        // what the save-time guard treats as degraded.
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

    @Test
    fun `post-drain snapshot pass upgrades a degraded album that streamed in during cold-start`() = runTest {
        // The production race the single-shot restoreFromSnapshot in
        // TimelineViewModel.init cannot cover: a channel's UpdateNewChat
        // streams in *during* the LoadChats drain, lands a degraded 1-photo
        // album (cold-cache surround fetch returns empty), and the one early
        // snapshot pass already read `_posts` before this channel existed —
        // so nothing repairs it short of the user opening the post. The
        // reactive post-drain upgrade (fires when [initialSyncDone] flips)
        // must rebuild the album from the previous session's saved member ids
        // via local GetMessage, which works on a cold chat-history cache.
        val harness = PostsRepositoryTestHarness(this)
        val chatId = -9100L
        val albumId = 777L
        val full = (1L..5L).map { id ->
            harness.fakePhotoAlbumMessage(chatId, id, date = baseDate, mediaAlbumId = albumId)
        }
        // Cold TDLib chat-history cache: the surround fetch can't recover siblings.
        harness.td.onAny("GetChatHistory") { TdApi.Messages(0, emptyArray()) }
        harness.td.onAny("LoadChats") { TdApi.Error(404, "no more") }
        // Previous healthy session persisted every member id; local GetMessage serves them.
        harness.snapshotStore.seed(full.map { chatId to it.id })
        harness.td.onAny("GetMessage") { req ->
            val q = req as TdApi.GetMessage
            full.firstOrNull { it.id == q.messageId } ?: TdApi.Error(404, "not found")
        }

        // Channel streams in with its album anchor as lastMessage → degraded card.
        harness.td.emitUpdate(TdApi.UpdateNewChat(harness.fakeChannel(id = chatId, lastMessage = full.last())))
        harness.advanceUntilIdle()
        assertEquals(
            1,
            (harness.repo.posts.value.single().content as PostContent.PhotoAlbum).items.size,
            "pre-condition: cold-cache ingest must yield a degraded 1-photo card",
        )

        // Drain completes → initialSyncDone flips → reactive post-drain pass fires.
        harness.repo.refresh()
        harness.advanceUntilIdle()

        val card = harness.repo.posts.value.single()
        assertEquals(
            5,
            card.albumMessageIds.size,
            "post-drain snapshot pass must rebuild the full 5-member album from saved ids",
        )
        assertEquals(
            5,
            (card.content as PostContent.PhotoAlbum).items.size,
            "merged card content must carry all 5 items, not 1",
        )
    }

    @Test
    fun `albumCandidateIds derives consecutive sibling ids around the anchor`() {
        // Channel message id = serverId << 20. A 5-member album posted atomically
        // has server ids S..S+4, i.e. client ids that differ by exactly 1 << 20.
        // Anchor on the last member (the Chat.lastMessage case); siblings sit below.
        val stride = 1L shl 20
        val anchor = 100L * stride
        val ids = albumCandidateIds(anchor).toSet()

        assertTrue((anchor - stride) in ids, "must include the immediately-lower sibling")
        assertTrue((anchor - 4 * stride) in ids, "must reach 4 members down (5-member album, last anchored)")
        assertTrue((anchor + stride) in ids, "must include the immediately-higher sibling (anchor not guaranteed last)")
        assertTrue(anchor !in ids, "anchor itself is already in hand; not a candidate")
        assertEquals(2 * (TELEGRAM_MAX_ALBUM_SIZE - 1), ids.size, "9 below + 9 above for MAX=10")
    }

    @Test
    fun `albumCandidateIds clamps to positive ids near the id-space floor`() {
        val stride = 1L shl 20
        val anchor = 2L * stride // only 1 valid sibling below before crossing 0
        val ids = albumCandidateIds(anchor)
        assertTrue(ids.all { it > 0L }, "no zero or negative candidate ids")
        assertTrue((anchor - stride) in ids)
        assertEquals(10, ids.size, "one below-sibling survives the clamp, plus all 9 above")
    }

    @Test
    fun `albumCandidateIds at the minimum anchor keeps only the above-siblings`() {
        val stride = 1L shl 20
        val ids = albumCandidateIds(stride) // anchor == ALBUM_ID_STRIDE: every below-offset is <= 0
        assertTrue(ids.all { it > 0L }, "no zero or negative candidate ids")
        assertEquals(TELEGRAM_MAX_ALBUM_SIZE - 1, ids.size, "only the 9 above-siblings remain")
        assertTrue((stride + stride) in ids, "the immediately-higher sibling is present")
    }

    @Test
    fun `never-cached album survives cold start as a single-member card without crashing`() = runTest {
        val harness = PostsRepositoryTestHarness(this)
        val chatId = -7800L
        val albumId = 1001L
        val stride = 1L shl 20
        val anchorId = 90L * stride
        val lastMember = harness.fakeChannelMessage(chatId, anchorId, date = baseDate, mediaAlbumId = albumId)

        // Genuinely cold: every sibling lookup 404s.
        harness.td.onAny("GetMessageLocally") { _ -> TdApi.Error(404, "not local") }

        harness.td.emitUpdate(TdApi.UpdateNewChat(harness.fakeChannel(id = chatId, lastMessage = lastMember)))
        harness.advanceUntilIdle()

        assertEquals(1, harness.repo.posts.value.size, "card present, not dropped")
        assertTrue(
            harness.repo.posts.value.single().albumMessageIds.size <= 1,
            "stays degraded (at most 1 member) when nothing is local — Layer 2 repairs on focus",
        )
        assertEquals(0, harness.td.rpcCount("GetChatHistory"), "no network on the cold-start local path")
    }

    @Test
    fun `requestAlbumRepair networks a degraded album and dedupes concurrent requests`() = runTest {
        val harness = PostsRepositoryTestHarness(this)
        val chatId = -7400L
        val albumId = 888L
        val stride = 1L shl 20
        val anchorId = 70L * stride

        // Seed a degraded 1-member album into the feed (cold-start local path, all 404).
        harness.td.onAny("GetMessageLocally") { _ -> TdApi.Error(404, "not local") }
        harness.td.emitUpdate(
            TdApi.UpdateNewChat(
                harness.fakeChannel(id = chatId, lastMessage = harness.fakeChannelMessage(chatId, anchorId, date = baseDate, mediaAlbumId = albumId)),
            ),
        )
        harness.advanceUntilIdle()
        assertTrue(harness.repo.posts.value.single().albumMessageIds.size <= 1, "pre-condition: degraded album")

        // Networked repair: GetMessage returns the anchor, GetChatHistory returns the full album.
        harness.td.onAny("GetMessage") { req ->
            val q = req as TdApi.GetMessage
            harness.fakeChannelMessage(q.chatId, q.messageId, date = baseDate, mediaAlbumId = albumId)
        }
        harness.td.onAny("GetChatHistory") { _ ->
            val members = (0L..4L).map { harness.fakeChannelMessage(chatId, anchorId + it * stride, date = baseDate, mediaAlbumId = albumId) }
            TdApi.Messages(members.size, members.toTypedArray())
        }

        // Two concurrent requests for the SAME album → one repair dispatch.
        harness.repo.requestAlbumRepair(chatId, anchorId, albumId)
        harness.repo.requestAlbumRepair(chatId, anchorId, albumId)
        harness.advanceUntilIdle()

        assertEquals(5, harness.repo.posts.value.single().albumMessageIds.size, "repaired to full album")
        assertEquals(1, harness.td.rpcCount("GetChatHistory"), "duplicate request for same album deduped to one repair")
    }

    @Test
    fun `requestAlbumRepair processes two distinct albums across the throttle window`() = runTest {
        val harness = PostsRepositoryTestHarness(this)
        val stride = 1L shl 20
        val chatA = -7450L
        val chatB = -7460L
        val albumA = 100L
        val albumB = 200L
        val anchorA = 40L * stride
        val anchorB = 60L * stride

        harness.td.onAny("GetMessageLocally") { _ -> TdApi.Error(404, "not local") }
        harness.td.emitUpdate(TdApi.UpdateNewChat(harness.fakeChannel(id = chatA, lastMessage = harness.fakeChannelMessage(chatA, anchorA, date = baseDate, mediaAlbumId = albumA))))
        harness.td.emitUpdate(TdApi.UpdateNewChat(harness.fakeChannel(id = chatB, lastMessage = harness.fakeChannelMessage(chatB, anchorB, date = baseDate, mediaAlbumId = albumB))))
        harness.advanceUntilIdle()

        harness.td.onAny("GetMessage") { req ->
            val q = req as TdApi.GetMessage
            harness.fakeChannelMessage(q.chatId, q.messageId, date = baseDate, mediaAlbumId = if (q.chatId == chatA) albumA else albumB)
        }
        harness.td.onAny("GetChatHistory") { req ->
            val q = req as TdApi.GetChatHistory
            val cid = q.chatId
            val alb = if (cid == chatA) albumA else albumB
            val anc = if (cid == chatA) anchorA else anchorB
            val members = (0L..4L).map { harness.fakeChannelMessage(cid, anc + it * stride, date = baseDate, mediaAlbumId = alb) }
            TdApi.Messages(members.size, members.toTypedArray())
        }

        harness.repo.requestAlbumRepair(chatA, anchorA, albumA)
        harness.repo.requestAlbumRepair(chatB, anchorB, albumB)
        harness.advanceUntilIdle()

        assertEquals(5, harness.repo.posts.value.first { it.chatId == chatA }.albumMessageIds.size, "album A repaired")
        assertEquals(5, harness.repo.posts.value.first { it.chatId == chatB }.albumMessageIds.size, "album B repaired")
        assertEquals(2, harness.td.rpcCount("GetChatHistory"), "two distinct albums → two repairs across the throttle window")
    }

    @Test
    fun `cold-start album coalesce stays offline to respect the FLOOD_WAIT budget`() = runTest {
        val harness = PostsRepositoryTestHarness(this)
        val chatId = -9200L
        val albumId = 888L
        val stride = 1L shl 20
        var localCalls = 0
        harness.td.onAny("GetMessageLocally") { _ -> localCalls++; TdApi.Error(404, "not local") }
        harness.td.onAny("GetChatHistory") { _ -> error("cold-start coalesce must not issue a networked GetChatHistory") }

        harness.td.emitUpdate(
            TdApi.UpdateNewChat(
                harness.fakeChannel(id = chatId, lastMessage = harness.fakePhotoAlbumMessage(chatId, 5L * stride, date = baseDate, mediaAlbumId = albumId)),
            ),
        )
        harness.advanceUntilIdle()

        assertEquals(0, harness.td.rpcCount("GetChatHistory"), "no networked GetChatHistory during the cold-start storm")
        assertTrue(localCalls > 0, "cold-start coalesce must probe the offline per-message local index")
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
