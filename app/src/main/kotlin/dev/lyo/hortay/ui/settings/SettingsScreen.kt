@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.lyo.hortay.ui.settings

import android.os.Build
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import dev.lyo.hortay.R
import dev.lyo.hortay.data.AutoDownloadStore
import dev.lyo.hortay.data.ChannelActionsRepository
import dev.lyo.hortay.data.FeedOrder
import dev.lyo.hortay.data.IgnoredChannelsStore
import dev.lyo.hortay.data.SettingsStore
import dev.lyo.hortay.data.StatsRepository
import dev.lyo.hortay.data.UserMessageBus
import dev.lyo.hortay.ui.components.HortayTopBar
import dev.lyo.hortay.ui.components.HortayTopBarSize
import dev.lyo.hortay.data.resolveEmojiStatusId
import dev.lyo.hortay.ui.components.PremiumStatusBadge
import dev.lyo.hortay.ui.media.TdAvatar
import dev.lyo.hortay.ui.theme.profileCoverBrush
import dev.lyo.hortay.ui.theme.profileOnCoverColor
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
     * Opens the post-archive settings screen. Wired in TDLib mode via ArchiveSettingsKey;
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
    // Settings sub-screens run on a NESTED Navigation 3 NavDisplay over a LOCAL back stack (not
    // the app-wide [dev.lyo.hortay.AppGraph.backStack]): the Settings master/detail is
    // self-contained and its sub-pages carry settings-only dependencies that don't belong on the
    // global stack. [SettingsNavKey.Main] is the always-present root; sub-pages push on top as
    // opaque full-screen scenes. NavDisplay owns predictive back (replacing the hand-rolled
    // PredictiveBackHandler + graphicsLayer peek) and its saveable-state decorator preserves the
    // Main page's scroll across a sub-screen round-trip. Plain `remember` (NOT rememberSaveable):
    // process death returns to Main, the same cold-launch-to-top rule the feed and top-level nav
    // follow. While at Main (size 1) NavDisplay doesn't consume back, so it falls through to the
    // host scaffold (→ Feed tab); a deeper page pops first.
    val settingsBackStack = remember { mutableStateListOf<SettingsNavKey>(SettingsNavKey.Main) }
    val pop: () -> Unit = {
        if (settingsBackStack.size > 1) settingsBackStack.removeAt(settingsBackStack.lastIndex)
    }

    NavDisplay(
        backStack = settingsBackStack,
        onBack = { pop() },
        modifier = Modifier.fillMaxSize(),
        entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator()),
        // Same bare horizontal shared-axis as the app's top-level NavDisplay (see MainScaffold):
        // detail slides in from the side, the page below parallaxes a third so a predictive-back
        // swipe moves both layers. All three specs BARE so the predictive seek tracks the finger
        // smoothly — a spring spec here jerks the drag (see MainScaffold's spec KDoc).
        transitionSpec = {
            slideInHorizontally { it } togetherWith slideOutHorizontally { -it / 3 }
        },
        popTransitionSpec = {
            slideInHorizontally { -it / 3 } togetherWith slideOutHorizontally { it }
        },
        predictivePopTransitionSpec = {
            slideInHorizontally { -it / 3 } togetherWith slideOutHorizontally { it }
        },
        entryProvider = entryProvider {
            entry<SettingsNavKey.Main> {
                SettingsMain(
                    settings = settings,
                    contentPadding = contentPadding,
                    scrollState = rememberScrollState(),
                    onLogout = onLogout,
                    onSignIn = onSignIn,
                    ignoredChannels = ignoredChannels,
                    onOpenHiddenChannels = { settingsBackStack.add(SettingsNavKey.HiddenChannels) },
                    onOpenDataStorage = { settingsBackStack.add(SettingsNavKey.DataStorage) },
                    onOpenPrivacy = { settingsBackStack.add(SettingsNavKey.Privacy) },
                    onOpenAbout = { settingsBackStack.add(SettingsNavKey.About) },
                    me = me,
                    userMessages = userMessages,
                    onNavigateToArchiveSettings = onNavigateToArchiveSettings,
                    archiveLossOnLogout = archiveLossOnLogout,
                )
            }
            entry<SettingsNavKey.AutoDownload> {
                autoDownload?.let { store ->
                    AutoDownloadHost(store = store, contentPadding = contentPadding, onBack = pop)
                }
            }
            entry<SettingsNavKey.HiddenChannels> {
                ignoredChannels?.let { store ->
                    HiddenChannelsScreen(
                        store = store,
                        contentPadding = contentPadding,
                        onBack = pop,
                        channelActions = channelActions,
                        webChannelByChatId = webChannelByChatId,
                    )
                }
            }
            entry<SettingsNavKey.DataStorage> {
                DataStorageScreen(
                    stats = stats,
                    contentPadding = contentPadding,
                    onClearWebCache = onClearWebCache,
                    autoDownloadAvailable = autoDownload != null,
                    onOpenAutoDownload = { settingsBackStack.add(SettingsNavKey.AutoDownload) },
                    onBack = pop,
                )
            }
            entry<SettingsNavKey.Privacy> {
                PrivacyScreen(
                    settings = settings,
                    contentPadding = contentPadding,
                    privacyTogglesAvailable = stats != null,
                    onBack = pop,
                )
            }
            entry<SettingsNavKey.About> {
                AboutScreen(contentPadding = contentPadding, onBack = pop)
            }
        },
    )
}

/**
 * In-tab settings sub-screen key for the nested NavDisplay's entryProvider. [Main] is the
 * always-present root; the rest push on top as opaque full-screen scenes. Data&storage →
 * AutoDownload is a two-deep path — the saveable-state decorator preserves both so a
 * predictive-back drag peeks the real parent beneath, not a blank.
 */
private sealed interface SettingsNavKey : NavKey {
    data object Main : SettingsNavKey
    data object DataStorage : SettingsNavKey
    data object Privacy : SettingsNavKey
    data object About : SettingsNavKey
    data object HiddenChannels : SettingsNavKey
    data object AutoDownload : SettingsNavKey
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsMain(
    settings: SettingsStore,
    contentPadding: PaddingValues,
    /** Hoisted at [SettingsScreen] scope so the page scroll survives a sub-screen round-trip. */
    scrollState: ScrollState,
    onLogout: (() -> Unit)?,
    onSignIn: (() -> Unit)?,
    ignoredChannels: IgnoredChannelsStore?,
    onOpenHiddenChannels: () -> Unit,
    onOpenDataStorage: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenAbout: () -> Unit,
    me: TdApi.User?,
    userMessages: UserMessageBus?,
    onNavigateToArchiveSettings: (() -> Unit)? = null,
    archiveLossOnLogout: Boolean = false,
) {
    val scope = rememberCoroutineScope()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    var confirmLogout by remember { mutableStateOf(false) }

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
                // verticalScroll is essential here: the content overflows shorter phones, and
                // a non-scrollable Column would silently clip the "Вийти" / "Версія" rows below
                // the fold. The state is hoisted (param) so it isn't reset on a sub-screen return.
                .verticalScroll(scrollState)
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

            // ---- Appearance (inline quick toggle) ---------------------------------
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val currentDynamicColor by settings.dynamicColor.collectAsStateWithLifecycle(true)
                SectionLabel(stringResource(R.string.settings_section_appearance))
                DynamicColorRow(
                    enabled = currentDynamicColor,
                    onToggle = { next -> if (next != currentDynamicColor) scope.launch { settings.setDynamicColor(next) } },
                )
            }

            // ---- Feed (inline quick toggles) --------------------------------------
            val currentFeedOrder by settings.feedOrder.collectAsStateWithLifecycle(FeedOrder.OldestUnreadFirst)
            val currentSnapScroll by settings.snapScroll.collectAsStateWithLifecycle(false)
            val currentInlineAutoplay by settings.inlineVideoAutoplay.collectAsStateWithLifecycle(true)
            SectionLabel(stringResource(R.string.settings_section_feed))
            val feedOrderToNewestSnackbar = stringResource(R.string.settings_feed_order_snackbar_to_newest)
            val feedOrderToOldestSnackbar = stringResource(R.string.settings_feed_order_snackbar_to_oldest)
            FeedOrderRows(
                current = currentFeedOrder,
                onSelect = { order ->
                    if (order != currentFeedOrder) {
                        scope.launch { settings.setFeedOrder(order) }
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
                onToggle = { next -> if (next != currentSnapScroll) scope.launch { settings.setSnapScroll(next) } },
            )
            InlineAutoplayRow(
                enabled = currentInlineAutoplay,
                onToggle = { next -> if (next != currentInlineAutoplay) scope.launch { settings.setInlineVideoAutoplay(next) } },
            )
            if (ignoredChannels != null) {
                val hidden by ignoredChannels.ignored.collectAsStateWithLifecycle(initialValue = persistentSetOf())
                SettingsRow(
                    symbol = "visibility_off",
                    title = stringResource(R.string.settings_hidden_channels_title),
                    subtitle = if (hidden.isEmpty()) {
                        stringResource(R.string.settings_hidden_channels_subtitle_empty)
                    } else {
                        pluralStringResource(R.plurals.settings_hidden_channels_count, hidden.size, hidden.size)
                    },
                    chevron = true,
                    onClick = onOpenHiddenChannels,
                )
            }

            // ---- Categories (drill into sub-screens) ------------------------------
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)) {
                // Index/count computed so the grouped card rounds correctly across the
                // mode-conditional rows. Data&storage is always present (TDLib stats OR
                // guest clear-cache); Archive only in TDLib mode.
                val total = 3 + if (onNavigateToArchiveSettings != null) 1 else 0
                var i = 0
                SettingsRow(
                    symbol = "storage",
                    title = stringResource(R.string.settings_category_data_title),
                    subtitle = stringResource(R.string.settings_category_data_subtitle),
                    chevron = true, index = i++, count = total,
                    onClick = onOpenDataStorage,
                )
                if (onNavigateToArchiveSettings != null) {
                    SettingsRow(
                        symbol = "delete_sweep",
                        title = stringResource(R.string.settings_archive_title),
                        subtitle = stringResource(R.string.archive_master_subtitle),
                        chevron = true, index = i++, count = total,
                        onClick = onNavigateToArchiveSettings,
                    )
                }
                SettingsRow(
                    symbol = "shield",
                    title = stringResource(R.string.settings_section_privacy),
                    subtitle = stringResource(R.string.settings_category_privacy_subtitle),
                    chevron = true, index = i++, count = total,
                    onClick = onOpenPrivacy,
                )
                SettingsRow(
                    symbol = "info",
                    title = stringResource(R.string.settings_section_about),
                    subtitle = stringResource(R.string.settings_category_about_subtitle),
                    chevron = true, index = i++, count = total,
                    onClick = onOpenAbout,
                )
            }

            // ---- Account ----------------------------------------------------------
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
    val coverBrush = profileCoverBrush(me.profileAccentColorId)
    val onCover = profileOnCoverColor(me.profileAccentColorId)
    // The whole card is the accent colour (clean two-shade gradient, no muddy surface blend);
    // the name + handle ride on top in an adaptive black/white colour for contrast — the way
    // Telegram paints its profile header.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(coverBrush)
            .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TdAvatar(
            name = displayName,
            thumb = me.profilePhoto?.minithumbnail?.data,
            fileId = me.profilePhoto?.small?.id,
            size = 80.dp,
            textStyle = MaterialTheme.typography.headlineSmall,
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = displayName,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = onCover,
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
                color = onCover.copy(alpha = 0.75f),
            )
        }
    }
}
