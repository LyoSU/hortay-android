@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.lyo.hortay.ui.settings

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import coil3.SingletonImageLoader
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.IntOffset
import androidx.compose.runtime.saveable.rememberSaveable
import dev.lyo.hortay.BuildConfig
import dev.lyo.hortay.R
import dev.lyo.hortay.data.AutoDownloadStore
import dev.lyo.hortay.data.FeedOrder
import dev.lyo.hortay.data.NetworkUsage
import dev.lyo.hortay.data.SettingsStore
import dev.lyo.hortay.data.StatsRepository
import dev.lyo.hortay.data.StorageUsage
import dev.lyo.hortay.ui.components.HortayTopBar
import dev.lyo.hortay.ui.components.HortayTopBarSize
import dev.lyo.hortay.ui.icons.Symbol
import kotlinx.coroutines.launch

/**
 * Single Settings screen used by both TDLib and guest (anonymous) modes.
 *
 * Mode is encoded by which optional services are passed:
 *   - [stats] non-null + [onLogout] non-null → authenticated TDLib mode.
 *     Renders Traffic + Storage cards backed by [StatsRepository], a Logout
 *     row, and the version row.
 *   - [stats] null + [onSignIn] non-null + [onClearWebCache] non-null → guest
 *     mode. Renders a "Sign in to Telegram" CTA, a guest-mode "Clear cache"
 *     row that wipes web.db, a privacy footer, and the version row.
 *
 * Why a single Composable rather than two: section labels, dividers, the
 * SettingsRow chip and the TopAppBar are identical across modes. Branching at
 * the data-source level (which sections render) keeps every visual primitive
 * in one place.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: SettingsStore,
    stats: StatsRepository?,
    contentPadding: PaddingValues,
    onLogout: (() -> Unit)? = null,
    onSignIn: (() -> Unit)? = null,
    onClearWebCache: (suspend () -> Unit)? = null,
    /**
     * Symmetric "go guest" path for authenticated users. When non-null, an
     * "Continue without account" row renders in the Account section that, on
     * confirmation, signs the user out of TDLib AND flips [GuestModeStore] to
     * true so the next routing pass lands [WebModeScaffold] instead of looping
     * back to [AuthScreen]. Without this row the only path from auth into
     * guest mode was a fresh install — discoverable only by accident.
     */
    onEnterGuest: (() -> Unit)? = null,
    autoDownload: AutoDownloadStore? = null,
) {
    // Sub-screen nav lives inside Settings — the auto-download list and category
    // screens are conceptually "deeper" pages of the same tab. Using AnimatedContent
    // keeps the bottom navigation visible (TG-style) and Material's shared-x slide
    // gives the user a clear sense of depth.
    var showAutoDownload by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = showAutoDownload) { showAutoDownload = false }

    // M3E shared-axis-X via MotionScheme: spatial spring for the slide, effects
    // spring for the crossfade. Same physics as MaterialTheme reads on every Material
    // component, so the screen transition feels of-a-piece with chip / card / banner
    // morphs instead of legacy duration-tween. Captured here (composable scope)
    // because AnimatedContent.transitionSpec is a non-composable lambda.
    val spatialSpec = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    val effectsSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    AnimatedContent(
        targetState = showAutoDownload,
        transitionSpec = {
            val forward = targetState && !initialState
            val direction = if (forward) SlideDirection.Left else SlideDirection.Right
            (slideIntoContainer(direction, spatialSpec) + fadeIn(effectsSpec)) togetherWith
                (slideOutOfContainer(direction, spatialSpec) + fadeOut(effectsSpec))
        },
        label = "settings-nav",
    ) { autoDownloadShown ->
        if (autoDownloadShown && autoDownload != null) {
            AutoDownloadHost(
                store = autoDownload,
                contentPadding = contentPadding,
                onBack = { showAutoDownload = false },
            )
        } else {
            SettingsMain(
                settings = settings,
                stats = stats,
                contentPadding = contentPadding,
                onLogout = onLogout,
                onSignIn = onSignIn,
                onClearWebCache = onClearWebCache,
                onEnterGuest = onEnterGuest,
                autoDownloadAvailable = autoDownload != null,
                onOpenAutoDownload = { showAutoDownload = true },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsMain(
    settings: SettingsStore,
    stats: StatsRepository?,
    contentPadding: PaddingValues,
    onLogout: (() -> Unit)?,
    onSignIn: (() -> Unit)?,
    onClearWebCache: (suspend () -> Unit)?,
    onEnterGuest: (() -> Unit)?,
    autoDownloadAvailable: Boolean,
    onOpenAutoDownload: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    var confirmLogout by remember { mutableStateOf(false) }
    var confirmClearWebCache by remember { mutableStateOf(false) }
    var confirmEnterGuest by remember { mutableStateOf(false) }
    var network by remember { mutableStateOf<NetworkUsage?>(null) }
    var storage by remember { mutableStateOf<StorageUsage?>(null) }
    var clearing by remember { mutableStateOf(false) }

    suspend fun refreshStats() {
        val s = stats ?: return
        network = s.networkUsage()
        storage = s.storageUsage()
    }
    LaunchedEffect(stats) { refreshStats() }

    Scaffold(
        modifier = Modifier
            .fillMaxSize()
            .nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            HortayTopBar(
                title = stringResource(R.string.settings_profile_title),
                subtitle = stringResource(R.string.settings_subtitle_profile),
                size = HortayTopBarSize.Large,
                scrollBehavior = scrollBehavior,
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                // verticalScroll is essential here: with the Traffic + Storage cards the
                // content overflows phones with shorter screens, and a non-scrollable
                // Column would silently clip the "Вийти" / "Версія" rows below the fold.
                .verticalScroll(rememberScrollState())
                .padding(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding(),
                    bottom = contentPadding.calculateBottomPadding() + 16.dp,
                ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ---- Mode-agnostic: feed-order + snap-scroll preferences -----------------
            // Generic display settings that apply to both TDLib and guest modes —
            // placed at the top so they read as "this is how the feed behaves"
            // before any mode-conditional sections.
            val currentFeedOrder by settings.feedOrder.collectAsStateWithLifecycle(FeedOrder.OldestUnreadFirst)
            val currentSnapScroll by settings.snapScroll.collectAsStateWithLifecycle(false)
            val currentInlineAutoplay by settings.inlineVideoAutoplay.collectAsStateWithLifecycle(true)
            SectionLabel(stringResource(R.string.settings_section_feed))
            FeedOrderRows(
                current = currentFeedOrder,
                onSelect = { order ->
                    if (order != currentFeedOrder) {
                        scope.launch { settings.setFeedOrder(order) }
                    }
                },
            )
            SnapScrollRow(
                enabled = currentSnapScroll,
                onToggle = { next ->
                    if (next != currentSnapScroll) {
                        scope.launch { settings.setSnapScroll(next) }
                    }
                },
            )
            InlineAutoplayRow(
                enabled = currentInlineAutoplay,
                onToggle = { next ->
                    if (next != currentInlineAutoplay) {
                        scope.launch { settings.setInlineVideoAutoplay(next) }
                    }
                },
            )

            // ---- TDLib-mode-only: traffic & storage cards backed by StatsRepository ----
            if (stats != null) {
                SectionLabel(stringResource(R.string.settings_section_traffic))
                TrafficCard(
                    network = network,
                    onReset = {
                        scope.launch {
                            stats.resetTrafficStats()
                            refreshStats()
                        }
                    },
                )

                Spacer(Modifier.height(8.dp))
                SectionLabel(stringResource(R.string.settings_section_storage))
                StorageCard(
                    storage = storage,
                    clearing = clearing,
                    onClearCache = {
                        scope.launch {
                            clearing = true
                            stats.clearCache()
                            // Coil's disk cache lives outside TDLib's filesDir; clear both
                            // so the user sees the actual freed space.
                            SingletonImageLoader.get(context).diskCache?.clear()
                            refreshStats()
                            clearing = false
                        }
                    },
                )

                // Auto-download is TDLib-mode-only — guest mode reads via t.me/s/ HTML
                // streams and doesn't go through MediaCache, so no policy applies there.
                if (autoDownloadAvailable) {
                    Spacer(Modifier.height(8.dp))
                    SectionLabel(stringResource(R.string.autodownload_section))
                    SettingsRow(
                        symbol = "download_for_offline",
                        title = stringResource(R.string.autodownload_entry_title),
                        subtitle = stringResource(R.string.autodownload_entry_subtitle),
                        chevron = true,
                        onClick = onOpenAutoDownload,
                    )
                }
            }

            // ---- Guest-mode-only: web.db cache clear + privacy footer -----------------
            if (onClearWebCache != null) {
                Spacer(Modifier.height(8.dp))
                SectionLabel(stringResource(R.string.web_settings_clear_cache))
                SettingsRow(
                    symbol = "delete",
                    title = stringResource(
                        if (clearing) R.string.web_settings_clearing
                        else R.string.web_settings_clear_cache,
                    ),
                    subtitle = stringResource(R.string.web_settings_clear_cache_helper),
                    // Confirmation dialog before the destructive action — the
                    // previous one-tap path felt like "clear cache" was a
                    // reversible toggle, but it dropped every locally cached
                    // post. The repository now preserves bookmarks by default,
                    // so the dialog body is honest about what survives.
                    onClick = { if (!clearing) confirmClearWebCache = true },
                )

                Spacer(Modifier.height(8.dp))
                SectionLabel(stringResource(R.string.web_settings_privacy_title))
                SettingsRow(
                    symbol = "shield",
                    title = stringResource(R.string.web_settings_privacy_title),
                    subtitle = stringResource(R.string.web_settings_privacy_body),
                )
            }

            // ---- Account section: logout (TDLib) OR sign-in CTA (guest) ---------------
            Spacer(Modifier.height(8.dp))
            SectionLabel(stringResource(R.string.settings_section_account))
            if (onLogout != null) {
                SettingsRow(
                    symbol = "logout",
                    title = stringResource(R.string.settings_logout_title),
                    subtitle = stringResource(R.string.settings_logout_subtitle),
                    tint = MaterialTheme.colorScheme.error,
                    onClick = { confirmLogout = true },
                )
            }
            if (onSignIn != null) {
                SettingsRow(
                    symbol = "login",
                    title = stringResource(R.string.web_settings_signin),
                    subtitle = stringResource(R.string.web_settings_signin_helper),
                    onClick = onSignIn,
                )
            }
            // Symmetric "auth → guest" path. Sits in the Account section under
            // the Logout row so the user sees both account-state exits in one
            // place. Confirmation dialog because the action both signs out and
            // flips the routing flag — a one-tap silent toggle would be too
            // easy to trigger by accident.
            if (onEnterGuest != null) {
                SettingsRow(
                    symbol = "visibility",
                    title = stringResource(R.string.settings_enter_guest_title),
                    subtitle = stringResource(R.string.settings_enter_guest_subtitle),
                    onClick = { confirmEnterGuest = true },
                )
            }

            // ---- Safety section: policy links (CSAE-compliance) ----------------------
            // Reporting itself lives on the long-press post action sheet and the
            // ChannelInfoSheet — there is no per-post entry point from Settings because
            // the user has no realistic way to know a Telegram message id ahead of
            // time. Settings keeps just the two policy-page links (Child Safety,
            // Privacy), opened via CustomTabsIntent so the user never leaves the app
            // chrome. Play Store review checks for discoverable policy links here.
            Spacer(Modifier.height(8.dp))
            SectionLabel(stringResource(R.string.settings_section_safety))
            Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                SettingsRow(
                    symbol = "child_care",
                    title = stringResource(R.string.settings_safety_child_policy_title),
                    subtitle = stringResource(R.string.settings_safety_child_policy_subtitle),
                    chevron = true,
                    index = 0,
                    count = 2,
                    onClick = {
                        try {
                            androidx.browser.customtabs.CustomTabsIntent.Builder()
                                .build()
                                .launchUrl(
                                    context,
                                    android.net.Uri.parse(dev.lyo.hortay.BuildConfig.CHILD_SAFETY_POLICY_URL),
                                )
                        } catch (_: android.content.ActivityNotFoundException) {
                            uriHandler.openUri(dev.lyo.hortay.BuildConfig.CHILD_SAFETY_POLICY_URL)
                        }
                    },
                )
                SettingsRow(
                    symbol = "shield",
                    title = stringResource(R.string.settings_safety_privacy_title),
                    subtitle = stringResource(R.string.settings_safety_privacy_subtitle),
                    chevron = true,
                    index = 1,
                    count = 2,
                    onClick = {
                        try {
                            androidx.browser.customtabs.CustomTabsIntent.Builder()
                                .build()
                                .launchUrl(
                                    context,
                                    android.net.Uri.parse(dev.lyo.hortay.BuildConfig.PRIVACY_POLICY_URL),
                                )
                        } catch (_: android.content.ActivityNotFoundException) {
                            uriHandler.openUri(dev.lyo.hortay.BuildConfig.PRIVACY_POLICY_URL)
                        }
                    },
                )
            }

            // ---- Author section: brand attribution + tg-handle links -----------------
            // Visible in both modes (TDLib and guest). Two grouped rows, lower in the
            // screen so they read as credits rather than competing with traffic / storage
            // for the user's first glance.
            Spacer(Modifier.height(8.dp))
            SectionLabel(stringResource(R.string.settings_section_author))
            // SegmentedListItem group — Author section pairs two rows that read as
            // one card. `ListItemDefaults.SegmentedGap` is the M3E-canonical
            // inter-row gap (~4 dp); each row's corner radii are computed by
            // `segmentedShapes(index, count, defaults)` so the first row has its
            // top corners fully rounded, the last row its bottom corners, and the
            // pressed-state morph rides M3 Expressive tokens automatically.
            Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                SettingsRow(
                    symbol = "campaign",
                    title = stringResource(R.string.settings_author_channel_title),
                    subtitle = "@$AUTHOR_CHANNEL_HANDLE",
                    chevron = true,
                    index = 0,
                    count = 2,
                    onClick = { uriHandler.openUri("https://t.me/$AUTHOR_CHANNEL_HANDLE") },
                )
                SettingsRow(
                    symbol = "person",
                    title = stringResource(R.string.settings_author_developer_title),
                    subtitle = "@$AUTHOR_DEVELOPER_HANDLE",
                    chevron = true,
                    index = 1,
                    count = 2,
                    onClick = { uriHandler.openUri("https://t.me/$AUTHOR_DEVELOPER_HANDLE") },
                )
            }

            Spacer(Modifier.height(8.dp))
            SectionLabel(stringResource(R.string.settings_section_about))
            SettingsRow(
                symbol = "info",
                title = stringResource(R.string.settings_version),
                subtitle = "${BuildConfig.VERSION_NAME} · build ${BuildConfig.VERSION_CODE}",
            )
        }
    }

    if (confirmLogout && onLogout != null) {
        AlertDialog(
            onDismissRequest = { confirmLogout = false },
            confirmButton = {
                TextButton(onClick = {
                    confirmLogout = false
                    onLogout()
                }) { Text(stringResource(R.string.settings_logout_confirm), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmLogout = false }) { Text(stringResource(R.string.settings_logout_cancel)) }
            },
            title = { Text(stringResource(R.string.settings_logout_dialog_title)) },
            text = { Text(stringResource(R.string.settings_logout_dialog_text)) },
        )
    }

    if (confirmClearWebCache && onClearWebCache != null) {
        AlertDialog(
            onDismissRequest = { confirmClearWebCache = false },
            confirmButton = {
                TextButton(onClick = {
                    confirmClearWebCache = false
                    if (!clearing) scope.launch {
                        clearing = true
                        onClearWebCache()
                        clearing = false
                    }
                }) {
                    Text(
                        stringResource(R.string.web_settings_clear_cache_confirm_action),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearWebCache = false }) {
                    Text(stringResource(R.string.web_settings_clear_cache_cancel))
                }
            },
            title = { Text(stringResource(R.string.web_settings_clear_cache_confirm_title)) },
            text = { Text(stringResource(R.string.web_settings_clear_cache_confirm_body)) },
        )
    }

    if (confirmEnterGuest && onEnterGuest != null) {
        AlertDialog(
            onDismissRequest = { confirmEnterGuest = false },
            confirmButton = {
                TextButton(onClick = {
                    confirmEnterGuest = false
                    onEnterGuest()
                }) { Text(stringResource(R.string.settings_enter_guest_confirm_action)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmEnterGuest = false }) {
                    Text(stringResource(R.string.settings_logout_cancel))
                }
            },
            title = { Text(stringResource(R.string.settings_enter_guest_confirm_title)) },
            text = { Text(stringResource(R.string.settings_enter_guest_confirm_body)) },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    // M3E grouped-list section header. titleSmall SemiBold reads as a list-section
    // delimiter rather than a chip-style label; the primary tint keeps the brand
    // accent the original design leaned on. Padding lifts the label off the row
    // below so each section reads as its own block.
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun TrafficCard(network: NetworkUsage?, onReset: () -> Unit) {
    val res = LocalContext.current.resources
    StatsCard {
        StatHero(
            primary = TwoColumn(
                left = StatHeroValue(
                    symbol = "arrow_downward",
                    label = stringResource(R.string.settings_traffic_downloaded),
                    value = network?.rxBytes?.let { formatBytes(it, res) } ?: "—",
                ),
                right = StatHeroValue(
                    symbol = "arrow_upward",
                    label = stringResource(R.string.settings_traffic_uploaded),
                    value = network?.txBytes?.let { formatBytes(it, res) } ?: "—",
                ),
            ),
        )
        Text(
            text = stringResource(R.string.settings_traffic_helper),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
        Spacer(Modifier.height(8.dp))
        TextButton(
            onClick = onReset,
            modifier = Modifier.fillMaxWidth(),
            shape = MaterialTheme.shapes.large,
        ) {
            Symbol(name = "refresh", size = 20.dp)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.settings_traffic_reset), fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun StorageCard(
    storage: StorageUsage?,
    clearing: Boolean,
    onClearCache: () -> Unit,
) {
    val totalBytes = (storage?.totalFilesBytes ?: 0L) + (storage?.databaseSizeBytes ?: 0L)
    val filesBytes = storage?.totalFilesBytes ?: 0L
    val dbBytes = storage?.databaseSizeBytes ?: 0L
    val fillFraction = if (totalBytes <= 0L) 0f else (filesBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
    val res = LocalContext.current.resources

    StatsCard {
        Row(verticalAlignment = Alignment.Top) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (storage == null) "—" else formatBytes(totalBytes, res),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = stringResource(R.string.settings_storage_used_by),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Symbol(
                name = "storage",
                tint = MaterialTheme.colorScheme.primary,
                size = 28.dp,
            )
        }

        Spacer(Modifier.height(12.dp))
        // Files vs database visual split. Files are the chunky bit; database is small but
        // can't be cleared without a logout, so we show it for honesty.
        StorageBar(filesFraction = fillFraction)

        Spacer(Modifier.height(10.dp))
        StorageLegend(
            filesBytes = filesBytes,
            dbBytes = dbBytes,
        )

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = onClearCache,
            shapes = ButtonDefaults.shapes(
                shape = MaterialTheme.shapes.large,
                pressedShape = MaterialTheme.shapes.small,
            ),
            enabled = !clearing && filesBytes > 0L,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            if (clearing) {
                LoadingIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.width(10.dp))
                Text(stringResource(R.string.settings_storage_clearing), fontWeight = FontWeight.SemiBold)
            } else {
                Symbol(name = "delete_sweep", size = 20.dp, tint = MaterialTheme.colorScheme.onPrimary)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.settings_storage_clear), fontWeight = FontWeight.SemiBold)
            }
        }
        Text(
            text = stringResource(R.string.settings_storage_clear_helper),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun StatsCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(16.dp),
        content = content,
    )
}

private data class StatHeroValue(val symbol: String, val label: String, val value: String)
private data class TwoColumn(val left: StatHeroValue, val right: StatHeroValue)

@Composable
private fun StatHero(primary: TwoColumn) {
    Row(modifier = Modifier.fillMaxWidth()) {
        StatColumn(primary.left, modifier = Modifier.weight(1f))
        Box(
            modifier = Modifier
                .width(1.dp)
                .height(48.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        StatColumn(primary.right, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun StatColumn(value: StatHeroValue, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Symbol(
                name = value.symbol,
                tint = MaterialTheme.colorScheme.primary,
                size = 18.dp,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                value.label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            value.value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun StorageBar(filesFraction: Float) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .weight(filesFraction.coerceAtLeast(0.001f))
                .background(MaterialTheme.colorScheme.primary),
        )
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .weight((1f - filesFraction).coerceAtLeast(0.001f))
                .background(MaterialTheme.colorScheme.tertiary),
        )
    }
}

@Composable
private fun StorageLegend(filesBytes: Long, dbBytes: Long) {
    val res = LocalContext.current.resources
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        LegendDot(
            color = MaterialTheme.colorScheme.primary,
            label = stringResource(R.string.settings_storage_media),
            value = formatBytes(filesBytes, res),
        )
        LegendDot(
            color = MaterialTheme.colorScheme.tertiary,
            label = stringResource(R.string.settings_storage_db),
            value = formatBytes(dbBytes, res),
        )
    }
}

@Composable
private fun LegendDot(color: androidx.compose.ui.graphics.Color, label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(color),
        )
        Spacer(Modifier.width(6.dp))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

private fun formatBytes(b: Long, res: android.content.res.Resources): String {
    if (b < 1024) return res.getString(R.string.size_bytes, b.toInt())
    val kb = b / 1024.0
    if (kb < 1024) return res.getString(R.string.size_kb, kb.toFloat())
    val mb = kb / 1024.0
    if (mb < 1024) return res.getString(R.string.size_mb, mb.toFloat())
    val gb = mb / 1024.0
    return res.getString(R.string.size_gb, gb.toFloat())
}

/**
 * Single Settings row backed by [SegmentedListItem] (M3 Expressive). Position in a
 * grouped section is communicated through (`index`, `count`) — the M3
 * `ListItemDefaults.segmentedShapes` factory derives the per-row corner radii
 * (single → fully rounded; top/middle/bottom → outer-rounded seam) and the
 * pressed-state morph shape from one source of truth. The previous custom
 * `RowPosition` enum + manual `RoundedCornerShape` hierarchy traded clarity for
 * shape-token drift: every adjustment had to be applied in two places (the
 * shape() builder and the `Arrangement.spacedBy`). The segmented API owns both.
 *
 * Static info rows (no `onClick`) still render through this helper — they pass
 * an empty click lambda so the visual stays consistent with actionable rows;
 * the row is just a no-op when tapped. Rationale: a separate non-clickable
 * code path would diverge over time from the clickable look, and "looks
 * tappable but does nothing" tests the same as TG-Android's version row.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SettingsRow(
    symbol: String,
    title: String,
    subtitle: String? = null,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    chevron: Boolean = false,
    index: Int = 0,
    count: Int = 1,
    onClick: (() -> Unit)? = null,
) {
    val shapes = ListItemDefaults.segmentedShapes(
        index = index,
        count = count,
        defaultShapes = ListItemDefaults.shapes(),
    )
    SegmentedListItem(
        onClick = onClick ?: {},
        shapes = shapes,
        leadingContent = { Symbol(name = symbol, tint = tint, size = 22.dp) },
        supportingContent = subtitle?.let {
            {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = if (chevron) {
            {
                Symbol(
                    name = "chevron_right",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    size = 20.dp,
                )
            }
        } else null,
        content = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = tint,
            )
        },
    )
}

/**
 * Two-row [SegmentedListItem] group for the feed-order preference. Selection is
 * shown by tinting the active row's leading icon — no [androidx.compose.material3.RadioButton]
 * on the trailing slot, which would import additional metaphor (mutually-exclusive
 * choices in a dialog) on top of an already-affordant row pair. Matches the rest of
 * the settings vocabulary where rows are tappable surfaces, not radio-style picks.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FeedOrderRows(
    current: FeedOrder,
    onSelect: (FeedOrder) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
        FeedOrderRow(
            symbol = "arrow_upward",
            title = stringResource(R.string.settings_feed_order_oldest_title),
            subtitle = stringResource(R.string.settings_feed_order_oldest_subtitle),
            isSelected = current == FeedOrder.OldestUnreadFirst,
            index = 0,
            count = 2,
            onClick = { onSelect(FeedOrder.OldestUnreadFirst) },
        )
        FeedOrderRow(
            symbol = "arrow_downward",
            title = stringResource(R.string.settings_feed_order_newest_title),
            subtitle = stringResource(R.string.settings_feed_order_newest_subtitle),
            isSelected = current == FeedOrder.Newest,
            index = 1,
            count = 2,
            onClick = { onSelect(FeedOrder.Newest) },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun FeedOrderRow(
    symbol: String,
    title: String,
    subtitle: String,
    isSelected: Boolean,
    index: Int,
    count: Int,
    onClick: () -> Unit,
) {
    val shapes = ListItemDefaults.segmentedShapes(
        index = index,
        count = count,
        defaultShapes = ListItemDefaults.shapes(),
    )
    val leadingTint by androidx.compose.animation.animateColorAsState(
        targetValue = if (isSelected) MaterialTheme.colorScheme.primary
        else MaterialTheme.colorScheme.onSurfaceVariant,
        animationSpec = MaterialTheme.motionScheme.fastEffectsSpec(),
        label = "feed-order-tint",
    )
    SegmentedListItem(
        onClick = onClick,
        shapes = shapes,
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(
                        if (isSelected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Symbol(name = symbol, tint = leadingTint, size = 22.dp)
            }
        },
        supportingContent = {
            Text(
                text = subtitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        trailingContent = null,
        content = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        },
    )
}

/**
 * Standalone [SegmentedListItem] for the snap-scroll toggle. Independent of
 * [FeedOrderRows] in the layout — snap is a presentation mode (how fling
 * behaves) orthogonal to ordering (what's shown). Single-row segment shape
 * (`segmentedShapes(0, 1)`) so it reads as its own card.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SnapScrollRow(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val shapes = ListItemDefaults.segmentedShapes(
        index = 0,
        count = 1,
        defaultShapes = ListItemDefaults.shapes(),
    )
    SegmentedListItem(
        onClick = { onToggle(!enabled) },
        shapes = shapes,
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(
                        if (enabled) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Symbol(
                    name = "play_circle",
                    tint = if (enabled) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    size = 22.dp,
                )
            }
        },
        supportingContent = {
            Text(
                text = stringResource(R.string.settings_snap_scroll_subtitle),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        trailingContent = {
            Switch(checked = enabled, onCheckedChange = onToggle)
        },
        content = {
            Text(
                text = stringResource(R.string.settings_snap_scroll_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        },
    )
}

/**
 * Standalone [SegmentedListItem] for the inline-video-autoplay toggle. Independent
 * of [SnapScrollRow] in the layout — autoplay is a media-playback policy
 * orthogonal to how the list scrolls. Single-row segment so it reads as its own
 * card.
 *
 * Note: autoplay is also gated by "is the file already on disk?" — toggling this
 * row on doesn't override the user's [AutoDownloadStore] policy; videos that
 * weren't pulled by auto-download still show their static poster + play badge
 * until the user opens them. The row's subtitle calls this out so users don't
 * think the toggle is broken when their videos sit still on a roaming plan.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun InlineAutoplayRow(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val shapes = ListItemDefaults.segmentedShapes(
        index = 0,
        count = 1,
        defaultShapes = ListItemDefaults.shapes(),
    )
    SegmentedListItem(
        onClick = { onToggle(!enabled) },
        shapes = shapes,
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .background(
                        if (enabled) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Symbol(
                    name = "smart_display",
                    tint = if (enabled) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    size = 22.dp,
                )
            }
        },
        supportingContent = {
            Text(
                text = stringResource(R.string.settings_inline_autoplay_subtitle),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        },
        trailingContent = {
            Switch(checked = enabled, onCheckedChange = onToggle)
        },
        content = {
            Text(
                text = stringResource(R.string.settings_inline_autoplay_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        },
    )
}

// ---- Author attribution ------------------------------------------------------

private const val AUTHOR_CHANNEL_HANDLE = "lyblog"
private const val AUTHOR_DEVELOPER_HANDLE = "lydev"

