package dev.lyo.hortay.data.web

import android.util.Log
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CacheControl
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Anonymous read access to a channel post's comments through Telegram's public
 * **discussion widget** — the iframe a website embeds with `data-telegram-discussion`.
 * Every piece of knowledge about that widget (URLs, form fields, the JSON endpoint,
 * its error shapes) lives in this file and [WebDiscussionParser]; nothing else in the
 * app may talk to it.
 *
 * **This is an undocumented internal endpoint**, not an API. It was mapped on 2026-11-27
 * from the widget's own scripts (`discussion-widget.js`, `widget-frame.js`) and checked in
 * a real browser, where it loaded all 86 comments of a test thread without an account:
 *
 *  1. `GET https://t.me/<channel>/<post>?embed=1&discussion=1` — static HTML with the
 *     newest comments, the total (`TWidgetDiscussion.init({"comments_cnt":N})`), the
 *     thread keys in a hidden form (`peer`, `top_msg_id`, `discussion_hash`), the
 *     endpoint (`TWidgetAuth.init({"api_url": …})`) and the paging markers. A post whose
 *     comments the widget won't show answers with "Discussion is not available".
 *  2. `POST <api_url>` form-encoded `method=loadComments` + the keys + exactly one of
 *     `before_id` (older page), `after_id` (newer comments, the live poll) or
 *     `comment_id` (the window around one comment) → JSON
 *     `{comments_html, header_html, comments_cnt, slowmode_wait}`; `comments_html` is the
 *     same markup as the page. `{"error":…, "flood_wait":N}` asks to retry in N seconds;
 *     HTTP 401 means the keys went stale — the widget reloads the page, and so do we
 *     (once). The widget sends `X-Requested-With: XMLHttpRequest` and its cookies; the
 *     cookies the embed page sets are replayed in memory for the thread's lifetime only.
 *
 * **Degradation contract.** Telegram can change or close this at any moment. Every
 * failure — HTTP error, timeout, unexpected markup, unknown JSON — comes back as a typed
 * `Failed` / `Unavailable` value, never as an exception, so the comments screen can say
 * "couldn't load" and offer Open in Telegram and Sign in. A failure is never turned into
 * an empty list: "no comments" is only reported when the widget itself says the count is 0.
 *
 * Load is kept far below the widget's own: one page GET per open, older pages only as the
 * reader asks for them, and a live poll every [WebCommentsThread.POLL_INTERVAL_MS] while the
 * screen is open and resumed (the widget polls every 3 s). That poll is usually one
 * `after_id` request starting at the comments on screen, which refreshes their edits and
 * reactions and picks up new comments at once (the widget never refreshes shown comments);
 * a second request follows only when that window was cut at the page size.
 */
class WebDiscussionClient(
    private val transport: DiscussionTransport,
) {

    /** Opens a thread: fetches the embed page and parses it. */
    suspend fun open(channel: String, postId: Long): DiscussionOpen {
        val url = buildDiscussionEmbedUrl(channel, postId) ?: return DiscussionOpen.Failed
        val reply = try {
            transport.get(url)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "open: ${e.javaClass.simpleName}")
            return DiscussionOpen.Failed
        }
        if (reply.code != HTTP_OK) return DiscussionOpen.Failed
        return when (val page = WebDiscussionParser.parsePage(reply.body)) {
            null -> DiscussionOpen.Failed
            DiscussionPage.Unavailable -> DiscussionOpen.Unavailable
            is DiscussionPage.Thread -> DiscussionOpen.Thread(
                session = DiscussionSession(channel, postId, page.keys, page.apiUrl, reply.cookies),
                totalCount = page.totalCount,
                fragment = page.fragment,
            )
        }
    }

    /**
     * One `loadComments` call. Honours `flood_wait` up to [MAX_FLOOD_WAIT_SEC] (at most
     * [MAX_FLOOD_RETRIES] times) and re-opens the thread once on HTTP 401 to refresh its keys.
     */
    suspend fun loadComments(session: DiscussionSession, cursor: CommentsCursor): CommentsFetch {
        var current = session
        var refreshed = false
        var floodRetries = 0
        while (true) {
            val reply = try {
                transport.post(current.apiUrl, buildLoadCommentsForm(current.keys, cursor), current.cookies)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                Log.w(TAG, "loadComments: ${e.javaClass.simpleName}")
                return CommentsFetch.Failed
            }
            if (reply.code == HTTP_UNAUTHORIZED) {
                if (refreshed) return CommentsFetch.Failed
                refreshed = true
                val reopened = open(current.channel, current.postId) as? DiscussionOpen.Thread
                    ?: return CommentsFetch.Failed
                current = reopened.session
                continue
            }
            if (reply.code != HTTP_OK) return CommentsFetch.Failed
            when (val parsed = WebDiscussionParser.parseLoadCommentsReply(reply.body)) {
                is LoadCommentsReply.Ok -> return CommentsFetch.Ok(parsed.fragment, parsed.totalCount, current)
                is LoadCommentsReply.FloodWait -> {
                    if (parsed.seconds > MAX_FLOOD_WAIT_SEC || floodRetries >= MAX_FLOOD_RETRIES) {
                        return CommentsFetch.Failed
                    }
                    floodRetries++
                    delay(parsed.seconds * 1_000L)
                }
                LoadCommentsReply.Failed -> return CommentsFetch.Failed
            }
        }
    }

    companion object {
        private const val TAG = "WebDiscussion"
        private const val HTTP_OK = 200
        private const val HTTP_UNAUTHORIZED = 401
        /** Longer waits than this fail the call; the next poll or tap tries again. */
        internal const val MAX_FLOOD_WAIT_SEC = 60
        internal const val MAX_FLOOD_RETRIES = 2
    }
}

/** Where a `loadComments` call reads from. */
sealed interface CommentsCursor {
    /** Comments older than [id] — the "load earlier" page. */
    data class Before(val id: Long) : CommentsCursor
    /** Comments newer than [id] — the live poll. */
    data class After(val id: Long) : CommentsCursor
    /** The window around comment [id] — jumping to a quoted comment that isn't loaded. */
    data class Around(val id: Long) : CommentsCursor
}

/** Thread keys + endpoint + cookies from one embed-page fetch. Lives in memory only. */
@Immutable
data class DiscussionSession(
    val channel: String,
    val postId: Long,
    val keys: DiscussionKeys,
    val apiUrl: String,
    /** `name=value` pairs the embed page set, replayed on `loadComments`. */
    val cookies: List<String>,
)

sealed interface DiscussionOpen {
    data class Thread(
        val session: DiscussionSession,
        val totalCount: Int?,
        val fragment: CommentsFragment,
    ) : DiscussionOpen
    data object Unavailable : DiscussionOpen
    data object Failed : DiscussionOpen
}

sealed interface CommentsFetch {
    /** [session] may differ from the one passed in after a 401 refresh — keep the new one. */
    data class Ok(val fragment: CommentsFragment, val totalCount: Int?, val session: DiscussionSession) : CommentsFetch
    data object Failed : CommentsFetch
}

/** Minimal HTTP surface the client needs, so its retry logic is testable without a network. */
interface DiscussionTransport {
    suspend fun get(url: String): TransportReply
    suspend fun post(url: String, form: List<Pair<String, String>>, cookies: List<String>): TransportReply
}

class TransportReply(val code: Int, val body: String, val cookies: List<String> = emptyList())

/**
 * `https://t.me/<channel>/<post>?embed=1&discussion=1`, or null for a handle that isn't a
 * plain username (same allowlist as [buildTmePreviewUrl]).
 */
internal fun buildDiscussionEmbedUrl(channel: String, postId: Long): String? {
    if (!DISCUSSION_HANDLE_REGEX.matches(channel) || postId <= 0L) return null
    return HttpUrl.Builder()
        .scheme("https")
        .host("t.me")
        .addPathSegment(channel)
        .addPathSegment(postId.toString())
        .addQueryParameter("embed", "1")
        .addQueryParameter("discussion", "1")
        .build()
        .toString()
}

private val DISCUSSION_HANDLE_REGEX = Regex("""[A-Za-z0-9_]{1,32}""")

/** The form fields of one `loadComments` call, in the order the widget sends them. */
internal fun buildLoadCommentsForm(keys: DiscussionKeys, cursor: CommentsCursor): List<Pair<String, String>> =
    buildList {
        add("peer" to keys.peer)
        add("top_msg_id" to keys.topMsgId.toString())
        add("discussion_hash" to keys.discussionHash)
        when (cursor) {
            is CommentsCursor.Before -> add("before_id" to cursor.id.toString())
            is CommentsCursor.After -> {
                add("after_id" to cursor.id.toString())
                add("focused" to "1")
                add("auto" to "1")
            }
            is CommentsCursor.Around -> add("comment_id" to cursor.id.toString())
        }
        add("method" to "loadComments")
    }

/**
 * OkHttp-backed [DiscussionTransport] over the shared guest-mode client. Same browser
 * User-Agent and Accept-Language as [WebTelegramClient]; responses bypass the disk cache
 * (thread keys and comments are live, and keys must never be persisted).
 */
class OkHttpDiscussionTransport(private val http: OkHttpClient) : DiscussionTransport {

    override suspend fun get(url: String): TransportReply = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", WebTelegramClient.USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Accept-Language", WebTelegramClient.ACCEPT_LANGUAGE)
            .cacheControl(NO_STORE)
            .build()
        execute(request).use { response -> response.toReply() }
    }

    override suspend fun post(
        url: String,
        form: List<Pair<String, String>>,
        cookies: List<String>,
    ): TransportReply = withContext(Dispatchers.IO) {
        val body = FormBody.Builder(Charsets.UTF_8).apply {
            form.forEach { (k, v) -> add(k, v) }
        }.build()
        val request = Request.Builder()
            .url(url)
            .post(body)
            .header("User-Agent", WebTelegramClient.USER_AGENT)
            .header("Accept", "application/json, text/javascript, */*; q=0.01")
            .header("Accept-Language", WebTelegramClient.ACCEPT_LANGUAGE)
            .header("X-Requested-With", "XMLHttpRequest")
            .apply { if (cookies.isNotEmpty()) header("Cookie", cookies.joinToString("; ")) }
            .cacheControl(NO_STORE)
            .build()
        execute(request).use { response -> response.toReply() }
    }

    private fun Response.toReply(): TransportReply = TransportReply(
        code = code,
        body = body.string(),
        cookies = headers("Set-Cookie").mapNotNull { header ->
            header.substringBefore(';').trim().takeIf { '=' in it }
        },
    )

    // Same cancellation-safe enqueue as WebTelegramClient.execute: a cancelled coroutine
    // cancels the call, and a response delivered after cancellation is closed.
    private suspend fun execute(request: Request): Response =
        suspendCancellableCoroutine { cont ->
            val call = http.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    cont.resume(response) { _, res, _ -> res.close() }
                }
            })
        }

    private companion object {
        val NO_STORE: CacheControl = CacheControl.Builder().noCache().noStore().build()
    }
}
