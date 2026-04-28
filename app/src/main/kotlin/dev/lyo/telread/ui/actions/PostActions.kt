package dev.lyo.telread.ui.actions

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.net.toUri
import dev.lyo.telread.data.PostContent
import dev.lyo.telread.data.TimelinePost

/**
 * Side-effects that bridge a post into the Android system: open the original in the
 * Telegram client and use the system share sheet.
 */
object PostActions {

    /**
     * Build the Telegram deep link for this post. Returns a `tg://` URI that any installed
     * Telegram client (official, Telegram X, MonoGram) handles natively.
     */
    fun telegramUri(post: TimelinePost): Uri {
        // Channel handles are stored as "@username" once resolved; private channels expose
        // none. TDLib message ids are encoded — server-side post id is the upper bits.
        val username = post.channelHandle?.removePrefix("@")
        val serverPostId = post.id ushr 20
        return when {
            !username.isNullOrBlank() -> "tg://resolve?domain=$username&post=$serverPostId".toUri()
            else -> {
                val rawChannelId = post.chatId.toString().removePrefix("-100")
                "tg://privatepost?channel=$rawChannelId&post=$serverPostId".toUri()
            }
        }
    }

    fun openInTelegram(context: Context, post: TimelinePost) {
        val intent = Intent(Intent.ACTION_VIEW, telegramUri(post)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    fun share(context: Context, post: TimelinePost) {
        val text = buildShareText(post)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, null))
    }

    private fun buildShareText(post: TimelinePost): String {
        val excerpt = post.content.captionPlain.take(200)
        val url = telegramUri(post).toString()
        return buildString {
            if (excerpt.isNotBlank()) {
                append(excerpt)
                if (post.content.captionPlain.length > 200) append("…")
                append("\n\n")
            }
            append("— ${post.channelTitle}\n$url")
        }
    }
}
