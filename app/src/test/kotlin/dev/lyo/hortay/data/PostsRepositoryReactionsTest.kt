package dev.lyo.hortay.data

import dev.lyo.hortay.testutil.PostsRepositoryTestHarness
import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.TdApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * End-to-end reaction plumbing through [dev.lyo.hortay.data.posts.PostsRepository]: a
 * message's own `interactionInfo.reactions` on ingest, and a live
 * `UpdateMessageInteractionInfo` landing on an already-ingested post.
 */
class PostsRepositoryReactionsTest {

    private fun reactions(vararg pairs: Pair<String, Int>) = TdApi.MessageReactions().apply {
        reactions = pairs.map { (emoji, count) ->
            TdApi.MessageReaction().apply {
                type = TdApi.ReactionTypeEmoji(emoji)
                totalCount = count
                recentSenderIds = emptyArray()
            }
        }.toTypedArray()
    }

    @Test
    fun `reactions carried by the ingested message reach the post`() = runTest {
        val harness = PostsRepositoryTestHarness(this)
        val msg = harness.fakeChannelMessage(-100L, 10L).apply {
            interactionInfo = TdApi.MessageInteractionInfo().apply {
                viewCount = 5
                reactions = reactions("👍" to 3)
            }
        }
        harness.td.emitUpdate(TdApi.UpdateNewChat(harness.fakeChannel(-100L, lastMessage = msg)))
        harness.advanceUntilIdle()

        val post = harness.repo.posts.value.single()
        assertEquals(listOf(3), post.reactions.items.map { it.count })
    }

    @Test
    fun `a live interaction update adds reactions to the post`() = runTest {
        val harness = PostsRepositoryTestHarness(this)
        val msg = harness.fakeChannelMessage(-100L, 10L)
        harness.td.emitUpdate(TdApi.UpdateNewChat(harness.fakeChannel(-100L, lastMessage = msg)))
        harness.advanceUntilIdle()

        harness.td.emitUpdate(
            TdApi.UpdateMessageInteractionInfo(
                -100L,
                10L,
                TdApi.MessageInteractionInfo().apply {
                    viewCount = 7
                    reactions = reactions("❤" to 2, "🔥" to 1)
                },
            ),
        )
        harness.advanceUntilIdle()

        val post = harness.repo.posts.value.single()
        assertEquals(7, post.views)
        assertEquals(listOf(2, 1), post.reactions.items.map { it.count })
    }
}
