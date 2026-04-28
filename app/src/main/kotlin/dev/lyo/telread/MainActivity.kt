package dev.lyo.telread

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
import dev.lyo.telread.data.AuthStage
import dev.lyo.telread.ui.auth.AuthScreen
import dev.lyo.telread.ui.main.MainScaffold
import dev.lyo.telread.ui.media.LocalMediaCache
import dev.lyo.telread.ui.theme.TelreadTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val graph = (application as TelreadApp).graph

        setContent {
            TelreadTheme {
                CompositionLocalProvider(LocalMediaCache provides graph.mediaCache) {
                    Surface(modifier = Modifier.fillMaxSize()) {
                        val auth by graph.tdClient.authStage.collectAsStateWithLifecycle()
                        when (auth) {
                            AuthStage.Ready -> MainScaffold(graph = graph)
                            else -> AuthScreen(client = graph.tdClient, stage = auth)
                        }
                    }
                }
            }
        }
    }
}
