package dev.lyo.hortay.data

import dev.lyo.hortay.data.posts.PostsRepository
import dev.lyo.hortay.testutil.PostsRepositoryTestHarness
import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.TdApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Covers [PostsRepository.restoreFromSnapshot] under the cold-start race that
 * ARCHITECTURE.md → "Cold-start snapshot" describes: the snapshot restore and
 * the [PostsRepository.triggerInitialSync]-driven live ingest both write
 * `_posts` around `auth.Ready`, in an undefined order.
 *
 * The hazard: the live cold-start ingest only volunteers `chat.lastMessage`
 * (exactly ONE post per channel). If it lands first, an all-or-nothing
 * "`_posts` non-empty → bail" restore discards the previous session's deep
 * history and the feed collapses to one post per channel after every restart.
 *
 * The restore must therefore be order-independent: a feed that already holds
 * the cold-start stubs must still gain the snapshot's deeper history.
 */
class PostsRepositorySnapshotRestoreTest {

    private fun PostsRepositoryTestHarness.respondGetMessageFromSnapshot() {
        td.onAny("GetMessage") { req ->
            val q = req as TdApi.GetMessage
            fakeChannelMessage(q.chatId, q.messageId, date = 1_700_000_000 + q.messageId.toInt())
        }
    }

    @Test
    fun `restore recovers deep history even when live ingest already seeded one post per channel`() = runTest {
        val harness = PostsRepositoryTestHarness(this)
        val chatId = -100L
        // Previous healthy session persisted five posts for this channel.
        harness.snapshotStore.seed((1L..5L).map { chatId to it })
        harness.respondGetMessageFromSnapshot()

        // Live cold-start ingest lands FIRST: UpdateNewChat carries only the
        // channel's lastMessage (id 5) — one post per channel.
        val chat = harness.fakeChannel(
            id = chatId,
            lastMessage = harness.fakeChannelMessage(chatId, 5L, date = 1_700_000_005),
        )
        harness.td.emitUpdate(TdApi.UpdateNewChat(chat))
        harness.advanceUntilIdle()
        assertEquals(
            setOf(5L), harness.repo.posts.value.map { it.id }.toSet(),
            "precondition: live ingest seeds exactly the lastMessage stub",
        )

        // THEN the snapshot restore fires (loses the race to first paint).
        harness.repo.restoreFromSnapshot()
        harness.advanceUntilIdle()

        val ids = harness.repo.posts.value.map { it.id }.toSet()
        assertTrue(
            ids.containsAll(setOf(1L, 2L, 3L, 4L, 5L)),
            "snapshot's deep history must survive the live stub having won the race; got $ids",
        )
    }

    /**
     * The snapshot store persists only `(chatId, messageId)` pairs and rebuilds every post by
     * re-mapping TDLib's locally-cached message on restore (see [TimelineSnapshotStore] KDoc) —
     * so [PostContent.RichMessage] rides the existing mechanism with no bespoke serialization.
     * A partial rich post round-trips as its honest truncated prefix (`isFull == false`); the
     * detail-mount [dev.lyo.hortay.data.posts.PostsRepository.ensureFullRichMessage] fetch
     * restores the full body on demand, and the live stream overwrites the snapshot per the
     * cold-start-snapshot contract.
     */
    @Test
    fun `restore rebuilds a partial rich message post from its id`() = runTest {
        val harness = PostsRepositoryTestHarness(this)
        val chatId = -300L
        val messageId = 9L
        harness.snapshotStore.seed(listOf(chatId to messageId))
        harness.td.onAny("GetMessage") { req ->
            val q = req as TdApi.GetMessage
            TdApi.Message().apply {
                id = q.messageId
                this.chatId = q.chatId
                date = 1_700_000_000 + q.messageId.toInt()
                senderId = TdApi.MessageSenderChat(q.chatId)
                content = TdApi.MessageRichMessage(
                    TdApi.RichMessage(
                        arrayOf(TdApi.PageBlockParagraph(TdApi.RichTextPlain("instant-view prefix"))),
                        false,
                        false,
                    ),
                )
            }
        }

        harness.repo.restoreFromSnapshot()
        harness.advanceUntilIdle()

        val post = harness.repo.posts.value.single { it.chatId == chatId && it.id == messageId }
        val rich = assertInstanceOf(PostContent.RichMessage::class.java, post.content)
        assertFalse(rich.document.isFull, "partial rich content round-trips honestly")
        assertEquals("instant-view prefix", rich.plainText)
    }

    @Test
    fun `restore fills an empty feed (snapshot wins the race)`() = runTest {
        val harness = PostsRepositoryTestHarness(this)
        val chatId = -200L
        harness.snapshotStore.seed((1L..3L).map { chatId to it })
        harness.respondGetMessageFromSnapshot()

        // No live ingest yet — restore lands on an empty feed.
        harness.repo.restoreFromSnapshot()
        harness.advanceUntilIdle()

        val ids = harness.repo.posts.value.map { it.id }.toSet()
        assertEquals(setOf(1L, 2L, 3L), ids, "empty-feed restore must paint the full snapshot")
    }
}
