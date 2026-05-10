package dev.lyo.hortay.ui.components

import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MediumFlexibleTopAppBar
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow

/**
 * Universal Material 3 Expressive top bar used by every destination and
 * sub-screen in the app. Wraps [LargeFlexibleTopAppBar] / [MediumFlexibleTopAppBar]
 * / [TopAppBar] under one [HortayTopBarSize] dial so callers don't drift
 * into per-screen Material API choices (the situation before this helper:
 * Channels used legacy LargeTopAppBar, Settings overrode the title to
 * displaySmall, Comments / Timeline rolled their own colours inline — three
 * places to keep in sync each time the design language moved).
 *
 * The helper enforces three invariants the screens were each restating:
 *  - Container colour rests on `background` and morphs into `surfaceContainer`
 *    on scroll. Same contract across Settings / AutoDownload / WebChannels /
 *    Comments / Timeline so every collapsing bar reads as one family.
 *  - Title typography stays at the Material 3 internal default — no caller
 *    overrides to `displaySmall` / `displayMedium`. Title-typography
 *    consistency was the user-visible inconsistency that motivated this pass.
 *  - `subtitle` is wired the same way everywhere — pass the text as a String
 *    via [HortayTopBar]'s `subtitle` parameter and the helper renders it in
 *    the canonical bodyMedium / onSurfaceVariant style. Passing a Composable
 *    is still supported via [subtitleSlot] when the screen needs richer
 *    content (e.g. comment-thread count that changes shape between Loading
 *    and Ready).
 *
 * Window-insets are exposed because the two scaffolds that own their own
 * status-bar strip (`TimelineScreen`, `CommentsScreen` — they render a
 * persistent zone-1 background to keep the bar from travelling under the
 * status bar on collapse) need to pass `WindowInsets(0)` here to avoid
 * double-padding; every other call site relies on [TopAppBarDefaults]'
 * default insets.
 */
enum class HortayTopBarSize {
    /** Two-row destination title — default for the Settings / AutoDownload / WebChannels family. */
    Large,
    /** One-and-a-bit-row destination title — Timeline / Comments / Channels. */
    Medium,
    /**
     * Single-row compact bar — tool stages (channel filter, search) that pin in
     * place rather than slide off-screen. Falls back to the non-Flexible
     * [TopAppBar] because the Flexible variants don't support pinned scroll behaviour
     * cleanly when the bar is meant to stay frozen.
     */
    Compact,
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HortayTopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    size: HortayTopBarSize = HortayTopBarSize.Large,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
) {
    HortayTopBar(
        title = { HortayTopBarTitle(title) },
        modifier = modifier,
        subtitleSlot = subtitle?.let { text -> { HortayTopBarSubtitle(text) } },
        size = size,
        navigationIcon = navigationIcon,
        actions = actions,
        scrollBehavior = scrollBehavior,
        windowInsets = windowInsets,
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HortayTopBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    subtitleSlot: (@Composable () -> Unit)? = null,
    size: HortayTopBarSize = HortayTopBarSize.Large,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
    windowInsets: WindowInsets = TopAppBarDefaults.windowInsets,
) {
    val colors = TopAppBarDefaults.topAppBarColors(
        containerColor = MaterialTheme.colorScheme.background,
        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
    )
    when (size) {
        HortayTopBarSize.Large -> LargeFlexibleTopAppBar(
            title = title,
            subtitle = subtitleSlot ?: {},
            navigationIcon = navigationIcon,
            actions = actions,
            colors = colors,
            scrollBehavior = scrollBehavior,
            windowInsets = windowInsets,
            modifier = modifier,
        )
        HortayTopBarSize.Medium -> MediumFlexibleTopAppBar(
            title = title,
            subtitle = subtitleSlot ?: {},
            navigationIcon = navigationIcon,
            actions = actions,
            colors = colors,
            scrollBehavior = scrollBehavior,
            windowInsets = windowInsets,
            modifier = modifier,
        )
        HortayTopBarSize.Compact -> TopAppBar(
            title = title,
            navigationIcon = navigationIcon,
            actions = actions,
            colors = colors,
            scrollBehavior = scrollBehavior,
            windowInsets = windowInsets,
            modifier = modifier,
        )
    }
}

@Composable
private fun HortayTopBarTitle(text: String) {
    Text(text = text, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun HortayTopBarSubtitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
