package dev.lyo.hortay.ui.settings

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import dev.lyo.hortay.ui.components.HortayTopBar
import dev.lyo.hortay.ui.components.HortayTopBarSize
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
 * Uses M3 1.5 [SegmentedListItem] (Expressive) for rows so first / middle / last
 * shapes morph through [ListItemDefaults.segmentedShapes]; [Switch] for toggles,
 * and [Slider] with discrete `steps` for the size cap.
 * Transitions between the two depths are a shared-x slide so the user has a clear
 * sense of going "into" and "out of" a category — Material's recommended pattern
 * for hierarchical depth changes.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
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

    // Forward (null → category) slides the new screen in from the right; back
    // (category → null) slides it out to the right. Same axis, opposite direction —
    // Material "shared-axis X" recipe. Specs captured here (composable scope) for
    // the non-composable transitionSpec lambda below.
    val spatialSpec = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    val effectsSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    // SaveableStateHolder preserves scroll and toggle state across the
    // list ↔ category slide. Each branch gets its own saveable scope so a
    // config change (rotation) or process death doesn't reset the user's
    // slider position in a category they were editing mid-session.
    val subStateHolder = rememberSaveableStateHolder()

    AnimatedContent(
        targetState = openCategory,
        transitionSpec = {
            val forward = initialState == null
            val direction = if (forward) SlideDirection.Left else SlideDirection.Right
            (slideIntoContainer(direction, spatialSpec) + fadeIn(effectsSpec)) togetherWith
                (slideOutOfContainer(direction, spatialSpec) + fadeOut(effectsSpec))
        },
        modifier = Modifier.fillMaxSize(),
        label = "auto-download-nav",
    ) { current ->
        if (current == null) {
            subStateHolder.SaveableStateProvider(key = "list") {
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
            }
        } else {
            subStateHolder.SaveableStateProvider(key = "category:${current.name}") {
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
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
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
            HortayTopBar(
                title = stringResource(R.string.autodownload_screen_title),
                subtitle = stringResource(R.string.settings_subtitle_auto_download),
                size = HortayTopBarSize.Large,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Symbol(name = "arrow_back", tint = MaterialTheme.colorScheme.onSurface, size = 22.dp)
                    }
                },
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
            // Wi-Fi / Mobile / Roaming read as a single grouped block — TG-Android
            // "Auto-Download Media" pattern. SegmentedListItem + segmentedShapes
            // handle inter-row seams and outer corners; SegmentedGap is the
            // M3E-canonical vertical seam (no manual Spacer between rows).
            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                CategoryRow(
                    title = stringResource(R.string.autodownload_category_wifi),
                    summary = summarize(settings.onWifi),
                    symbol = "wifi",
                    index = 0,
                    count = 3,
                    onClick = { onCategoryClick(AutoDownloadCategory.Wifi) },
                )
                CategoryRow(
                    title = stringResource(R.string.autodownload_category_mobile),
                    summary = summarize(settings.onMobile),
                    symbol = "signal_cellular_alt",
                    index = 1,
                    count = 3,
                    onClick = { onCategoryClick(AutoDownloadCategory.Mobile) },
                )
                CategoryRow(
                    title = stringResource(R.string.autodownload_category_roaming),
                    summary = summarize(settings.onRoaming),
                    symbol = "public",
                    index = 2,
                    count = 3,
                    onClick = { onCategoryClick(AutoDownloadCategory.Roaming) },
                )
            }

            Spacer(Modifier.height(16.dp))
            TextButton(
                onClick = onReset,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
            ) {
                Symbol(name = "refresh", size = 20.dp, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.autodownload_reset), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CategoryRow(
    title: String,
    summary: String,
    symbol: String,
    index: Int,
    count: Int,
    onClick: () -> Unit,
) {
    val shapes = ListItemDefaults.segmentedShapes(
        index = index,
        count = count,
        defaultShapes = ListItemDefaults.shapes(),
    )
    SegmentedListItem(
        onClick = onClick,
        shapes = shapes,
        // Naked Solar icon, matching the unified settings icon language (G1 / doctrine §5).
        leadingContent = {
            Symbol(name = symbol, tint = MaterialTheme.colorScheme.onSurfaceVariant, size = 22.dp)
        },
        supportingContent = {
            Text(
                text = summary,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        trailingContent = {
            Symbol(
                name = "chevron_right",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                size = 22.dp,
            )
        },
        content = { Text(title, fontWeight = FontWeight.SemiBold) },
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
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
    val isDataSaverActive by rememberDataSaverActive(category)
    val title = when (category) {
        AutoDownloadCategory.Wifi -> stringResource(R.string.autodownload_category_wifi)
        AutoDownloadCategory.Mobile -> stringResource(R.string.autodownload_category_mobile)
        AutoDownloadCategory.Roaming -> stringResource(R.string.autodownload_category_roaming)
    }
    val subtitle = when (category) {
        AutoDownloadCategory.Wifi -> stringResource(R.string.settings_subtitle_auto_download_category_wifi)
        AutoDownloadCategory.Mobile -> stringResource(R.string.settings_subtitle_auto_download_category_mobile)
        AutoDownloadCategory.Roaming -> stringResource(R.string.settings_subtitle_auto_download_category_roaming)
    }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            HortayTopBar(
                title = title,
                subtitle = subtitle,
                size = HortayTopBarSize.Large,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Symbol(name = "arrow_back", tint = MaterialTheme.colorScheme.onSurface, size = 22.dp)
                    }
                },
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
                val context = LocalContext.current
                DataSaverBanner(
                    text = stringResource(R.string.autodownload_data_saver_note),
                    onClick = {
                        // Surface the OS toggle directly so the user can flip it without
                        // hunting through Android Settings. ACTION_DATA_USAGE_SETTINGS is
                        // the Data-Usage screen (where Data Saver lives) but was only
                        // added in API 28 — below that the constant resolves to no
                        // matching activity, so on API 26-27 we fall back to the general
                        // wireless/network settings surface (present since API 1). Both
                        // are wrapped in runCatching because some Samsung One UI builds
                        // have been reported to throw ActivityNotFoundException on this
                        // exact intent on locked-down enterprise devices; falling through
                        // silently is correct (the banner stays visible, the user can
                        // still toggle in Settings → Connections → Data Usage manually).
                        val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            Settings.ACTION_DATA_USAGE_SETTINGS
                        } else {
                            Settings.ACTION_WIRELESS_SETTINGS
                        }
                        runCatching {
                            context.startActivity(
                                Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            // Photos + Videos read as a 2-row grouped block. The Videos row has a
            // companion `VideoSizeSlider` directly under it; the slider belongs
            // conceptually to Videos but isn't a SegmentedListItem so it sits
            // outside the group with a small Spacer above.
            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                ToggleRow(
                    title = stringResource(R.string.autodownload_toggle_photos),
                    subtitle = stringResource(R.string.autodownload_toggle_photos_helper),
                    symbol = "image",
                    checked = policy.photos,
                    index = 0,
                    count = 2,
                    onCheckedChange = { onPolicyChange(policy.copy(photos = it)) },
                )
                ToggleRow(
                    title = stringResource(R.string.autodownload_toggle_videos),
                    subtitle = stringResource(R.string.autodownload_toggle_videos_helper),
                    symbol = "play_circle",
                    checked = policy.videos,
                    index = 1,
                    count = 2,
                    onCheckedChange = { onPolicyChange(policy.copy(videos = it)) },
                )
            }

            VideoSizeSlider(
                enabled = policy.videos,
                currentBytes = policy.videoMaxBytes,
                onChange = { newBytes -> onPolicyChange(policy.copy(videoMaxBytes = newBytes)) },
            )

            Spacer(Modifier.height(8.dp))
            // Animations on its own row — independent from Photos/Videos
            // semantically (sticker-set traffic, not media autodownload).
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                ToggleRow(
                    title = stringResource(R.string.autodownload_toggle_animations),
                    subtitle = stringResource(R.string.autodownload_toggle_animations_helper),
                    symbol = "gif_box",
                    checked = policy.animations,
                    onCheckedChange = { onPolicyChange(policy.copy(animations = it)) },
                )
            }

            Spacer(Modifier.height(16.dp))
            TextButton(
                onClick = onResetCategory,
                modifier = Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth(),
                shape = MaterialTheme.shapes.medium,
            ) {
                Symbol(name = "refresh", size = 20.dp, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.autodownload_reset), fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    symbol: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    index: Int = 0,
    count: Int = 1,
) {
    val shapes = ListItemDefaults.segmentedShapes(
        index = index,
        count = count,
        defaultShapes = ListItemDefaults.shapes(),
    )
    SegmentedListItem(
        // Tapping anywhere on the row flips the toggle — TG-Android idiom. The
        // companion `Switch` is the visual cue but isn't the only hit target.
        onClick = { onCheckedChange(!checked) },
        shapes = shapes,
        // Naked Solar icon (G1 / doctrine §5); tints `primary` when on so a glance reads the
        // active state, matching the top-level feed toggles. The Switch remains the control.
        leadingContent = {
            Symbol(
                name = symbol,
                tint = if (checked) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                size = 22.dp,
            )
        },
        supportingContent = {
            Text(
                text = subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        },
        content = { Text(title, fontWeight = FontWeight.SemiBold) },
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
    val externalIdx = remember(currentBytes) {
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
    // Local drag state — decouples thumb position from the DataStore round-trip.
    // Without this, every onValueChange wrote through DataStore.update() (a
    // suspend IO + flow re-emit) before the Slider's `value` parameter caught
    // up; the thumb visibly lagged the user's finger. We commit on
    // [Slider.onValueChangeFinished] only, and re-sync to [externalIdx] when
    // the source of truth changes from elsewhere (settings reset, profile
    // rewritten by another path).
    var localIdx by remember(externalIdx) { mutableStateOf(externalIdx) }
    val context = LocalContext.current
    val displayValue = formatMbInt(steps[localIdx], context)

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
            value = localIdx.toFloat(),
            onValueChange = { v ->
                localIdx = v.toInt().coerceIn(0, steps.lastIndex)
            },
            onValueChangeFinished = {
                if (steps[localIdx] != currentBytes) onChange(steps[localIdx])
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
private fun DataSaverBanner(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Symbol(
            name = "data_saver_on",
            tint = MaterialTheme.colorScheme.onTertiaryContainer,
            size = 22.dp,
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Symbol(
            name = "chevron_right",
            tint = MaterialTheme.colorScheme.onTertiaryContainer,
            size = 22.dp,
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
    return parts.joinToString(", ")
}

/**
 * Cellular-only Data-Saver detection. Wi-Fi never triggers the OS toggle, and a
 * roaming connection is still cellular for restriction purposes — query in both
 * cases. We don't touch [HortayNetworkType] here on purpose: this banner is
 * informational about a system setting, not the user's per-network choice.
 *
 * Returns a [State] (not a plain Boolean) so the banner re-renders when the user
 * toggles the OS setting and returns to the app. Two re-check sources:
 *   • Lifecycle ON_RESUME — covers the canonical "user dipped into Android
 *     Settings → Data Saver, came back to Hortay" path. Cheap to re-query.
 *   • [ConnectivityManager.OnRestrictBackgroundChangedListener] does not exist
 *     as a public API; the supported mechanism is the
 *     ACTION_RESTRICT_BACKGROUND_CHANGED implicit broadcast, which manifest-
 *     declared receivers can't get on Oreo+. Foreground re-check on resume
 *     covers our use case (the banner is only seen on the foreground).
 */
@Composable
private fun rememberDataSaverActive(category: AutoDownloadCategory): State<Boolean> {
    val context = LocalContext.current
    val state = remember(category) { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(category, lifecycleOwner) {
        if (category == AutoDownloadCategory.Wifi) {
            state.value = false
            return@DisposableEffect onDispose { }
        }
        val cm = context.getSystemService(ConnectivityManager::class.java)
        fun recompute() {
            state.value = cm?.let {
                runCatching {
                    it.restrictBackgroundStatus == ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
                }.getOrDefault(false)
            } ?: false
        }
        recompute()
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) recompute()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return state
}

/** Round MB display to the nearest int — matches Telegram's slider labels exactly. */
private fun formatMbInt(bytes: Long, context: Context): String {
    val mb = (bytes / (1024 * 1024)).toInt().coerceAtLeast(1)
    return context.getString(R.string.size_mb_int, mb)
}
