package dev.lyo.hortay.ui.timeline

import dev.lyo.hortay.data.ForwardOrigin
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
        ChannelBadge(chatId = p.chatId, title = p.senderName, thumb = p.avatarThumb, fileId = null, latestPostDate = p.date)

    private fun scope(vararg posts: TimelinePost) = storyScopeOf(posts.toList(), ::badge)

    @Test
    fun `coverage names the other channels and leaves existing annotations alone`() {
        val a = post(1, 10)
        val b = post(2, 11)
        val c = post(3, 12)
        val peers = mapOf(a.feedKey() to listOf(StoryPeer(b.feedKey(), 2), StoryPeer(c.feedKey(), 3)))
        val series = FeedAnnotation(seriesHidden = 3, seriesKey = a.feedKey())
        val merged = withStoryCoverage(persistentMapOf(a.feedKey() to series), peers, scope(a, b, c))
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
        assertSame(annotations, withStoryCoverage(annotations, emptyMap(), scope(a, b)))
        assertTrue(withStoryCoverage(annotations, peers, scope(a, b)).isEmpty())
    }

    @Test
    fun `a channel already named as a forwarder is not listed again`() {
        val a = post(1, 10)
        val b = post(2, 11)
        val c = post(3, 12)
        val d = post(4, 13)
        val forwarded = FeedAnnotation(forwardedBy = persistentListOf(badge(b)))
        val peers = mapOf(
            a.feedKey() to listOf(StoryPeer(b.feedKey(), 2), StoryPeer(c.feedKey(), 3), StoryPeer(d.feedKey(), 4)),
        )
        val merged = withStoryCoverage(persistentMapOf(a.feedKey() to forwarded), peers, scope(a, b, c, d))
        assertEquals(listOf(3L, 4L), merged.getValue(a.feedKey()).coveredBy.map { it.chatId })
        assertEquals(listOf(2L), merged.getValue(a.feedKey()).forwardedBy.map { it.chatId })
    }

    @Test
    fun `the three-channel minimum is checked after the forwarders are taken out`() {
        val a = post(1, 10)
        val b = post(2, 11)
        val c = post(3, 12)
        val forwarded = FeedAnnotation(forwardedBy = persistentListOf(badge(b)))
        val peers = mapOf(a.feedKey() to listOf(StoryPeer(b.feedKey(), 2), StoryPeer(c.feedKey(), 3)))
        val annotations = persistentMapOf(a.feedKey() to forwarded)
        val merged = withStoryCoverage(annotations, peers, scope(a, b, c))
        assertEquals(annotations, merged)
        assertTrue(merged.getValue(a.feedKey()).coveredBy.isEmpty())
    }

    @Test
    fun `a views or reactions tick leaves the scope and the coverage equal`() {
        val a = post(1, 10).copy(avatarThumb = byteArrayOf(1, 2, 3))
        val b = post(2, 11).copy(avatarThumb = byteArrayOf(4, 5))
        val c = post(3, 12)
        // The tick re-maps every post: new list, new thumb arrays with the same bytes, new counters.
        val ticked = listOf(a, b, c).map {
            it.copy(views = it.views + 7, forwardCount = 3, avatarThumb = it.avatarThumb?.copyOf())
        }
        val before = storyScopeOf(listOf(a, b, c), ::badge)
        val after = storyScopeOf(ticked, ::badge)
        assertEquals(before, after)
        val peers = mapOf(a.feedKey() to listOf(StoryPeer(b.feedKey(), 2), StoryPeer(c.feedKey(), 3)))
        val annotations = persistentMapOf<String, FeedAnnotation>()
        assertEquals(withStoryCoverage(annotations, peers, before), withStoryCoverage(annotations, peers, after))
        // A real change of what the line draws (a newer post of the channel) does not compare equal.
        assertFalse(before == storyScopeOf(ticked + post(3, 99), ::badge))
    }

    @Test
    fun `forwards of any origin are not clustering input`() {
        val own = post(1, 10).copy(content = PostContent.Text(FormattedText.plain("own words")))
        val origins = listOf(
            ForwardOrigin.Channel("src", null, sourceChatId = 9, sourceHandle = null, sourceMessageId = 5),
            ForwardOrigin.Chat("grp", null, sourceChatId = 8, sourceHandle = null),
            ForwardOrigin.User("user", userId = 7),
            ForwardOrigin.HiddenUser("hidden"),
        )
        val forwards = origins.mapIndexed { i, origin ->
            post(2L + i, 20L + i).copy(
                content = PostContent.Text(FormattedText.plain("forwarded words")),
                forwardOrigin = origin,
            )
        }
        assertEquals(listOf(own.feedKey()), storyInputOf(listOf(own) + forwards).map { it.key })
    }

    @Test
    fun `an original and its forward do not make a three-channel story with one more channel`() {
        fun text(t: String) = PostContent.Text(FormattedText.plain(t))
        val original = post(1, 10).copy(date = 0L, content = text("$tankerStory ${word(70_001)}"))
        val forward = post(2, 11).copy(
            date = hour,
            content = text("$tankerStory ${word(70_001)}"),
            forwardOrigin = ForwardOrigin.Channel("ch1", null, sourceChatId = 1, sourceHandle = null, sourceMessageId = 10),
        )
        val independent = post(3, 12).copy(date = 2 * hour, content = text("$tankerStory ${word(70_002)}"))
        val feed = listOf(original, forward, independent)
        // Control: counted as its own channel, the forward would make this a story.
        val withForward = feed.map { StoryPost(it.feedKey(), it.chatId, it.date, it.content.captionPlain) }
        assertTrue(cluster(withForward + filler()).isNotEmpty())
        // Only two channels wrote their own text: below the minimum, no story at all.
        val input = storyInputOf(feed)
        assertEquals(setOf(1L, 3L), input.map { it.chatId }.toSet())
        assertTrue(cluster(input + filler()).isEmpty())
    }

    @Test
    fun `the clustering input ignores views and reactions`() {
        val a = post(1, 10).copy(content = PostContent.Text(FormattedText.plain("some words here")))
        val ticked = a.copy(views = 500, forwardCount = 4, commentCount = 12)
        assertEquals(storyInputOf(listOf(a)), storyInputOf(listOf(ticked)))
        val edited = a.copy(editDate = 99L, content = PostContent.Text(FormattedText.plain("other words here")))
        assertFalse(storyInputOf(listOf(a)) == storyInputOf(listOf(edited)))
    }

    @Test
    fun `coverage peers drop forwarders and out-of-scope chats and keep clusterer order`() {
        val a = post(1, 10)
        val b = post(2, 11)
        val c = post(3, 12)
        val d = post(4, 13)
        val peers = listOf(
            StoryPeer(d.feedKey(), 4),
            StoryPeer(b.feedKey(), 2),
            StoryPeer("out-of-scope", 9),
            StoryPeer(c.feedKey(), 3),
        )
        val listed = coveragePeersOf(peers, listOf(badge(b)), scope(a, b, c, d))
        assertEquals(listOf(4L, 3L), listed.map { it.first.chatId })
        assertEquals(listOf(4L, 3L), listed.map { it.second.chatId })
        // Below the minimum once the forwarder is out: nothing.
        assertTrue(coveragePeersOf(peers.take(2), listOf(badge(b)), scope(a, b, c, d)).isEmpty())
    }

    @Test
    fun `the coverage sheet lists exactly the channels the line names`() {
        val a = post(1, 10)
        val b = post(2, 11)
        val c = post(3, 12).copy(content = PostContent.Text(FormattedText.plain("  the tanker story  ")))
        val d = post(4, 13)
        val forwarded = FeedAnnotation(forwardedBy = persistentListOf(badge(b)))
        val peers = mapOf(
            a.feedKey() to listOf(StoryPeer(b.feedKey(), 2), StoryPeer(c.feedKey(), 3), StoryPeer(d.feedKey(), 4)),
        )
        val feed = listOf(a, b, c, d)
        val line = withStoryCoverage(persistentMapOf(a.feedKey() to forwarded), peers, scope(a, b, c, d))
        val items = storyCoverageItems(a.feedKey(), peers, forwarded, scope(a, b, c, d), feed)
        assertEquals(line.getValue(a.feedKey()).coveredBy.map { it.chatId }, items.map { it.chatId })
        assertEquals(listOf(12L, 13L), items.map { it.messageId })
        assertEquals("the tanker story", items.first().excerpt)
        assertEquals(c.date, items.first().date)
    }

    @Test
    fun `the coverage sheet skips posts that left the feed and is empty without a line`() {
        val a = post(1, 10)
        val b = post(2, 11)
        val c = post(3, 12)
        val peers = mapOf(a.feedKey() to listOf(StoryPeer(b.feedKey(), 2), StoryPeer(c.feedKey(), 3)))
        val scope = scope(a, b, c)
        // c's post is gone from the feed (deleted) while the scope still names its chat.
        val items = storyCoverageItems(a.feedKey(), peers, null, scope, listOf(a, b))
        assertEquals(listOf(2L), items.map { it.chatId })
        // Unknown row, or a row whose line vanished: nothing to list, no crash.
        assertTrue(storyCoverageItems("missing", peers, null, scope, listOf(a, b, c)).isEmpty())
        assertTrue(storyCoverageItems(a.feedKey(), peers, null, scope(a, b), listOf(a, b, c)).isEmpty())
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
