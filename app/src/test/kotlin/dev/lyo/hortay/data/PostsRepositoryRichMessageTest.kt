package dev.lyo.hortay.data

import dev.lyo.hortay.data.rich.RichBlock
import dev.lyo.hortay.testutil.PostsRepositoryTestHarness
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.TdApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Covers [dev.lyo.hortay.data.posts.PostsRepository.ensureFullRichMessage] — the on-demand
 * fetch that swaps a partial [PostContent.RichMessage] (`isFull == false`, a truncated block
 * prefix) for its full document via [TdApi.GetFullRichMessage].
 *
 * The [dev.lyo.hortay.data.FakeTdSender] answers `send()` synchronously (no real suspension),
 * so a two-coroutines-in-flight assertion of the [richFullFetchInFlight]-set dedup isn't
 * reproducible here — the first call always completes before the second starts. The set's
 * single-flight guard is verified by inspection; these tests cover the observable contract:
 * exactly one RPC per partial post, a pure content swap, and the short-circuits (already-full,
 * absent post) that keep the shared FLOOD_WAIT budget from being spent needlessly.
 */
class PostsRepositoryRichMessageTest {

    private fun paragraph(text: String): TdApi.PageBlock =
        TdApi.PageBlockParagraph(TdApi.RichTextPlain(text))

    private fun richMessageContent(isFull: Boolean, vararg text: String): TdApi.MessageRichMessage =
        TdApi.MessageRichMessage(
            TdApi.RichMessage(text.map { paragraph(it) }.toTypedArray(), false, isFull),
        )

    private fun PostsRepositoryTestHarness.richChannelMessage(
        chatId: Long,
        messageId: Long,
        content: TdApi.MessageRichMessage,
    ): TdApi.Message = TdApi.Message().apply {
        this.id = messageId
        this.chatId = chatId
        this.date = 1_700_000_000 + messageId.toInt()
        this.mediaAlbumId = 0L
        this.senderId = TdApi.MessageSenderChat(chatId)
        this.content = content
    }

    private suspend fun PostsRepositoryTestHarness.seedPartialRichPost(chatId: Long, messageId: Long) {
        val chat = fakeChannel(
            id = chatId,
            lastMessage = richChannelMessage(chatId, messageId, richMessageContent(isFull = false, "preview")),
        )
        td.emitUpdate(TdApi.UpdateNewChat(chat))
        advanceUntilIdle()
    }

    private fun PostsRepositoryTestHarness.richContentOf(chatId: Long, messageId: Long): PostContent.RichMessage =
        assertInstanceOf(
            PostContent.RichMessage::class.java,
            repo.posts.value.first { it.chatId == chatId && it.id == messageId }.content,
        )

    @Test
    fun `fetches and swaps in the full document for a partial rich post`() = runTest {
        val harness = PostsRepositoryTestHarness(this)
        val chatId = -100L
        val messageId = 7L
        harness.seedPartialRichPost(chatId, messageId)

        // Precondition: the cold-start ingest lands the truncated prefix.
        val before = harness.richContentOf(chatId, messageId)
        assertFalse(before.document.isFull, "precondition: ingested rich post is partial")
        assertEquals(1, before.document.blocks.size)

        harness.td.onAny("GetFullRichMessage") { req ->
            val q = req as TdApi.GetFullRichMessage
            assertEquals(chatId, q.chatId)
            assertEquals(messageId, q.messageId)
            TdApi.RichMessage(
                arrayOf(
                    TdApi.PageBlockParagraph(TdApi.RichTextPlain("preview")),
                    TdApi.PageBlockParagraph(TdApi.RichTextPlain("the rest of the article")),
                ),
                false,
                true,
            )
        }

        harness.repo.ensureFullRichMessage(chatId, messageId)
        harness.advanceUntilIdle()

        val after = harness.richContentOf(chatId, messageId)
        assertTrue(after.document.isFull, "content must be swapped for the full document")
        assertEquals(2, after.document.blocks.size)
        assertInstanceOf(RichBlock.Paragraph::class.java, after.document.blocks[1])
        assertTrue(after.plainText.contains("the rest of the article"), "plainText re-projected")
        assertEquals(1, harness.td.rpcCount("GetFullRichMessage"))
    }

    @Test
    fun `re-triggering an already-full post issues no further RPC`() = runTest {
        val harness = PostsRepositoryTestHarness(this)
        val chatId = -100L
        val messageId = 7L
        harness.seedPartialRichPost(chatId, messageId)
        harness.td.onAny("GetFullRichMessage") { _ ->
            TdApi.RichMessage(arrayOf(TdApi.PageBlockParagraph(TdApi.RichTextPlain("full"))), false, true)
        }

        harness.repo.ensureFullRichMessage(chatId, messageId)
        harness.advanceUntilIdle()
        assertEquals(1, harness.td.rpcCount("GetFullRichMessage"))

        // Second call — the post is now full, so the isFull short-circuit fires before the RPC.
        harness.repo.ensureFullRichMessage(chatId, messageId)
        harness.advanceUntilIdle()
        assertEquals(1, harness.td.rpcCount("GetFullRichMessage"), "already-full post must not re-fetch")
    }

    @Test
    fun `a mid-flight edit wins over a slower full-document fetch`() = runTest {
        val harness = PostsRepositoryTestHarness(this)
        val chatId = -100L
        val messageId = 7L
        harness.seedPartialRichPost(chatId, messageId)

        // The GetFullRichMessage RPC parks on this gate so we can land a fresh edit while the
        // fetch is in flight — the exact race the reference-equality guard protects against.
        val rpcGate = CompletableDeferred<Unit>()
        harness.td.onAnySuspending("GetFullRichMessage") {
            rpcGate.await()
            // Stale full body — reflects the partial's revision, NOT the mid-flight edit.
            TdApi.RichMessage(
                arrayOf(TdApi.PageBlockParagraph(TdApi.RichTextPlain("stale full body"))),
                false,
                true,
            )
        }

        val fetch = launch { harness.repo.ensureFullRichMessage(chatId, messageId) }
        harness.advanceUntilIdle() // ensureFullRichMessage captures the partial, then parks on the RPC gate

        // A newer rich revision lands while the fetch is parked.
        harness.td.emitUpdate(
            TdApi.UpdateMessageContent(chatId, messageId, richMessageContent(isFull = true, "fresh edit")),
        )
        harness.advanceUntilIdle()
        assertTrue(harness.richContentOf(chatId, messageId).plainText.contains("fresh edit"), "edit applied mid-flight")

        // Release the stale RPC — its result must be discarded, not clobber the edit.
        rpcGate.complete(Unit)
        harness.advanceUntilIdle()
        fetch.join()

        val after = harness.richContentOf(chatId, messageId)
        assertTrue(after.plainText.contains("fresh edit"), "the mid-flight edit wins")
        assertFalse(after.plainText.contains("stale"), "the stale full body is discarded")
        assertEquals(1, harness.td.rpcCount("GetFullRichMessage"))
    }

    @Test
    fun `absent post issues no RPC`() = runTest {
        val harness = PostsRepositoryTestHarness(this)
        harness.td.onAny("GetFullRichMessage") { _ ->
            TdApi.RichMessage(emptyArray(), false, true)
        }

        harness.repo.ensureFullRichMessage(chatId = -999L, messageId = 1L)
        harness.advanceUntilIdle()

        assertEquals(0, harness.td.rpcCount("GetFullRichMessage"), "no post to update — no fetch")
    }
}
