package dev.lyo.hortay.ui.search

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.material3.ExpandedFullScreenSearchBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lyo.hortay.R
import dev.lyo.hortay.data.BookmarkStore
import dev.lyo.hortay.data.ChannelDirectory
import dev.lyo.hortay.data.TimelinePost
import dev.lyo.hortay.data.bookmarkKey
import dev.lyo.hortay.data.posts.PostsRepository
import dev.lyo.hortay.ui.actions.PostActions
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.timeline.PostCard
import dev.lyo.hortay.ui.timeline.PostInteractions
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Search across every channel the user follows (signed-in counterpart of the guest
 * `WebSearchScreen`, same full-screen M3 search bar). Results come from Telegram's
 * server-side `searchMessages`, so they reach posts that never scrolled through the
 * feed — not just the locally cached ones.
 *
 * [textFieldState] is hoisted to the scaffold so reopening search keeps the last query.
 * A result tap closes search and opens its channel AT the post ([onOpenPost]): the
 * search bar is a full-screen dialog, so a pushed screen would otherwise sit under it.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ChannelsSearchScreen(
    textFieldState: TextFieldState,
    repo: PostsRepository,
    channels: ChannelDirectory,
    bookmarks: BookmarkStore,
    onOpenPost: (chatId: Long, messageId: Long) -> Unit,
    onDismiss: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val searchBarState = rememberSearchBarState(initialValue = SearchBarValue.Expanded)
    val query by remember { derivedStateOf { textFieldState.text.toString().trim() } }

    var results by remember { mutableStateOf<ImmutableList<TimelinePost>>(persistentListOf()) }
    var searching by remember { mutableStateOf(false) }
    LaunchedEffect(query) {
        if (query.length < MIN_QUERY_LENGTH) {
            results = persistentListOf()
            searching = false
            return@LaunchedEffect
        }
        searching = true
        // A server round-trip per query: wait for typing to settle first.
        delay(SEARCH_DEBOUNCE_MS)
        results = repo.searchAllChannels(query).toImmutableList()
        searching = false
    }

    val close: () -> Unit = {
        scope.launch {
            runCatching { searchBarState.animateToCollapsed() }
            onDismiss()
        }
    }
    BackHandler(onBack = close)

    val bookmarkedKeys by bookmarks.bookmarks.collectAsStateWithLifecycle(initialValue = emptySet())
    val interactions = remember(bookmarkedKeys) {
        val open: (TimelinePost) -> Unit = { post -> onOpenPost(post.chatId, post.id) }
        PostInteractions(
            onPostClick = open,
            onChannelClick = open,
            onShareClick = { post -> scope.launch { PostActions.share(context, channels, post) } },
            onCopyClick = { post -> PostActions.copyText(context, post) },
            onOpenClick = { post -> scope.launch { PostActions.openInTelegram(context, channels, post) } },
            onBookmarkClick = { post -> scope.launch { bookmarks.toggle(post) } },
            isBookmarked = { post -> post.bookmarkKey() in bookmarkedKeys },
        )
    }

    ExpandedFullScreenSearchBar(
        state = searchBarState,
        inputField = {
            SearchBarDefaults.InputField(
                textFieldState = textFieldState,
                searchBarState = searchBarState,
                onSearch = { keyboard?.hide() },
                placeholder = {
                    Text(
                        text = stringResource(R.string.web_search_hint),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                leadingIcon = {
                    IconButton(onClick = close) {
                        Symbol(name = "arrow_back", contentDescription = stringResource(R.string.web_cancel))
                    }
                },
                trailingIcon = if (textFieldState.text.isNotEmpty()) {
                    {
                        IconButton(onClick = { textFieldState.clearText() }) {
                            Symbol(name = "close", contentDescription = stringResource(R.string.web_search_clear))
                        }
                    }
                } else {
                    null
                },
            )
        },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .imePadding(),
        ) {
            when {
                query.length < MIN_QUERY_LENGTH -> SearchHint(stringResource(R.string.web_search_prompt))
                searching && results.isEmpty() -> LoadingIndicator(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(40.dp),
                )
                results.isEmpty() -> SearchHint(stringResource(R.string.web_search_no_results, query))
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
                ) {
                    items(results, key = { "${it.chatId}/${it.id}" }, contentType = { "post" }) { post ->
                        PostCard(post = post, interactions = interactions)
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchHint(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Symbol(name = "search", tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 32.dp)
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

private const val MIN_QUERY_LENGTH = 2
private const val SEARCH_DEBOUNCE_MS = 400L
