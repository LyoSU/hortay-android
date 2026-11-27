package dev.lyo.hortay.data.web

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import dev.lyo.hortay.data.web.db.WebDatabase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * The `channel.notify` / `channel.notified_seq` columns (7.sqm) behind guest new-post
 * notifications. Reached as [WebRepository.notify], like [WebOlderWalkStore].
 *
 * `notified_seq` is the newest seq already accounted for — notified, seen in the
 * foreground, or the newest stored post when the bell was turned on — and only ever moves
 * up. The background check notifies posts above it; everything else here moves it.
 */
class WebNotifyStore internal constructor(
    private val db: WebDatabase,
    private val ioDispatcher: CoroutineDispatcher,
) {
    /** A subscribed channel with its bell on. */
    data class NotifyChannel(
        val username: String,
        val title: String,
        val avatarUrl: String?,
        /** Null until a baseline exists (the bell went on before any post was stored). */
        val notifiedSeq: Long?,
    )

    /** Handles of the subscribed channels with the bell on, re-emitted only when the set changes. */
    fun observeNotifyUsernames(): Flow<Set<String>> =
        db.channelQueries.selectNotifyChannels()
            .asFlow()
            .mapToList(ioDispatcher)
            .map { rows -> rows.mapTo(LinkedHashSet()) { it.username } }
            .distinctUntilChanged()
            .flowOn(ioDispatcher)

    suspend fun notifyChannels(): List<NotifyChannel> = withContext(ioDispatcher) {
        db.channelQueries.selectNotifyChannels().executeAsList().map {
            NotifyChannel(it.username, it.title, it.avatar_url, it.notified_seq)
        }
    }

    /**
     * Turn [username]'s bell on or off. On: the baseline becomes the newest stored post, so
     * the backlog is never notified. Off keeps the baseline; turning it on again resets it.
     */
    suspend fun setNotify(username: String, on: Boolean) = withContext(ioDispatcher) {
        if (on) db.channelQueries.setNotifyOn(username) else db.channelQueries.setNotifyOff(username)
    }

    /** `notified_seq` of [username] if its bell is on; null when off, unknown or without a baseline. */
    suspend fun notifiedSeqIfOn(username: String): Long? = withContext(ioDispatcher) {
        db.channelQueries.selectNotifiedSeq(username).executeAsOneOrNull()
            ?.takeIf { it.notify }
            ?.notified_seq
    }

    /** Raise [username]'s baseline to [seq]; a smaller value is ignored. */
    suspend fun advance(username: String, seq: Long) = withContext(ioDispatcher) {
        db.channelQueries.advanceNotified(seq = seq, username = username)
    }

    /** Raise [username]'s baseline to its newest stored post. */
    suspend fun advanceToLatest(username: String) = withContext(ioDispatcher) {
        db.postQueries.selectMaxSeq(username).executeAsOneOrNull()?.max_seq
            ?.let { db.channelQueries.advanceNotified(seq = it, username = username) }
    }

    /**
     * Raise every bell-on channel's baseline to its newest stored post: what reached the
     * database while the app was open counts as seen. Skips the write when nothing is behind,
     * since any write to `channel` re-runs the feed queries that join it.
     */
    suspend fun advanceAllToLatest() = withContext(ioDispatcher) {
        db.transaction {
            if (db.channelQueries.countNotifyBehind().executeAsOne() > 0L) {
                db.channelQueries.advanceNotifiedToLatest()
            }
        }
    }
}
