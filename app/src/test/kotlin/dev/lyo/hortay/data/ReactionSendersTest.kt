package dev.lyo.hortay.data

import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import org.drinkless.tdlib.TdApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReactionSendersTest {

    private val heart = ReactionKind.Emoji("❤")
    private val face = ReplierAvatar(name = "A", thumb = null, fileId = null)

    private fun senders(n: Int, withFaces: Boolean = true) = (1..n).map {
        ReactionSender(SenderRef.User(it.toLong()), face.takeIf { withFaces })
    }.toImmutableList()

    @Test
    fun `faces replace the count when every sender of a small bucket is known`() {
        for (n in 1..MAX_REACTION_FACES) {
            assertTrue(ReactionItem(heart, n, recentSenders = senders(n)).showsSenderFaces(), "n=$n")
        }
    }

    @Test
    fun `count stays when the bucket is larger than the face limit`() {
        val n = MAX_REACTION_FACES + 1
        assertFalse(ReactionItem(heart, n, recentSenders = senders(n)).showsSenderFaces())
    }

    @Test
    fun `count stays when the sender list does not match the count`() {
        // Optimistic +1 before TDLib refreshes the senders, and a stale list after a removal.
        assertFalse(ReactionItem(heart, 3, recentSenders = senders(2)).showsSenderFaces())
        assertFalse(ReactionItem(heart, 1, recentSenders = senders(2)).showsSenderFaces())
    }

    @Test
    fun `count stays for anonymous reactions and unresolved faces`() {
        assertFalse(ReactionItem(heart, 2).showsSenderFaces())
        assertFalse(ReactionItem(heart, 2, recentSenders = senders(2, withFaces = false)).showsSenderFaces())
        assertFalse(ReactionItem(heart, 0, recentSenders = persistentListOf()).showsSenderFaces())
    }

    @Test
    fun `optimistic toggle keeps the senders and the list flag`() {
        val current = Reactions(
            totalCount = 2,
            items = listOf(ReactionItem(heart, 2, recentSenders = senders(2))),
            canGetAddedReactions = true,
        )
        val next = ReactionTogglePolicy.apply(current, heart, nowChosen = true)
        assertTrue(next.canGetAddedReactions)
        assertEquals(2, next.items[0].recentSenders.size)
        // Three now, two known senders: the chip falls back to the number until TDLib catches up.
        assertFalse(next.items[0].showsSenderFaces())
    }

    @Test
    fun `mapReactions carries recent senders and the can-list flag`() {
        val reactions = TdApi.MessageReactions().apply {
            canGetAddedReactions = true
            this.reactions = arrayOf(
                TdApi.MessageReaction().apply {
                    type = TdApi.ReactionTypeEmoji("❤")
                    totalCount = 2
                    recentSenderIds = arrayOf(TdApi.MessageSenderUser(7), TdApi.MessageSenderChat(-100))
                },
                TdApi.MessageReaction().apply {
                    type = TdApi.ReactionTypeEmoji("👍")
                    totalCount = 40
                    recentSenderIds = null
                },
            )
        }
        val mapped = MessageContentMapper.mapReactions(reactions)
        assertTrue(mapped.canGetAddedReactions)
        assertEquals(
            listOf(SenderRef.User(7), SenderRef.Chat(-100)),
            mapped.items[0].recentSenders.map { it.ref },
        )
        assertTrue(mapped.items[1].recentSenders.isEmpty())
    }

    @Test
    fun `mapAddedReactions maps rows and skips unknown senders and kinds`() {
        val added = TdApi.AddedReactions(
            12,
            arrayOf(
                TdApi.AddedReaction(TdApi.ReactionTypeEmoji("❤"), TdApi.MessageSenderUser(1), false, 0),
                TdApi.AddedReaction(TdApi.ReactionTypeCustomEmoji(55), TdApi.MessageSenderChat(-2), false, 0),
                TdApi.AddedReaction(TdApi.ReactionTypeEmoji(""), TdApi.MessageSenderUser(3), false, 0),
                TdApi.AddedReaction(TdApi.ReactionTypeEmoji("👍"), null, false, 0),
            ),
            "next",
        )
        val page = MessageContentMapper.mapAddedReactions(added)
        assertEquals(12, page.totalCount)
        assertEquals("next", page.nextOffset)
        assertEquals(
            listOf(
                Reactor(SenderRef.User(1), heart),
                Reactor(SenderRef.Chat(-2), ReactionKind.CustomEmoji(55)),
            ),
            page.reactors,
        )
    }
}
