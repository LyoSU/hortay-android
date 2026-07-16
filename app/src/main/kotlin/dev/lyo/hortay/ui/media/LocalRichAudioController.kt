package dev.lyo.hortay.ui.media

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The process-singleton [RichAudioController] driving inline rich-message audio / voice-note
 * playback. Provided in `MainActivity` from `AppGraph`; rich player rows read it to start /
 * toggle playback and observe the single active track.
 */
val LocalRichAudioController = staticCompositionLocalOf<RichAudioController> {
    error("RichAudioController was not provided. Wrap your composition in CompositionLocalProvider(LocalRichAudioController provides …).")
}
