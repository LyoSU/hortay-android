@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.lyo.hortay.ui.timeline

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import dev.lyo.hortay.R
import dev.lyo.hortay.ui.components.HortayTopBar
import dev.lyo.hortay.ui.components.HortayTopBarSize
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.media.TdAvatar

/**
 * Shared chat-screen header used by both TDLib-mode [ChannelScreen] and guest-mode
 * `WebChannelScreen`. Renders the canonical M3 Compact (Small TopAppBar, 64 dp)
 * with a single tappable title row containing:
 *
 *   - 40 dp circular avatar (Telegram-Android / X chat-screen sizing)
 *   - Title (titleMedium, single line, ellipsis)
 *   - Subtitle (bodySmall, onSurfaceVariant, optional)
 *
 * The whole row is one clickable affordance — same UX Telegram-Android offers
 * (tap channel name / avatar opens info). The avatar source is mode-specific
 * ([ChannelHeaderAvatar.Td] for TDLib's 3-tier minithumb→file→letter pipeline,
 * [ChannelHeaderAvatar.WebUrl] for Coil-loaded t.me/s/ CDN URLs), but the rest
 * of the bar is identical — single source of truth for chat-screen chrome.
 *
 * This file deliberately lives in `ui/timeline` rather than `ui/components`
 * because the avatar variants couple it to TDLib's [TdAvatar] and the guest-mode
 * URL fallback — moving it to `components` would force `components` to depend on
 * media/data symbols it otherwise stays clean of.
 */
@Immutable
sealed interface ChannelHeaderAvatar {
    val name: String

    /** TDLib avatar: 3-tier ladder driven by [TdAvatar] (minithumb → file → letter). */
    @Immutable
    data class Td(
        val fileId: Int?,
        val thumb: ByteArray?,
        override val name: String,
    ) : ChannelHeaderAvatar {
        // ByteArray equality is reference-based by default; ChannelHeaderAvatar
        // instances thread through Compose stability, so explicit content
        // equality keeps recomposition skippable when the same thumb bytes
        // arrive in a different instance.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Td) return false
            return fileId == other.fileId && name == other.name &&
                ((thumb == null && other.thumb == null) ||
                    (thumb != null && other.thumb != null && thumb.contentEquals(other.thumb)))
        }
        override fun hashCode(): Int {
            var result = fileId ?: 0
            result = 31 * result + (thumb?.contentHashCode() ?: 0)
            result = 31 * result + name.hashCode()
            return result
        }
    }

    /** Guest-mode avatar: HTTPS URL to a t.me CDN-served image; loaded via Coil. */
    @Immutable
    data class WebUrl(val url: String?, override val name: String) : ChannelHeaderAvatar
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChannelHeaderBar(
    titleText: String,
    subtitleText: String?,
    /**
     * Optional avatar slot. `null` hides the disc + its spacing entirely so the
     * title row hugs the navigation icon. Channel-detail screens pass null
     * because the user is already inside the channel — repeating the avatar
     * next to the back-arrow is redundant chrome. Other surfaces (channel
     * lists, search hits) keep it for identity.
     */
    avatar: ChannelHeaderAvatar?,
    onBack: () -> Unit,
    onTitleTap: () -> Unit,
    scrollBehavior: TopAppBarScrollBehavior,
    actions: @Composable RowScope.() -> Unit = {},
) {
    HortayTopBar(
        size = HortayTopBarSize.Compact,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .clickable(onClick = onTitleTap, role = Role.Button)
                    .padding(end = 8.dp),
            ) {
                if (avatar != null) {
                    ChannelHeaderAvatarSlot(avatar)
                    Spacer(modifier = Modifier.width(12.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = titleText,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // Always render the subtitle slot, even when the value
                    // hasn't arrived yet — an empty Text still occupies one
                    // line of the bodySmall style, so the title row's
                    // intrinsic height stays constant across the
                    // null → subscriber-count transition. Without this, the
                    // Column is single-line until [channelSubscribers]
                    // resolves; the M3 Compact TopAppBar then centers that
                    // single line vertically inside the 64 dp slot and
                    // visibly shifts the title upward the moment the
                    // subscriber count lands ("назва по центру а потім
                    // зміщається через це"). Reserved slot kills the CLS.
                    Text(
                        text = subtitleText.orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Symbol(
                    name = "arrow_back",
                    contentDescription = stringResource(R.string.action_back),
                )
            }
        },
        actions = actions,
        scrollBehavior = scrollBehavior,
        windowInsets = WindowInsets(0, 0, 0, 0),
    )
}

@Composable
private fun ChannelHeaderAvatarSlot(avatar: ChannelHeaderAvatar) {
    when (avatar) {
        is ChannelHeaderAvatar.Td -> TdAvatar(
            name = avatar.name,
            thumb = avatar.thumb,
            fileId = avatar.fileId,
            size = 40.dp,
        )
        is ChannelHeaderAvatar.WebUrl -> Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            if (avatar.url != null) {
                AsyncImage(
                    model = avatar.url,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Text(
                    text = avatar.name.firstOrNull()?.uppercase() ?: "?",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}


/**
 * Status-bar-inclusive wrapper for a pinned chat-screen top bar. Hosts the
 * status-bar inset strip and [content] (the bar itself) in ONE column whose
 * background crossfades `background` → `surfaceContainer` on the same
 * scrolled-under signal the M3 top bar uses ([TopAppBarState.overlappedFraction]).
 *
 * Why: the bar passes `windowInsets = WindowInsets(0)` and the wrapper owns the
 * status-bar inset, so when only the bar tinted on scroll the status-bar band
 * above it stayed canvas-coloured — an untinted strip over a tinted bar that
 * read as a seam (owner device report). Tinting the wrapper paints the whole
 * chrome block — status bar included — as one surface. Used by both TDLib-mode
 * [ChannelScreen] and guest-mode `WebChannelScreen` (guest parity).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelTopBarColumn(
    scrollBehavior: TopAppBarScrollBehavior,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrolled = scrollBehavior.state.overlappedFraction > 0.01f
    val barBg by animateColorAsState(
        targetValue = if (scrolled) MaterialTheme.colorScheme.surfaceContainer
        else MaterialTheme.colorScheme.background,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "channel-bar-tint",
    )
    Column(modifier = Modifier.background(barBg)) {
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsTopHeight(WindowInsets.statusBars),
        )
        content()
    }
}
