package dev.lyo.hortay.ui.timeline

import androidx.compose.runtime.compositionLocalOf

/**
 * The emoji a double tap on a post leaves as a reaction, or null when the gesture is off
 * ([dev.lyo.hortay.data.SettingsStore.quickReaction]). Provided by `MainActivity` around
 * the authenticated UI only: guest mode cannot react, so cards there keep the plain,
 * undelayed single tap.
 */
val LocalQuickReaction = compositionLocalOf<String?> { null }
