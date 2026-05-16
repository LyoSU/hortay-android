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
        // GetMessageProperties and goes directly to GetMessageThread. The
        // responder queue mirrors that order: GetMessageThread → OpenChat →
        // history page → CloseChat.
        val td = FakeTdSender().apply {
            // 1. resolve thread directly (no GetMessageProperties for standalone).
            onNext { _ ->
                TdApi.MessageThreadInfo().apply {
                    this.chatId = threadChatId
                    this.messageThreadId = rootId
                }
            }
            // 2. OpenChat — fired before any history fetch so TDLib prioritises this
            // thread chat and starts streaming updates for it.
            onNext { _ -> TdApi.Ok() }
            // 3. history page (one comment + the root mirror, mirror should be filtered).
            // Progressive emit: Ready surfaces right after this batch, so the test's
            // first{} cancels the flow before any subsequent page is requested.
            onNext { _ ->
                TdApi.Messages().apply {
                    messages = arrayOf(
                        message(id = rootId, threadChatId, fromUserId = 11L),
                        message(id = 100L, threadChatId, fromUserId = 11L),
                    )
                    totalCount = 2
                }
            }
            // 4. CloseChat fires from the flow's finally block on cancellation.
            onNext { _ -> TdApi.Ok() }
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

    private fun fakeMapper(td: TdSender): MessageMapper = MessageMapper(td, FakeStrings)

    private fun message(
        id: Long,
        chatId: Long,
        fromUserId: Long,
        topicRoot: Long? = null,
    ): TdApi.Message {
        // Minimal Message — only the fields the mapper + tree builder touch.
        return TdApi.Message().apply {
            this.id = id
            this.chatId = chatId
            this.senderId = TdApi.MessageSenderUser(fromUserId)
            this.date = 1700000000
            this.content = TdApi.MessageText(TdApi.FormattedText("hi", emptyArray()), null, null)
            if (topicRoot != null) this.topicId = TdApi.MessageTopicThread(topicRoot)
        }
    }
}
