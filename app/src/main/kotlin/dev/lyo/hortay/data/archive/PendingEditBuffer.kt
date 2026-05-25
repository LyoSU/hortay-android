package dev.lyo.hortay.data.archive

import org.drinkless.tdlib.TdApi
import java.util.concurrent.ConcurrentHashMap

/**
 * Pairs `UpdateMessageContent` (UMC) with `UpdateMessageEdited` (UME) so the archive
 * captures a new VERSION row **only** for genuine admin edits.
 *
 * ## Why this exists
 *
 * Per TDLib's TL schema (`td_api.tl:9844`):
 *
 *   > updateMessageEdited: A message was edited. Changes in the message content will
 *   > come in a separate updateMessageContent.
 *
 * Admin edits emit BOTH `updateMessageEdited(editDate > 0)` AND a separate
 * `updateMessageContent` with the new payload. The order between the two is not
 * guaranteed by TDLib.
 *
 * Non-edit content mutations (poll voter ticks, live-location coordinate updates,
 * paid-media reveals, self-destruct timer expiry, fact-checks) emit ONLY
 * `updateMessageContent` — no paired UME. Tying capture to bare UMC produced
 * "phantom edit" rows on every poll vote. Tying capture to UME alone misses
 * the fresh content (TDLib doesn't carry it on UME).
 *
 * This buffer holds incoming UMC payloads for up to [TTL_MS] and either:
 *  - **commits** the buffered content when a paired UME arrives (via [commitOnEdited]), OR
 *  - **drops** the entry on TTL expiry (poll vote / live loc / paid reveal — discarded).
 *
 * Also handles the reverse order: UME arriving FIRST. In that case [commitOnEdited]
 * returns `null` and the caller is expected to fall back to `GetMessage` for fresh content.
 *
 * Thread-safety: backed by [ConcurrentHashMap]; all methods are safe to call from
 * any TDLib-update dispatch context.
 *
 * @param nowMs clock injected for testability; defaults to wall-clock.
 */
class PendingEditBuffer(private val nowMs: () -> Long = System::currentTimeMillis) {

    private data class Entry(val content: TdApi.MessageContent, val expiresAtMs: Long)

    private val pending = ConcurrentHashMap<Pair<Long, Long>, Entry>()
    private val stashCount = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Stash an incoming [TdApi.UpdateMessageContent]. If a paired
     * [TdApi.UpdateMessageEdited] arrives within [TTL_MS], [commitOnEdited]
     * returns this content. Otherwise the entry expires silently on next
     * call to [pruneExpired] / [commitOnEdited].
     *
     * Opportunistic prune: every [STASH_PRUNE_EVERY] stash() folds in a
     * [pruneExpired] sweep so the map can't grow unboundedly on channels
     * that emit many non-paired UMC events (poll-vote / live-location
     * streams the user idles on). Amortised O(1) per stash and no separate
     * timer coroutine to coordinate with test schedulers.
     */
    fun stash(chatId: Long, messageId: Long, content: TdApi.MessageContent) {
        pending[chatId to messageId] = Entry(content, nowMs() + TTL_MS)
        if (stashCount.incrementAndGet() % STASH_PRUNE_EVERY == 0) pruneExpired()
    }

    /**
     * Called when a [TdApi.UpdateMessageEdited] with `editDate > 0` arrives.
     *
     * @return the buffered fresh [TdApi.MessageContent] when UMC arrived first;
     *   `null` when UME arrived first (caller should `GetMessage` as fallback).
     */
    fun commitOnEdited(chatId: Long, messageId: Long): TdApi.MessageContent? {
        pruneExpired()
        return pending.remove(chatId to messageId)?.content
    }

    /** Drop entries that aged past [TTL_MS] without a paired UME. */
    fun pruneExpired() {
        val now = nowMs()
        val it = pending.entries.iterator()
        while (it.hasNext()) {
            if (it.next().value.expiresAtMs < now) it.remove()
        }
    }

    /** Clear everything (called on logout). */
    fun clear() {
        pending.clear()
    }

    /** Test-only size. */
    internal fun size(): Int = pending.size

    companion object {
        /**
         * Time a UMC stays in the buffer waiting for its paired UME.
         *
         * 1500 ms covers the worst-case gap between the two TDLib updates that I've
         * observed in field logs (typically < 50 ms; outliers up to ~800 ms on
         * congested mobile networks during cold-start storms). Anything longer and
         * the UMC is almost certainly a non-edit mutation that won't pair.
         */
        const val TTL_MS: Long = 1500L

        /**
         * Stash-counter modulus that triggers an opportunistic [pruneExpired].
         * 32 keeps amortised cost negligible — one map iteration per dozens of
         * stash calls — while bounding the worst case at `STASH_PRUNE_EVERY`
         * entries above the true active set between sweeps.
         */
        const val STASH_PRUNE_EVERY = 32
    }
}
