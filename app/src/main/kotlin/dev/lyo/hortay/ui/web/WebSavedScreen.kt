package dev.lyo.hortay.ui.web

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lyo.hortay.AppGraph

/**
 * Bookmarked posts in guest mode. Mirrors the TDLib-mode "Збережене" tab —
 * same card style, same divider rhythm — but reads from
 * [WebRepository.observeBookmarked] instead of TDLib's bookmark store.
 *
 * Empty state intentionally non-prescriptive: we don't tell the user "tap the
 * bookmark icon" because that affordance doesn't exist on web posts yet
 * (Phase 2 polish task). For now, this tab just displays the empty state
 * until bookmarks are wired in.
 */
@Composable
fun WebSavedScreen(
    graph: AppGraph,
    contentPadding: PaddingValues,
) {
    val saved by graph.webRepository.observeBookmarked()
        .collectAsStateWithLifecycle(initialValue = kotlinx.collections.immutable.persistentListOf())
    val layoutDirection = LocalLayoutDirection.current

    if (saved.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Поки немає збережених постів.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(32.dp),
            )
        }
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = contentPadding.calculateStartPadding(layoutDirection),
            end = contentPadding.calculateEndPadding(layoutDirection),
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
    ) {
        items(saved, key = { it.post.id }) { entry ->
            WebPostCard(
                post = entry.post,
                emojiResolver = graph.webCustomEmoji,
                channelTitle = entry.channel.title,
                channelAvatarUrl = entry.channel.avatarUrl,
            )
        }
    }
}
