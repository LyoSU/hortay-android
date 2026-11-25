@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package dev.lyo.hortay.ui.channels

import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lyo.hortay.R
import dev.lyo.hortay.data.ChannelActionsRepository
import dev.lyo.hortay.data.ChannelInfo
import dev.lyo.hortay.data.IgnoredChannelsStore
import dev.lyo.hortay.data.discover.ChannelDiscoveryRepository
import dev.lyo.hortay.data.discover.DiscoverChannel
import dev.lyo.hortay.ui.components.copyPlainText
import dev.lyo.hortay.ui.components.rememberExpandedSheetState
import dev.lyo.hortay.ui.discover.ChannelDiscoverRow
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.media.TdAvatar
import dev.lyo.hortay.ui.text.HashtagUnhandled
import dev.lyo.hortay.ui.text.LocalHashtagTap
import dev.lyo.hortay.ui.text.LocalShowFullPost
import dev.lyo.hortay.ui.text.rememberRenderableText
import dev.lyo.hortay.ui.timeline.ExpandableText
import dev.lyo.hortay.ui.timeline.formatSubscribers
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.text.NumberFormat

/**
 * Bottom sheet rendered when the user taps a channel header in filtered-feed mode. Pulls
 * fresh metadata (title, handle, description, subscriber count, mute/member state) from
 * [ChannelActionsRepository] on entry; the sheet itself stays visible during the loading
 * window so the trigger feels instant — fields populate inline as TDLib responds.
 *
 * Layout, top to bottom: a centred profile header (avatar, title, `@handle · subscribers`),
 * the description in a tonal card with tappable links, a strip of equal tonal tiles for the
 * primary actions (Media / Notifications / Copy link — see [ActionTileStrip] for when it
 * falls back to rows), then the secondary actions as rows and "Similar channels".
 *
 * The header is centred rather than start-aligned next to the avatar: a stacked header
 * gives the title the full sheet width (a long title wraps to two lines instead of being
 * squeezed beside a 64 dp avatar, which at font scale 2.0 left room for a few characters),
 * and it lines up with the symmetric tile strip right below it. Same shape as the
 * user-profile sheet's hero.
 *
 * Mute / Leave toggles are optimistic: the local state flips first, then the call goes
 * out. Errors fall back to the server state on the next refresh; given how rare a write
 * failure is for these endpoints, the optimism reads as snappy without misleading the
 * user for long.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelInfoSheet(
    chatId: Long,
    actions: ChannelActionsRepository,
    onDismiss: () -> Unit,
    /**
     * Tapping the Report row opens the TDLib reportChat flow at the channel level
     * (no message id) — same flow the per-post action sheet uses with a message id.
     * Null hides the row (guest mode currently passes null; the row is auth-only
     * because the dynamic option flow requires TDLib).
     */
    onReport: (() -> Unit)? = null,
    /**
     * Hidden-channels store. When non-null, the sheet renders a "Hide from feed"
     * toggle that flips the channel's membership in the ignore set. Null hides
     * the row entirely — kept optional so call sites that haven't been wired
     * yet (or test harnesses) still compile.
     */
    ignoredChannels: IgnoredChannelsStore? = null,
    /** Source of "Similar channels"; null hides the section (guest mode). */
    discovery: ChannelDiscoveryRepository? = null,
    /** Opens a similar channel; the sheet dismisses itself first. */
    onOpenChannel: ((Long) -> Unit)? = null,
    /**
     * Opens the channel's shared-media grid; the sheet dismisses itself first. Null hides
     * the row (guest mode, or any host without the grid on its back stack).
     */
    onOpenMedia: (() -> Unit)? = null,
    /**
     * The channel photo for the header avatar — the same small-file id and inline minithumb
     * the channel top bar already holds, so the avatar paints without a request. Both null
     * leaves the initial-letter disc.
     */
    avatarFileId: Int? = null,
    avatarThumb: ByteArray? = null,
) {
    val sheetState = rememberExpandedSheetState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var info by remember(chatId) { mutableStateOf<ChannelInfo?>(null) }

    // Live "is this channel hidden from the feed" state. Reads through to the
    // DataStore Flow so toggling from elsewhere (Settings → Hidden Channels)
    // reflects here within one emit. Null store → flow of empty set so the
    // derived `isIgnored` is false and the row is hidden via `if`-gate below.
    val ignoredFlow: Flow<Set<Long>> = ignoredChannels?.ignored ?: flowOf(persistentSetOf())
    val ignored by ignoredFlow.collectAsStateWithLifecycle(initialValue = persistentSetOf())
    val isIgnored = chatId in ignored

    // First open or chatId switch → fetch fresh.
    LaunchedEffect(chatId) {
        info = actions.channelInfo(chatId)
    }
    val similar by produceState<ImmutableList<DiscoverChannel>>(persistentListOf(), chatId, discovery) {
        value = discovery?.similar(chatId).orEmpty().take(SIMILAR_MAX).toImmutableList()
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        val current = info
        // Rows carry ListItem's own 16 dp inset, so the column only pads them by
        // [ROW_INSET]; every other block adds [CONTENT_INSET] to land on the same 24 dp
        // edge as the rows' leading discs.
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            val blockPadding = Modifier.padding(horizontal = CONTENT_INSET)
            if (current == null) {
                Text(
                    stringResource(R.string.channels_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = blockPadding.padding(vertical = 24.dp),
                )
                return@Column
            }
            ChannelInfoHeader(
                info = current,
                avatarFileId = avatarFileId,
                avatarThumb = avatarThumb,
                modifier = blockPadding,
            )
            current.description?.let { desc ->
                Spacer(Modifier.height(20.dp))
                ChannelDescription(text = desc, onNavigate = onDismiss, modifier = blockPadding)
            }
            Spacer(Modifier.height(16.dp))

            val copiedToast = stringResource(R.string.link_copied_toast)
            val mediaLabel = stringResource(R.string.channels_media)
            val copyLabel = stringResource(R.string.link_action_copy)
            val muteTileLabel = stringResource(
                if (current.isMuted) R.string.channels_tile_unmute else R.string.channels_tile_mute,
            )
            val muteRowLabel = stringResource(
                if (current.isMuted) R.string.channels_unmute else R.string.channels_mute,
            )
            val activeTint = MaterialTheme.colorScheme.primary
            val mutedTint = MaterialTheme.colorScheme.onSurfaceVariant
            val tiles = buildList {
                if (onOpenMedia != null) {
                    add(
                        ChannelActionTile(
                            key = "media",
                            symbol = "image",
                            tileLabel = mediaLabel,
                            rowLabel = mediaLabel,
                            iconTint = activeTint,
                            onClick = {
                                onDismiss()
                                onOpenMedia()
                            },
                        ),
                    )
                }
                // The glyph shows the current state (bell ringing / bell off), the label the
                // action a tap performs — Telegram's convention for this toggle.
                add(
                    ChannelActionTile(
                        key = "notifications",
                        symbol = if (current.isMuted) "notifications_off" else "notifications_active",
                        tileLabel = muteTileLabel,
                        rowLabel = muteRowLabel,
                        iconTint = if (current.isMuted) mutedTint else activeTint,
                        onClick = {
                            val target = !current.isMuted
                            info = current.copy(isMuted = target)
                            scope.launch { actions.setMuted(chatId, target) }
                        },
                    ),
                )
                // Public channels only: a private channel has no stable t.me link to hand out.
                channelPublicLink(current.handle)?.let { link ->
                    add(
                        ChannelActionTile(
                            key = "copy_link",
                            symbol = "link",
                            tileLabel = copyLabel,
                            rowLabel = copyLabel,
                            iconTint = activeTint,
                            onClick = { copyChannelLink(context, link, copiedToast) },
                        ),
                    )
                }
            }.toImmutableList()
            ActionTileStrip(
                tiles = tiles,
                contentInset = CONTENT_INSET,
                row = { tile ->
                    key(tile.key) {
                        ActionRow(symbol = tile.symbol, label = tile.rowLabel, onClick = tile.onClick)
                    }
                },
            )
            Spacer(Modifier.height(8.dp))

            // "Hide from feed" row. Mute silences notifications but the
            // channel still scrolls past in the merged timeline; this toggle
            // hides those posts entirely. Two different user intents, two
            // rows. Kept above the destructive "Leave" row so a tap landing
            // here while reaching for Leave is the less-destructive
            // out-of-bounds.
            if (ignoredChannels != null) {
                ActionRow(
                    symbol = if (isIgnored) "visibility" else "visibility_off",
                    label = stringResource(
                        if (isIgnored) R.string.channels_unhide_from_feed
                        else R.string.channels_hide_from_feed,
                    ),
                    onClick = { scope.launch { ignoredChannels.toggle(chatId) } },
                )
            }
            // Tint rule: Leave and Report read in the error role — the same rule as the post
            // menu, where Report is `destructive`. Join is the one positive row (primary).
            if (current.isMember) {
                ActionRow(
                    symbol = "logout",
                    label = stringResource(R.string.channels_leave),
                    tint = MaterialTheme.colorScheme.error,
                    onClick = {
                        info = current.copy(isMember = false)
                        // Optimistic flip; roll back if TDLib rejects the leave (the
                        // error itself surfaces through UserMessageBus).
                        scope.launch {
                            if (!actions.leaveChat(chatId)) info = info?.copy(isMember = true)
                        }
                    },
                )
            } else {
                ActionRow(
                    symbol = "add",
                    label = stringResource(R.string.channels_join),
                    tint = MaterialTheme.colorScheme.primary,
                    onClick = {
                        info = current.copy(isMember = true)
                        scope.launch {
                            if (!actions.joinChat(chatId)) info = info?.copy(isMember = false)
                        }
                    },
                )
            }
            // Report (CSAE-compliance): channel-level entry point into the same
            // TDLib reportChat dynamic flow the long-press post sheet uses. Hidden
            // in guest mode (caller passes onReport=null) — guest mode has no
            // TDLib bridge for the dynamic option flow.
            if (onReport != null) {
                ActionRow(
                    symbol = "flag",
                    label = stringResource(R.string.report_action),
                    tint = MaterialTheme.colorScheme.error,
                    onClick = {
                        onReport()
                        onDismiss()
                    },
                )
            }
            if (similar.isNotEmpty()) {
                Column(modifier = blockPadding) {
                    SimilarChannels(
                        channels = similar,
                        onSubscribe = { channel -> actions.joinChat(channel.chatId) },
                        onOpen = onOpenChannel?.let { open ->
                            { id: Long ->
                                discovery?.reportSimilarOpened(sourceChatId = chatId, openedChatId = id)
                                onDismiss()
                                open(id)
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * Centred profile header: 64 dp avatar, the title in the display face (two lines max), and
 * one quiet meta line `@handle · Subscribers: N`. The meta line is a [FlowRow] so at large
 * font scales the subscriber count wraps under the handle instead of being cut off.
 *
 * The avatar is decorative for screen readers — the title right under it already says the
 * channel's name, and reading it twice per header is noise.
 */
@Composable
private fun ChannelInfoHeader(
    info: ChannelInfo,
    avatarFileId: Int?,
    avatarThumb: ByteArray?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.clearAndSetSemantics {}) {
            TdAvatar(
                name = info.title.ifBlank { "?" },
                thumb = avatarThumb,
                fileId = avatarFileId,
                size = AVATAR_SIZE,
                textStyle = MaterialTheme.typography.headlineMedium,
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = info.title,
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        val handle = info.handle
        val subscribers = info.subscribers?.let {
            stringResource(R.string.channels_subscribers, formatThousands(it))
        }
        if (handle != null || subscribers != null) {
            val metaStyle = MaterialTheme.typography.bodyMedium
            val metaColor = MaterialTheme.colorScheme.onSurfaceVariant
            FlowRow(
                horizontalArrangement = Arrangement.Center,
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                if (handle != null) {
                    // Reads like a link, but the action is "copy" — same as the @handle in the
                    // user-profile sheet (Telegram-Android's convention).
                    val context = LocalContext.current
                    Text(
                        text = handle,
                        style = metaStyle,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .clip(MaterialTheme.shapes.small)
                            .clickable(
                                role = Role.Button,
                                onClickLabel = stringResource(R.string.user_profile_copy_handle),
                            ) { copyHandle(context, handle) }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                if (subscribers != null) {
                    Text(
                        text = if (handle != null) "· $subscribers" else subscribers,
                        style = metaStyle,
                        color = metaColor,
                        modifier = Modifier.padding(horizontal = 2.dp),
                    )
                }
            }
        }
    }
}

/**
 * The description in a tonal card. Plain TDLib text is linkified by [linkifyDescription] and
 * drawn by the same renderer as post bodies, so URLs and `@mentions` go through the app's
 * link handler (t.me opens in-app) and hashtags through the hashtag hook — no separate
 * open-path to keep in sync. A tap that navigates closes the sheet first ([onNavigate]) so
 * the destination isn't hidden under it. Long descriptions collapse to
 * [DESCRIPTION_MAX_LINES] with the post body's "Show more", expanding in place
 * ([LocalShowFullPost] is cleared — there is no post to open from here).
 */
@Composable
private fun ChannelDescription(
    text: String,
    onNavigate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val formatted = remember(text) { linkifyDescription(text.trim()) }
    val baseUri = LocalUriHandler.current
    val baseHashtag = LocalHashtagTap.current
    val sheetUri = remember(baseUri, onNavigate) {
        object : UriHandler {
            override fun openUri(uri: String) {
                onNavigate()
                baseUri.openUri(uri)
            }
        }
    }
    val sheetHashtag = remember<(String) -> Unit>(baseHashtag, onNavigate) {
        if (baseHashtag === HashtagUnhandled) {
            baseHashtag
        } else {
            { tag ->
                onNavigate()
                baseHashtag(tag)
            }
        }
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        CompositionLocalProvider(
            LocalUriHandler provides sheetUri,
            LocalHashtagTap provides sheetHashtag,
            LocalShowFullPost provides null,
        ) {
            Column {
                ExpandableText(
                    renderable = rememberRenderableText(formatted),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = DESCRIPTION_MAX_LINES,
                )
            }
        }
    }
}

/**
 * Bottom-sheet action row built on Material 3 [ListItem]. Each entry is a
 * standalone action (mute, leave, join, report), not part of a connected
 * segmented group — so plain `ListItem` is the right primitive here rather
 * than [androidx.compose.material3.SegmentedListItem]. The 36 dp tinted disc
 * around the leading glyph is preserved as the leading slot content so the
 * sheet retains its visual identity (matches Telegram's chat-info modal where
 * destructive actions read in red-on-tonal). Container is transparent so it
 * sits flat on the sheet surface, with the standard ListItem padding handling
 * vertical rhythm — no hand-tuned `padding(vertical = 12.dp)` required.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionRow(
    symbol: String,
    label: String,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier
            .padding(horizontal = ROW_INSET)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(role = Role.Button, onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Symbol(name = symbol, tint = tint, size = 20.dp)
            }
        },
        content = {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = tint,
            )
        },
    )
}

private val AVATAR_SIZE = 64.dp
private val CONTENT_INSET = 24.dp
private val ROW_INSET = 8.dp
private const val DESCRIPTION_MAX_LINES = 6

/**
 * "Similar channels" — Telegram's own recommendations for this channel, one tap to
 * subscribe and a tap on the row to open it. Already-followed channels are filtered
 * out upstream, so every row is something new.
 */
@Composable
private fun SimilarChannels(
    channels: ImmutableList<DiscoverChannel>,
    onSubscribe: suspend (DiscoverChannel) -> Boolean,
    onOpen: ((Long) -> Unit)?,
) {
    val subscribeLabel = stringResource(R.string.discover_subscribe)
    val subscribedLabel = stringResource(R.string.discover_subscribed)
    Spacer(Modifier.height(20.dp))
    Text(
        text = stringResource(R.string.channels_similar_title),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp),
    )
    channels.forEach { channel ->
        key(channel.chatId) {
            ChannelDiscoverRow(
                name = channel.title,
                handle = channel.username?.let { "@$it" }.orEmpty(),
                subscribers = channel.subscribers?.let { formatSubscribers(it) },
                description = null,
                avatarThumb = channel.avatarThumb,
                avatarFileId = channel.avatarFileId,
                avatarUrl = null,
                actionLabel = subscribeLabel,
                subscribedLabel = subscribedLabel,
                actionEnabled = true,
                onAction = { onSubscribe(channel) },
                onClick = onOpen?.let { open -> { open(channel.chatId) } },
            )
        }
    }
}

private const val SIMILAR_MAX = 6

/**
 * `https://t.me/<username>` for a channel's public handle (`@name` or bare `name`), or null
 * when the channel has none. Pure so it is unit-tested.
 */
internal fun channelPublicLink(handle: String?): String? {
    val name = handle?.trim()?.removePrefix("@")?.takeIf { it.isNotEmpty() } ?: return null
    return "https://t.me/$name"
}

/**
 * Same clip shape as the post menu's "Copy link" (plain text, so apps reading
 * `ClipData.Item.getText()` paste the URL); the toast only below Android 13, where the
 * system doesn't confirm clipboard writes itself.
 */
private fun copyChannelLink(context: Context, link: String, copiedToast: String) {
    context.copyPlainText("link", link)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, copiedToast, Toast.LENGTH_SHORT).show()
    }
}

/** Copies the `@handle`; the toast only below Android 13 (see [copyChannelLink]). */
private fun copyHandle(context: Context, handle: String) {
    context.copyPlainText(handle, handle)
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, context.getString(R.string.user_profile_handle_copied), Toast.LENGTH_SHORT).show()
    }
}

// Grouping follows the current UI locale (observable via LocalConfiguration, so a
// per-app language switch re-renders) instead of a hardcoded one.
@Composable
private fun formatThousands(n: Int): String =
    NumberFormat.getNumberInstance(LocalConfiguration.current.locales[0]).format(n)
