package dev.lyo.hortay.ui.media

import androidx.compose.runtime.staticCompositionLocalOf
import okhttp3.OkHttpClient

/**
 * Process-wide [OkHttpClient] used by media composables that need to fetch raw
 * remote bytes (TGS Lottie payloads, sometimes WebM stickers without TDLib file
 * ids). Provided once from [dev.lyo.hortay.MainActivity], default value is a
 * minimal client so previews / previews-only consumers don't crash.
 */
val LocalWebHttpClient = staticCompositionLocalOf<OkHttpClient> {
    OkHttpClient.Builder().build()
}
