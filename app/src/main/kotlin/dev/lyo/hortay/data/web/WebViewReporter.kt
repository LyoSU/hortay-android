package dev.lyo.hortay.data.web

import android.os.SystemClock
import dev.lyo.hortay.data.warnUnlessCancelled
import dev.lyo.hortay.ui.media.boundedLruCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/**
 * Counts the guest's reads as channel views, the way the t.me/s/ page itself does.
 *
 * Every post on a t.me/s/ page carries a `data-view` token. t.me's page script queues the
 * token of each post that comes into view, once, and posts the queue to `t.me/v/`
 * (`views=<tok>;<tok>;…`) at most every 10 s unless 50 are waiting, one page — so one
 * channel — per request. That is how a browser reader shows up in the channel's view
 * counter. Hortay read the same pages but never sent anything, so a guest reader was
 * invisible to the channel; this sends the same requests for the posts the guest
 * actually read.
 *
 * Flow:
 *  - [recordPage] keeps each fetched page's tokens in a bounded LRU ([TOKEN_CACHE_SIZE]),
 *    stamped with the receipt time on the MONOTONIC clock. The server's own `t` field is
 *    never compared against: the device clock may be anywhere. Called by every
 *    [WebFeedSource] page fetch, also when the ingest fingerprint guard skips the DB
 *    write — the tokens are fresh either way. Not from Add channel's lookup: a channel
 *    the user only peeked at was not read.
 *  - [onPostsRead] is fed by the guest read-ack dwell ([WebReadAck]) — the same 60 %
 *    visible + idle + resumed gate that marks a post read, so a post merely scrolled past
 *    is not a view. A token older than [TOKEN_MAX_AGE_MS] is dropped rather than sent
 *    (the server is assumed to reject stale ones); a post already reported this process
 *    is skipped ([SENT_CACHE_SIZE] LRU, memory only).
 *  - Per channel, a batch goes out once [FLUSH_INTERVAL_MS] have passed since that
 *    channel's last request (or its first queued read), or right away at [MAX_BATCH]
 *    tokens, and every batch goes out when the app leaves the foreground ([bind]).
 *    Nothing is sent while [isGated] — the shared t.me rate-limit backoff — holds; the
 *    batch waits for the next round. No retries: a failed request is dropped.
 *
 * Tokens stay out of the database on purpose: they change on every fetch, so storing
 * them (on `post` or [WebPost]) would make every sweep rewrite every row and undo the
 * ingest fingerprint guard. That also means a post read before any page fetch of this
 * process carries no token and is not reported — the next sweep brings tokens, and the
 * post's next read is.
 *
 * [enabled] combines the "Count my views" setting with being in guest mode: while it is
 * false nothing is recorded or sent, and the moment it turns false (setting off, sign-in,
 * leaving guest mode) everything held is dropped unsent.
 */
class WebViewReporter(
    private val scope: CoroutineScope,
    private val transport: suspend (username: String, tokens: List<String>) -> Unit,
    private val enabled: StateFlow<Boolean>,
    private val isGated: () -> Boolean = { false },
    private val nowMs: () -> Long = SystemClock::elapsedRealtime,
) {
    private class Token(val value: String, val receivedAtMs: Long)

    private class Batch(val firstQueuedAtMs: Long) {
        val tokens = ArrayList<Token>()
    }

    private val lock = Any()

    // Everything below is guarded by [lock].
    private val tokens = boundedLruCache<String, Token>(TOKEN_CACHE_SIZE)
    private val sent = boundedLruCache<String, Unit>(SENT_CACHE_SIZE)
    private val queue = LinkedHashMap<String, Batch>()
    private val lastSendAtMs = HashMap<String, Long>()
    private var deferUntilMs = Long.MIN_VALUE
    private var timerRunning = false

    /**
     * Flush everything when the app goes to the background, and drop everything once
     * [enabled] turns false. Call once.
     */
    fun bind(foreground: StateFlow<Boolean>) {
        scope.launch { foreground.filter { !it }.collect { flush(force = true) } }
        scope.launch { enabled.filter { !it }.collect { clear() } }
    }

    /** Remember the view tokens of a freshly fetched page. */
    fun recordPage(page: WebChannelPage) {
        if (!enabled.value || page.viewTokens.isEmpty()) return
        val now = nowMs()
        synchronized(lock) {
            for ((postId, token) in page.viewTokens) tokens[postId] = Token(token, now)
        }
    }

    /** Queue a view for each post in [postIds] (`<channel>/<seq>`) that has a usable token. */
    fun onPostsRead(postIds: Collection<String>) {
        if (!enabled.value || postIds.isEmpty()) return
        val now = nowMs()
        val queued = synchronized(lock) {
            var any = false
            for (postId in postIds) {
                if (enqueueLocked(postId, now)) any = true
            }
            if (!any || timerRunning) {
                false
            } else {
                timerRunning = true
                true
            }
        }
        if (queued) startTimer()
    }

    /** Queue [postId]'s token unless it was reported already, is unknown or too old. */
    private fun enqueueLocked(postId: String, now: Long): Boolean {
        if (postId in sent) return false
        val token = tokens.remove(postId) ?: return false
        if (now - token.receivedAtMs > TOKEN_MAX_AGE_MS) return false
        queue.getOrPut(postId.substringBefore('/')) { Batch(now) }.tokens += token
        sent[postId] = Unit
        return true
    }

    /** Drop every token, queued view and dedupe entry. */
    fun clear() = synchronized(lock) {
        tokens.clear()
        sent.clear()
        queue.clear()
        lastSendAtMs.clear()
        deferUntilMs = Long.MIN_VALUE
    }

    /**
     * Send every batch that is due ([force]: every batch). Internal for tests; the timer
     * and the background hook call it.
     */
    internal suspend fun flush(force: Boolean) {
        val now = nowMs()
        val due = synchronized(lock) {
            if (isGated()) {
                deferUntilMs = now + FLUSH_INTERVAL_MS
                return
            }
            val dueChannels = queue.filter { (channel, batch) -> force || isDue(channel, batch, now) }.keys.toList()
            dueChannels.flatMap { channel ->
                val fresh = queue.remove(channel)?.tokens.orEmpty()
                    .filter { token -> now - token.receivedAtMs <= TOKEN_MAX_AGE_MS }
                    .map { token -> token.value }
                if (fresh.isNotEmpty()) lastSendAtMs[channel] = now
                fresh.chunked(MAX_BATCH).map { chunk -> channel to chunk }
            }
        }
        for ((channel, batch) in due) {
            runCatching { transport(channel, batch) }.warnUnlessCancelled(TAG, "view report for $channel")
        }
    }

    private fun isDue(channel: String, batch: Batch, now: Long): Boolean {
        if (batch.tokens.size >= MAX_BATCH) return true
        val since = maxOf(lastSendAtMs[channel] ?: Long.MIN_VALUE, batch.firstQueuedAtMs)
        return now - since >= FLUSH_INTERVAL_MS
    }

    /** Milliseconds until the next batch is due, or null (and the timer stops) when none is queued. */
    private fun nextDelayLocked(now: Long): Long? {
        if (queue.isEmpty()) {
            timerRunning = false
            return null
        }
        val soonest = queue.minOf { (channel, batch) ->
            if (batch.tokens.size >= MAX_BATCH) {
                0L
            } else {
                val since = maxOf(lastSendAtMs[channel] ?: Long.MIN_VALUE, batch.firstQueuedAtMs)
                since + FLUSH_INTERVAL_MS - now
            }
        }
        val deferred = if (deferUntilMs > now) deferUntilMs - now else 0L
        return maxOf(soonest, deferred, 0L)
    }

    private fun startTimer() {
        scope.launch {
            try {
                while (true) {
                    val wait = synchronized(lock) { nextDelayLocked(nowMs()) } ?: break
                    delay(wait)
                    flush(force = false)
                }
            } catch (e: CancellationException) {
                // The normal exit already cleared the flag under the lock (so a read
                // queued right after starts a fresh timer); only a cancelled loop does here.
                synchronized(lock) { timerRunning = false }
                throw e
            }
        }
    }

    internal companion object {
        private const val TAG = "WebViewReporter"

        /** Tokens held for posts not yet read — a few hundred channel pages' worth. */
        const val TOKEN_CACHE_SIZE = 2_000

        /** Posts already reported this process. */
        const val SENT_CACHE_SIZE = 2_000

        /** t.me's page script sends at most every 10 s… */
        const val FLUSH_INTERVAL_MS = 10_000L

        /** …unless this many views are waiting. */
        const val MAX_BATCH = 50

        /**
         * A token older than this (since it was received) is dropped, not sent. The
         * server's real limit is unknown; a t.me page left open for an hour is about
         * the oldest a browser would send.
         */
        const val TOKEN_MAX_AGE_MS = 50L * 60 * 1000
    }
}
