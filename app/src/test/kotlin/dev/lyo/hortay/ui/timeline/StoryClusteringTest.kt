package dev.lyo.hortay.ui.timeline

import dev.lyo.hortay.data.FormattedText
import dev.lyo.hortay.data.PostContent
import dev.lyo.hortay.data.Reactions
import dev.lyo.hortay.data.TimelinePost
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

class StoryClusteringTest {

    private val hour = 60 * 60 * 1000L

    /** A six-letter word unique to [n], so filler posts never share a stem by accident. */
    private fun word(n: Int): String {
        var x = n
        return buildString {
            repeat(6) {
                append('a' + x % 26)
                x /= 26
            }
        }.let { "q$it" }
    }

    /** Unrelated background posts: they give the IDF a realistic base, as a real feed does. */
    private fun filler(count: Int = 30, firstChat: Long = 100): List<StoryPost> = (0 until count).map { i ->
        StoryPost(
            key = "f$i",
            chatId = firstChat + i,
            date = 0L,
            text = (0 until 10).joinToString(" ") { j -> word(1000 + i * 10 + j) },
        )
    }

    private val tankerStory =
        "Tanker drifted towards Gotland harbour, the coastguard evacuated 42 sailors overnight"
    private val bridgeStory =
        "Engineers closed the Darnytskyi bridge after inspectors found cracked girders, repairs 17 weeks"

    /** One channel's version of a story: the shared wording plus two words of its own. */
    private fun story(key: String, chatId: Long, date: Long = 0L, text: String = tankerStory) =
        StoryPost(key, chatId, date, "$text ${word(5_000 + key.hashCode() % 50_000)} ${word(60_000 + key.hashCode() % 50_000)}")

    private fun cluster(posts: List<StoryPost>) = StoryClusterer().cluster(posts)

    @Test
    fun `tokeniser stems words, keeps numbers, drops years, urls and stop words`() {
        val tokens = storyLineTokens("Обстріли Харкова: 42 поранених у 2025 році https://t.me/chan/7 Kyiv")
        assertTrue("обстрі" in tokens)
        assertTrue("харков" in tokens)
        assertTrue("поране" in tokens)
        assertTrue("#42" in tokens)
        assertTrue("kyiv" in tokens)
        assertFalse("#2025" in tokens, "years are dropped")
        assertFalse("році" in tokens, "stop word")
        assertFalse(tokens.any { it.startsWith("http") || it == "chan" || it == "#7" }, "urls are removed")
        assertFalse("у" in tokens, "short words are dropped")
    }

    @Test
    fun `footer lines repeated across a channel are stripped`() {
        val footer = "Subscribe to Daily Wire"
        val lines = (0 until 5).map { i ->
            1L to (if (i < 4) setOf("story number $i body", footer) else setOf("story number $i body"))
        } + (0 until 5).map { i -> 2L to setOf("other story $i", footer.takeIf { i == 0 } ?: "x$i") }
        val footers = footerLines(lines)
        assertEquals(setOf(footer), footers[1L])
        assertTrue(footers[2L].isNullOrEmpty(), "a line seen once is not a footer")

        val tokens = storyTokens("Tanker drifted near Gotland\n$footer", footers.getValue(1L))
        assertEquals(setOf("tanker", "drifte", "near", "gotlan"), tokens)
    }

    @Test
    fun `a story told by three channels links every post to the other two channels`() {
        val result = cluster(filler() + story("a", 1) + story("b", 2) + story("c", 3))
        assertEquals(setOf("a", "b", "c"), result.keys)
        assertEquals(listOf(2L, 3L), result.getValue("a").map { it.chatId }.sorted())
        assertEquals(listOf(1L, 3L), result.getValue("b").map { it.chatId }.sorted())
    }

    @Test
    fun `two channels are not a story`() {
        assertTrue(cluster(filler() + story("a", 1) + story("b", 2)).isEmpty())
    }

    @Test
    fun `repeats from one channel do not count as another channel`() {
        val posts = filler() + story("a1", 1) + story("a2", 1, date = hour) + story("a3", 1, date = 2 * hour) + story("b", 2)
        assertTrue(cluster(posts).isEmpty())
    }

    @Test
    fun `posts more than 24 hours apart do not link`() {
        val late = cluster(filler() + story("a", 1) + story("b", 2, date = hour) + story("c", 3, date = 26 * hour))
        assertTrue(late.isEmpty())
        val inside = cluster(filler() + story("a", 1) + story("b", 2, date = hour) + story("c", 3, date = 23 * hour))
        assertEquals(setOf("a", "b", "c"), inside.keys)
    }

    @Test
    fun `short template posts are ignored`() {
        val alerts = (1L..4L).map { StoryPost("alert$it", it, 0L, "Air raid alert, Kyiv region") }
        assertTrue(cluster(filler() + alerts).isEmpty())
    }

    @Test
    fun `two unrelated stories sharing one word stay apart`() {
        val shared = " Zaporizhzhia"
        val posts = filler() +
            story("a", 1, text = tankerStory + shared) + story("b", 2, text = tankerStory + shared) +
            story("c", 3, text = bridgeStory + shared) + story("d", 4, text = bridgeStory + shared)
        assertTrue(cluster(posts).isEmpty())

        val withThird = posts + story("e", 5, text = bridgeStory + shared)
        val result = cluster(withThird)
        assertEquals(setOf("c", "d", "e"), result.keys)
        assertTrue(result.values.flatten().none { it.chatId == 1L || it.chatId == 2L })
    }

    @Test
    fun `result does not depend on input order`() {
        val posts = filler() + story("a", 1) + story("b", 2, date = hour) + story("c", 3) +
            story("d", 4, text = bridgeStory) + story("e", 5, text = bridgeStory) + story("f", 6, text = bridgeStory)
        val expected = cluster(posts)
        assertEquals(6, expected.size)
        repeat(5) { seed -> assertEquals(expected, cluster(posts.shuffled(Random(seed)))) }
    }

    @Test
    fun `each post is tokenised once until its text changes`() {
        val clusterer = StoryClusterer()
        val posts = filler() + story("a", 1) + story("b", 2) + story("c", 3)
        clusterer.cluster(posts)
        assertEquals(posts.size, clusterer.tokenisedCount)
        clusterer.cluster(posts)
        assertEquals(posts.size, clusterer.tokenisedCount)
        val edited = posts.map { if (it.key == "a") it.copy(text = "edited", version = 1) else it }
        val result = clusterer.cluster(edited)
        assertEquals(posts.size + 1, clusterer.tokenisedCount)
        assertTrue(result.isEmpty(), "the edited post left the story, leaving two channels")
    }

    @Test
    fun `a pass started before a session reset does not publish, and the next one starts cold`() {
        val inner = StoryClusterer()
        val session = SessionStoryClusterer(inner)
        val posts = filler() + story("a", 1) + story("b", 2) + story("c", 3)
        val before = session.currentGeneration
        assertEquals(3, session.cluster(posts, before)?.size)
        assertEquals(posts.size, inner.tokenisedCount)

        // The feed was read under `before`; the session ends before the pass runs.
        val next = session.reset()
        assertEquals(null, session.cluster(posts, before))
        assertEquals(posts.size, inner.tokenisedCount, "a stale pass does no work")

        // The first pass of the new session clears the previous account's cache first.
        assertEquals(3, session.cluster(posts, next)?.size)
        assertEquals(2 * posts.size, inner.tokenisedCount)
    }

    @Test
    fun `a reset never waits on a running pass, whose generation it retires`() {
        val session = SessionStoryClusterer()
        val posts = filler() + story("a", 1) + story("b", 2) + story("c", 3)
        val generation = session.currentGeneration
        val started = java.util.concurrent.CountDownLatch(1)
        val resetDone = java.util.concurrent.CountDownLatch(1)
        // Hold the monitor the way a long pass does; reset must not wait for it.
        val holder = Thread {
            synchronized(session) {
                started.countDown()
                resetDone.await()
            }
        }.apply { start() }
        started.await()
        session.reset()
        resetDone.countDown()
        holder.join()
        assertEquals(null, session.cluster(posts, generation))
    }

    // withStoryCoverage: merging into the feed annotations.

    private fun post(chatId: Long, id: Long): TimelinePost = TimelinePost(
        id = id,
        chatId = chatId,
        mediaAlbumId = 0L,
        senderName = "ch$chatId",
        senderHandle = null,
        avatarThumb = null,
        avatarFileId = null,
        content = PostContent.Text(FormattedText.plain("body")),
        views = 0,
        date = id,
        editDate = 0L,
        forwardOrigin = null,
        authorSignature = null,
        reply = null,
        reactions = Reactions(0, emptyList()),
        commentCount = null,
        albumMessageIds = emptyList(),
    )

    private fun badge(p: TimelinePost) =
        ChannelBadge(chatId = p.chatId, title = p.senderName, thumb = null, fileId = null, latestPostDate = p.date)

    @Test
    fun `coverage names the other channels and leaves existing annotations alone`() {
        val a = post(1, 10)
        val b = post(2, 11)
        val c = post(3, 12)
        val peers = mapOf(a.feedKey() to listOf(StoryPeer(b.feedKey(), 2), StoryPeer(c.feedKey(), 3)))
        val series = FeedAnnotation(seriesHidden = 3, seriesKey = a.feedKey())
        val merged = withStoryCoverage(persistentMapOf(a.feedKey() to series), peers, listOf(a, b, c), ::badge)
        val annotation = merged.getValue(a.feedKey())
        assertEquals(listOf(2L, 3L), annotation.coveredBy.map { it.chatId })
        assertEquals(series, annotation.copy(coveredBy = persistentListOf()))
    }

    @Test
    fun `coverage needs three channels inside the current scope`() {
        val a = post(1, 10)
        val b = post(2, 11)
        val peers = mapOf(a.feedKey() to listOf(StoryPeer(b.feedKey(), 2), StoryPeer("gone", 3)))
        val annotations = persistentMapOf<String, FeedAnnotation>()
        assertSame(annotations, withStoryCoverage(annotations, emptyMap(), listOf(a, b), ::badge))
        assertTrue(withStoryCoverage(annotations, peers, listOf(a, b), ::badge).isEmpty())
    }

    @Test
    fun `a channel already named as a forwarder is not listed again`() {
        val a = post(1, 10)
        val b = post(2, 11)
        val c = post(3, 12)
        val forwarded = FeedAnnotation(forwardedBy = persistentListOf(badge(b)))
        val peers = mapOf(a.feedKey() to listOf(StoryPeer(b.feedKey(), 2), StoryPeer(c.feedKey(), 3)))
        val merged = withStoryCoverage(persistentMapOf(a.feedKey() to forwarded), peers, listOf(a, b, c), ::badge)
        assertEquals(listOf(3L), merged.getValue(a.feedKey()).coveredBy.map { it.chatId })
        assertEquals(listOf(2L), merged.getValue(a.feedKey()).forwardedBy.map { it.chatId })
    }

    @Test
    fun `coverage does not change read state`() {
        val a = post(1, 10)
        val covered = FeedAnnotation(coveredBy = persistentListOf(badge(post(2, 11)), badge(post(3, 12))))
        val cursors = mapOf(1L to 9L, 2L to 100L, 3L to 100L)
        assertEquals(rowHasUnread(a, null) { cursors[it] }, rowHasUnread(a, covered) { cursors[it] })
        assertEquals(1, rowUnreadCount(a, covered) { cursors[it] })
        val read = mapOf(1L to 10L)
        assertFalse(rowHasUnread(a, covered) { read[it] })
    }
}
