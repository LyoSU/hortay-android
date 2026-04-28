package dev.lyo.telread.ui.timeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.lyo.telread.data.BookmarkStore
import dev.lyo.telread.data.PostsRepository
import dev.lyo.telread.data.TimelinePost
import dev.lyo.telread.data.bookmarkKey
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class TimelineViewModel(
    private val repo: PostsRepository,
    private val bookmarks: BookmarkStore,
) : ViewModel() {

    private val _channelFilter = MutableStateFlow<Long?>(null)
    val channelFilter: StateFlow<Long?> = _channelFilter.asStateFlow()

    val posts: StateFlow<List<TimelinePost>> = combine(
        repo.posts,
        _channelFilter,
    ) { all, channelId ->
        if (channelId == null) all else all.filter { it.chatId == channelId }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val bookmarkedKeys: StateFlow<Set<String>> = bookmarks.bookmarks
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptySet())

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        if (_refreshing.value) return
        viewModelScope.launch {
            _refreshing.value = true
            repo.refresh()
            _refreshing.value = false
        }
    }

    fun setChannelFilter(chatId: Long?) {
        _channelFilter.value = chatId
    }

    fun toggleBookmark(post: TimelinePost) {
        viewModelScope.launch { bookmarks.toggle(post) }
    }

    fun isBookmarked(snapshot: Set<String>, post: TimelinePost): Boolean =
        post.bookmarkKey() in snapshot

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
