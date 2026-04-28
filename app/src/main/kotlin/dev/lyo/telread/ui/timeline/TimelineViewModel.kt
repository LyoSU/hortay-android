package dev.lyo.telread.ui.timeline

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.lyo.telread.data.PostsRepository
import dev.lyo.telread.data.TdClient
import dev.lyo.telread.data.TimelinePost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TimelineViewModel(client: TdClient) : ViewModel() {

    private val repo = PostsRepository(client)

    val posts: StateFlow<List<TimelinePost>> = repo.posts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        if (_refreshing.value) return
        viewModelScope.launch {
            _refreshing.value = true
            runCatching { repo.refresh() }
            _refreshing.value = false
        }
    }
}
