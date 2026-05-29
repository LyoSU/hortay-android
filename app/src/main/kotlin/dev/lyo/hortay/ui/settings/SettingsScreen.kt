@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.lyo.hortay.ui.settings

import android.os.Build
import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import kotlin.math.abs
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.coroutines.CancellationException
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
    // In-tab sub-screen stack. Plain `remember` (NOT rememberSaveable): process death
    // should return to the Main page, mirroring the cold-launch-to-top rule for the feed.
    // In-tab sub-screen stack. Plain `remember` (NOT rememberSaveable): process death returns
    // to the Main page, mirroring the cold-launch-to-top rule for the feed.
    val stack = remember { mutableStateListOf<SettingsRoute>() }
    // Main scroll is hoisted here so it survives a sub-screen round-trip — Main is rendered as
    // the always-mounted base layer below the overlays, so its scroll state is never torn down.
    val mainScrollState = rememberScrollState()
    fun pop() { if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) }

    // Predictive back WITH peek — identical to the channel / comments / archive overlay
    // (ui/main/NavOverlayRenderer): the top sub-screen follows the finger and reveals the layer
    // beneath (a parent sub-screen, or the Main page) as it drags away. Because Main and any
    // lower sub-screen stay mounted underneath, the peek shows the real page, not a blank — the
    // gap the earlier in-place AnimatedContent version couldn't fill.
    val backCommitSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val backRewindSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    val backProgress = remember { Animatable(0f) }
    var backEdge by remember { mutableIntStateOf(BackEventCompat.EDGE_LEFT) }
    PredictiveBackHandler(enabled = stack.isNotEmpty()) { progress ->
        try {
            progress.collect { event ->
                backEdge = event.swipeEdge
                val next = event.progress
                if (abs(next - backProgress.value) >= 0.005f) backProgress.snapTo(next)
            }
            backProgress.animateTo(SETTINGS_BACK_EXIT, backCommitSpec)
            pop()
            backProgress.snapTo(0f)
        } catch (_: CancellationException) {
            backProgress.animateTo(0f, backRewindSpec)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Base layer: the Main page, always mounted (scroll preserved + the predictive-back
        // peek target).
        SettingsMain(
            settings = settings,
            contentPadding = contentPadding,
            scrollState = mainScrollState,
            onLogout = onLogout,
            onSignIn = onSignIn,
            ignoredChannels = ignoredChannels,
            onOpenHiddenChannels = { stack.add(SettingsRoute.HiddenChannels) },
            onOpenDataStorage = { stack.add(SettingsRoute.DataStorage) },
            onOpenPrivacy = { stack.add(SettingsRoute.Privacy) },
            onOpenAbout = { stack.add(SettingsRoute.About) },
            me = me,
            userMessages = userMessages,
            onNavigateToArchiveSettings = onNavigateToArchiveSettings,
            archiveLossOnLogout = archiveLossOnLogout,
        )

        // Overlay layers — each opaque sub-screen covers what's beneath. Pushes are immediate
        // (no slide), matching the archive / channel overlays; the only motion is the
        // predictive-back transform on the top layer, driven by [backProgress].
        stack.forEachIndexed { idx, route ->
            val isTop = idx == stack.lastIndex
            key(route) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (isTop) {
                                Modifier.graphicsLayer {
                                    val p = backProgress.value.coerceIn(0f, SETTINGS_BACK_EXIT)
                                    val signed = if (backEdge == BackEventCompat.EDGE_RIGHT) -p else p
                                    translationX = signed * size.width * 0.25f
                                    val s = 1f - p.coerceAtMost(1f) * 0.05f
                                    scaleX = s
                                    scaleY = s
                                    alpha = (1f - p.coerceAtMost(1f) * 0.9f).coerceAtLeast(0f)
                                }
                            } else {
                                Modifier
                            },
                        ),
                ) {
                    when (route) {
                        SettingsRoute.AutoDownload -> autoDownload?.let { store ->
                            AutoDownloadHost(store = store, contentPadding = contentPadding, onBack = ::pop)
                        }
                        SettingsRoute.HiddenChannels -> ignoredChannels?.let { store ->
                            HiddenChannelsScreen(
                                store = store,
                                contentPadding = contentPadding,
                                onBack = ::pop,
                                channelActions = channelActions,
                                webChannelByChatId = webChannelByChatId,
                            )
                        }
                        SettingsRoute.DataStorage -> DataStorageScreen(
                            stats = stats,
                            contentPadding = contentPadding,
                            onClearWebCache = onClearWebCache,
                            autoDownloadAvailable = autoDownload != null,
                            onOpenAutoDownload = { stack.add(SettingsRoute.AutoDownload) },
                            onBack = ::pop,
                        )
                        SettingsRoute.Privacy -> PrivacyScreen(
                            settings = settings,
                            contentPadding = contentPadding,
                            privacyTogglesAvailable = stats != null,
                            onBack = ::pop,
                        )
                        SettingsRoute.About -> AboutScreen(contentPadding = contentPadding, onBack = ::pop)
                    }
                }
            }
        }
    }
}

/**
 * In-tab settings sub-screen, rendered as an opaque overlay layer above the always-mounted
 * Main page. The Main page itself is the base layer, never a route here. Order in the stack is
 * the layer order; Data&storage → AutoDownload is a two-deep path (both stay mounted so the
 * predictive-back peek reveals the parent).
 */
private enum class SettingsRoute {
    DataStorage,
    Privacy,
    About,
    HiddenChannels,
    AutoDownload,
}

/** Predictive-back commit target: 1f = peek, 2f = full slide-off + fade before the pop. */
private const val SETTINGS_BACK_EXIT = 2f

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
