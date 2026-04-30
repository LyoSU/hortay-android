package dev.lyo.hortay

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
import dev.lyo.hortay.ui.media.MediaViewerHost
import dev.lyo.hortay.ui.media.LocalMediaCache
import dev.lyo.hortay.ui.theme.HortayTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val graph = (application as HortayApp).graph

        setContent {
            HortayTheme {
                CompositionLocalProvider(LocalMediaCache provides graph.mediaCache) {
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
}
