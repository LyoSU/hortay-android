package dev.lyo.hortay.data

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi

/**
 * Single source of truth for `OpenChat` / `CloseChat` lifecycle in the app.
 *
 * Why this exists: per TDLib docs, in supergroups and channels **updates are received
 * only for opened chats**, and the daemon prioritises history loading + caching for
 * them. Skipping `OpenChat` (or pairing it incorrectly) is the difference between a
 * cold-cache `GetChatHistory` / `GetMessageThreadHistory` paying full server round-trip
 * latency vs. answering from the local DB in tens of milliseconds.
 *
 * The two patterns:
 *   - [openChat] / [closeChat] — long-lived "user is viewing this chat" intent. Use
 *     when the lifetime is bound to a UI screen (e.g. channel filter, comments overlay
 *     before being inlined into [CommentsRepository.threadFlow]). The caller MUST pair
 *     them in a `try { … } finally { … }` block, ideally via [NonCancellable] so a fast
 *     back-press still flushes the close.
 *   - [withOpenChat] — bounded "do this work while the chat is opened" scope. Right
 *     for short-lived priming (warm-up history fetches, single read APIs that benefit
 *     from open-chat priority). Pairing is enforced structurally so it can never leak.
 *
 * All errors are swallowed and logged via [warnUnlessCancelled]: a transient TDLib
 * failure to ack OpenChat is not worth surfacing to UI — the worst case is a slower
 * subsequent fetch, not a crash. Cancellation is preserved so the cooperative coroutine
 * cancellation contract still holds.
 */
internal object ChatPresence {
    private const val TAG = "ChatPresence"

    suspend fun openChat(td: TdSender, chatId: Long) {
        runCatching { td.send(TdApi.OpenChat(chatId)) }
            .warnUnlessCancelled(TAG, "openChat($chatId)")
    }

    suspend fun closeChat(td: TdSender, chatId: Long) {
        runCatching { td.send(TdApi.CloseChat(chatId)) }
            .warnUnlessCancelled(TAG, "closeChat($chatId)")
    }

    /**
     * Run [block] with [chatId] marked as opened in TDLib. `CloseChat` is guaranteed
     * to fire even on cancellation via [NonCancellable], so a fast screen dismissal
     * still cleans up TDLib's open-chat refcount.
     */
    suspend inline fun <T> withOpenChat(
        td: TdSender,
        chatId: Long,
        block: () -> T,
    ): T {
        openChat(td, chatId)
        try {
            return block()
        } finally {
            withContext(NonCancellable) { closeChat(td, chatId) }
        }
    }
}
