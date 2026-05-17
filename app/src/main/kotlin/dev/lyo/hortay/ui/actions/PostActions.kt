package dev.lyo.hortay.ui.actions

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.core.net.toUri
import dev.lyo.hortay.data.posts.PostsRepository
import dev.lyo.hortay.data.TimelinePost

/**
 * Side-effects that bridge a post into the Android system: open the original in the
 * Telegram client and use the system share sheet.
 *
 * Share-link generation prefers TDLib's offline `GetMessageLink` (via
 * [PostsRepository.canonicalShareUrl]) — Telegram knows the canonical format for albums
 * / topics / message-thread anchors better than any string template we'd write here.
 * Falls back to a hand-rolled URL for guest-mode posts (no TDLib chat to query) and for
 * the rare TDLib refusal (e.g. restricted source chat with `canGetLink = false`).
 *
 * The guest-mode threshold lives below: posts whose `chatId` is in the synthesised
 * range minted by [dev.lyo.hortay.data.web.WebPostAdapter.stableChatId] skip the TDLib
 * call entirely because TDLib has never heard of those chats.
 */
object PostActions {

    /**
     * Resolved share URL: TDLib-minted when available, hand-rolled otherwise. Always
     * returns a non-empty string suitable for embedding in share text / OS clipboard.
     *
     * Mode discriminator is the nullability of [repo], not the chatId range. The previous
     * `chatId <= -1_000_000_000_000` check looked like a clean way to detect synthetic
     * web ids, but TDLib supergroup ids share the same range — they're computed as
     * `-1_000_000_000_000 - <internal 32-bit channelId>`, so real channels land in
     * `[-1_004_294_967_295, -1_000_000_000_000)` and overlap synthetic web ids
     * exactly. That misrouted real TDLib posts into the fallback path for a small but
     * not vanishingly small fraction of channels — visible to the user as a `t.me/<handle>/<huge-mangled-id>`
     * link when "open in Telegram" / "share" was tapped. The callers already pass
     * `null` for guest mode and the live repo for TDLib mode, so the safe2-derive2
     * boolean is just `repo == null`.
     */
    private suspend fun shareUrl(repo: PostsRepository?, post: TimelinePost): String =
        repo?.canonicalShareUrl(post) ?: fallbackShareUri(post, isGuest = repo == null).toString()

    /**
     * Hand-rolled `https://t.me/...` fallback. Public channels get `t.me/<handle>/<post>`;
     * private channels get `t.me/c/<rawId>/<post>` (which only resolves for users in
     * that channel — Telegram's invariant, same as the official "Copy link" action).
     */
    private fun fallbackShareUri(post: TimelinePost, isGuest: Boolean): Uri {
        val username = post.senderHandle?.removePrefix("@")
        val serverPostId = fallbackServerPostId(post, isGuest)
        return when {
            !username.isNullOrBlank() -> "https://t.me/$username/$serverPostId".toUri()
            else -> {
                val rawChannelId = post.chatId.toString().removePrefix("-100")
                "https://t.me/c/$rawChannelId/$serverPostId".toUri()
            }
        }
    }

    /**
     * Hand-rolled fallback for the post's server-visible message number. TDLib internally
     * bit-packs `(serverId shl 20)`; the visible number is the high 44 bits. Guest-mode
     * posts skip the shift because their id IS the raw `t.me/<u>/<seq>` number — applying
     * `ushr 20` would collapse `seq=36046` to 0 and produce a `/0` URL.
     */
    private fun fallbackServerPostId(post: TimelinePost, isGuest: Boolean): Long =
        if (isGuest) post.id else post.id ushr 20

    suspend fun openInTelegram(context: Context, repo: PostsRepository?, post: TimelinePost) {
        // Hand the OS an https://t.me/... URL — Telegram's intent-filter claims it and
        // routes into the official client cleanly, just as well as tg:// did, but
        // without the "no client installed → ActivityNotFoundException" failure mode
        // (the OS chooser steps in for unknown handlers, vs tg:// where the absence
        // crashes the dispatch).
        val intent = Intent(Intent.ACTION_VIEW, shareUrl(repo, post).toUri()).apply {
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

    suspend fun share(context: Context, repo: PostsRepository?, post: TimelinePost) {
        val url = shareUrl(repo, post)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, buildShareText(post, url))
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, null))
    }

    private fun buildShareText(post: TimelinePost, url: String): String {
        val excerpt = post.content.captionPlain.take(200)
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
