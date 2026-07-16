package dev.lyo.hortay

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lyo.hortay.data.AuthStage
import dev.lyo.hortay.data.LocaleStore
import dev.lyo.hortay.ui.auth.AuthScreen
import dev.lyo.hortay.ui.main.MainScaffold
import dev.lyo.hortay.ui.media.LocalCustomEmoji
import dev.lyo.hortay.ui.media.LocalCustomEmojiAnimator
import dev.lyo.hortay.ui.media.LocalExoPlayerPool
import dev.lyo.hortay.ui.media.LocalMediaCache
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
        intent?.data?.let { uri ->
            graph.appScope.launch {
                graph.linkResolver.resolve(uri)?.let { graph.deepLinkRouter.submit(it) }
            }
        }

        setContent {
            // Material You (wallpaper) vs brand palette — user preference, default on.
            // HortayTheme still guards the actual dynamic-colour call behind SDK >= S,
            // so passing `true` on pre-12 devices safely falls through to the brand scheme.
            val dynamicColor by graph.settingsStore.dynamicColor
                .collectAsStateWithLifecycle(initialValue = true)
            HortayTheme(dynamicColor = dynamicColor) {
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
                            auth == AuthStage.Ready -> MediaViewerHost { MainScaffold(graph = graph) }
                            // Guest mode also needs MediaViewerHost: TimelineScreen reads
                            // LocalMediaViewer to open photo/video previews on tap, and
                            // PostCard's full media-rendering chain assumes the host is
                            // present. Without this wrap the first measure pass crashes.
                            isGuest -> MediaViewerHost { WebModeScaffold(graph = graph) }
                            else -> AuthScreen(graph = graph, stage = auth)
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
                                    onDismiss = { /* coordinator clears pendingProposal */ },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTop / re-entry path: a fresh tg:// or https://t.me URL arriving while the
        // activity is already alive. The router's Channel.BUFFERED queue holds rapid-fire
        // links during a transition until MainScaffold's collector drains them in order.
        val graph = (application as HortayApp).graph
        intent.data?.let { uri ->
            graph.appScope.launch {
                graph.linkResolver.resolve(uri)?.let { graph.deepLinkRouter.submit(it) }
            }
        }
    }
}
