package dev.lyo.hortay.data

import dev.lyo.hortay.testutil.PostsRepositoryTestHarness
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.TdApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
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
        assertNotNull(harness.registry.cachedChat(chat.id),
            "UpdateNewChat must register the chat before the UpdateChatLastMessage test")

        // Step B: emit UpdateChatLastMessage with a fresh message.
        val msg = harness.fakeChannelMessage(chatId = chat.id, messageId = 42L, date = 1_700_000_000)
        harness.td.emitUpdate(TdApi.UpdateChatLastMessage(chat.id, msg, emptyArray()))
        harness.advanceUntilIdle()

        // Then: the feed contains the post.
        // Note: we deliberately do NOT mutate chat.lastMessage in the listener — that
        // was a write-through race on a shared mutable TdApi.Chat object (see the
        // UpdateChatLastMessage listener comment in PostsRepository). The cache field
        // stays null; feed correctness depends on ingest, not on the cache field.
        val cachedChat = harness.registry.cachedChat(chat.id)
        assertEquals(1, harness.repo.posts.value.size,
            "feed should have 1 post after UpdateChatLastMessage; chat registered: ${cachedChat != null}")
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

    @Test
    fun `UpdateChatLastMessage after a title change still ingests instead of parking forever`() = runTest {
        val harness = PostsRepositoryTestHarness(this)
        val chat = harness.fakeChannel(id = -1003L, title = "Before", lastMessage = null)
        harness.td.emitUpdate(TdApi.UpdateNewChat(chat))
        harness.advanceUntilIdle()

        // A rename evicts the cached POJO; UpdateNewChat will never fire again for this chat.
        harness.td.emitUpdate(TdApi.UpdateChatTitle(chat.id, "After"))
        harness.advanceUntilIdle()

        val msg = harness.fakeChannelMessage(chatId = chat.id, messageId = 99L, date = 1_700_000_000)
        harness.td.emitUpdate(TdApi.UpdateChatLastMessage(chat.id, msg, emptyArray()))
        harness.advanceUntilIdle()

        assertEquals(listOf(99L), harness.repo.posts.value.map { it.id },
            "a known-but-evicted chat's last message must reach the feed via the offline GetChat")
    }

    @Test
    fun `a cold-start lastMessage whose ingest waits does not stall the update collector`() = runTest {
        // Before the initial sync, an UpdateChatLastMessage used to be ingested INLINE on
        // the single update collector. Anything ingest waits on (here the GetChat re-read
        // of an evicted chat; in production a networked reply GetMessage behind the
        // FLOOD_WAIT gate) then held back every later update.
        val harness = PostsRepositoryTestHarness(this)
        val chat = harness.fakeChannel(id = -1004L, title = "Before", lastMessage = null)
        harness.td.emitUpdate(TdApi.UpdateNewChat(chat))
        harness.advanceUntilIdle()
        harness.td.emitUpdate(TdApi.UpdateChatTitle(chat.id, "After"))
        harness.advanceUntilIdle()

        val gate = CompletableDeferred<Unit>()
        harness.td.onAnySuspending("GetChat") {
            gate.await()
            harness.fakeChannel(id = chat.id, title = "After")
        }
        val msg = harness.fakeChannelMessage(chatId = chat.id, messageId = 77L, date = 1_700_000_000)
        harness.td.emitUpdate(TdApi.UpdateChatLastMessage(chat.id, msg, emptyArray()))
        harness.runCurrent()
        assertTrue(harness.repo.posts.value.isEmpty(), "precondition: the ingest is parked on GetChat")

        harness.td.emitUpdate(TdApi.UpdateChatReadInbox(chat.id, 70L, 3))
        harness.runCurrent()
        assertEquals(70L, harness.repo.chatReadCursors.value[chat.id],
            "updates after the parked lastMessage must still be handled")

        gate.complete(Unit)
        harness.advanceUntilIdle()
        assertEquals(listOf(77L), harness.repo.posts.value.map { it.id },
            "the queued lastMessage still lands once its ingest resumes")
    }
}
