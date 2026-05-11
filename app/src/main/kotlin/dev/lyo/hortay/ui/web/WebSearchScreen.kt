package dev.lyo.hortay.ui.web

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExpandedFullScreenSearchBar
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SearchBarValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSearchBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
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
import dev.lyo.hortay.data.TimelinePost
import dev.lyo.hortay.data.bookmarkKey
import dev.lyo.hortay.data.web.WebRepository
import dev.lyo.hortay.ui.actions.PostActions
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.timeline.PostCard
import dev.lyo.hortay.ui.timeline.PostInteractions
import kotlinx.collections.immutable.PersistentList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Cross-channel local search for guest mode. Reads posts straight from `web.db`
 * via [WebRepository.search] (indexed `LIKE` on `text_plain`) — never hits the
 * network. The DB stores everything that has scrolled past the user, so the
 * search corpus matches exactly what they've already seen in the feed.
 *
 * UI uses M3 1.5 Expressive's [ExpandedFullScreenSearchBar] over the older
 * "TopAppBar + BasicTextField" stack the rest of the app had built up. The
 * expressive search bar handles the rounded input pill, leading / trailing icon
 * slots, IME action, and full-screen layout — all of which we used to wire
 * by hand. Internally it mounts a Dialog at full-screen so our `Scaffold` and
 * its IME insets stay out of the way.
 *
 * State pair:
 *   - [rememberTextFieldState] is the M3 Compose 1.7+ canonical text input
 *     state holder; we drive the search query off `textFieldState.text` via
 *     [snapshotFlow] so the 220 ms debounce + `flatMapLatest` cancellation
 *     pipeline keeps working unchanged.
 *   - [rememberSearchBarState] is initialised to `Expanded` because this
 *     screen is mounted only when the user has already tapped the search
 *     entry — no "collapsed → expanded" reveal animation needed. The
 *     `animateToCollapsed` call on dismiss is best-effort: the parent
 *     unmounts WebSearchScreen synchronously once `onDismiss` fires, so
 *     the collapse animation tends to run for only a frame or two; the
 *     IME hide is the user-visible bit.
 *
 * Tap-on-result behaviour: opens the post in the official Telegram client via
 * `tg://` deep link. Guest mode has no native post-detail screen and forcing a
 * channel-filter detour from search would be more confusing than helpful — the
 * t.me URL is the one truly stable handle a public-channel post has.
 */
@OptIn(
    ExperimentalMaterial3Api::class,
    ExperimentalMaterial3ExpressiveApi::class,
    FlowPreview::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)
@Composable
fun WebSearchScreen(
    repository: WebRepository,
    bookmarks: BookmarkStore,
    onDismiss: () -> Unit,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()

    val textFieldState = rememberTextFieldState()
    val searchBarState = rememberSearchBarState(initialValue = SearchBarValue.Expanded)

    // The trimmed query is what we send to the DB; recomputed only when the
    // TextFieldState's text mutates (snapshot-aware) so unrelated recomposes
    // don't churn the search flow.
    val trimmedQuery by remember {
        derivedStateOf { textFieldState.text.toString().trim() }
    }

    // System back closes the overlay. We don't wire predictive-back here
    // (unlike CommentsScreen) — this is a modal-style overlay mounted from
    // WebModeScaffold via `searchOpen`. The SearchBar's own collapse runs
    // briefly before the parent unmount tears down the Dialog.
    BackHandler {
        scope.launch {
            runCatching { searchBarState.animateToCollapsed() }
            onDismiss()
        }
    }

    // Debounced query → repository.search flow → list. snapshotFlow on the
    // (recomposition-driven) trimmedQuery debounces local-typing bursts; an
    // under-2-char query is short-circuited so an empty bar doesn't pull
    // every post in the DB.
    val results by remember {
        snapshotFlow { trimmedQuery }
            .distinctUntilChanged()
            .debounce(220)
            .flatMapLatest { q ->
                if (q.length < MIN_QUERY_LENGTH) flowOf(persistentListOf<TimelinePost>())
                else repository.search(q)
            }
    }.collectAsStateWithLifecycle(initialValue = persistentListOf<TimelinePost>())

    val bookmarkedKeys by bookmarks.bookmarks
        .collectAsStateWithLifecycle(initialValue = emptySet())

    val interactions = remember {
        // Guest mode has no in-app post-detail screen; tap on result opens the post in
        // the official Telegram client via the t.me deep link. PostActions receives a
        // null PostsRepository here — its share-URL builder skips the TDLib path
        // (`GetMessageLink` wouldn't have a real chat to query against in guest mode)
        // and uses the hand-rolled `t.me/<handle>/<msg>` fallback.
        PostInteractions(
            onPostClick = { post -> scope.launch { PostActions.openInTelegram(context, null, post) } },
            onShareClick = { post -> scope.launch { PostActions.share(context, null, post) } },
            onCopyClick = { post -> PostActions.copyText(context, post) },
            onOpenClick = { post -> scope.launch { PostActions.openInTelegram(context, null, post) } },
            onBookmarkClick = { post ->
                scope.launch { bookmarks.toggle(post) }
            },
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
                    IconButton(onClick = {
                        scope.launch {
                            runCatching { searchBarState.animateToCollapsed() }
                            onDismiss()
                        }
                    }) {
                        Symbol(
                            name = "arrow_back",
                            contentDescription = stringResource(R.string.web_cancel),
                        )
                    }
                },
                trailingIcon = if (textFieldState.text.isNotEmpty()) {
                    {
                        IconButton(onClick = { textFieldState.clearText() }) {
                            Symbol(
                                name = "close",
                                contentDescription = stringResource(R.string.web_search_clear),
                            )
                        }
                    }
                } else null,
            )
        },
    ) {
        WebSearchBody(
            query = trimmedQuery,
            results = results,
            interactions = interactions,
            outerPadding = contentPadding,
        )
    }
}

@Composable
private fun ColumnScope.WebSearchBody(
    query: String,
    results: PersistentList<TimelinePost>,
    interactions: PostInteractions,
    outerPadding: PaddingValues,
) {
    val listState = rememberLazyListState()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // imePadding so the keyboard doesn't occlude the bottom of the
            // result list. The ExpandedFullScreenSearchBar dialog supplies
            // window-level insets for the input field itself; we add IME
            // padding only to the result body.
            .imePadding(),
    ) {
        when {
            query.length < MIN_QUERY_LENGTH -> SearchEmptyState(
                text = stringResource(R.string.web_search_prompt),
            )
            results.isEmpty() -> SearchEmptyState(
                text = stringResource(R.string.web_search_no_results, query),
            )
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    bottom = outerPadding.calculateBottomPadding(),
                ),
                verticalArrangement = Arrangement.Top,
            ) {
                items(results, key = { "${it.chatId}/${it.id}" }) { post ->
                    PostCard(post = post, interactions = interactions)
                }
            }
        }
    }
}

@Composable
private fun SearchEmptyState(text: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            Symbol(
                name = "search",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                size = 32.dp,
            )
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

// 2 chars: shortest meaningful Cyrillic / English query token. 1 char would
// drag the entire DB into a recomposition burst on first keystroke for no
// product value — search hit rate on a single letter is essentially "all posts".
private const val MIN_QUERY_LENGTH = 2
