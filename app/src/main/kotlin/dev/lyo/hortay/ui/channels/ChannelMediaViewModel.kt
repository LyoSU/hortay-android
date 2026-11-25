package dev.lyo.hortay.ui.channels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.lyo.hortay.data.ChannelMediaRepository
import dev.lyo.hortay.data.ChannelMediaState
import dev.lyo.hortay.data.appended
import dev.lyo.hortay.data.failed
import dev.lyo.hortay.data.loading
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Pages a channel's photos and videos for [ChannelMediaScreen]. Lives in the nav entry's
 * own ViewModelStore (NavDisplay decorator), so it is created when the grid opens, cleared
 * when it is popped, and wiped with the back stack on logout — it holds no account state
 * past that.
 *
 * Exactly one page request runs at a time ([inFlight]); [loadMore] is a no-op while one is
 * running, after an error (that waits for [retry]) and once the history is exhausted.
 */
class ChannelMediaViewModel(
    private val repo: ChannelMediaRepository,
    private val chatId: Long,
    channelTitle: suspend (Long) -> String?,
) : ViewModel() {

    private val _state = MutableStateFlow(ChannelMediaState())
    val state: StateFlow<ChannelMediaState> = _state.asStateFlow()

    private val _title = MutableStateFlow<String?>(null)

    /** The channel's name for the top bar; a local TDLib read, null until it lands. */
    val title: StateFlow<String?> = _title.asStateFlow()

    private var inFlight: Job? = null

    init {
        viewModelScope.launch { _title.value = channelTitle(chatId) }
        fetch()
    }

    /** Called by the grid as it nears its end. */
    fun loadMore() {
        if (_state.value.canLoadMore) fetch()
    }

    fun retry() {
        if (_state.value.status == ChannelMediaState.Status.Error) fetch()
    }

    private fun fetch() {
        if (inFlight?.isActive == true) return
        _state.update { it.loading() }
        inFlight = viewModelScope.launch {
            // A page can hold only messages the grid can't show (TDLib's photo-and-video
            // filter is server-side, but a paid or otherwise unmapped item can slip in).
            // Such a page adds no cells, so the grid's near-end trigger would never fire
            // again; walk on a bounded number of pages instead of stalling.
            repeat(MAX_PAGES_PER_FETCH) {
                val from = _state.value.nextFromMessageId
                val before = _state.value.items.size
                val page = repo.loadPage(chatId, from).getOrNull()
                if (page == null) {
                    _state.update { it.failed() }
                    return@launch
                }
                _state.update { it.appended(page, requestedFrom = from) }
                val now = _state.value
                if (now.items.size > before || !now.canLoadMore) return@launch
                _state.update { it.loading() }
            }
            // Bounded walk ran out without a visible cell: settle as Idle, which re-arms the
            // grid's near-end trigger (it keys on `canLoadMore`) for the next stretch. With no
            // cells at all there is no grid to trigger it; the screen shows "keep looking"
            // ([ChannelMediaState.View.KeepLooking]) and the next stretch waits for that tap.
            _state.update { s ->
                if (s.status == ChannelMediaState.Status.Loading) s.copy(status = ChannelMediaState.Status.Idle) else s
            }
        }
    }

    private companion object {
        const val MAX_PAGES_PER_FETCH = 3
    }
}
