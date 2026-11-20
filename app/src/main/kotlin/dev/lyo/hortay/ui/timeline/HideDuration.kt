package dev.lyo.hortay.ui.timeline

import androidx.annotation.StringRes
import dev.lyo.hortay.R

/**
 * How long "Hide channel from feed" keeps a channel out of the merged feed. [millis] is
 * null for the open-ended hide, which is the same permanent entry the Channels tab and
 * the channel info card write.
 */
internal enum class HideDuration(@param:StringRes val label: Int, val millis: Long?) {
    EightHours(R.string.hide_channel_8h, 8L * 60 * 60 * 1000),
    Day(R.string.hide_channel_1d, 24L * 60 * 60 * 1000),
    Week(R.string.hide_channel_1w, 7L * 24 * 60 * 60 * 1000),
    Forever(R.string.hide_channel_forever, null),
}
