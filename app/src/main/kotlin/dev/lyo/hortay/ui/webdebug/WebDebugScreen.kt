package dev.lyo.hortay.ui.webdebug

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.lyo.hortay.data.web.FetchResult
import dev.lyo.hortay.data.web.ResolvedEmoji
import dev.lyo.hortay.data.web.WebChannelInfo
import dev.lyo.hortay.data.web.WebChannelPage
import dev.lyo.hortay.data.web.WebCustomEmojiResolver
import dev.lyo.hortay.data.web.WebMedia
import dev.lyo.hortay.data.web.WebPost
import dev.lyo.hortay.data.web.WebReaction
import dev.lyo.hortay.data.web.WebTelegramClient
import dev.lyo.hortay.data.web.WebTextContent
import dev.lyo.hortay.data.web.WebTextRenderer
import dev.lyo.hortay.data.web.parseUsernameFromInput
import dev.lyo.hortay.ui.icons.Symbol
import kotlinx.coroutines.launch
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.produceState
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.em

/**
 * Debug-only end-to-end smoke test for the web pipeline.
 *
 * Goal: prove that [WebTelegramClient] + [dev.lyo.hortay.data.web.TmePageParser] work
 * on a real device — real network conditions, real ART, real Coil pipeline. This is
 * NOT the production anonymous-mode UI; that comes in Phase 2 with proper subscription
 * persistence, scheduling, mode switching, and PostCard reuse.
 *
 * Surfaced only from [dev.lyo.hortay.ui.settings.SettingsScreen] when `BuildConfig.DEBUG`
 * is true, so beta/release builds never expose it. No localization (English literals)
 * because debug surfaces aren't user-facing.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun WebDebugScreen(
    client: WebTelegramClient,
    emojiResolver: WebCustomEmojiResolver,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
) {
    var input by rememberSaveable { mutableStateOf("durov") }
    var state by remember { mutableStateOf<WebDebugState>(WebDebugState.Idle) }
    val scope = rememberCoroutineScope()

    fun fetch() {
        val username = parseUsernameFromInput(input)
        if (username == null) {
            state = WebDebugState.Error("Invalid username — try '@durov' or 't.me/durov'")
            return
        }
        state = WebDebugState.Loading(username)
        scope.launch {
            state = when (val result = client.fetchChannelPage(username)) {
                is FetchResult.Page -> WebDebugState.Loaded(result.page)
                FetchResult.NotModified -> WebDebugState.Error("304 Not Modified — no cached body to display")
                FetchResult.NotFound -> WebDebugState.Error("Channel @$username not found")
                FetchResult.PrivateChannel -> WebDebugState.Error("@$username is private (no public preview)")
                FetchResult.ParseFailure -> WebDebugState.Error("Telegram returned HTML we couldn't parse — selectors may have drifted")
                is FetchResult.RateLimited -> WebDebugState.Error("Rate limited — retry in ${result.retryAfterMs / 1000}s")
                is FetchResult.NetworkError -> WebDebugState.Error("Network error: ${result.cause.message}")
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Web mode (debug)") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Symbol(name = "arrow_back", contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = padding.calculateTopPadding()),
        ) {
            FetchControls(
                input = input,
                onInputChange = { input = it },
                onFetch = ::fetch,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            )
            ResultArea(
                state = state,
                emojiResolver = emojiResolver,
                contentPadding = PaddingValues(
                    horizontal = 16.dp,
                    vertical = 8.dp,
                ),
                bottomInset = contentPadding.calculateBottomPadding(),
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun FetchControls(
    input: String,
    onInputChange: (String) -> Unit,
    onFetch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = onInputChange,
            label = { Text("Channel @username or t.me link") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        Button(onClick = onFetch) { Text("Fetch") }
    }
}

@Composable
private fun ResultArea(
    state: WebDebugState,
    emojiResolver: WebCustomEmojiResolver,
    contentPadding: PaddingValues,
    bottomInset: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    when (state) {
        WebDebugState.Idle -> CenteredMessage(
            text = "Type a channel handle and tap Fetch.\n" +
                "Try: durov, telegram, nexta_live",
            modifier = modifier,
        )

        is WebDebugState.Loading -> Box(
            modifier = modifier,
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(12.dp))
                Text("Fetching @${state.username}…")
            }
        }

        is WebDebugState.Error -> CenteredMessage(
            text = state.message,
            modifier = modifier,
        )

        is WebDebugState.Loaded -> LazyColumn(
            modifier = modifier,
            contentPadding = PaddingValues(
                start = contentPadding.calculateStartPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                end = contentPadding.calculateEndPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                top = contentPadding.calculateTopPadding(),
                bottom = bottomInset + 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { ChannelHeaderCard(state.page.channel) }
            items(state.page.posts, key = { it.id }) { post ->
                PostCard(post, emojiResolver)
            }
        }
    }
}

@Composable
private fun CenteredMessage(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.padding(32.dp),
        )
    }
}

@Composable
private fun ChannelHeaderCard(channel: WebChannelInfo) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (channel.avatarUrl != null) {
                AsyncImage(
                    model = channel.avatarUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(28.dp)),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = channel.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (channel.isVerified) {
                        Text("✓", color = MaterialTheme.colorScheme.primary)
                    }
                }
                Text(
                    text = "@${channel.username}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (channel.subscribers != null) {
                    Text(
                        text = "${channel.subscribers} subscribers",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun PostCard(post: WebPost, emojiResolver: WebCustomEmojiResolver) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = post.publishedAt,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (post.forwardedFrom != null) {
                Text(
                    text = "↪ ${post.forwardedFrom.channelName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            // Theme-aware link styles passed to WebTextRenderer so <a> spans inherit
            // primary colour + underline decoration. Compose 1.7+ Text auto-handles
            // LinkAnnotation taps via LocalUriHandler — no manual click wiring needed.
            val linkStyles = TextLinkStyles(
                style = SpanStyle(
                    color = MaterialTheme.colorScheme.primary,
                    textDecoration = TextDecoration.Underline,
                ),
            )
            val content = remember(post.textHtml) {
                WebTextRenderer.render(post.textHtml, linkStyles = linkStyles)
            }
            if (content.isNotEmpty) {
                WebPostText(
                    content = content,
                    emojiResolver = emojiResolver,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }
            post.media.forEach { media ->
                Spacer(Modifier.height(6.dp))
                MediaPreview(media)
            }
            if (post.webPreview != null) {
                Spacer(Modifier.height(6.dp))
                WebPreviewCard(post.webPreview)
            }
            FooterRow(post, emojiResolver)
        }
    }
}

@Composable
private fun MediaPreview(media: WebMedia) {
    when (media.kind) {
        WebMedia.Kind.Photo, WebMedia.Kind.Sticker -> AsyncImage(
            model = media.url,
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth()
                .let { mod -> if (media.aspectRatio != null) mod.aspectRatio(media.aspectRatio) else mod }
                .clip(RoundedCornerShape(12.dp)),
        )
        WebMedia.Kind.Video,
        WebMedia.Kind.RoundVideo,
        WebMedia.Kind.Gif -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .let { mod -> if (media.aspectRatio != null) mod.aspectRatio(media.aspectRatio) else mod.height(180.dp) }
                    .clip(RoundedCornerShape(12.dp)),
            ) {
                if (media.thumbnailUrl != null) {
                    AsyncImage(
                        model = media.thumbnailUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    val label = when {
                        media.kind == WebMedia.Kind.RoundVideo -> "● ${media.durationSec ?: ""}s"
                        media.kind == WebMedia.Kind.Gif -> "GIF"
                        media.durationSec != null -> "▶ ${media.durationSec}s"
                        else -> "▶"
                    }
                    Text(
                        text = label,
                        color = androidx.compose.ui.graphics.Color.White,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
        WebMedia.Kind.Voice -> Text(
            text = "🎤 Voice ${media.durationSec ?: 0}s",
            style = MaterialTheme.typography.bodyMedium,
        )
        WebMedia.Kind.Document -> Text(
            text = "📎 Document",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun WebPreviewCard(preview: dev.lyo.hortay.data.web.WebPreview) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            preview.siteName?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            preview.title?.let {
                Text(text = it, style = MaterialTheme.typography.titleSmall)
            }
            preview.description?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            preview.imageUrl?.let {
                Spacer(Modifier.height(6.dp))
                AsyncImage(
                    model = it,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp)
                        .clip(RoundedCornerShape(6.dp)),
                )
            }
        }
    }
}

/**
 * Renders a [WebTextContent] as a Compose `Text` with inline custom-emoji
 * placeholders backed by [WebCustomEmojiResolver]. Each emoji-id referenced in the
 * content gets an [InlineTextContent] entry whose children Composable resolves the
 * sticker URL and shows it via Coil's [AsyncImage] (static thumb for now —
 * promotion to animated TGS / WebM Lottie / ExoPlayer rendering is the next polish
 * pass).
 *
 * Fallback strategy: while the resolver runs (cold cache), or when it returns null
 * (resolution failure / unknown id), the [Text]'s alternateText for that placeholder
 * — which is the unicode hint extracted from `<b>` during preprocessing — surfaces
 * automatically. So users always see at least a static unicode emoji even if the
 * sticker URL never lands.
 */
@Composable
private fun WebPostText(
    content: WebTextContent,
    emojiResolver: WebCustomEmojiResolver,
    style: TextStyle,
    modifier: Modifier = Modifier,
) {
    // Resolver fan-out: one produceState per id, each cached by the resolver. Stable
    // while the post stays in composition; cancels automatically on scroll-out.
    val inlineContent: Map<String, InlineTextContent> = content.emojiIds.associate { id ->
        WebTextRenderer.INLINE_EMOJI_KEY_PREFIX + id to inlineEmojiContent(
            emojiId = id,
            fallbackGlyph = content.emojiFallbacks[id].orEmpty(),
            emojiResolver = emojiResolver,
        )
    }
    Text(
        text = content.text,
        style = style,
        inlineContent = inlineContent,
        modifier = modifier,
    )
}

@Composable
private fun inlineEmojiContent(
    emojiId: String,
    fallbackGlyph: String,
    emojiResolver: WebCustomEmojiResolver,
): InlineTextContent = InlineTextContent(
    placeholder = Placeholder(
        // 1.2em ≈ a single line height with a small margin. Telegram's own widget
        // renders custom emoji at roughly the same scale relative to surrounding
        // text, so this matches the visual rhythm we're imitating.
        width = 1.2.em,
        height = 1.2.em,
        placeholderVerticalAlign = PlaceholderVerticalAlign.Center,
    ),
    children = {
        val resolved by produceState<dev.lyo.hortay.data.web.ResolvedEmoji?>(null, emojiId) {
            value = emojiResolver.resolve(emojiId)
        }
        val url = resolved?.thumbUrl ?: resolved?.url
        if (url != null) {
            AsyncImage(
                model = url,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (fallbackGlyph.isNotBlank()) {
            // Render the unicode fallback while the resolver is in flight or after
            // a failure. We use a Text rather than the alternateText placeholder
            // path because Compose hides alternateText whenever a children block is
            // provided — even when the children render nothing.
            Text(
                text = fallbackGlyph,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    },
)

@Composable
private fun FooterRow(post: WebPost, emojiResolver: WebCustomEmojiResolver) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        post.reactions.forEach { reaction ->
            ReactionChip(reaction, emojiResolver)
        }
        Spacer(Modifier.weight(1f))
        if (post.views != null) {
            Text(
                text = "👁 ${post.views}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One reaction. Three rendering paths matching the three real reaction shapes
 * surveyed in TmePageParser:
 *
 *   - Custom-emoji (emojiId set, glyph blank): resolve via [emojiResolver] and
 *     render the JSON's `thumb` URL as a static image. Animated TGS / WebM
 *     promotion is deferred to Phase 2 polish (needs URL-driven Lottie player).
 *   - Unicode reaction (glyph populated, no emojiId): render glyph as text.
 *   - Paid stars (isPaid): render the ⭐ sentinel from the parser.
 */
@Composable
private fun ReactionChip(reaction: WebReaction, emojiResolver: WebCustomEmojiResolver) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        when {
            reaction.emojiId != null && reaction.glyph.isBlank() -> {
                // produceState issues the resolver call once per emojiId; cancellation
                // happens automatically when the chip leaves composition (e.g. fast
                // scroll past). The resolver caches results in-memory so a second
                // post with the same id resolves instantly.
                val resolved by produceState<ResolvedEmoji?>(null, reaction.emojiId) {
                    value = emojiResolver.resolve(reaction.emojiId)
                }
                val thumbUrl = resolved?.thumbUrl ?: resolved?.url
                if (thumbUrl != null) {
                    AsyncImage(
                        model = thumbUrl,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                } else {
                    // Fallback while resolving / on resolver failure: a small box keeps
                    // layout stable and the count still conveys "this reaction exists".
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(RoundedCornerShape(4.dp)),
                    )
                }
            }
            reaction.glyph.isNotBlank() -> Text(
                text = reaction.glyph,
                style = MaterialTheme.typography.bodyMedium,
            )
            else -> Text(
                text = "·",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Text(
            text = reaction.count,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private sealed interface WebDebugState {
    data object Idle : WebDebugState
    data class Loading(val username: String) : WebDebugState
    data class Loaded(val page: WebChannelPage) : WebDebugState
    data class Error(val message: String) : WebDebugState
}

private fun PaddingValues.calculateStartPadding(
    layoutDirection: androidx.compose.ui.unit.LayoutDirection,
): androidx.compose.ui.unit.Dp = calculateLeftPadding(layoutDirection)

private fun PaddingValues.calculateEndPadding(
    layoutDirection: androidx.compose.ui.unit.LayoutDirection,
): androidx.compose.ui.unit.Dp = calculateRightPadding(layoutDirection)
