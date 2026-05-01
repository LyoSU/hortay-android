package dev.lyo.hortay.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.TdApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommentsRepositoryTest {

    @Test
    fun `error state when no candidate has canGetMessageThread`() = runTest {
        val td = FakeTdSender().apply {
            onNext { _ -> TdApi.MessageProperties().apply { canGetMessageThread = false } }
        }
        val repo = CommentsRepository(td, fakeMapper(td), TestScope(StandardTestDispatcher(testScheduler)), FakeStrings)

        val state = repo.observeThread(chatId = 123L, candidateMessageIds = listOf(1L)).first()

        assertTrue(state is CommentsRepository.ThreadState.Error)
    }

    @Test
    fun `ready state once anchor and history resolve`() = runTest {
        val chatId = 123L
        val anchor = 7L
        val threadChatId = 9999L
        val rootId = 7L

        val td = FakeTdSender().apply {
            // 1. probe canGetMessageThread.
            onNext { _ -> TdApi.MessageProperties().apply { canGetMessageThread = true } }
            // 2. resolve thread.
            onNext { _ ->
                TdApi.MessageThreadInfo().apply {
                    this.chatId = threadChatId
                    this.messageThreadId = rootId
                }
            }
            // 3. OpenChat — fired before any history fetch so TDLib prioritises this
            // thread chat and starts streaming updates for it.
            onNext { _ -> TdApi.Ok() }
            // 4. history page (one comment + the root mirror, mirror should be filtered).
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
            // 5. CloseChat fires from the flow's finally block on cancellation.
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

    private fun fakeMapper(td: TdSender): MessageMapper = MessageMapper(td, FakeStrings)

    private fun message(id: Long, chatId: Long, fromUserId: Long): TdApi.Message {
        // Minimal Message — only the fields the mapper + tree builder touch.
        return TdApi.Message().apply {
            this.id = id
            this.chatId = chatId
            this.senderId = TdApi.MessageSenderUser(fromUserId)
            this.date = 1700000000
            this.content = TdApi.MessageText(TdApi.FormattedText("hi", emptyArray()), null, null)
        }
    }
}
