package dev.lyo.hortay.data.web

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toPersistentList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/**
 * One comment as Telegram's public discussion widget prints it. Same markup family as
 * `t.me/s/` posts (`tgme_widget_message_*`), so media, text and reactions go through the
 * [TmePageParser] helpers; only the author block and the reply header differ.
 */
@Immutable
data class WebComment(
    /** `data-post-id`: the comment's message id inside the linked discussion group. */
    val id: Long,
    val authorName: String,
    /** Absolute https avatar URL. Null for the widget's generated letter tiles (`data:` SVG). */
    val avatarUrl: String?,
    /** ISO-8601 `<time datetime>`. */
    val publishedAt: String,
    val textHtml: String,
    val media: ImmutableList<WebMedia>,
    val webPreview: WebPreview?,
    val reactions: ImmutableList<WebReaction>,
    val isEdited: Boolean,
    /** `data-reply-to` of the reply header: the comment answered, or null for a top-level one. */
    val replyToId: Long?,
    val replyAuthor: String,
    val replyExcerpt: String,
    val replyThumbUrl: String?,
)

/** The hidden comment-form fields that address one thread in `loadComments` calls. */
@Immutable
data class DiscussionKeys(
    val peer: String,
    /** The channel post's mirror in the discussion group — the thread root. */
    val topMsgId: Long,
    val discussionHash: String,
)

/** A batch of comments plus the widget's pagination markers around it. */
@Immutable
data class CommentsFragment(
    val comments: ImmutableList<WebComment>,
    /** `.js-messages_more[data-before]`: older comments exist before this id. */
    val beforeCursor: Long?,
    /** `.js-messages_more.autoload[data-after]`: the live poll asks for comments after this id. */
    val afterCursor: Long?,
)

/** Outcome of parsing the `?embed=1&discussion=1` page. */
sealed interface DiscussionPage {
    /** Telegram's "Discussion is not available at the moment." — no comments to show anonymously. */
    data object Unavailable : DiscussionPage

    data class Thread(
        val keys: DiscussionKeys,
        /** `TWidgetAuth.init({"api_url": …})` — the endpoint `loadComments` posts to. */
        val apiUrl: String,
        val totalCount: Int?,
        val fragment: CommentsFragment,
    ) : DiscussionPage
}

/** Outcome of parsing a `loadComments` JSON reply. */
sealed interface LoadCommentsReply {
    data class Ok(val fragment: CommentsFragment, val totalCount: Int?) : LoadCommentsReply
    /** `{"error": …, "flood_wait": N}` — retry after [seconds]. */
    data class FloodWait(val seconds: Int) : LoadCommentsReply
    /** `{"error": …}` without a wait, or a body that isn't the expected JSON. */
    data object Failed : LoadCommentsReply
}

/**
 * Pure-JVM parser for Telegram's public comments widget (`t.me/<channel>/<post>?embed=1&discussion=1`
 * and its `loadComments` replies). See [WebDiscussionClient] for what the widget is and why it
 * is treated as fragile. Every entry point returns null / [LoadCommentsReply.Failed] instead
 * of throwing, and a single malformed comment is dropped rather than failing its batch.
 *
 * Markup evidence: the fixtures under `app/src/test/resources/tme-discussion/` (real pages
 * saved on 2026-11-27: two `lyblog` threads, a `tginfo` thread with a photo album and a
 * nested reply, and a `durov` post whose discussion is "not available").
 */
object WebDiscussionParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Parses the embed page. Null = the page is neither a thread nor the "not available" stub. */
    fun parsePage(html: String): DiscussionPage? = runCatching {
        val doc = Jsoup.parse(html)
        val keys = doc.selectFirst("form.js-new_message_form")?.let { form ->
            val peer = form.selectFirst("input[name=peer]")?.attr("value")?.trim().orEmpty()
            val top = form.selectFirst("input[name=top_msg_id]")?.attr("value")?.trim()?.toLongOrNull()
            val hash = form.selectFirst("input[name=discussion_hash]")?.attr("value")?.trim().orEmpty()
            if (peer.isEmpty() || top == null || hash.isEmpty()) null else DiscussionKeys(peer, top, hash)
        }
        if (keys == null) {
            return@runCatching if (doc.selectFirst(".tme_no_messages_found") != null) {
                DiscussionPage.Unavailable
            } else {
                null
            }
        }
        val scripts = doc.select("script").joinToString("\n") { it.data() }
        val apiUrl = initObject(scripts, "TWidgetAuth.init")
            ?.get("api_url")?.jsonPrimitive?.contentOrNull
            ?.takeIf { isTmeApiUrl(it) }
            ?: return@runCatching null
        val count = initObject(scripts, "TWidgetDiscussion.init")
            ?.get("comments_cnt")?.jsonPrimitive?.intOrNull
        val history = doc.selectFirst(".js-message_history") ?: return@runCatching null
        DiscussionPage.Thread(
            keys = keys,
            apiUrl = apiUrl,
            totalCount = count,
            fragment = parseFragmentElement(history),
        )
    }.getOrNull()

    /** Parses a `comments_html` fragment from a `loadComments` reply. */
    fun parseFragment(html: String): CommentsFragment? = runCatching {
        parseFragmentElement(Jsoup.parseBodyFragment(html).body())
    }.getOrNull()

    /** Parses the JSON body of a `loadComments` reply. */
    fun parseLoadCommentsReply(body: String): LoadCommentsReply {
        val obj = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
            ?: return LoadCommentsReply.Failed
        if (obj["error"] != null) {
            val wait = obj["flood_wait"]?.jsonPrimitive?.intOrNull
            return if (wait != null && wait > 0) LoadCommentsReply.FloodWait(wait) else LoadCommentsReply.Failed
        }
        val html = obj["comments_html"]?.jsonPrimitive?.contentOrNull ?: return LoadCommentsReply.Failed
        val fragment = parseFragment(html) ?: return LoadCommentsReply.Failed
        return LoadCommentsReply.Ok(fragment, obj["comments_cnt"]?.jsonPrimitive?.intOrNull)
    }

    private fun parseFragmentElement(root: Element): CommentsFragment {
        val comments = root.select(".js-widget_message_wrap .js-widget_message[data-post-id]")
            .mapNotNull { runCatching { parseComment(it) }.getOrNull() }
        val before = root.select(".js-messages_more[data-before]")
            .firstNotNullOfOrNull { it.attr("data-before").toLongOrNull() }
        val after = root.select(".js-messages_more[data-after]")
            .firstNotNullOfOrNull { it.attr("data-after").toLongOrNull() }
        return CommentsFragment(comments.toPersistentList(), before, after)
    }

    private fun parseComment(msg: Element): WebComment? {
        val id = msg.attr("data-post-id").toLongOrNull() ?: return null
        val bubble = msg.selectFirst(".tgme_widget_message_bubble") ?: msg
        // The author block sits directly in the bubble; the reply header and the hidden
        // reply template each carry their own `.tgme_widget_message_author`, so pick the
        // one that is not inside either.
        val author = bubble.select(".tgme_widget_message_author_name")
            .firstOrNull { el ->
                el.parents().none { it.hasClass("tgme_widget_message_reply") || it.hasClass("js-reply_tpl") }
            }
            ?.text()?.trim().orEmpty()
        val avatar = TmePageParser.safeSrc(msg.selectFirst(".tgme_widget_message_user_photo img"))
        val footer = bubble.selectFirst(".tgme_widget_message_footer")
        val reply = bubble.selectFirst(".tgme_widget_message_reply[data-reply-to]")
        // Reactions, media and the text are read with the reply header and template removed,
        // so a quoted photo or quoted text can't pass for the comment's own.
        val own = bubble.clone()
        own.select(".tgme_widget_message_reply, .js-reply_tpl").remove()
        return WebComment(
            id = id,
            authorName = author,
            avatarUrl = avatar,
            publishedAt = footer?.selectFirst("time[datetime]")?.attr("datetime").orEmpty(),
            textHtml = TmePageParser.messageTextHtml(own),
            media = TmePageParser.parseMedia(own).toPersistentList(),
            webPreview = TmePageParser.parseWebPreview(own),
            reactions = TmePageParser.parseReactions(own).toPersistentList(),
            isEdited = footer?.ownText()?.contains("edited", ignoreCase = true) == true,
            replyToId = reply?.attr("data-reply-to")?.toLongOrNull(),
            replyAuthor = reply?.selectFirst(".tgme_widget_message_author_name")?.text()?.trim().orEmpty(),
            replyExcerpt = reply?.selectFirst(".js-message_reply_text")?.text()?.trim().orEmpty(),
            replyThumbUrl = reply?.selectFirst(".tgme_widget_message_reply_thumb")
                ?.attr("style")?.let(TmePageParser::extractCssBackgroundUrl),
        )
    }

    /** The JSON object literal passed to `<call>({…})` in an inline script. */
    private fun initObject(scripts: String, call: String): JsonObject? {
        val start = scripts.indexOf("$call(")
        if (start < 0) return null
        val open = scripts.indexOf('{', start)
        if (open < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in open until scripts.length) {
            val c = scripts[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) {
                        return runCatching { json.parseToJsonElement(scripts.substring(open, i + 1)).jsonObject }
                            .getOrNull()
                    }
                }
            }
        }
        return null
    }

    /**
     * Only `https://t.me/api/…` is accepted as the endpoint: the URL comes out of a page,
     * and the client must never be steered into posting thread keys anywhere else.
     */
    internal fun isTmeApiUrl(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        return parsed.scheme == "https" && parsed.host == "t.me" && parsed.encodedPath.startsWith("/api/")
    }
}
