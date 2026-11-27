package dev.lyo.hortay.data.web

import dev.lyo.hortay.data.ReplyMediaKind
import dev.lyo.hortay.data.TimelinePost

/** The system side of guest new-post notifications; [WebPostNotifier] in production. */
interface WebPostNotifications {
    /** What the channel's notification shows right now, or null when none is on screen. */
    fun shown(username: String): WebNotifySelection.Shown?

    /** Post or replace the one notification of [channel]. */
    suspend fun show(channel: WebNotifyStore.NotifyChannel, shown: WebNotifySelection.Shown)

    fun cancel(username: String)

    fun cancelAll()

    /** Line for a post with no text; [ReplyMediaKind.None] is the generic "New post". */
    fun label(kind: ReplyMediaKind): String
}

/** The periodic background check; WorkManager in production ([WebNotifyWork]). */
interface WebNotifyScheduling {
    /** Idempotent: a repeat call keeps the schedule and only updates its constraints. */
    fun schedule(wifiOnly: Boolean)

    fun cancel()
}

/**
 * One run of the tier-3 background check (see [WebFeedScheduler] for the tiers), driven
 * by [WebNotifyWork] every 30 minutes. For each subscribed channel with its bell on: fetch
 * the newest page ([WebFeedSource.fetchNewestInBackground]), then notify the posts above
 * the channel's baseline that the feed would show ([WebNotifySelection.select]) and raise
 * the baseline past every candidate.
 *
 * Cost bounds, in order of the checks below:
 *  - nothing at all outside guest mode or with no bell on — the caller then cancels the
 *    schedule, so a stale one can't keep waking the app;
 *  - nothing while the app is in the foreground: the tier-2 poller already covers it, and
 *    what it ingests counts as seen ([WebNotifyCoordinator.onForegroundSettled]);
 *  - one page per channel, one channel at a time, and the run ends at the first sign of
 *    t.me's rate limit (the shared gate, which a network error also raises) — the next
 *    period tries again instead of retrying now.
 */
class WebNotifyCheck(
    private val store: WebNotifyStore,
    private val isGuestActive: suspend () -> Boolean,
    private val isForeground: () -> Boolean,
    private val isGated: () -> Boolean,
    private val fetchNewest: suspend (String) -> WebFeedSource.BackgroundFetch,
    private val postsAfter: suspend (username: String, afterSeq: Long) -> List<TimelinePost>,
    /** Whether the reader hid the channel from the feed. */
    private val isHidden: suspend (username: String) -> Boolean,
    private val mutedWords: suspend () -> Set<String>,
    private val notifications: WebPostNotifications,
) {
    enum class Outcome {
        /** Not in guest mode, or no channel has its bell on: the schedule should go. */
        Disabled,

        /** The app is in the foreground; nothing fetched. */
        Foreground,

        /** t.me's rate limit is up; the rest waits for the next period. */
        Gated,

        Done,
    }

    suspend fun run(): Outcome {
        if (!isGuestActive()) return Outcome.Disabled
        val channels = store.notifyChannels()
        if (channels.isEmpty()) return Outcome.Disabled
        if (isForeground()) return Outcome.Foreground
        val muted = mutedWords()
        for (channel in channels) {
            if (isGated()) return Outcome.Gated
            when (fetchNewest(channel.username)) {
                WebFeedSource.BackgroundFetch.Gated -> return Outcome.Gated
                // NoChange too: a channel still without a baseline gets one now, not on the
                // first page that actually carries a new post (which it would swallow).
                WebFeedSource.BackgroundFetch.Fresh,
                WebFeedSource.BackgroundFetch.NoChange,
                -> notifyChannel(channel, muted)
                WebFeedSource.BackgroundFetch.Failed -> Unit
            }
        }
        return Outcome.Done
    }

    private suspend fun notifyChannel(channel: WebNotifyStore.NotifyChannel, muted: Set<String>) {
        // Re-read: the bell may have gone off, or the baseline moved, while the page loaded.
        val baseline = store.notifiedSeqIfOn(channel.username)
        if (baseline == null) {
            // No baseline yet (the bell went on before anything was stored): set one, notify nothing.
            store.advanceToLatest(channel.username)
            return
        }
        val selection = WebNotifySelection.select(
            candidates = postsAfter(channel.username, baseline),
            notifiedSeq = baseline,
            channelHidden = isHidden(channel.username),
            mutedWords = muted,
        )
        val maxSeq = selection.maxSeq ?: return
        if (selection.previews.isNotEmpty()) {
            val lines = selection.previews.map { it.text ?: notifications.label(it.kind) }
            val shown = WebNotifySelection.merge(
                previous = notifications.shown(channel.username),
                newLines = lines,
                newCount = selection.previews.size,
                newMaxSeq = selection.previews.first().seq,
            )
            notifications.show(channel, shown)
        }
        store.advance(channel.username, maxSeq)
    }
}
