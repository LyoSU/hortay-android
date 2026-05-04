package dev.lyo.hortay.ui.actions

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.core.net.toUri
import dev.lyo.hortay.data.PostContent
import dev.lyo.hortay.data.TimelinePost

/**
 * Side-effects that bridge a post into the Android system: open the original in the
 * Telegram client and use the system share sheet.
 */
object PostActions {

    /**
     * Telegram-internal `tg://` URI for this post. Handed to [openInTelegram] so it routes
     * directly into the official Telegram client without bouncing through the browser. NOT
     * suitable for sharing externally — `tg://` is opaque to non-Telegram users; use
     * [shareUri] for that.
     */
    fun telegramUri(post: TimelinePost): Uri {
        val username = post.senderHandle?.removePrefix("@")
        val serverPostId = post.id ushr 20
        return when {
            !username.isNullOrBlank() -> "tg://resolve?domain=$username&post=$serverPostId".toUri()
            else -> {
                val rawChannelId = post.chatId.toString().removePrefix("-100")
                "tg://privatepost?channel=$rawChannelId&post=$serverPostId".toUri()
            }
        }
    }

    /**
     * Public-web `https://t.me/...` URI suitable for sharing. Renders as a real preview in
     * any messenger / browser, opens in Telegram when the recipient has the app, and falls
     * back to t.me's own preview page otherwise. Public channels: `t.me/<handle>/<post>`;
     * private channels: `t.me/c/<rawId>/<post>` (works only for users in that channel, but
     * that's Telegram's invariant — same as the official "Copy link" action).
     */
    fun shareUri(post: TimelinePost): Uri {
        val username = post.senderHandle?.removePrefix("@")
        val serverPostId = post.id ushr 20
        return when {
            !username.isNullOrBlank() -> "https://t.me/$username/$serverPostId".toUri()
            else -> {
                val rawChannelId = post.chatId.toString().removePrefix("-100")
                "https://t.me/c/$rawChannelId/$serverPostId".toUri()
            }
        }
    }

    fun openInTelegram(context: Context, post: TimelinePost) {
        val intent = Intent(Intent.ACTION_VIEW, telegramUri(post)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(intent) }
    }

    /**
     * Copy the post body to the system clipboard. Android 12L+ shows its own toast for
     * clipboard writes, so we suppress ours there to avoid the double-toast smell.
     */
    fun copyText(context: Context, post: TimelinePost) {
        val text = post.content.captionPlain
        if (text.isBlank()) return
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        cm.setPrimaryClip(ClipData.newPlainText(post.senderName, text))
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(context, context.getString(dev.lyo.hortay.R.string.post_copied_toast), Toast.LENGTH_SHORT).show()
        }
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
        // Always share the public https://t.me URL — recipients without Telegram see the
        // t.me preview page, while Telegram users get an in-app handoff. The internal
        // tg:// scheme would be a dead link for everyone outside the Telegram ecosystem.
        val url = shareUri(post).toString()
        return buildString {
            if (excerpt.isNotBlank()) {
                append(excerpt)
                if (post.content.captionPlain.length > 200) append("…")
                append("\n\n")
            }
            append("— ${post.senderName}\n$url")
        }
    }
}
