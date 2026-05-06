package dev.lyo.hortay.ui.settings

import android.content.Context
import android.net.ConnectivityManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.R
import dev.lyo.hortay.data.AutoDownloadCategory
import dev.lyo.hortay.data.AutoDownloadPolicy
import dev.lyo.hortay.data.AutoDownloadSettings
import dev.lyo.hortay.data.AutoDownloadStore
import dev.lyo.hortay.data.defaultPolicy
import dev.lyo.hortay.data.policy
import dev.lyo.hortay.data.withPolicy
import dev.lyo.hortay.ui.icons.Symbol
import kotlinx.coroutines.launch

/**
 * Two-level navigation for auto-download settings, mirroring Telegram's
 * "Data and Storage" → "Auto-Download Media" UX:
 *   1. [AutoDownloadListScreen] — three categories (Wi-Fi / Mobile / Roaming) with
 *      a one-line summary of each, plus a Reset action.
 *   2. [AutoDownloadCategoryScreen] — three toggles (Photos / Videos / GIFs) and a
 *      discrete-step Slider for the video size cap, with a contextual Data-Saver
 *      banner when the OS-level toggle is on.
 *
 * Navigation lives in [AutoDownloadHost]: [BackHandler] returns to the list when a
 * category is open, and [AutoDownloadHost] returns true so the parent
 * ([SettingsScreen]) can keep its own back-stack management consistent.
 *
 * Uses M3 1.4 [ListItem] for rows (proper density + 56 dp leading icon slot),
 * [Switch] for toggles, and [Slider] with discrete `steps` for the size cap.
 * Transitions between the two depths are a shared-x slide so the user has a clear
 * sense of going "into" and "out of" a category — Material's recommended pattern
 * for hierarchical depth changes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutoDownloadHost(
    store: AutoDownloadStore,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
) {
    val settings by store.settings.collectAsStateWithLifecycle(initialValue = AutoDownloadSettings.DEFAULT)
    // Enum (Serializable) survives the default Saver path — no custom Saver needed.
    var openCategory by rememberSaveable { mutableStateOf<AutoDownloadCategory?>(null) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val resetMessage = stringResource(R.string.autodownload_reset_done)

    BackHandler(enabled = openCategory != null) { openCategory = null }

    AnimatedContent(
        targetState = openCategory,
        transitionSpec = {
            // Forward (null → category) slides the new screen in from the right;
            // back (category → null) slides it out to the right. Same axis, opposite
            // direction — consistent with the Material "shared-axis X" recipe and
            // matches what Telegram's hierarchical settings do.
            val forward = initialState == null
            val direction = if (forward) SlideDirection.Left else SlideDirection.Right
            (slideIntoContainer(direction, tween(220)) + fadeIn(tween(220))) togetherWith
                (slideOutOfContainer(direction, tween(220)) + fadeOut(tween(220)))
        },
        modifier = Modifier.fillMaxSize(),
        label = "auto-download-nav",
    ) { current ->
        if (current == null) {
            AutoDownloadListScreen(
                settings = settings,
                snackbarHostState = snackbarHostState,
                contentPadding = contentPadding,
                onBack = onBack,
                onCategoryClick = { openCategory = it },
                onReset = {
                    scope.launch {
                        store.resetAll()
                        snackbarHostState.showSnackbar(resetMessage)
                    }
                },
            )
        } else {
            AutoDownloadCategoryScreen(
                category = current,
                policy = settings.policy(current),
                contentPadding = contentPadding,
                onBack = { openCategory = null },
                onPolicyChange = { newPolicy ->
                    scope.launch { store.update { it.withPolicy(current, newPolicy) } }
                },
                onResetCategory = {
                    scope.launch { store.update { it.withPolicy(current, current.defaultPolicy()) } }
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutoDownloadListScreen(
    settings: AutoDownloadSettings,
    snackbarHostState: SnackbarHostState,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onCategoryClick: (AutoDownloadCategory) -> Unit,
    onReset: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(stringResource(R.string.autodownload_screen_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Symbol(name = "arrow_back", tint = MaterialTheme.colorScheme.onSurface, size = 22.dp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) { Snackbar(snackbarData = it) } },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    top = padding.calculateTopPadding(),
                    bottom = contentPadding.calculateBottomPadding() + 16.dp,
                ),
        ) {
            Text(
                text = stringResource(R.string.autodownload_header_helper),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )

            Spacer(Modifier.height(8.dp))
            CategoryRow(
                title = stringResource(R.string.autodownload_category_wifi),
                summary = summarize(settings.onWifi),
                symbol = "wifi",
                onClick = { onCategoryClick(AutoDownloadCategory.Wifi) },
            )
            CategoryRow(
                title = stringResource(R.string.autodownload_category_mobile),
                summary = summarize(settings.onMobile),
                symbol = "signal_cellular_alt",
                onClick = { onCategoryClick(AutoDownloadCategory.Mobile) },
            )
            CategoryRow(
                title = stringResource(R.string.autodownload_category_roaming),
                summary = summarize(settings.onRoaming),
                symbol = "public",
                onClick = { onCategoryClick(AutoDownloadCategory.Roaming) },
            )

            Spacer(Modifier.height(16.dp))
            TextButton(
                onClick = onReset,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) {
                Symbol(name = "refresh", size = 20.dp, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.autodownload_reset), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun CategoryRow(
    title: String,
    summary: String,
    symbol: String,
    onClick: () -> Unit,
) {
    // M3 ListItem doesn't expose onClick directly, so we wrap it in a clickable
    // surface — same idiom Material's reference samples use. Padding stays on the
    // outer Box so the ripple covers the entire row.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        ListItem(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            headlineContent = { Text(title, fontWeight = FontWeight.SemiBold) },
            supportingContent = {
                Text(
                    text = summary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            leadingContent = {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Symbol(name = symbol, tint = MaterialTheme.colorScheme.onPrimaryContainer, size = 22.dp)
                }
            },
            trailingContent = {
                Symbol(
                    name = "chevron_right",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    size = 22.dp,
                )
            },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutoDownloadCategoryScreen(
    category: AutoDownloadCategory,
    policy: AutoDownloadPolicy,
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onPolicyChange: (AutoDownloadPolicy) -> Unit,
    onResetCategory: () -> Unit,
) {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val isDataSaverActive = rememberDataSaverActive(category)
    val title = when (category) {
        AutoDownloadCategory.Wifi -> stringResource(R.string.autodownload_category_wifi)
        AutoDownloadCategory.Mobile -> stringResource(R.string.autodownload_category_mobile)
        AutoDownloadCategory.Roaming -> stringResource(R.string.autodownload_category_roaming)
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Symbol(name = "arrow_back", tint = MaterialTheme.colorScheme.onSurface, size = 22.dp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                scrollBehavior = scrollBehavior,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(
                    top = padding.calculateTopPadding(),
                    bottom = contentPadding.calculateBottomPadding() + 16.dp,
                ),
        ) {
            if (isDataSaverActive) {
                DataSaverBanner(
                    text = stringResource(R.string.autodownload_data_saver_note),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            ToggleRow(
                title = stringResource(R.string.autodownload_toggle_photos),
                subtitle = stringResource(R.string.autodownload_toggle_photos_helper),
                symbol = "image",
                checked = policy.photos,
                onCheckedChange = { onPolicyChange(policy.copy(photos = it)) },
            )

            ToggleRow(
                title = stringResource(R.string.autodownload_toggle_videos),
                subtitle = stringResource(R.string.autodownload_toggle_videos_helper),
                symbol = "play_circle",
                checked = policy.videos,
                onCheckedChange = { onPolicyChange(policy.copy(videos = it)) },
            )

            VideoSizeSlider(
                enabled = policy.videos,
                currentBytes = policy.videoMaxBytes,
                onChange = { newBytes -> onPolicyChange(policy.copy(videoMaxBytes = newBytes)) },
            )

            ToggleRow(
                title = stringResource(R.string.autodownload_toggle_animations),
                subtitle = stringResource(R.string.autodownload_toggle_animations_helper),
                symbol = "gif_box",
                checked = policy.animations,
                onCheckedChange = { onPolicyChange(policy.copy(animations = it)) },
            )

            Spacer(Modifier.height(16.dp))
            TextButton(
                onClick = onResetCategory,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            ) {
                Symbol(name = "refresh", size = 20.dp, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.autodownload_reset), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    symbol: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        headlineContent = { Text(title, fontWeight = FontWeight.SemiBold) },
        supportingContent = {
            Text(
                text = subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        },
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHighest),
                contentAlignment = Alignment.Center,
            ) {
                Symbol(name = symbol, tint = MaterialTheme.colorScheme.onSurface, size = 22.dp)
            }
        },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background),
    )
}

@Composable
private fun VideoSizeSlider(
    enabled: Boolean,
    currentBytes: Long,
    onChange: (Long) -> Unit,
) {
    val steps = AutoDownloadPolicy.VIDEO_SIZE_STEPS
    // Snap the current value to the nearest step. The Slider works in [0..size-1]
    // discrete positions; we translate position → bytes and back using the static
    // step list so the displayed label always reflects exactly what's persisted.
    val currentIdx = remember(currentBytes) {
        var bestIdx = 0
        var bestDelta = Long.MAX_VALUE
        steps.forEachIndexed { idx, candidate ->
            val delta = kotlin.math.abs(candidate - currentBytes)
            if (delta < bestDelta) {
                bestDelta = delta
                bestIdx = idx
            }
        }
        bestIdx
    }
    val context = LocalContext.current
    val displayValue = formatMbInt(steps[currentIdx], context)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.autodownload_video_max_label),
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.autodownload_video_max_value, displayValue),
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Slider(
            value = currentIdx.toFloat(),
            onValueChange = { v ->
                val idx = v.toInt().coerceIn(0, steps.lastIndex)
                if (steps[idx] != currentBytes) onChange(steps[idx])
            },
            valueRange = 0f..(steps.lastIndex.toFloat()),
            // Slider's `steps` parameter counts intermediate stops *between* the
            // endpoints, so for N values we pass N-2.
            steps = steps.size - 2,
            enabled = enabled,
        )
    }
}

@Composable
private fun DataSaverBanner(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Symbol(
            name = "data_saver_off",
            tint = MaterialTheme.colorScheme.onTertiaryContainer,
            size = 22.dp,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}

/**
 * One-line summary of an [AutoDownloadPolicy] for the category list — "Photos,
 * Videos to 10 MB, GIFs" / "Off" / etc. Mirrors what Telegram displays under each
 * category row, so the user sees the whole policy at a glance without entering.
 */
@Composable
private fun summarize(policy: AutoDownloadPolicy): String {
    val context = LocalContext.current
    if (!policy.photos && !policy.videos && !policy.animations) {
        return stringResource(R.string.autodownload_summary_off)
    }
    val parts = mutableListOf<String>()
    if (policy.photos) parts += stringResource(R.string.autodownload_summary_photos)
    if (policy.videos) {
        parts += stringResource(
            R.string.autodownload_summary_videos_capped,
            formatMbInt(policy.videoMaxBytes, context),
        )
    }
    if (policy.animations) parts += stringResource(R.string.autodownload_summary_animations)
    return parts.joinToString(stringResource(R.string.autodownload_summary_separator))
}

/**
 * Cellular-only Data-Saver detection. Wi-Fi never triggers the OS toggle, and a
 * roaming connection is still cellular for restriction purposes — query in both
 * cases. We don't touch [HortayNetworkType] here on purpose: this banner is
 * informational about a system setting, not the user's per-network choice.
 */
@Composable
private fun rememberDataSaverActive(category: AutoDownloadCategory): Boolean {
    val context = LocalContext.current
    return remember(context, category) {
        if (category == AutoDownloadCategory.Wifi) return@remember false
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return@remember false
        runCatching {
            cm.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
        }.getOrDefault(false)
    }
}

/** Round MB display to the nearest int — matches Telegram's slider labels exactly. */
private fun formatMbInt(bytes: Long, context: Context): String {
    val mb = (bytes / (1024 * 1024)).toInt().coerceAtLeast(1)
    return context.getString(R.string.size_mb_int, mb)
}
