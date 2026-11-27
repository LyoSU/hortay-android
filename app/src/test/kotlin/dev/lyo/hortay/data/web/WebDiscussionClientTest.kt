package dev.lyo.hortay.data.web

import dev.lyo.hortay.data.CommentsRepository.ThreadState
import dev.lyo.hortay.data.FakeStrings
import dev.lyo.hortay.data.OlderComments
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

/**
 * [WebDiscussionClient] request building and retry rules, and [WebCommentsThread] state, over
 * a scripted [DiscussionTransport] — no network. Embed pages are the saved fixtures.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WebDiscussionClientTest {

    private val keys = DiscussionKeys(peer = "c1_2", topMsgId = 100L, discussionHash = "h")

    @Test
    fun `embed url and its handle allowlist`() {
        assertEquals("https://t.me/lyblog/8192?embed=1&discussion=1", buildDiscussionEmbedUrl("lyblog", 8192))
        assertNull(buildDiscussionEmbedUrl("..", 1))
        assertNull(buildDiscussionEmbedUrl("a/b", 1))
        assertNull(buildDiscussionEmbedUrl("lyblog", 0))
    }

    @Test
    fun `loadComments form for each cursor`() {
        val base = listOf("peer" to "c1_2", "top_msg_id" to "100", "discussion_hash" to "h")
        assertEquals(
            base + ("before_id" to "50") + ("method" to "loadComments"),
            buildLoadCommentsForm(keys, CommentsCursor.Before(50)),
        )
        assertEquals(
            base + ("after_id" to "60") + ("focused" to "1") + ("auto" to "1") + ("method" to "loadComments"),
            buildLoadCommentsForm(keys, CommentsCursor.After(60)),
        )
        assertEquals(
            base + ("comment_id" to "70") + ("method" to "loadComments"),
            buildLoadCommentsForm(keys, CommentsCursor.Around(70)),
        )
    }

    @Test
    fun `open parses the page and keeps its cookies`() = runTest {
        val transport = FakeTransport(getReplies = mutableListOf(TransportReply(200, load("lyblog_8192.html"), listOf("stel_ssid=abc"))))
        val opened = WebDiscussionClient(transport).open("lyblog", 8192) as DiscussionOpen.Thread
        assertEquals(listOf("stel_ssid=abc"), opened.session.cookies)
        assertEquals(86, opened.totalCount)
        assertEquals("https://t.me/lyblog/8192?embed=1&discussion=1", transport.gets.single())
    }

    @Test
    fun `open failures are typed, never thrown`() = runTest {
        val client = WebDiscussionClient(
            FakeTransport(
                getReplies = mutableListOf(
                    TransportReply(500, ""),
                    TransportReply(200, "<html></html>"),
                    TransportReply(200, load("durov_573.html")),
                ),
                getError = null,
            ),
        )
        assertEquals(DiscussionOpen.Failed, client.open("lyblog", 1))
        assertEquals(DiscussionOpen.Failed, client.open("lyblog", 1))
        assertEquals(DiscussionOpen.Unavailable, client.open("durov", 573))
        assertEquals(DiscussionOpen.Failed, WebDiscussionClient(FakeTransport(getError = IOException("x"))).open("lyblog", 1))
    }

    @Test
    fun `flood_wait is waited out and retried`() = runTest {
        val transport = FakeTransport(
            postReplies = mutableListOf(TransportReply(200, """{"error":"FLOOD","flood_wait":5}"""), ok()),
        )
        val fetched = WebDiscussionClient(transport).loadComments(session(), CommentsCursor.Before(10))
        assertTrue(fetched is CommentsFetch.Ok)
        assertEquals(2, transport.posts.size)
        assertEquals(5_000L, currentTime)
        assertEquals(listOf("stel_ssid=abc"), transport.cookiesSent.first())
    }

    @Test
    fun `too long or repeated flood_wait fails`() = runTest {
        val long = FakeTransport(postReplies = mutableListOf(TransportReply(200, """{"error":"FLOOD","flood_wait":3600}""")))
        assertEquals(CommentsFetch.Failed, WebDiscussionClient(long).loadComments(session(), CommentsCursor.After(1)))
        assertEquals(1, long.posts.size)

        val flood = """{"error":"FLOOD","flood_wait":1}"""
        val repeated = FakeTransport(postReplies = MutableList(5) { TransportReply(200, flood) })
        assertEquals(CommentsFetch.Failed, WebDiscussionClient(repeated).loadComments(session(), CommentsCursor.After(1)))
        assertEquals(WebDiscussionClient.MAX_FLOOD_RETRIES + 1, repeated.posts.size)
    }

    @Test
    fun `401 refreshes the keys once and retries with them`() = runTest {
        val transport = FakeTransport(
            getReplies = mutableListOf(TransportReply(200, load("lyblog_8192.html"))),
            postReplies = mutableListOf(TransportReply(401, ""), ok()),
        )
        val fetched = WebDiscussionClient(transport).loadComments(session(), CommentsCursor.Before(10)) as CommentsFetch.Ok
        assertEquals(1, transport.gets.size)
        assertEquals("a27555e767d19654b2", fetched.session.keys.discussionHash)
        assertTrue(transport.posts[1].contains("discussion_hash" to "a27555e767d19654b2"))
    }

    @Test
    fun `second 401 gives up`() = runTest {
        val transport = FakeTransport(
            getReplies = MutableList(3) { TransportReply(200, load("lyblog_8192.html")) },
            postReplies = MutableList(3) { TransportReply(401, "") },
        )
        assertEquals(CommentsFetch.Failed, WebDiscussionClient(transport).loadComments(session(), CommentsCursor.Before(10)))
        assertEquals(1, transport.gets.size)
        assertEquals(2, transport.posts.size)
    }

    @Test
    fun `thread opens newest first and threads replies`() = runTest {
        val thread = thread(FakeTransport(getReplies = mutableListOf(TransportReply(200, load("lyblog_8192.html")))))
        thread.start()
        runCurrent()
        val ready = thread.state.value as ThreadState.Ready
        assertEquals(86, ready.totalCount)
        assertEquals(listOf(0, 1, 2, 3, 3), ready.rows.map { it.depth })
        // The first comment answers one that isn't loaded: its quote card carries the context.
        assertTrue(ready.rows[0].message.reply!!.isQuote)
        assertFalse(ready.rows[1].message.reply!!.isQuote)
        assertEquals(OlderComments.Available, thread.older.value)
    }

    @Test
    fun `loading earlier comments merges them and reaches the start`() = runTest {
        val older = buildJsonObject {
            put("comments_html", JsonPrimitive(WebDiscussionParserTest.OLDER_FRAGMENT.substringAfter("</div>")))
        }.toString()
        val transport = FakeTransport(
            getReplies = mutableListOf(TransportReply(200, load("lyblog_8192.html"))),
            postReplies = mutableListOf(TransportReply(200, older)),
        )
        val thread = thread(transport)
        thread.start()
        runCurrent()
        thread.loadOlder()
        runCurrent()
        assertTrue(transport.posts.single().contains("before_id" to "1774847"))
        val ready = thread.state.value as ThreadState.Ready
        assertEquals(7, ready.rows.size)
        assertEquals(OlderComments.None, thread.older.value)
    }

    @Test
    fun `failed first load is LoadFailed, unavailable is Error, never an empty list`() = runTest {
        val failed = thread(FakeTransport(getError = IOException("offline")))
        failed.start()
        runCurrent()
        assertEquals(ThreadState.LoadFailed, failed.state.value)

        val unavailable = thread(FakeTransport(getReplies = mutableListOf(TransportReply(200, load("durov_573.html")))))
        unavailable.start()
        runCurrent()
        assertTrue(unavailable.state.value is ThreadState.Error)
    }

    @Test
    fun `failed earlier page keeps the thread and reports Failed`() = runTest {
        val transport = FakeTransport(
            getReplies = mutableListOf(TransportReply(200, load("lyblog_8192.html"))),
            postReplies = mutableListOf(TransportReply(502, "")),
        )
        val thread = thread(transport)
        thread.start()
        runCurrent()
        thread.loadOlder()
        runCurrent()
        assertEquals(5, (thread.state.value as ThreadState.Ready).rows.size)
        assertEquals(OlderComments.Failed, thread.older.value)
    }

    @Test
    fun `jumping to a comment loads the window around it`() = runTest {
        val around = buildJsonObject { put("comments_html", JsonPrimitive(WebDiscussionParserTest.OLDER_FRAGMENT)) }.toString()
        val transport = FakeTransport(
            getReplies = mutableListOf(TransportReply(200, load("lyblog_8192.html"))),
            postReplies = mutableListOf(TransportReply(200, around)),
        )
        val thread = thread(transport)
        thread.start()
        runCurrent()
        assertTrue(thread.loadAround(1774701L))
        assertTrue(transport.posts.single().contains("comment_id" to "1774701"))
        val ready = thread.state.value as ThreadState.Ready
        assertEquals(listOf(1774700L, 1774701L), ready.rows.map { it.message.id })
        // Already loaded: no request.
        assertTrue(thread.loadAround(1774700L))
        assertEquals(1, transport.posts.size)
    }

    @Test
    fun `live poll asks after the newest comment and adds new ones`() = runTest {
        val newer = buildJsonObject {
            put(
                "comments_html",
                JsonPrimitive(
                    WebDiscussionParserTest.OLDER_FRAGMENT.substringAfter("</div>")
                        .replace("1774700", "1774900").replace("1774701", "1774901") +
                        """<div class="tme_messages_more js-messages_more autoload hide" data-after="1774901"></div>""",
                ),
            )
        }.toString()
        val transport = FakeTransport(
            getReplies = mutableListOf(TransportReply(200, load("lyblog_8192.html"))),
            postReplies = mutableListOf(TransportReply(200, newer), TransportReply(200, """{"comments_html":""}""")),
        )
        val thread = thread(transport)
        thread.start()
        runCurrent()
        thread.pollOnce()
        assertTrue(transport.posts[0].contains("after_id" to "1774851"))
        assertEquals(7, (thread.state.value as ThreadState.Ready).rows.size)
        thread.pollOnce()
        assertTrue(transport.posts[1].contains("after_id" to "1774901"))
    }

    private fun kotlinx.coroutines.test.TestScope.thread(transport: FakeTransport) =
        WebCommentsThread(WebDiscussionClient(transport), "lyblog", 8192, FakeStrings, backgroundScope)

    private fun session() = DiscussionSession(
        channel = "lyblog",
        postId = 8192,
        keys = keys,
        apiUrl = "https://t.me/api/method?api_hash=x",
        cookies = listOf("stel_ssid=abc"),
    )

    private fun ok() = TransportReply(
        200,
        buildJsonObject { put("comments_html", JsonPrimitive(WebDiscussionParserTest.OLDER_FRAGMENT)) }.toString(),
    )

    private fun load(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("tme-discussion/$name")!!.bufferedReader().use { it.readText() }

    private class FakeTransport(
        val getReplies: MutableList<TransportReply> = mutableListOf(),
        val postReplies: MutableList<TransportReply> = mutableListOf(),
        val getError: IOException? = null,
    ) : DiscussionTransport {
        val gets = mutableListOf<String>()
        val posts = mutableListOf<List<Pair<String, String>>>()
        val cookiesSent = mutableListOf<List<String>>()

        override suspend fun get(url: String): TransportReply {
            gets += url
            getError?.let { throw it }
            return getReplies.removeAt(0)
        }

        override suspend fun post(url: String, form: List<Pair<String, String>>, cookies: List<String>): TransportReply {
            posts += form
            cookiesSent += cookies
            return postReplies.removeAt(0)
        }
    }
}
