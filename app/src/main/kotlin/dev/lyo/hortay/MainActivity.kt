package dev.lyo.hortay

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lyo.hortay.data.AuthStage
import dev.lyo.hortay.data.EmojiStyle
import dev.lyo.hortay.data.LocaleStore
import dev.lyo.hortay.data.nav.HomeKey
import dev.lyo.hortay.ui.auth.AuthScreen
import dev.lyo.hortay.ui.main.MainScaffold
import dev.lyo.hortay.ui.media.LocalCustomEmoji
import dev.lyo.hortay.ui.media.LocalCustomEmojiAnimator
import dev.lyo.hortay.ui.media.LocalExoPlayerPool
import dev.lyo.hortay.ui.media.LocalMediaCache
import dev.lyo.hortay.ui.media.LocalReactionEmojiIcons
import dev.lyo.hortay.ui.timeline.LocalQuickReaction
import dev.lyo.hortay.ui.media.LocalAudioPlaybackSession
import dev.lyo.hortay.ui.media.LocalStickerOutline
import dev.lyo.hortay.ui.media.LocalWebHttpClient
import dev.lyo.hortay.ui.media.LocalWebmClock
import dev.lyo.hortay.ui.media.LocalWebmFrameCache
import dev.lyo.hortay.ui.media.MediaViewerHost
import dev.lyo.hortay.ui.media.WebmAnimationClock
import dev.lyo.hortay.ui.theme.HortayTheme
import dev.lyo.hortay.ui.theme.LocalProfileAccent
import dev.lyo.hortay.ui.web.MigrationProposalSheet
import dev.lyo.hortay.ui.channels.ChannelImportSheetHost
import dev.lyo.hortay.data.subscriptions.TransferMode
import dev.lyo.hortay.ui.web.WebModeScaffold
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    // API 26-32 path for the in-app language picker. AppCompatDelegate.setApplicationLocales
    // is a no-op without an AppCompatActivity in the process (it dispatches through an
    // internal sActivityDelegates set), so LocaleStore wraps the base context with the
    // user-chosen locale before resources resolve. API 33+ is handled by the platform
    // LocaleManager and this wrap is a no-op there. See LocaleStore for the rationale.
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleStore.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val graph = (application as HortayApp).graph
        // Cold-launch deep link: resolve + buffer into the router before MainScaffold's
        // collector subscribes. Resolution is async (TDLib GetInternalLinkType is an
        // offline JNI call but still a coroutine boundary) — appScope.launch wins the
        // race in practice because the Channel buffers the resulting event regardless
        // of subscriber arrival ordering. Warm launches arrive via [onNewIntent] below.
        // Only on a fresh launch: recreation (language switch, process-death restore,
        // Recents) re-delivers the original intent and would replay the link. A task
        // relaunched from Recents after its activity was finished arrives with a null
        // savedInstanceState too, but carries FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY.
        val launchedFromHistory =
            ((intent?.flags ?: 0) and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0
        if (savedInstanceState == null && !launchedFromHistory) {
            intent?.data?.let { uri ->
                graph.appScope.launch {
                    graph.linkResolver.resolve(uri)?.let { graph.deepLinkRouter.submit(it) }
                }
            }
            intent?.let { offerSharedText(graph, it) }
        }

        setContent {
            // Material You (wallpaper) vs brand palette — user preference, default off
            // (brand periwinkle; see SettingsStore.dynamicColor). Read from the graph's
            // pre-warmed StateFlow so the first frame already carries the persisted choice
            // instead of an initialValue that disagrees with it. HortayTheme still guards
            // the actual dynamic-colour call behind SDK >= S, so `true` on pre-12 devices
            // safely falls through to the brand scheme.
            val dynamicColor by graph.dynamicColorState.collectAsStateWithLifecycle()
            // Theme mode / pure black / reading text size — pre-warmed by SettingsStore for the
            // same first-frame reason. Only ThemeMode.System consults the platform night mode.
            val appearance by graph.settingsStore.appearanceState.collectAsStateWithLifecycle()
            HortayTheme(
                darkTheme = appearance.themeMode.isDark(systemDark = isSystemInDarkTheme()),
                dynamicColor = dynamicColor,
                pureBlack = appearance.pureBlack,
                readingScale = appearance.textSize.scale,
            ) {
                val webmClock = remember { WebmAnimationClock() }
                CompositionLocalProvider(
                    LocalMediaCache provides graph.mediaCache,
                    LocalCustomEmoji provides graph.customEmoji,
                    LocalCustomEmojiAnimator provides graph.customEmojiAnimator,
                    LocalStickerOutline provides graph.stickerOutline,
                    LocalExoPlayerPool provides graph.exoPlayerPool,
                    LocalAudioPlaybackSession provides graph.audioPlaybackSession,
                    LocalWebHttpClient provides graph.webHttpClient,
                    LocalWebmFrameCache provides graph.webmFrameCache,
                    LocalWebmClock provides webmClock,
                    LocalProfileAccent provides graph.profileAccent,
                ) {
                    LaunchedEffect(webmClock) { webmClock.run() }
                    Surface(modifier = Modifier.fillMaxSize()) {
                        val auth by graph.tdClient.authStage.collectAsStateWithLifecycle()
                        val isGuest by graph.guestMode.isGuest.collectAsStateWithLifecycle(
                            initialValue = false,
                        )
                        // Leaving guest mode for sign-in drops the guest detail stack. The
                        // back stack is shared by both scaffolds, and CommentsKey is a key
                        // both modes push: a guest post's comments screen left on top would
                        // be rendered for real by MainScaffold once auth reaches Ready — with
                        // a synthetic web chatId TDLib has never heard of. The key can't tell
                        // a guest anchor apart (synthetic ids overlap real supergroup ids, see
                        // PostActions), so MainScaffold can't self-heal it; the mode flip is
                        // the one seam every sign-in path crosses (comments hero, Settings,
                        // Add channel, the snackbar "Sign in" action). Only a guest → non-guest
                        // edge counts: the remembered flag starts false, so recreation (which
                        // keeps the graph-scoped stack across rotation) never resets.
                        val wasGuest = remember { booleanArrayOf(false) }
                        LaunchedEffect(isGuest) {
                            if (wasGuest[0] && !isGuest) {
                                Snapshot.withMutableSnapshot {
                                    graph.backStack.apply {
                                        clear()
                                        add(HomeKey)
                                    }
                                }
                            }
                            wasGuest[0] = isGuest
                        }
                        // Routing precedence:
                        //   1. Authenticated (auth.Ready) → full TDLib UI. The user signed
                        //      in; show them everything regardless of any earlier guest
                        //      preference.
                        //   2. Guest-mode flag set → web-only UI. The user explicitly
                        //      chose to read without signing in; honour that across cold
                        //      starts even though TDLib's auth state is "WaitPhone" by
                        //      default.
                        //   3. Otherwise → AuthScreen, where the user can either sign in
                        //      or flip the guest-mode flag.
                        when {
                            auth == AuthStage.Ready -> {
                                // Telegram reaction artwork needs a TDLib session, so the resolver
                                // is provided only here: guest mode and the auth screen see null
                                // and draw reaction emoji with the system font. Under
                                // EmojiStyle.System it is null too, so reaction glyphs issue no
                                // TDLib call; flipping the setting recomposes only the glyphs.
                                val emojiStyle by graph.emojiStyleState.collectAsStateWithLifecycle()
                                val quickReaction by graph.settingsStore.quickReactionState.collectAsStateWithLifecycle()
                                CompositionLocalProvider(
                                    LocalReactionEmojiIcons provides graph.reactionEmojiIcons
                                        .takeIf { emojiStyle == EmojiStyle.Telegram },
                                    LocalQuickReaction provides quickReaction,
                                ) {
                                    MediaViewerHost { MainScaffold(graph = graph) }
                                }
                            }
                            // Guest mode also needs MediaViewerHost: TimelineScreen reads
                            // LocalMediaViewer to open photo/video previews on tap, and
                            // PostCard's full media-rendering chain assumes the host is
                            // present. Without this wrap the first measure pass crashes.
                            isGuest -> MediaViewerHost { WebModeScaffold(graph = graph) }
                            else -> AuthScreen(graph = graph, stage = auth)
                        }

                        // Channel-list import sheet, opened from the Channels tab menu or by
                        // text shared to Hortay. Mounted for whichever mode is on screen; on
                        // the auth screen a shared list waits until the user picks a mode.
                        when {
                            auth == AuthStage.Ready ->
                                ChannelImportSheetHost(graph.subscriptionTransfer, TransferMode.Account)
                            isGuest -> ChannelImportSheetHost(graph.subscriptionTransfer, TransferMode.Guest)
                        }

                        // One-time post-sign-in migration proposal. Renders ON TOP of the
                        // authenticated UI when [MigrationCoordinator] surfaces a pending
                        // candidate list. Self-dismisses when the user confirms / skips —
                        // the coordinator persists "shown" so it doesn't reappear next
                        // session.
                        if (auth == AuthStage.Ready) {
                            val proposal by graph.migrationCoordinator.pendingProposal
                                .collectAsStateWithLifecycle()
                            proposal?.let { candidates ->
                                MigrationProposalSheet(
                                    coordinator = graph.migrationCoordinator,
                                    candidates = candidates,
                                    // No host-side state to reset: every close path inside the
                                    // sheet (confirm, Skip, swipe, scrim, Back) goes through the
                                    // coordinator, which clears pendingProposal and unmounts it.
                                    onDismiss = {},
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // uiMode is handled in-process (manifest configChanges), so the Activity is no longer
    // recreated on a dark-mode flip. Re-running enableEdgeToEdge refreshes the
    // SystemBarStyle.auto navigation-bar scrim (API < 29), which is otherwise only evaluated
    // once in onCreate. Every run also resets the bar ICON appearance to the system theme;
    // Theme.kt keys its bar-appearance effect on LocalConfiguration, so it re-applies the
    // app's (possibly pinned) choice after each handled change, rotation included.
    // locale / layoutDirection are deliberately NOT handled: the language picker relies on
    // recreation so attachBaseContext re-wraps the resources.
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        enableEdgeToEdge()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // singleTask / re-entry path: a fresh tg:// or https://t.me URL arriving while the
        // activity is already alive. The router's Channel.BUFFERED queue holds rapid-fire
        // links during a transition until MainScaffold's collector drains them in order.
        val graph = (application as HortayApp).graph
        intent.data?.let { uri ->
            graph.appScope.launch {
                graph.linkResolver.resolve(uri)?.let { graph.deepLinkRouter.submit(it) }
            }
        }
        offerSharedText(graph, intent)
    }

    /**
     * ACTION_SEND text/plain from another app: channel links in it open the import preview
     * ([dev.lyo.hortay.data.subscriptions.SubscriptionTransfer.offerSharedText]). Same
     * fresh-launch-only guard as the VIEW deep link above, so recreation or a relaunch
     * from Recents never replays a share.
     */
    private fun offerSharedText(graph: AppGraph, intent: Intent) {
        if (intent.action != Intent.ACTION_SEND) return
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            ?.take(dev.lyo.hortay.data.subscriptions.SubscriptionListFormat.MAX_IMPORT_CHARS) ?: return
        graph.subscriptionTransfer.offerSharedText(text)
    }
}
