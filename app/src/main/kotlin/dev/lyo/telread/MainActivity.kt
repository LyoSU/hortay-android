package dev.lyo.telread

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lyo.telread.data.AuthStage
import dev.lyo.telread.ui.auth.AuthScreen
import dev.lyo.telread.ui.theme.TelreadTheme
import dev.lyo.telread.ui.timeline.TimelineScreen

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as TelreadApp
        val client = app.tdClient

        setContent {
            TelreadTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val auth by client.authStage.collectAsStateWithLifecycle()
                    when (auth) {
                        AuthStage.Ready -> TimelineScreen(client = client)
                        else -> AuthScreen(client = client, stage = auth)
                    }
                }
            }
        }
    }
}
