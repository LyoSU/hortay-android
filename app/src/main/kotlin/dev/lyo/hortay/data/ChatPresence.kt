package dev.lyo.hortay.data

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.drinkless.tdlib.TdApi

/**
 * Single source of truth for the family of TDLib presence + read-state signals:
 * `OpenChat` / `CloseChat` (chat is being viewed) and `ViewMessages` (specific
 * messages were seen / read). All three travel together in TDLib's mental model
 * — they decide when the daemon prioritises updates, when reactions/views start
 * streaming, and when `lastReadInboxMessageId` advances — so they all flow
 * through this object.
 *
 * Why this exists: per TDLib docs, in supergroups and channels **updates are received
 * only for opened chats**, and the daemon prioritises history loading + caching for
 * them. Skipping `OpenChat` (or pairing it incorrectly) is the difference between a
 * cold-cache `GetChatHistory` / `GetMessageThreadHistory` paying full server round-trip
 * latency vs. answering from the local DB in tens of milliseconds.
 *
 * **Refcount discipline (OpenChat/CloseChat):** TDLib's open-chat state for a given
 * chat is a boolean, not a counter. Two callers issuing overlapping OpenChat/CloseChat
 * pairs for the same chat would have the inner CloseChat tear down the open state
 * mid-flight for the outer caller. We refcount on our side and only forward the
 * OpenChat on the 0→1 transition and the CloseChat on the 1→0 transition. This keeps
 * the "user is here" signal accurate when a screen-level open
 * ([PostsRepository.openChat]) and a bounded helper
 * ([CommentsRepository.prefetchThread]'s [withOpenChat]) overlap on the same thread
 * chat id.
 *
 * **One open chat at a time (mostly):** the maintainer's design intent is that clients
 * usually have at most one chat opened — see tdlib/td#2695 ("Usually, users have at
 * most one chat opened.") The app honours this strictly in the merged feed: a
 * dwell-driven focus tracker in [TimelineScreen] keeps exactly ONE chat opened — the
 * one carrying the topmost visible post — and transitions it as the user scrolls
 * between channels. Read-state advance for non-focused channels still works through
 * [viewMessages] with `forceRead=true`. The channel-filter screen opens its single
 * chat through the same [openChat] entry point, and the Comments overlay opens a
 * single discussion-thread chat via [withOpenChat]; the refcount lets these
 * concurrent opens safely overlap (e.g. when the user opens comments on a post in
 * the currently-focused channel — refcount goes 1→2 on the channel and 0→1 on the
 * thread chat, both clean).
 *
 * The three patterns:
 *   - [openChat] / [closeChat] — long-lived "user is viewing this chat" intent. Use
 *     when the lifetime is bound to a UI screen (e.g. channel filter). The caller MUST
 *     pair them in a `try { … } finally { … }` block, ideally via [NonCancellable] so
 *     a fast back-press still flushes the close.
 *   - [withOpenChat] — bounded "do this work while the chat is opened" scope. Right
 *     for short-lived priming (warm-up history fetches, single read APIs that benefit
 *     from open-chat priority). Pairing is enforced structurally so it can never leak.
 *   - [viewMessages] — "these specific messages have been seen by the user." Bumps
 *     server-side view counters and (with `forceRead=true`) advances
 *     `lastReadInboxMessageId` even for chats that are NOT currently opened. The
 *     authoritative way to mark closed-chat messages as read per tdlib/td#46 +
 *     tdlib/td#219. Caller batches sensibly — tdlib/td#2312 notes "Only a few messages
 *     can be viewed in a time"; do not bulk-flush the whole feed in one call.
 *
 * All errors are swallowed and logged via [warnUnlessCancelled]: a transient TDLib
 * failure to ack any of these signals is not worth surfacing to UI — the worst case is
 * a slower subsequent fetch or a temporarily lagging unread badge, not a crash.
 * Cancellation is preserved so the cooperative coroutine cancellation contract still
 * holds.
 */
internal object ChatPresence {
    private const val TAG = "ChatPresence"

    private val refMutex = Mutex()
    private val refCounts = mutableMapOf<Long, Int>()

    suspend fun openChat(td: TdSender, chatId: Long) {
        val shouldSend = refMutex.withLock {
            val next = (refCounts[chatId] ?: 0) + 1
            refCounts[chatId] = next
            next == 1
        }
        if (!shouldSend) return
        val err = runCatching { td.send(TdApi.OpenChat(chatId)) }.exceptionOrNull() ?: return
        // Send failed: the refcount we bumped above claims the chat is open, but
        // TDLib never opened it. Left as-is, subsequent [openChat] calls short-
        // circuit (next != 1 → no RPC), [isOpen] returns true so [viewMessages]
        // picks `force_read=false` against a chat TDLib considers closed (read
        // state silently stops advancing), and the eventual [closeChat] sends a
        // stray CloseChat for a chat that was never opened. Roll the refcount back
        // so local state matches TDLib.
        //
        // NonCancellable is load-bearing: the failure may itself be a
        // CancellationException (the send was cancelled mid-flight), and a bare
        // `refMutex.withLock` in an already-cancelled coroutine would immediately
        // re-throw before unwinding the count. On cancellation TDLib's actual
        // open-state is unknown, but the local count MUST unwind either way — a
        // leaked +1 poisons every later open/close pair on this id.
        withContext(NonCancellable) {
            refMutex.withLock {
                val current = refCounts[chatId] ?: 0
                when {
                    current <= 1 -> refCounts.remove(chatId)
                    else -> refCounts[chatId] = current - 1
                }
            }
        }
        if (err is CancellationException) throw err
        Log.w(TAG, "openChat($chatId) failed", err)
    }

    /**
     * Whether [chatId] currently has an outstanding `OpenChat` on its behalf.
     * Reads the refcount under the same mutex that mutates it so the answer
     * is consistent with the state the daemon sees. Callers use this to pick
     * the right `force_read` on [viewMessages]: TDLib documents
     * `force_read=true` as the closed-chat workaround for advancing
     * `lastReadInboxMessageId`; passing it for an opened chat is API misuse
     * and observed (tdlib/td#2312) to deprioritise the interaction-info
     * stream the chat is otherwise entitled to.
     */
    suspend fun isOpen(chatId: Long): Boolean = refMutex.withLock {
        (refCounts[chatId] ?: 0) > 0
    }

    suspend fun closeChat(td: TdSender, chatId: Long) {
        val shouldSend = refMutex.withLock {
            val current = refCounts[chatId] ?: 0
            // Defensive: an unbalanced close (no matching open) is treated as a no-op
            // rather than driving the count negative or sending a stray CloseChat. In
            // practice every caller pairs via try/finally or withOpenChat, so this is
            // belt-and-braces — but the safety net keeps us from corrupting future pairs
            // on the same chat if a callsite ever drops a close.
            when {
                current <= 0 -> false
                current == 1 -> { refCounts.remove(chatId); true }
                else -> { refCounts[chatId] = current - 1; false }
            }
        }
        if (!shouldSend) return
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

    /**
     * Hold every DISTINCT chat in [chatIds] open for the duration of [block], each
     * closed on exit (even on cancellation, via [NonCancellable]). Opened in
     * argument order, closed in reverse.
     *
     * Closes EXACTLY the chats this call actually opened — tracked in [opened] as
     * each [openChat] returns — never the raw [chatIds]. This matters because the
     * open loop has suspension points: if the coroutine is cancelled after opening
     * the first id but before the second, the `finally` must close only the first.
     * Blindly closing every requested id would [closeChat] one we never opened and
     * decrement a refcount another holder owns (e.g. the feed focus-tracker holding
     * the same channel), wrongly evicting their chat. Duplicate ids are de-duped so
     * a single (open, close) pair covers a repeated id.
     *
     * Use when one surface needs TDLib's open-chat-only streams
     * (`updateMessageInteractionInfo`, read-state) for MORE than one chat at once.
     * The comments overlay is the canonical case: it needs the discussion-thread
     * chat open (so comments stream) AND the host channel chat open (so the pinned
     * anchor post's own reactions / views / reply count keep streaming — the feed's
     * focus-tracker that normally holds the channel open is suspended under the
     * overlay). Keeping this a single primitive means the multi-chat lifecycle lives
     * in one place instead of nested [withOpenChat] calls or ad-hoc open/close pairs
     * scattered across screens.
     */
    suspend inline fun <T> withOpenChats(
        td: TdSender,
        vararg chatIds: Long,
        block: () -> T,
    ): T {
        val opened = ArrayList<Long>(chatIds.size)
        try {
            for (id in chatIds.distinct()) {
                openChat(td, id)
                opened.add(id)
            }
            return block()
        } finally {
            withContext(NonCancellable) {
                for (id in opened.asReversed()) closeChat(td, id)
            }
        }
    }

    /**
     * Tells TDLib the user has seen [messageIds] in [chatId]. Always passes through
     * here so call-site policy (which [TdApi.MessageSource] subtype, whether
     * `forceRead` is set) lives in one place per use case and the read-state TDLib
     * traffic is observable from a single tag.
     *
     * - [source] tells TDLib in what UI context the user saw the message. Channel
     *   feed → [TdApi.MessageSourceChatHistory]; comments thread →
     *   [TdApi.MessageSourceMessageThreadHistory]; sponsored / search / notification
     *   sources are also valid. Passing `null` lets TDLib guess from chat-open
     *   state, but being explicit avoids surprises and is what the docs recommend.
     * - [forceRead] = `true` advances `lastReadInboxMessageId` even when the chat
     *   isn't currently opened — required for the merged-feed case where the
     *   channel under the user's gaze isn't OpenChat'd. With `false`, read state
     *   only advances if [chatId] is currently opened (refcount > 0); the view
     *   counter still bumps either way.
     *
     * Empty [messageIds] is a no-op so callers can pass filtered lists without
     * guarding upstream. Errors are swallowed-and-logged: a transient ack failure
     * just means TDLib retries later — not a UI-actionable error.
     */
    /**
     * Wipe the per-account refcount map. Called from [AppGraph] on logout.
     *
     * Why this matters: the refcount map is process-singleton (this is an
     * `internal object`), but its entries are per-account — TDLib chat ids
     * are scoped to the local TDLib database, which `LogOut` wipes. After
     * logout, a stale `refCounts[chatId] = 1` entry from account A can
     * collide with the same numerical chat id used by another chat in
     * account B's database (TDLib re-uses internal supergroup ids
     * within its local db sequence; collisions are rare but possible).
     * If account B's UI then opens that chat, the refcount goes 1 → 2,
     * and the eventual close goes 2 → 1 — never sending CloseChat to
     * TDLib because the 0-transition never fires. Worse: if B never
     * opens that exact id and only closes it via some other path, the
     * close goes 1 → 0 and TDLib sees a CloseChat for a chat that was
     * never opened in this session.
     *
     * Wiping the map at logout makes the next account's refcount
     * accounting start from a true zero, regardless of what the previous
     * session left behind. UI cleanup (TimelineScreen's focus tracker,
     * channel-filter screen) still flushes its own CloseChat via the
     * `try { … } finally { closeChat(...) }` pattern when its scope
     * cancels — that path runs concurrently with this clear, but the
     * `closeChat` it issues will see refcount 0 (post-clear) and treat
     * the unbalanced close as a no-op (the defensive branch in
     * [closeChat]). Net result: no stray CloseChat to TDLib, no
     * stale state for the next account.
     */
    suspend fun clear() {
        refMutex.withLock { refCounts.clear() }
    }

    suspend fun viewMessages(
        td: TdSender,
        chatId: Long,
        messageIds: List<Long>,
        source: TdApi.MessageSource,
        forceRead: Boolean,
    ) {
        if (messageIds.isEmpty()) return
        runCatching {
            td.send(
                TdApi.ViewMessages(
                    chatId,
                    messageIds.toLongArray(),
                    source,
                    forceRead,
                ),
            )
        }.warnUnlessCancelled(TAG, "viewMessages($chatId, n=${messageIds.size}, force=$forceRead)")
    }
}
