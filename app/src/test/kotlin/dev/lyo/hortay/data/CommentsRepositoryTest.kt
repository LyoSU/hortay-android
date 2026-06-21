package dev.lyo.hortay.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.TdApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommentsRepositoryTest {

    @Test
    fun `error state when no album candidate has canGetMessageThread`() = runTest {
        // Multi-id input (album) — exercises the GetMessageProperties probe loop;
        // standalone single-id posts now skip the probe entirely (B9 optimisation
        // in CommentsRepository.ensureAnchor) so they wouldn't reach this code
        // path. Two MessageProperties responders are queued, both reporting
        // canGetMessageThread=false, so firstOrNull returns null and the flow
        // surfaces Error.
        val td = FakeTdSender().apply {
            onNext { _ -> TdApi.MessageProperties().apply { canGetMessageThread = false } }
            onNext { _ -> TdApi.MessageProperties().apply { canGetMessageThread = false } }
        }
        val repo = CommentsRepository(td, fakeMapper(td), TestScope(StandardTestDispatcher(testScheduler)), FakeStrings)

        val state = repo.observeThread(chatId = 123L, candidateMessageIds = listOf(1L, 2L)).first()

        assertTrue(state is CommentsRepository.ThreadState.Error)
    }

    @Test
    fun `ready state once anchor and history resolve`() = runTest {
        val chatId = 123L
        val anchor = 7L
        val threadChatId = 9999L
        val rootId = 7L

        // Standalone post (single-id input) — ensureAnchor's B9 fast path skips
        // GetMessageProperties and goes directly to GetMessageThread. The responder
        // queue mirrors that order: GetMessageThread → OpenChat ×2 (thread + host) →
        // history pages → CloseChat ×2.
        val td = FakeTdSender().apply {
            // 1. resolve thread directly (no GetMessageProperties for standalone).
            onNext { _ ->
                TdApi.MessageThreadInfo().apply {
                    this.chatId = threadChatId
                    this.messageThreadId = rootId
                }
            }
            // 2-3. OpenChat ×2 — observeThread wraps its work in
            // withOpenChats(threadChatId, chatId), keeping the host channel open so the
            // anchor post's interaction info stays live, so two OpenChats fire before
            // any history fetch.
            onNext { _ -> TdApi.Ok() }
            onNext { _ -> TdApi.Ok() }
            // 4. first thread-history page (one comment + the root mirror, filtered).
            onNext { _ ->
                TdApi.Messages().apply {
                    messages = arrayOf(
                        message(id = rootId, threadChatId, fromUserId = 11L),
                        message(id = 100L, threadChatId, fromUserId = 11L),
                    )
                    totalCount = 2
                }
            }
            // Bootstrap collects ALL pages, emitting Ready once; an empty page ends the
            // loop. Both CloseChats fire from the finally block (NonCancellable).
            onAny("GetMessageThreadHistory") { _ ->
                TdApi.Messages().apply { messages = emptyArray(); totalCount = 0 }
            }
            onAny("CloseChat") { _ -> TdApi.Ok() }
        }
        val repo = CommentsRepository(td, fakeMapper(td), TestScope(StandardTestDispatcher(testScheduler)), FakeStrings)

        val ready = repo.observeThread(chatId, listOf(anchor)).first {
            it is CommentsRepository.ThreadState.Ready
        } as CommentsRepository.ThreadState.Ready

        assertEquals(threadChatId, ready.threadChatId)
        assertEquals(1, ready.rows.size, "rootId mirror must be filtered out")
        assertEquals(100L, ready.rows.single().message.id)
    }

    @Test
    fun `UpdateNewMessage from a foreign thread in the same discussion chat is ignored`() = runTest {
        // Regression: a single discussion supergroup hosts every thread for the
        // channel. UpdateNewMessage carries the SAME chatId for every thread; the
        // discriminator is Message.topicId = MessageTopicThread(messageThreadId).
        // Before the fix, applyUpdate() filtered only by chatId — comments authored
        // on a different post (different messageThreadId, same discussion chat)
        // were spliced into the live list of whichever thread the user was
        // currently viewing.
        val chatId = 123L
        val anchor = 7L
        val threadChatId = 9999L
        val rootId = 7L
        val otherThreadRoot = 42L

        val testScope = TestScope(StandardTestDispatcher(testScheduler))
        val td = FakeTdSender().apply {
            onNext { _ ->
                TdApi.MessageThreadInfo().apply {
                    this.chatId = threadChatId
                    this.messageThreadId = rootId
                }
            }
            onNext { _ -> TdApi.Ok() }
            onNext { _ ->
                TdApi.Messages().apply {
                    messages = arrayOf(
                        message(id = rootId, threadChatId, fromUserId = 11L, topicRoot = rootId),
                    )
                    totalCount = 1
                }
            }
            onAny("CloseChat") { _ -> TdApi.Ok() }
            // Subsequent thread-history pages — progressive emit may keep filling.
            onAny("GetMessageThreadHistory") { _ ->
                TdApi.Messages().apply { messages = emptyArray(); totalCount = 0 }
            }
        }
        val repo = CommentsRepository(td, fakeMapper(td), testScope, FakeStrings)

        val collected = mutableListOf<CommentsRepository.ThreadState>()
        val job = testScope.launch {
            repo.observeThread(chatId, listOf(anchor)).collect { collected += it }
        }
        advanceUntilIdle()

        // Foreign-thread comment in the same discussion chat — must NOT appear.
        td.emitUpdate(
            TdApi.UpdateNewMessage(
                message(id = 200L, threadChatId, fromUserId = 22L, topicRoot = otherThreadRoot),
            ),
        )
        advanceUntilIdle()

        // Our-thread comment — must appear.
        td.emitUpdate(
            TdApi.UpdateNewMessage(
                message(id = 201L, threadChatId, fromUserId = 22L, topicRoot = rootId),
            ),
        )
        advanceUntilIdle()

        val final = collected.last() as CommentsRepository.ThreadState.Ready
        val visibleIds = final.rows.map { it.message.id }.toSet()
        assertFalse(200L in visibleIds, "foreign-thread comment must not be visible")
        assertTrue(201L in visibleIds, "our-thread comment must appear")

        job.cancel()
    }

    @Test
    fun `album posted as a comment collapses into a single PhotoAlbum row`() = runTest {
        // Regression: a user/admin posting an album (N >= 2 photos) in a discussion
        // thread produces N separate TdApi.Messages with the same mediaAlbumId. Before
        // the fix, buildTree mapped each 1:1 → N stacked single-photo bubbles, only
        // one of which carried the caption (Telegram pins it to the canonical
        // member) and only one carried reactions (tdlib/td#2312 — interactionInfo on
        // the first id only). The fix routes mapped posts through
        // PostFilterStrategy.mergeAlbums, identical to the feed path.
        val chatId = 123L
        val anchor = 7L
        val threadChatId = 9999L
        val rootId = 7L
        val albumId = 555L

        val td = FakeTdSender().apply {
            onNext { _ ->
                TdApi.MessageThreadInfo().apply {
                    this.chatId = threadChatId
                    this.messageThreadId = rootId
                }
            }
            // OpenChat ×2 — observeThread keeps both the thread chat and the host
            // channel open via withOpenChats, so two OpenChats fire here.
            onNext { _ -> TdApi.Ok() }
            onNext { _ -> TdApi.Ok() }
            onNext { _ ->
                TdApi.Messages().apply {
                    messages = arrayOf(
                        // Root mirror — text, no album, filtered by id == rootId.
                        message(id = rootId, threadChatId, fromUserId = 11L, topicRoot = rootId),
                        // Three album members posted by user 22 AS COMMENTS — same
                        // mediaAlbumId, consecutive ids, each a MessagePhoto.
                        photoMessage(id = 100L, chatId = threadChatId, fromUserId = 22L,
                            topicRoot = rootId, mediaAlbumId = albumId, caption = "shared moments"),
                        photoMessage(id = 101L, chatId = threadChatId, fromUserId = 22L,
                            topicRoot = rootId, mediaAlbumId = albumId),
                        photoMessage(id = 102L, chatId = threadChatId, fromUserId = 22L,
                            topicRoot = rootId, mediaAlbumId = albumId),
                    )
                    totalCount = 4
                }
            }
            onAny("GetMessageThreadHistory") { _ ->
                TdApi.Messages().apply { messages = emptyArray(); totalCount = 0 }
            }
            onAny("CloseChat") { _ -> TdApi.Ok() }
        }
        val repo = CommentsRepository(td, fakeMapper(td), TestScope(StandardTestDispatcher(testScheduler)), FakeStrings)

        val ready = repo.observeThread(chatId, listOf(anchor)).first {
            it is CommentsRepository.ThreadState.Ready
        } as CommentsRepository.ThreadState.Ready

        assertEquals(1, ready.rows.size, "3 album members must merge into ONE thread row")
        val merged = ready.rows.single().message
        assertEquals(100L, merged.id, "merged anchor must be the lowest-id member")
        assertEquals(listOf(100L, 101L, 102L), merged.albumMessageIds)
        val items = (merged.content as PostContent.PhotoAlbum).items
        assertEquals(3, items.size, "merged card must carry all 3 photo items")
    }

    @Test
    fun `root-album mirror siblings are filtered from the thread`() = runTest {
        // Regression: when the channel post itself is an album, TDLib mirrors it
        // into the linked discussion supergroup as N messages with the same
        // mediaAlbumId, all sharing topicId=MessageTopicThread(rootId). The
        // existing seenIds.add(anchor.rootId) gate filtered exactly ONE of those
        // ids; the other (N-1) siblings leaked into the thread as phantom
        // "comments". Visible on every album post that has comments enabled.
        val chatId = 123L
        val anchor = 7L
        val threadChatId = 9999L
        val rootId = 7L
        val rootAlbumId = 444L

        val td = FakeTdSender().apply {
            onNext { _ ->
                TdApi.MessageThreadInfo().apply {
                    this.chatId = threadChatId
                    this.messageThreadId = rootId
                }
            }
            // OpenChat ×2 — observeThread keeps both the thread chat and the host
            // channel open via withOpenChats, so two OpenChats fire here.
            onNext { _ -> TdApi.Ok() }
            onNext { _ -> TdApi.Ok() }
            onNext { _ ->
                TdApi.Messages().apply {
                    messages = arrayOf(
                        // 5-photo root-album mirror — ids 7..11, same mediaAlbumId,
                        // canonical first (lowest id) == rootId. Per
                        // GetMessageThreadHistory ordering (id-desc), siblings come
                        // first and rootId itself last in the batch.
                        photoMessage(id = 11L, chatId = threadChatId, fromUserId = 0L,
                            topicRoot = rootId, mediaAlbumId = rootAlbumId),
                        photoMessage(id = 10L, chatId = threadChatId, fromUserId = 0L,
                            topicRoot = rootId, mediaAlbumId = rootAlbumId),
                        photoMessage(id = 9L, chatId = threadChatId, fromUserId = 0L,
                            topicRoot = rootId, mediaAlbumId = rootAlbumId),
                        photoMessage(id = 8L, chatId = threadChatId, fromUserId = 0L,
                            topicRoot = rootId, mediaAlbumId = rootAlbumId),
                        photoMessage(id = rootId, chatId = threadChatId, fromUserId = 0L,
                            topicRoot = rootId, mediaAlbumId = rootAlbumId, caption = "channel post"),
                        // One actual user comment.
                        message(id = 200L, chatId = threadChatId, fromUserId = 22L, topicRoot = rootId),
                    )
                    totalCount = 6
                }
            }
            onAny("GetMessageThreadHistory") { _ ->
                TdApi.Messages().apply { messages = emptyArray(); totalCount = 0 }
            }
            onAny("CloseChat") { _ -> TdApi.Ok() }
        }
        val repo = CommentsRepository(td, fakeMapper(td), TestScope(StandardTestDispatcher(testScheduler)), FakeStrings)

        val ready = repo.observeThread(chatId, listOf(anchor)).first {
            it is CommentsRepository.ThreadState.Ready
        } as CommentsRepository.ThreadState.Ready

        val ids = ready.rows.map { it.message.id }
        assertEquals(listOf(200L), ids,
            "thread must contain only the actual comment — root-album mirror members (8,9,10,11) must be filtered")
    }

    @Test
    fun `reply targeting a mid-album member is reparented to the merged anchor`() = runTest {
        // Telegram-UX reroutes "reply" on any album photo to the canonical (first)
        // member, but Telegram-server still accepts a reply pointing to ANY album
        // sibling id (e.g. via API clients). After merging, the canonical anchor is
        // the lowest-id member; the buildTree parent-lookup must redirect reply ids
        // referencing any album member to that canonical id, otherwise the reply
        // falls out of the tree (parent ∉ mapped → collapsed to depth-0).
        val chatId = 123L
        val anchor = 7L
        val threadChatId = 9999L
        val rootId = 7L
        val albumId = 555L

        val td = FakeTdSender().apply {
            onNext { _ ->
                TdApi.MessageThreadInfo().apply {
                    this.chatId = threadChatId
                    this.messageThreadId = rootId
                }
            }
            // OpenChat ×2 — observeThread keeps both the thread chat and the host
            // channel open via withOpenChats, so two OpenChats fire here.
            onNext { _ -> TdApi.Ok() }
            onNext { _ -> TdApi.Ok() }
            onNext { _ ->
                TdApi.Messages().apply {
                    messages = arrayOf(
                        message(id = rootId, threadChatId, fromUserId = 11L, topicRoot = rootId),
                        photoMessage(id = 100L, chatId = threadChatId, fromUserId = 22L,
                            topicRoot = rootId, mediaAlbumId = albumId),
                        photoMessage(id = 101L, chatId = threadChatId, fromUserId = 22L,
                            topicRoot = rootId, mediaAlbumId = albumId),
                        photoMessage(id = 102L, chatId = threadChatId, fromUserId = 22L,
                            topicRoot = rootId, mediaAlbumId = albumId),
                        // Reply pointing at the MIDDLE album member (101), not the
                        // canonical anchor (100).
                        message(id = 200L, chatId = threadChatId, fromUserId = 33L,
                            topicRoot = rootId, replyToMessageId = 101L),
                    )
                    totalCount = 5
                }
            }
            onAny("GetMessageThreadHistory") { _ ->
                TdApi.Messages().apply { messages = emptyArray(); totalCount = 0 }
            }
            onAny("CloseChat") { _ -> TdApi.Ok() }
        }
        val repo = CommentsRepository(td, fakeMapper(td), TestScope(StandardTestDispatcher(testScheduler)), FakeStrings)

        val ready = repo.observeThread(chatId, listOf(anchor)).first {
            it is CommentsRepository.ThreadState.Ready
        } as CommentsRepository.ThreadState.Ready

        assertEquals(2, ready.rows.size, "merged album + reply = 2 rows")
        val albumRow = ready.rows.first { it.message.id == 100L }
        val replyRow = ready.rows.first { it.message.id == 200L }
        assertEquals(0, albumRow.depth, "merged album sits at depth 0")
        assertEquals(1, replyRow.depth,
            "reply must be reparented to canonical anchor (100), not collapse to depth 0")
    }

    private fun fakeMapper(td: TdSender): MessageMapper = MessageMapper(td, FakeStrings)

    private fun message(
        id: Long,
        chatId: Long,
        fromUserId: Long,
        topicRoot: Long? = null,
        replyToMessageId: Long? = null,
    ): TdApi.Message {
        // Minimal Message — only the fields the mapper + tree builder touch.
        return TdApi.Message().apply {
            this.id = id
            this.chatId = chatId
            this.senderId = TdApi.MessageSenderUser(fromUserId)
            this.date = 1700000000
            this.content = TdApi.MessageText(TdApi.FormattedText("hi", emptyArray()), null, null)
            if (topicRoot != null) this.topicId = TdApi.MessageTopicThread(topicRoot)
            if (replyToMessageId != null) {
                this.replyTo = TdApi.MessageReplyToMessage().apply {
                    this.chatId = chatId
                    this.messageId = replyToMessageId
                }
            }
        }
    }

    private fun photoMessage(
        id: Long,
        chatId: Long,
        fromUserId: Long,
        topicRoot: Long,
        mediaAlbumId: Long,
        caption: String = "",
    ): TdApi.Message = TdApi.Message().apply {
        this.id = id
        this.chatId = chatId
        this.senderId =
            if (fromUserId == 0L) TdApi.MessageSenderChat(chatId)
            else TdApi.MessageSenderUser(fromUserId)
        this.date = 1700000000
        this.mediaAlbumId = mediaAlbumId
        this.content = TdApi.MessagePhoto().apply {
            photo = TdApi.Photo(false, null, emptyArray())
            this.caption = TdApi.FormattedText(caption, emptyArray())
        }
        this.topicId = TdApi.MessageTopicThread(topicRoot)
    }
}
