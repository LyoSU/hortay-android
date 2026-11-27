package dev.lyo.hortay.data.web

import dev.lyo.hortay.data.CommentsRepository.ThreadState
import dev.lyo.hortay.data.FakeStrings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [WebCommentsThread]'s live refresh of the comments on screen, over a scripted
 * [DiscussionTransport]. The thread opens on the saved `lyblog_8192.html` page, which loads
 * comments 1774847..1774851 (newest marker `data-after="1774851"`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WebCommentsThreadTest {

    @Test
    fun `an edit or a new reaction on a visible comment replaces it in place`() = runTest {
        val transport = transport(reply(comment(1774848, edited = true, reactions = 7), comment(1774849), comment(1774850), comment(1774851)))
        val thread = opened(transport)
        thread.onVisibleRange(1774848, 1774850)
        thread.pollOnce()

        assertTrue(transport.posts.single().contains("after_id" to "1774847"))
        val edited = ready(thread).rows.single { it.message.id == 1774848L }.message
        assertTrue(edited.editDate != 0L)
        assertEquals(7, edited.reactions.totalCount)
        assertEquals(LOADED, ids(thread))
    }

    @Test
    fun `a comment missing from the refresh window was deleted`() = runTest {
        val transport = transport(reply(comment(1774848), comment(1774850), comment(1774851)))
        val thread = opened(transport)
        thread.onVisibleRange(1774848, 1774851)
        thread.pollOnce()

        assertEquals(listOf(1774847L, 1774848L, 1774850L, 1774851L), ids(thread))
        assertEquals(1, transport.posts.size)
    }

    @Test
    fun `a failed refresh prunes nothing and sends no second request`() = runTest {
        val transport = transport(TransportReply(502, ""))
        val thread = opened(transport)
        thread.onVisibleRange(1774848, 1774851)
        thread.pollOnce()

        assertEquals(LOADED, ids(thread))
        assertEquals(1, transport.posts.size)
    }

    @Test
    fun `an empty refresh prunes nothing and falls back to the newer-comments poll`() = runTest {
        val transport = transport(EMPTY, EMPTY)
        val thread = opened(transport)
        thread.onVisibleRange(1774848, 1774851)
        thread.pollOnce()

        assertEquals(LOADED, ids(thread))
        assertEquals(2, transport.posts.size)
        assertTrue(transport.posts[0].contains("after_id" to "1774847"))
        assertTrue(transport.posts[1].contains("after_id" to "1774851"))
    }

    @Test
    fun `a window cut at the page size also polls for newer comments`() = runTest {
        val capped = (0 until WebCommentsThread.WIDGET_PAGE_SIZE).map { comment(1774848L + it) }
        val transport = transport(reply(*capped.toTypedArray(), after = 1774897), EMPTY)
        val thread = opened(transport)
        thread.onVisibleRange(1774848, 1774851)
        thread.pollOnce()

        assertEquals(2, transport.posts.size)
        // The newer-comments poll keeps its own cursor; the refresh window doesn't move it.
        assertTrue(transport.posts[1].contains("after_id" to "1774851"))
        assertEquals(1 + WebCommentsThread.WIDGET_PAGE_SIZE, ids(thread).size)
    }

    @Test
    fun `a window that reaches the newest comment is the only request and moves the cursor`() = runTest {
        val transport = transport(
            reply(comment(1774848), comment(1774849), comment(1774850), comment(1774851), comment(1774852), after = 1774852),
            EMPTY,
        )
        val thread = opened(transport)
        thread.onVisibleRange(1774848, 1774851)
        thread.pollOnce()
        assertEquals(1, transport.posts.size)
        assertEquals(LOADED + 1774852L, ids(thread))

        // Nothing on screen that is loaded: back to the plain poll, after the new newest id.
        thread.onVisibleRange(1, 2)
        thread.pollOnce()
        assertTrue(transport.posts[1].contains("after_id" to "1774852"))
    }

    @Test
    fun `without a visible range the tick is the plain newer-comments poll`() = runTest {
        val transport = transport(EMPTY)
        val thread = opened(transport)
        thread.pollOnce()

        assertEquals(1, transport.posts.size)
        assertTrue(transport.posts.single().contains("after_id" to "1774851"))
        assertEquals(LOADED, ids(thread))
    }

    private fun TestScope.opened(transport: FakeTransport): WebCommentsThread {
        val thread = WebCommentsThread(WebDiscussionClient(transport), "lyblog", 8192, FakeStrings, backgroundScope)
        thread.start()
        runCurrent()
        assertEquals(LOADED, ids(thread))
        return thread
    }

    private fun ready(thread: WebCommentsThread) = thread.state.value as ThreadState.Ready

    private fun ids(thread: WebCommentsThread) = ready(thread).rows.map { it.message.id }.sorted()

    private fun transport(vararg posts: TransportReply) = FakeTransport(
        getReplies = mutableListOf(TransportReply(200, load("lyblog_8192.html"))),
        postReplies = posts.toMutableList(),
    )

    private fun reply(vararg comments: String, after: Long? = null): TransportReply {
        val marker = after?.let { """<div class="tme_messages_more js-messages_more autoload hide" data-after="$it"></div>""" }
        val html = comments.joinToString("\n") + marker.orEmpty()
        return TransportReply(200, buildJsonObject { put("comments_html", JsonPrimitive(html)) }.toString())
    }

    private fun comment(id: Long, edited: Boolean = false, reactions: Int = 0): String {
        val reactionsHtml = if (reactions > 0) {
            """<div class="tgme_widget_message_reactions"><span class="tgme_reaction"><i class="emoji"><b>👍</b></i>$reactions</span></div>"""
        } else {
            ""
        }
        val editedMark = if (edited) "edited " else ""
        return """
            <div class="tgme_widget_message_wrap js-widget_message_wrap"><div class="tgme_widget_message js-widget_message" data-post-id="$id">
              <div class="tgme_widget_message_bubble">
                <div class="tgme_widget_message_author accent_color"><span class="tgme_widget_message_author_name"><span dir="auto" class="name">Ann</span></span></div>
                <div class="tgme_widget_message_text js-message_text" dir="auto">Comment $id</div>
                $reactionsHtml
                <div class="tgme_widget_message_footer js-message_footer">$editedMark<a class="tgme_widget_message_date" href="https://t.me/lyblog/8192?comment=$id"><time datetime="2026-11-23T10:00:00+00:00">Nov 23</time></a></div>
              </div>
            </div></div>
        """.trimIndent()
    }

    private fun load(name: String): String =
        javaClass.classLoader!!.getResourceAsStream("tme-discussion/$name")!!.bufferedReader().use { it.readText() }

    private class FakeTransport(
        val getReplies: MutableList<TransportReply>,
        val postReplies: MutableList<TransportReply>,
    ) : DiscussionTransport {
        val posts = mutableListOf<List<Pair<String, String>>>()

        override suspend fun get(url: String): TransportReply = getReplies.removeAt(0)

        override suspend fun post(url: String, form: List<Pair<String, String>>, cookies: List<String>): TransportReply {
            posts += form
            return postReplies.removeAt(0)
        }
    }

    private companion object {
        val LOADED = listOf(1774847L, 1774848L, 1774849L, 1774850L, 1774851L)
        val EMPTY = TransportReply(200, """{"comments_html":""}""")
    }
}
