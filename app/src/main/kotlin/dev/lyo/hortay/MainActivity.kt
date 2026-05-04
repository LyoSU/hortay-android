package dev.lyo.hortay

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lyo.hortay.data.AuthStage
import dev.lyo.hortay.ui.auth.AuthScreen
import dev.lyo.hortay.ui.main.MainScaffold
import dev.lyo.hortay.ui.media.LocalCustomEmoji
import dev.lyo.hortay.ui.media.LocalExoPlayerPool
import dev.lyo.hortay.ui.media.LocalMediaCache
import dev.lyo.hortay.ui.media.MediaViewerHost
import dev.lyo.hortay.ui.theme.HortayTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val graph = (application as HortayApp).graph
        // Cold-launch deep link: parse + buffer it into the router *before* setContent so the
        // router has the event ready by the time MainScaffold subscribes a frame later. Warm
        // launches into an existing task arrive via [onNewIntent] below.
        graph.deepLinkRouter.submit(intent?.data)

        setContent {
            HortayTheme {
                CompositionLocalProvider(
                    LocalMediaCache provides graph.mediaCache,
                    LocalCustomEmoji provides graph.customEmoji,
                    LocalExoPlayerPool provides graph.exoPlayerPool,
                ) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        val auth by graph.tdClient.authStage.collectAsStateWithLifecycle()
                        when (auth) {
                            AuthStage.Ready -> MediaViewerHost { MainScaffold(graph = graph) }
                            else -> AuthScreen(graph = graph, stage = auth)
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
        graph.deepLinkRouter.submit(intent.data)
    }
}
