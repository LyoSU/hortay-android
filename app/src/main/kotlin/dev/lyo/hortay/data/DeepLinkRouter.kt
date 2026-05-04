package dev.lyo.hortay.data

import android.net.Uri
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Sole entry point for incoming `tg://` and `https://t.me/...` deep links.
 *
 * The router parses each URI into a strongly-typed [DeepLink] and buffers it on a
 * [Channel]. The UI layer collects [events] and dispatches once. We deliberately use a
 * Channel rather than a [kotlinx.coroutines.flow.SharedFlow] because:
 *
 *   • **Cold-launch ordering** — [MainActivity.onCreate] submits the URI BEFORE Compose
 *     mounts and subscribes. A SharedFlow with replay = 0 would drop the link on the
 *     floor (no subscriber); replay = 1 would replay the same link on every config
 *     change, re-navigating the user after every rotation. Channel buffers items until
 *     a collector arrives and consumes each exactly once.
 *
 *   • **One-shot semantics** — opening a deep link IS a one-shot side effect. Channel
 *     models that directly; SharedFlow models a topic of multi-cast values.
 *
 * Parsing is intentionally permissive: any unknown host, missing parameter, or malformed
 * id returns null and the caller (MainActivity) silently swallows it. We never crash on a
 * weird URL the user clicked from the wild.
 *
 * URI shapes we recognise:
 *
 *   tg://resolve?domain=<handle>[&post=<id>]      → public channel by @handle, optional post
 *   tg://privatepost?channel=<rawId>&post=<id>    → private channel post
 *   tg://openmessage?chat_id=<id>&message_id=<id> → direct chat+message reference
 *   https://t.me/<handle>[/<id>]                  → public, with optional post
 *   https://t.me/c/<rawId>/<id>                   → private channel post
 *   https://t.me/<handle>                         → public channel root
 *
 * `id` here is Telegram's *server-side* post number (1, 2, 3…). TDLib internally encodes
 * messages as `serverId shl 20`, so the dispatcher converts when it needs a TDLib-shaped id.
 */
class DeepLinkRouter {

    private val _events = Channel<DeepLink>(capacity = Channel.BUFFERED)
    val events: Flow<DeepLink> = _events.receiveAsFlow()

    /** Parse + buffer a deep link. Returns true when something was actually queued. */
    fun submit(uri: Uri?): Boolean {
        val parsed = uri?.let(::parse) ?: return false
        return _events.trySend(parsed).isSuccess
    }

    /**
     * Programmatic short-cut for in-app navigations that already know the canonical chat
     * id (e.g. tapping a quote card whose [DeepLink.Message] is fully known). Bypasses
     * URI parsing — same delivery channel as external `tg://` arrivals.
     */
    fun submit(link: DeepLink): Boolean = _events.trySend(link).isSuccess

    private fun parse(uri: Uri): DeepLink? = when (uri.scheme?.lowercase()) {
        "tg" -> parseTg(uri)
        "http", "https" -> if (uri.host?.lowercase()?.removePrefix("www.") == "t.me") parseTMe(uri) else null
        else -> null
    }

    private fun parseTg(uri: Uri): DeepLink? {
        // tg:// URIs use the "host" slot for the action ("resolve", "privatepost"…).
        val action = uri.host?.lowercase() ?: return null
        return when (action) {
            "resolve" -> {
                val domain = uri.getQueryParameter("domain")?.takeIf { it.isNotBlank() } ?: return null
                val post = uri.getQueryParameter("post")?.toLongOrNull()
                DeepLink.PublicChannel(handle = domain.removePrefix("@"), serverPostId = post)
            }
            "privatepost" -> {
                val raw = uri.getQueryParameter("channel")?.toLongOrNull() ?: return null
                val post = uri.getQueryParameter("post")?.toLongOrNull()
                DeepLink.PrivateChannel(chatId = "-100$raw".toLongOrNull() ?: return null, serverPostId = post)
            }
            "openmessage" -> {
                val chatId = uri.getQueryParameter("chat_id")?.toLongOrNull() ?: return null
                val msg = uri.getQueryParameter("message_id")?.toLongOrNull() ?: return null
                // Heuristic: openmessage's message_id is sometimes the TDLib-shifted id and
                // sometimes the server-side number, depending on which Telegram client wrote
                // it. If it's small (< 2^20 = ~1 M) it's a server number; otherwise treat as
                // already-shifted and pass through.
                val serverId = if (msg < (1L shl 20)) msg else msg ushr 20
                DeepLink.Message(chatId = chatId, serverPostId = serverId)
            }
            else -> null
        }
    }

    private fun parseTMe(uri: Uri): DeepLink? {
        val segments = uri.pathSegments.filter { it.isNotBlank() }
        return when {
            segments.isEmpty() -> null
            segments[0] == "c" && segments.size >= 2 -> {
                val raw = segments[1].toLongOrNull() ?: return null
                val msg = segments.getOrNull(2)?.toLongOrNull()
                DeepLink.PrivateChannel(chatId = "-100$raw".toLongOrNull() ?: return null, serverPostId = msg)
            }
            segments[0] in BLOCKED_PUBLIC_HOSTS -> null
            else -> {
                val handle = segments[0]
                val msg = segments.getOrNull(1)?.toLongOrNull()
                DeepLink.PublicChannel(handle = handle.removePrefix("@"), serverPostId = msg)
            }
        }
    }

    private companion object {
        // t.me serves a few non-channel pages we should NOT treat as a public channel handle
        // (otherwise opening "https://t.me/joinchat/..." would try to filter the feed by a
        // channel literally named "joinchat"). Anything in this set falls through to the
        // standard "open in Telegram" fallback.
        val BLOCKED_PUBLIC_HOSTS = setOf("joinchat", "addstickers", "share", "addtheme", "proxy", "socks")
    }
}

/** Strongly-typed result of parsing a Telegram deep link. */
sealed interface DeepLink {
    /** A public channel/user/bot reachable by [handle] (without `@`). */
    data class PublicChannel(val handle: String, val serverPostId: Long?) : DeepLink

    /** A private channel referenced by its TDLib chat id (`-100<raw>`). */
    data class PrivateChannel(val chatId: Long, val serverPostId: Long?) : DeepLink

    /** A specific chat+message pair (used by `tg://openmessage`). */
    data class Message(val chatId: Long, val serverPostId: Long) : DeepLink
}
