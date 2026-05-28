@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.lyo.hortay.ui.settings

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
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
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.SingletonImageLoader
import dev.lyo.hortay.BuildConfig
import dev.lyo.hortay.R
import dev.lyo.hortay.data.AutoDownloadStore
import dev.lyo.hortay.data.ChannelActionsRepository
import dev.lyo.hortay.data.FeedOrder
import dev.lyo.hortay.data.IgnoredChannelsStore
import dev.lyo.hortay.data.NetworkUsage
import dev.lyo.hortay.data.SettingsStore
import dev.lyo.hortay.data.StatsRepository
import dev.lyo.hortay.data.StorageUsage
import dev.lyo.hortay.data.UserMessageBus
import dev.lyo.hortay.ui.components.HortayTopBar
import dev.lyo.hortay.ui.components.HortayTopBarSize
import dev.lyo.hortay.data.resolveEmojiStatusId
import dev.lyo.hortay.ui.components.PremiumStatusBadge
import dev.lyo.hortay.ui.media.TdAvatar
import org.drinkless.tdlib.TdApi
import kotlinx.collections.immutable.persistentSetOf
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
    autoDownload: AutoDownloadStore? = null,
    /**
     * Authenticated user shown in the TG-style hero header (avatar + name +
     * @handle / phone). Null in guest mode and during the cold-start window
     * before [TdApi.GetMe] resolves; in either case the hero block is skipped
     * and the screen opens directly with the section grouping.
     */
    me: TdApi.User? = null,
    /**
     * Hidden-channels store. Surfaces a "Hidden channels (N)" row + manage
     * sub-screen. Optional so a test harness or a stripped build can drop it
     * without rewiring the call sites.
     */
    ignoredChannels: IgnoredChannelsStore? = null,
    /**
     * TDLib resolver for the Hidden Channels sub-screen — resolves channel
     * title / handle for each hidden chatId. Null in guest mode (the
     * web-channel resolver below covers that path).
     */
    channelActions: ChannelActionsRepository? = null,
    /**
     * Guest-mode resolver for the Hidden Channels sub-screen. Looks up a
     * channel by its stable hash-derived chatId from
     * [dev.lyo.hortay.data.web.WebPostAdapter.stableChatId]. Null in TDLib mode.
     */
    webChannelByChatId: ((Long) -> WebChannelDescriptor?)? = null,
    /**
     * Process-wide bus used to surface a confirmation snackbar after the user
     * flips the feed-order toggle. Optional so a test harness can omit it —
     * the toggle still persists the preference, just without the toast.
     */
    userMessages: UserMessageBus? = null,
    /**
     * Opens the post-archive settings screen. Wired in TDLib mode via NavEntry.ArchiveSettings;
     * null in guest mode (archive requires an authenticated session).
     */
    onNavigateToArchiveSettings: (() -> Unit)? = null,
    /**
     * True when the post archive is enabled, so logging out will irreversibly wipe
     * the locally-stored edit/delete history (cleared in [AppGraph.runLogoutCleanup]).
     * Drives the logout dialog to add a data-loss warning — but only for users who
     * actually opted into archiving, so the 99% with it off see the plain message.
     */
    archiveLossOnLogout: Boolean = false,
) {
    // Sub-screen nav lives inside Settings — the auto-download list and category
    // screens are conceptually "deeper" pages of the same tab. Using AnimatedContent
    // keeps the bottom navigation visible (TG-style) and Material's shared-x slide
    // gives the user a clear sense of depth.
    var showAutoDownload by rememberSaveable { mutableStateOf(false) }
    var showHiddenChannels by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = showAutoDownload) { showAutoDownload = false }
    BackHandler(enabled = showHiddenChannels) { showHiddenChannels = false }

    // M3E shared-axis-X via MotionScheme: spatial spring for the slide, effects
    // spring for the crossfade. Same physics as MaterialTheme reads on every Material
    // component, so the screen transition feels of-a-piece with chip / card / banner
    // morphs instead of legacy duration-tween. Captured here (composable scope)
    // because AnimatedContent.transitionSpec is a non-composable lambda.
    val spatialSpec = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    val effectsSpec = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    // Three sub-screens (Main, AutoDownload, HiddenChannels) share one
    // AnimatedContent so the forward/back shared-axis-X slide reads the
    // same regardless of which depth-1 page the user is on. Enum target
    // lets the transitionSpec compute slide direction from a stable depth
    // ordering — main = 0, deeper pages = 1 — so any depth-0↔depth-1 move
    // animates as "go deeper / come back" rather than two unrelated
    // crossfades.
    val current = when {
        showAutoDownload && autoDownload != null -> SettingsSection.AutoDownload
        showHiddenChannels && ignoredChannels != null -> SettingsSection.HiddenChannels
        else -> SettingsSection.Main
    }
    AnimatedContent(
        targetState = current,
        transitionSpec = {
            val forward = targetState.depth > initialState.depth
            val direction = if (forward) SlideDirection.Left else SlideDirection.Right
            (slideIntoContainer(direction, spatialSpec) + fadeIn(effectsSpec)) togetherWith
                (slideOutOfContainer(direction, spatialSpec) + fadeOut(effectsSpec))
        },
        label = "settings-nav",
    ) { section ->
        when (section) {
            // The non-null guard is in `current` above. `?.let` here keeps a transient
            // null during AnimatedContent's outgoing fade from crashing — render-nothing
            // is preferable to NPE during the few frames the lambda is invoked with the
            // pre-transition section while the upstream nullable just flipped.
            SettingsSection.AutoDownload -> autoDownload?.let { store ->
                AutoDownloadHost(
                    store = store,
                    contentPadding = contentPadding,
                    onBack = { showAutoDownload = false },
                )
            }
            SettingsSection.HiddenChannels -> ignoredChannels?.let { store ->
                HiddenChannelsScreen(
                    store = store,
                    contentPadding = contentPadding,
                    onBack = { showHiddenChannels = false },
                    channelActions = channelActions,
                    webChannelByChatId = webChannelByChatId,
                )
            }
            SettingsSection.Main -> SettingsMain(
                settings = settings,
                stats = stats,
                contentPadding = contentPadding,
                onLogout = onLogout,
                onSignIn = onSignIn,
                onClearWebCache = onClearWebCache,
                autoDownloadAvailable = autoDownload != null,
                onOpenAutoDownload = { showAutoDownload = true },
                ignoredChannels = ignoredChannels,
                onOpenHiddenChannels = { showHiddenChannels = true },
                me = me,
                userMessages = userMessages,
                onNavigateToArchiveSettings = onNavigateToArchiveSettings,
                archiveLossOnLogout = archiveLossOnLogout,
            )
        }
    }
}

/**
 * Settings depth-aware page tag. `depth` drives the AnimatedContent slide
 * direction so every depth-0 → depth-1 traversal slides left, every back
 * slides right, regardless of which sub-screen we're entering / leaving.
 */
private enum class SettingsSection(val depth: Int) {
    Main(0),
    AutoDownload(1),
    HiddenChannels(1),
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
    autoDownloadAvailable: Boolean,
    onOpenAutoDownload: () -> Unit,
    ignoredChannels: IgnoredChannelsStore?,
    onOpenHiddenChannels: () -> Unit,
    me: TdApi.User?,
    userMessages: UserMessageBus?,
    onNavigateToArchiveSettings: (() -> Unit)? = null,
    archiveLossOnLogout: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    var confirmLogout by remember { mutableStateOf(false) }
    var confirmClearWebCache by remember { mutableStateOf(false) }
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
            // ---- Hero block: TG-style profile card -----------------------------------
            // Anchors the screen the same way Telegram's own Settings → Profile does:
            // the user sees who they're signed in as before any settings rows. Skipped
            // in guest mode (no [TdApi.User] to render) and during the cold-start
            // window between AuthStage.Ready and the first GetMe — both surface as
            // [me] == null, which collapses to "no hero, start directly with the
            // section grouping" instead of stubbing fake data.
            if (me != null) {
                ProfileHero(me)
                Spacer(Modifier.height(4.dp))
            }

            // ---- Appearance: Material You (wallpaper) vs Hortay brand palette --------
            // Android 12+ only — below S the platform can't derive a wallpaper palette,
            // so the brand periwinkle scheme is the only option and a toggle would be a
            // dead control. Default on (wallpaper colours); off pins the brand identity.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val currentDynamicColor by settings.dynamicColor.collectAsStateWithLifecycle(true)
                SectionLabel(stringResource(R.string.settings_section_appearance))
                DynamicColorRow(
                    enabled = currentDynamicColor,
                    onToggle = { next ->
                        if (next != currentDynamicColor) {
                            scope.launch { settings.setDynamicColor(next) }
                        }
                    },
                )
            }

            // ---- Mode-agnostic: feed-order + snap-scroll preferences -----------------
            // Generic display settings that apply to both TDLib and guest modes —
            // placed at the top so they read as "this is how the feed behaves"
            // before any mode-conditional sections.
            val currentFeedOrder by settings.feedOrder.collectAsStateWithLifecycle(FeedOrder.OldestUnreadFirst)
            val currentSnapScroll by settings.snapScroll.collectAsStateWithLifecycle(false)
            val currentInlineAutoplay by settings.inlineVideoAutoplay.collectAsStateWithLifecycle(true)
            val currentHideOnline by settings.hideOnlineStatus.collectAsStateWithLifecycle(false)
            SectionLabel(stringResource(R.string.settings_section_feed))
            val feedOrderToNewestSnackbar = stringResource(R.string.settings_feed_order_snackbar_to_newest)
            val feedOrderToOldestSnackbar = stringResource(R.string.settings_feed_order_snackbar_to_oldest)
            FeedOrderRows(
                current = currentFeedOrder,
                onSelect = { order ->
                    if (order != currentFeedOrder) {
                        scope.launch { settings.setFeedOrder(order) }
                        // One-line confirmation: the feed reorders in the background while
                        // the user stays in Settings, so an explicit toast is the cheapest
                        // way to make the side-effect visible. Mode-specific copy doubles as
                        // a mental-model reminder ("newest at the bottom, like a chat") for
                        // users who haven't internalised the OldestUnreadFirst layout yet.
                        userMessages?.post(
                            text = when (order) {
                                FeedOrder.Newest -> feedOrderToNewestSnackbar
                                FeedOrder.OldestUnreadFirst -> feedOrderToOldestSnackbar
                            },
                            severity = UserMessageBus.Severity.Info,
                        )
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

            // ---- Hidden channels: per-user feed-exclusion list ----------------------
            // Rendered as part of the Feed section because hiding a channel is a
            // feed-shaping decision (same vocabulary as feed order, snap-scroll,
            // autoplay). Single row that surfaces the count and drills into a
            // dedicated manage sub-screen. Hidden when the store wasn't wired —
            // mirrors the conditional rendering of the auto-download entry row.
            if (ignoredChannels != null) {
                val hidden by ignoredChannels.ignored.collectAsStateWithLifecycle(
                    initialValue = persistentSetOf(),
                )
                SettingsRow(
                    symbol = "visibility_off",
                    title = stringResource(R.string.settings_hidden_channels_title),
                    subtitle = if (hidden.isEmpty()) {
                        stringResource(R.string.settings_hidden_channels_subtitle_empty)
                    } else {
                        pluralStringResource(
                            R.plurals.settings_hidden_channels_count,
                            hidden.size,
                            hidden.size,
                        )
                    },
                    chevron = true,
                    onClick = onOpenHiddenChannels,
                )
            }

            // ---- Archive settings: TDLib-mode-only entry point ----------------------
            // Post archive requires an authenticated session (capture hooks live in
            // PostsRepository and CommentsRepository; the DB is wiped on logout). The
            // row is hidden in guest mode by passing onNavigateToArchiveSettings = null
            // from WebModeScaffold's SettingsScreen call site.
            if (onNavigateToArchiveSettings != null) {
                SettingsRow(
                    symbol = "delete_sweep",
                    title = stringResource(R.string.settings_archive_title),
                    subtitle = stringResource(R.string.archive_master_subtitle),
                    chevron = true,
                    onClick = onNavigateToArchiveSettings,
                )
            }

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
            // ---- Privacy section: local presence toggles (TDLib-mode only) -----------
            // TDLib's `online` option is what drives Telegram's green dot / last-seen
            // (per Aliaksei Levin in tdlib/td#3144: "online option is about the user,
            // not the network"). Hortay sits between ProcessLifecycleOwner and TDLib
            // via TdLifecycleBridge, so toggling this row simply removes one factor
            // from the "should we present the user as online" combine — content
            // updates continue to flow over OpenChat + SetNetworkType. Hidden in
            // guest mode because TDLib isn't running there, and the server-side
            // privacy.lastSeen knob stays under the official client's control either
            // way.
            if (stats != null) {
                Spacer(Modifier.height(8.dp))
                SectionLabel(stringResource(R.string.settings_section_privacy))
                HideOnlineStatusRow(
                    enabled = currentHideOnline,
                    onToggle = { next ->
                        if (next != currentHideOnline) {
                            scope.launch { settings.setHideOnlineStatus(next) }
                        }
                    },
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
                                    android.net.Uri.parse(BuildConfig.CHILD_SAFETY_POLICY_URL),
                                )
                        } catch (_: android.content.ActivityNotFoundException) {
                            uriHandler.openUri(BuildConfig.CHILD_SAFETY_POLICY_URL)
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
                                    android.net.Uri.parse(BuildConfig.PRIVACY_POLICY_URL),
                                )
                        } catch (_: android.content.ActivityNotFoundException) {
                            uriHandler.openUri(BuildConfig.PRIVACY_POLICY_URL)
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
                    count = 3,
                    onClick = { uriHandler.openUri("https://t.me/$AUTHOR_CHANNEL_HANDLE") },
                )
                SettingsRow(
                    symbol = "person",
                    title = stringResource(R.string.settings_author_developer_title),
                    subtitle = "@$AUTHOR_DEVELOPER_HANDLE",
                    chevron = true,
                    index = 1,
                    count = 3,
                    onClick = { uriHandler.openUri("https://t.me/$AUTHOR_DEVELOPER_HANDLE") },
                )
                SettingsRow(
                    symbol = "code",
                    title = stringResource(R.string.settings_author_source_title),
                    subtitle = AUTHOR_SOURCE_LABEL,
                    chevron = true,
                    index = 2,
                    count = 3,
                    onClick = { uriHandler.openUri(AUTHOR_SOURCE_URL) },
                )
            }

            Spacer(Modifier.height(8.dp))
            SectionLabel(stringResource(R.string.settings_section_about))
            Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                LanguageRow(index = 0, count = 2)
                SettingsRow(
                    symbol = "info",
                    title = stringResource(R.string.settings_version),
                    subtitle = "${BuildConfig.VERSION_NAME} · build ${BuildConfig.VERSION_CODE}",
                    index = 1,
                    count = 2,
                )
            }
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
            text = {
                Text(
                    stringResource(
                        if (archiveLossOnLogout) {
                            R.string.settings_logout_dialog_text_archive
                        } else {
                            R.string.settings_logout_dialog_text
                        },
                    ),
                )
            },
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

}

// ---- Profile hero ------------------------------------------------------------

/**
 * TG-style "this is you" header card. Mirrors the layout of Telegram's own
 * Settings → Profile entry: a circular avatar pyramid (initial letter → minithumb →
 * small file) on the left, display name + secondary handle/phone on the right.
 *
 * Display name composition follows Telegram's own resolution: `firstName lastName`
 * trimmed; falls back to `@activeUsername` when both name fields are empty (an
 * uncommon but legitimate state for accounts that signed up with a username
 * only). The premium star sits next to the name as a tiny tint indicator, same
 * affordance the official client uses.
 *
 * No tap action — Hortay is a reader, not a profile editor. The card reads as
 * an identity badge, not a settings row.
 */
@Composable
private fun ProfileHero(me: TdApi.User) {
    val displayName = remember(me.firstName, me.lastName, me.usernames) {
        val joined = listOf(me.firstName, me.lastName)
            .map { it.orEmpty().trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        joined.ifBlank {
            me.usernames?.activeUsernames?.firstOrNull()?.let { "@$it" }.orEmpty()
        }
    }
    val subtitle = remember(me.phoneNumber, me.usernames) {
        val handle = me.usernames?.activeUsernames?.firstOrNull()
        when {
            !handle.isNullOrBlank() -> "@$handle"
            me.phoneNumber.isNotBlank() -> "+${me.phoneNumber}"
            else -> ""
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        TdAvatar(
            name = displayName,
            thumb = me.profilePhoto?.minithumbnail?.data,
            fileId = me.profilePhoto?.small?.id,
            size = 72.dp,
            textStyle = MaterialTheme.typography.headlineSmall,
        )
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val emojiStatusId = remember(me.emojiStatus) { resolveEmojiStatusId(me.emojiStatus) }
                if (me.isPremium || emojiStatusId != null) {
                    Spacer(Modifier.width(6.dp))
                    PremiumStatusBadge(
                        isPremium = me.isPremium,
                        emojiStatusId = emojiStatusId,
                        size = 18.dp,
                    )
                }
            }
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---- Author attribution ------------------------------------------------------

private const val AUTHOR_CHANNEL_HANDLE = "lyblog"
private const val AUTHOR_DEVELOPER_HANDLE = "lydev"
private const val AUTHOR_SOURCE_URL = "https://github.com/LyoSU/hortay-android"
private const val AUTHOR_SOURCE_LABEL = "github.com/LyoSU/hortay-android"
