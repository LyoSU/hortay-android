package dev.lyo.hortay.data

import dev.lyo.hortay.testutil.PostsRepositoryTestHarness
import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.TdApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class PostsRepositoryUpdateChatLastMessageTest {

    @Test
    fun `UpdateChatLastMessage on a known chat ingests the message into the feed`() = runTest {
        val harness = PostsRepositoryTestHarness(this)

        // Step A: seed a channel chat with no lastMessage yet via UpdateNewChat.
        val chat = harness.fakeChannel(id = -1001L, title = "Test channel", lastMessage = null)
        harness.td.emitUpdate(TdApi.UpdateNewChat(chat))
        harness.advanceUntilIdle()
        // Verify UpdateNewChat seeded the cache before proceeding.
        assertNotNull(harness.repo.chatCacheForTest(chat.id),
            "UpdateNewChat must seed chatCache before the UpdateChatLastMessage test")

        // Step B: emit UpdateChatLastMessage with a fresh message.
        val msg = harness.fakeChannelMessage(chatId = chat.id, messageId = 42L, date = 1_700_000_000)
        harness.td.emitUpdate(TdApi.UpdateChatLastMessage(chat.id, msg, emptyArray()))
        harness.advanceUntilIdle()

        // Then: the feed contains the post.
        // Note: we deliberately do NOT mutate chat.lastMessage in the listener — that
        // was a write-through race on a shared mutable TdApi.Chat object (see the
        // UpdateChatLastMessage listener comment in PostsRepository). The cache field
        // stays null; feed correctness depends on ingest, not on the cache field.
        val cachedChat = harness.repo.chatCacheForTest(chat.id)
        assertEquals(1, harness.repo.posts.value.size,
            "feed should have 1 post after UpdateChatLastMessage; chatCache hit: ${cachedChat != null}")
        assertEquals(msg.id, harness.repo.posts.value[0].id)
    }

    @Test
    fun `UpdateChatLastMessage twice with the same message id does not duplicate the feed entry`() = runTest {
        val harness = PostsRepositoryTestHarness(this)
        val chat = harness.fakeChannel(id = -1002L, title = "Same msg")
        val msg = harness.fakeChannelMessage(chatId = chat.id, messageId = 7L, date = 1_700_000_000)

        harness.td.emitUpdate(TdApi.UpdateNewChat(chat))
        harness.runCurrent()
        harness.td.emitUpdate(TdApi.UpdateChatLastMessage(chat.id, msg, emptyArray()))
        harness.advanceUntilIdle()
        harness.td.emitUpdate(TdApi.UpdateChatLastMessage(chat.id, msg, emptyArray()))
        harness.advanceUntilIdle()

        assertEquals(1, harness.repo.posts.value.size,
            "second UpdateChatLastMessage with the same id must be de-duped by ingest")
    }
}
