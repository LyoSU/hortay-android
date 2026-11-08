package dev.lyo.hortay.data

import dev.lyo.hortay.testutil.PostsRepositoryTestHarness
import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.TdApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PostsRepositoryPinnedTest {

    @Test
    fun `pinned messages come back newest first with their excerpts`() = runTest {
        val harness = PostsRepositoryTestHarness(this)
        harness.td.onAny("SearchChatMessages") { req ->
            val search = req as TdApi.SearchChatMessages
            assertTrue(search.filter is TdApi.SearchMessagesFilterPinned)
            TdApi.FoundChatMessages(
                2,
                arrayOf(
                    harness.fakeChannelMessage(search.chatId, 20L, text = "Rules\nsecond line"),
                    harness.fakeChannelMessage(search.chatId, 10L, text = "Welcome"),
                ),
                0L,
            )
        }

        val pinned = harness.repo.pinnedMessages(-100L)

        assertEquals(listOf(20L, 10L), pinned.map { it.messageId })
        assertEquals("Rules\nsecond line", pinned.first().excerpt)
    }

    @Test
    fun `a failed pinned search yields an empty bar instead of an error`() = runTest {
        val harness = PostsRepositoryTestHarness(this)
        harness.td.onAny("SearchChatMessages") { TdApi.Error(400, "CHANNEL_PRIVATE") }

        assertTrue(harness.repo.pinnedMessages(-100L).isEmpty())
    }
}
