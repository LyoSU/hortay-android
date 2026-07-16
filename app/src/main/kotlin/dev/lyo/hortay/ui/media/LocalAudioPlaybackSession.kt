package dev.lyo.hortay.ui.media

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The process-singleton [AudioPlaybackSession] driving inline audio / voice-note playback
 * across the whole app (regular feed + rich messages). Provided in `MainActivity` from
 * `AppGraph`; player rows read it to start / toggle playback and observe the single active
 * track.
 */
val LocalAudioPlaybackSession = staticCompositionLocalOf<AudioPlaybackSession> {
    error("AudioPlaybackSession was not provided. Wrap your composition in CompositionLocalProvider(LocalAudioPlaybackSession provides …).")
}
