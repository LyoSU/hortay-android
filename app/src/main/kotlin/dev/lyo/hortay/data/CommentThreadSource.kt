package dev.lyo.hortay.data

import androidx.compose.runtime.Stable
import kotlinx.coroutines.flow.StateFlow

/**
 * A comments thread the comments screen reads from outside [CommentsRepository] — today the
 * guest-mode [dev.lyo.hortay.data.web.WebCommentsThread], which pages through Telegram's
 * public discussion widget. Read-only by contract: there is nothing here to send, react or
 * vote with; the screen keeps its sign-in explanations for those.
 *
 * Unlike [CommentsRepository.observeThread], which hands over the whole thread at once, a
 * source can hold a partial thread: [older] says whether earlier comments can be fetched,
 * and [CommentsRepository.ThreadState.Ready.totalCount] carries the full size.
 */
@Stable
interface CommentThreadSource {
    val state: StateFlow<CommentsRepository.ThreadState>
    val older: StateFlow<OlderComments>

    /** Loads the first page if nothing is loaded or in flight yet. Idempotent. */
    fun start()

    /** After a failed load: drops the failure and loads the first page again. */
    fun retry()

    /** Fetches the page before the oldest loaded comment, when [older] is [OlderComments.Available]. */
    fun loadOlder()

    /**
     * Loads the comments around [commentId] (a quoted comment that isn't loaded). Returns
     * whether the comment is in [state] afterwards.
     */
    suspend fun loadAround(commentId: Long): Boolean

    /** Polls for new comments until cancelled. Run only while the screen is open and resumed. */
    suspend fun keepLive()
}

/** Whether a partial thread has earlier comments to fetch. */
enum class OlderComments { None, Available, Loading, Failed }
