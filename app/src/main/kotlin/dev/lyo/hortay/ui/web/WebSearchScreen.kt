package dev.lyo.hortay.ui.web

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
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
 * UI shape mirrors `TimelineTopBar`'s in-channel search variant: back arrow,
 * auto-focused single-line text field, optional clear-X. Results render via
 * [PostCard] so a found post looks identical to its row in the feed (long-press
 * action sheet, share/copy/open in Telegram all wired). Debounce sits on the
 * UI side (220 ms) — DB reads are fast but unmounting the SQLDelight subscription
 * on every keystroke would still churn coroutine scopes.
 *
 * Tap-on-result behaviour: opens the post in the official Telegram client via
 * `tg://` deep link. Guest mode has no native post-detail screen and forcing a
 * channel-filter detour from search would be more confusing than helpful — the
 * t.me URL is the one truly stable handle a public-channel post has.
 */
@OptIn(
    ExperimentalMaterial3Api::class,
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

    // System back dismisses the overlay. Predictive-back animation is
    // intentionally NOT wired here (unlike CommentsScreen) — this isn't a
    // stacked screen with its own back-stack entry, it's a modal-like overlay
    // mounted from WebModeScaffold via a `searchOpen` boolean. Material 3
    // search-overlay convention: tap-back closes it just like the up-arrow.
    BackHandler { onDismiss() }

    var query by rememberSaveable { mutableStateOf("") }
    val trimmedQuery by remember {
        derivedStateOf { query.trim() }
    }

    // Debounced query → repository.search flow → list. snapshotFlow on the
    // (recomposition-driven) trimmedQuery debounces local-typing bursts; the
    // query is also short-circuited on blank so an empty bar doesn't pull every
    // post in the DB.
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
        // Guest mode has no in-app post-detail screen; tap on result opens
        // the post in the official Telegram client via the t.me deep link.
        PostInteractions(
            onPostClick = { post -> PostActions.openInTelegram(context, post) },
            onShareClick = { post -> PostActions.share(context, post) },
            onCopyClick = { post -> PostActions.copyText(context, post) },
            onOpenClick = { post -> PostActions.openInTelegram(context, post) },
            onBookmarkClick = { post ->
                scope.launch { bookmarks.toggle(post) }
            },
            isBookmarked = { post -> post.bookmarkKey() in bookmarkedKeys },
        )
    }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        textStyle = MaterialTheme.typography.titleMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                        ),
                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                            imeAction = ImeAction.Search,
                        ),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                            onSearch = { keyboard?.hide() },
                        ),
                        decorationBox = { inner ->
                            Box {
                                if (query.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.web_search_hint),
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                inner()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Symbol(
                            name = "arrow_back",
                            contentDescription = stringResource(R.string.web_cancel),
                        )
                    }
                },
                actions = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { query = "" }) {
                            Symbol(
                                name = "close",
                                contentDescription = stringResource(R.string.web_search_clear),
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        WebSearchBody(
            query = trimmedQuery,
            results = results,
            interactions = interactions,
            innerPadding = padding,
            outerPadding = contentPadding,
        )
    }
}

@Composable
private fun WebSearchBody(
    query: String,
    results: PersistentList<TimelinePost>,
    interactions: PostInteractions,
    innerPadding: PaddingValues,
    outerPadding: PaddingValues,
) {
    val listState = rememberLazyListState()
    // derivedStateOf so this read recomputes only when the boolean flips,
    // not on every scroll-frame's offset delta. AnimatedVisibility reads
    // this ~60Hz when scrolling otherwise.
    val isScrolledPastTop by remember(listState) {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 ||
                listState.firstVisibleItemScrollOffset > 0
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(top = innerPadding.calculateTopPadding())
            // imePadding so the keyboard doesn't occlude the bottom of the
            // result list. Lives on the result container, not the Scaffold,
            // because the TopAppBar text field handles its own focus/keyboard
            // and we only need to push the body up.
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
        // A subtle divider beneath the search bar so it doesn't visually float
        // on the same surface as the list when content scrolls underneath.
        AnimatedVisibility(
            visible = isScrolledPastTop,
            modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
        ) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            )
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
