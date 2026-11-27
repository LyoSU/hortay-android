package dev.lyo.hortay.data.web

import dev.lyo.hortay.data.CommentThreadSource
import dev.lyo.hortay.data.CommentsRepository.ThreadState
import dev.lyo.hortay.data.OlderComments
import dev.lyo.hortay.data.StringResolver
import dev.lyo.hortay.data.buildThreadRows
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One guest-mode comments thread, read through [WebDiscussionClient]. Created per comments
 * screen and scoped to it ([scope] is the screen's composition scope, so leaving the
 * screen cancels every request in flight).
 *
 * Loading order follows the widget: the embed page brings the newest comments first,
 * [loadOlder] walks back with `before_id` as the reader reaches the top of what is loaded,
 * and [loadAround] swaps in the window around a quoted comment (`comment_id`). Comments are
 * kept by id, so a page or poll that repeats one (edited text, new reactions) replaces it
 * in place.
 *
 * Live refresh ([keepLive], every [POLL_INTERVAL_MS] while the screen is resumed). The
 * widget itself only ever appends comments after its `data-after` marker and never
 * refreshes the ones it already shows, so their edits, reactions and deletions would stay
 * stale for as long as the screen is open. Instead, each tick asks for `after_id` = one
 * below the lowest comment id on screen ([onVisibleRange]): the reply (up to
 * [WIDGET_PAGE_SIZE] comments in ascending order) re-sends the visible comments in their
 * current form AND carries any new ones, so one request covers both in the common case.
 * Because the loaded comments are one contiguous run (pages only ever extend it at its
 * edges, and [loadAround] replaces it), a loaded comment whose id falls inside the range
 * the reply covered but which is missing from it was deleted, and is dropped. Only when
 * that window is cut at the page size (or comes back empty) does the same tick also ask
 * for the comments after the newest known one, as the widget does. With nothing on screen
 * yet, the tick is just that newer-comments poll.
 *
 * Degradation: a failed first load is [ThreadState.LoadFailed], a post the widget won't
 * show is [ThreadState.Error] — never an empty [ThreadState.Ready]. A failed later page
 * or poll leaves what is on screen alone; older pages report it through [older]. A refresh
 * only prunes on a successful, non-empty reply, and only inside the id range it covered.
 */
class WebCommentsThread(
    private val client: WebDiscussionClient,
    private val channel: String,
    private val postId: Long,
    private val strings: StringResolver,
    private val scope: CoroutineScope,
) : CommentThreadSource {

    private val _state = MutableStateFlow<ThreadState>(ThreadState.Loading)
    override val state: StateFlow<ThreadState> = _state.asStateFlow()

    private val _older = MutableStateFlow(OlderComments.None)
    override val older: StateFlow<OlderComments> = _older.asStateFlow()

    /** Serialises every request and every write of the fields below. */
    private val mutex = Mutex()
    private var session: DiscussionSession? = null
    private val comments = LinkedHashMap<Long, WebComment>()
    private var beforeCursor: Long? = null
    private var afterCursor: Long? = null
    private var totalCount: Int? = null
    private var bootstrap: Job? = null

    /** Lowest visible comment id, from [onVisibleRange]; written from the UI, read by the poll. */
    @Volatile private var viewportFirstId: Long? = null

    /** Synthetic chat id the thread's comments share; see [WebPostAdapter.commentToTimelinePost]. */
    private val threadChatId = WebPostAdapter.stableChatId("$channel/$postId#discussion")

    override fun start() {
        if (bootstrap?.isActive == true || session != null || _state.value is ThreadState.Error) return
        bootstrap = scope.launch { mutex.withLock { openLocked() } }
    }

    override fun retry() {
        if (bootstrap?.isActive == true) return
        _state.value = ThreadState.Loading
        bootstrap = scope.launch { mutex.withLock { openLocked() } }
    }

    private suspend fun openLocked() {
        when (val opened = client.open(channel, postId)) {
            is DiscussionOpen.Thread -> {
                session = opened.session
                comments.clear()
                applyFragment(opened.fragment, replace = true)
                totalCount = opened.totalCount
                publish()
            }
            DiscussionOpen.Unavailable -> _state.value = ThreadState.Error(UNAVAILABLE)
            DiscussionOpen.Failed -> _state.value = ThreadState.LoadFailed
        }
    }

    override fun loadOlder() {
        if (_older.value != OlderComments.Available && _older.value != OlderComments.Failed) return
        _older.value = OlderComments.Loading
        scope.launch {
            mutex.withLock {
                val current = session
                val cursor = beforeCursor
                if (current == null || cursor == null) {
                    _older.value = OlderComments.None
                    return@withLock
                }
                when (val fetched = client.loadComments(current, CommentsCursor.Before(cursor))) {
                    is CommentsFetch.Ok -> {
                        session = fetched.session
                        fetched.totalCount?.let { totalCount = it }
                        fetched.fragment.comments.forEach { comments[it.id] = it }
                        // A page with no "more" marker inside is the first page of the thread.
                        beforeCursor = fetched.fragment.beforeCursor
                            ?.takeIf { fetched.fragment.comments.isNotEmpty() }
                        publish()
                    }
                    CommentsFetch.Failed -> _older.value = OlderComments.Failed
                }
            }
        }
    }

    override suspend fun loadAround(commentId: Long): Boolean = mutex.withLock {
        if (commentId in comments) return@withLock true
        val current = session ?: return@withLock false
        when (val fetched = client.loadComments(current, CommentsCursor.Around(commentId))) {
            is CommentsFetch.Ok -> {
                if (fetched.fragment.comments.none { it.id == commentId }) return@withLock false
                session = fetched.session
                fetched.totalCount?.let { totalCount = it }
                // As the widget does: the window around the comment replaces the list, with
                // its own markers, so the loaded comments stay one contiguous run.
                comments.clear()
                applyFragment(fetched.fragment, replace = true)
                publish()
                true
            }
            CommentsFetch.Failed -> false
        }
    }

    override suspend fun keepLive() {
        while (true) {
            delay(POLL_INTERVAL_MS)
            pollOnce()
        }
    }

    override fun onVisibleRange(firstId: Long, lastId: Long) {
        viewportFirstId = firstId
    }

    /** One live tick: see the class KDoc. Internal for tests — [keepLive] loops over it. */
    internal suspend fun pollOnce() = mutex.withLock {
        val current = session ?: return@withLock
        val anchor = viewportFirstId?.takeIf { it in comments }
        if (anchor == null || !refreshFromLocked(current, anchor)) {
            pollNewerLocked(session ?: current)
        }
    }

    /**
     * Re-reads the comments from [anchor] on and merges them in place. Returns whether the
     * reply reached the newest end of the thread, so no separate newer-comments poll is needed.
     */
    private suspend fun refreshFromLocked(current: DiscussionSession, anchor: Long): Boolean {
        val newestLoaded = comments.keys.max()
        val fetched = client.loadComments(current, CommentsCursor.After(anchor - 1)) as? CommentsFetch.Ok
            ?: return true // A failed refresh leaves the screen alone and spends no second request.
        session = fetched.session
        fetched.totalCount?.let { totalCount = it }
        val returned = fetched.fragment.comments
        if (returned.isEmpty()) return false
        val lastReturned = returned.maxOf { it.id }
        val returnedIds = returned.mapTo(HashSet()) { it.id }
        comments.keys.removeAll { it in anchor..lastReturned && it !in returnedIds }
        returned.forEach { comments[it.id] = it }
        val reachedNewest = returned.size < WIDGET_PAGE_SIZE && lastReturned >= newestLoaded
        if (reachedNewest) {
            val marker = fetched.fragment.afterCursor ?: lastReturned
            afterCursor = maxOf(afterCursor ?: marker, marker)
        }
        publish()
        return reachedNewest
    }

    /** The widget's own live poll: comments after the newest known one. */
    private suspend fun pollNewerLocked(current: DiscussionSession) {
        val cursor = afterCursor ?: comments.keys.maxOrNull() ?: return
        val fetched = client.loadComments(current, CommentsCursor.After(cursor)) as? CommentsFetch.Ok
            ?: return
        session = fetched.session
        fetched.totalCount?.let { totalCount = it }
        fetched.fragment.comments.forEach { comments[it.id] = it }
        val marker = fetched.fragment.afterCursor ?: fetched.fragment.comments.maxOfOrNull { it.id }
        afterCursor = maxOf(cursor, marker ?: cursor)
        publish()
    }

    private fun applyFragment(fragment: CommentsFragment, replace: Boolean) {
        fragment.comments.forEach { comments[it.id] = it }
        if (replace) {
            beforeCursor = fragment.beforeCursor
            afterCursor = fragment.afterCursor ?: fragment.comments.maxOfOrNull { it.id }
        }
    }

    private fun publish() {
        val loaded = comments.values.toList()
        val ids = comments.keys
        val root = session?.keys?.topMsgId ?: 0L
        val posts = loaded.map { c ->
            val orphan = c.replyToId != null && c.replyToId != root && c.replyToId !in ids
            WebPostAdapter.commentToTimelinePost(c, threadChatId, showReplyCard = orphan, strings = strings)
        }
        val rows = buildThreadRows(posts, rootMessageId = root, maxDepth = MAX_DEPTH)
        // The widget's own count when it gave one; never below what is actually loaded.
        val total = totalCount?.coerceAtLeast(rows.size)
        _state.value = ThreadState.Ready(rows.toPersistentList(), threadChatId, total)
        _older.value = when {
            beforeCursor == null -> OlderComments.None
            else -> OlderComments.Available
        }
    }

    companion object {
        /**
         * Live-poll period while the screen is resumed. The widget itself polls every 3 s
         * focused / 30 s blurred; a reader app has no composer waiting on replies, so a
         * gentler cadence costs Telegram a fifth of the requests.
         */
        const val POLL_INTERVAL_MS = 15_000L

        /**
         * Most comments one `loadComments` reply carries (probed 2026-11-27: `after_id` on an
         * 86-comment thread returned 50, then the remaining 36). A refresh window of this size
         * may have been cut short, so the newest comments get their own poll.
         */
        internal const val WIDGET_PAGE_SIZE = 50
        private const val MAX_DEPTH = 3
        private const val UNAVAILABLE = "discussion unavailable"
    }
}
