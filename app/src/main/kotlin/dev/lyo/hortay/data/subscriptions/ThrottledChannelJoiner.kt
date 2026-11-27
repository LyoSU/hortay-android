package dev.lyo.hortay.data.subscriptions

import android.util.Log
import dev.lyo.hortay.data.ChannelActionsRepository
import dev.lyo.hortay.data.ChannelDirectory
import dev.lyo.hortay.data.PublicChatLookup
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow

/**
 * Joins a list of public channels by username, one at a time: `SearchPublicChat` +
 * `JoinChat` per channel, [throttleMs] apart. Shared by the guest → account migration
 * ([dev.lyo.hortay.data.web.MigrationCoordinator]) and the channel-list import, the two
 * places that join many channels in one go.
 *
 * Why one channel per second: each channel costs two RPCs, and firing a 50-channel batch
 * in the first second is a reliable way to earn an account-global FLOOD_WAIT. TDLib's
 * per-method gate tolerates ~30 `SearchPublicChat` per minute on Telegram's side; staying
 * at 60/min for the slower-throttled `JoinChat` is safer. Never run the joins in parallel.
 *
 * FLOOD_WAIT: every RPC already goes through [dev.lyo.hortay.data.TdClient]'s global gate,
 * which SLEEPS until the deadline passes. That is right for the migration, which simply
 * resumes after the wait. An import the user is watching should not sit frozen for
 * minutes, so with `stopOnFloodWait = true` the loop reads the gate's deadline
 * ([floodWaitUntilMs]) before each channel and after each failure, and stops with
 * [JoinRunResult.floodLimited] instead of queueing behind it.
 *
 * Cancellation is cooperative between channels (and inside the throttle delay), so a
 * cancelled run stops without draining the rest of the list.
 */
class ThrottledChannelJoiner(
    private val channels: ChannelDirectory,
    private val channelActions: ChannelActionsRepository,
    private val floodWaitUntilMs: StateFlow<Long>,
    private val throttleMs: Long = THROTTLE_MS,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    /**
     * Join every handle in [usernames] in order. [onProgress] gets the number of channels
     * already processed and the one about to be processed; [onJoined] runs right after
     * each successful join (the migration books each success the moment it lands, so a
     * cancelled run doesn't re-offer channels it already joined).
     */
    suspend fun joinAll(
        usernames: List<String>,
        stopOnFloodWait: Boolean,
        onProgress: (processed: Int, current: String) -> Unit = { _, _ -> },
        onJoined: suspend (username: String) -> Unit = {},
    ): JoinRunResult {
        val joined = ArrayList<String>()
        val notFound = ArrayList<String>()
        val failed = ArrayList<String>()
        var floodLimited = false
        for ((i, username) in usernames.withIndex()) {
            currentCoroutineContext().ensureActive()
            if (stopOnFloodWait && isFloodLimited()) floodLimited = true
            if (floodLimited) break
            val cleaned = username.removePrefix("@").trim().lowercase()
            if (cleaned.isNotEmpty()) {
                onProgress(i, cleaned)
                val ok = joinOne(cleaned, joined, notFound, failed, onJoined)
                // A failure that registered a FLOOD_WAIT ends the run at the top of the
                // next iteration, without sleeping the throttle first.
                if (stopOnFloodWait && !ok && isFloodLimited()) floodLimited = true
                if (!floodLimited && i < usernames.lastIndex) delay(throttleMs)
            }
        }
        return JoinRunResult(
            joined = joined,
            notFound = notFound,
            failed = failed,
            floodLimited = floodLimited,
        )
    }

    /**
     * Look up and join one channel, filing it under [joined], [notFound] or [failed].
     * Returns false only for a retryable failure (the one case a FLOOD_WAIT can cause).
     */
    private suspend fun joinOne(
        cleaned: String,
        joined: MutableList<String>,
        notFound: MutableList<String>,
        failed: MutableList<String>,
        onJoined: suspend (String) -> Unit,
    ): Boolean {
        // lookupPublicChat, not resolvePublicChat: the latter swallows every exception
        // into null, so an offline or flood-limited SearchPublicChat would read as
        // "no such channel".
        when (val lookup = channels.lookupPublicChat(cleaned)) {
            PublicChatLookup.NotFound -> {
                // The handle is malformed or not taken (wrong handle, deleted channel).
                Log.w(TAG, "SearchPublicChat($cleaned) found no chat")
                notFound += cleaned
            }
            PublicChatLookup.Failed -> {
                Log.w(TAG, "SearchPublicChat($cleaned) failed transiently")
                failed += cleaned
                return false
            }
            is PublicChatLookup.Found -> {
                // joinChat swallows (and surfaces) its own failure and reports it as false.
                if (!channelActions.joinChat(lookup.chatId)) {
                    Log.w(TAG, "JoinChat($cleaned) failed")
                    failed += cleaned
                    return false
                }
                joined += cleaned
                onJoined(cleaned)
            }
        }
        return true
    }

    private fun isFloodLimited(): Boolean = floodWaitUntilMs.value > nowMs()

    companion object {
        private const val TAG = "ChannelJoiner"

        /** One channel per second — two TDLib RPCs each (SearchPublicChat + JoinChat). */
        const val THROTTLE_MS = 1_000L
    }
}

/**
 * Outcome of [ThrottledChannelJoiner.joinAll]. [notFound] is permanent (wrong handle or
 * deleted channel); [failed] is worth retrying later; [floodLimited] means the run stopped
 * early on a Telegram rate limit, leaving the rest of the list unprocessed.
 */
data class JoinRunResult(
    val joined: List<String>,
    val notFound: List<String>,
    val failed: List<String>,
    val floodLimited: Boolean,
)
