package dev.lyo.hortay.ui.media

import androidx.compose.runtime.staticCompositionLocalOf
import dev.lyo.hortay.data.media.WebmFrameCache

val LocalWebmFrameCache = staticCompositionLocalOf<WebmFrameCache> { error("WebmFrameCache not provided") }
