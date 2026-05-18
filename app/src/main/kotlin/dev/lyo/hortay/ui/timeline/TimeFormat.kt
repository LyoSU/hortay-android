package dev.lyo.hortay.ui.timeline

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.lyo.hortay.R
import java.text.DateFormat
import java.util.Date

/**
 * Single source of truth for the "X min/hour/day ago" timestamp used on every message
 * surface (feed post header, comment header). Kept in `ui.timeline` because PostCard
 * is the canonical owner of the message-header design language; CommentsScreen reuses
 * by import — mirrors the [label] / [symbolName] split in ReplyKindResources.kt.
 */
@Composable
internal fun formatRelative(epochMs: Long): String {
    val diffMin = (System.currentTimeMillis() - epochMs) / 60_000
    return when {
        diffMin < 1 -> stringResource(R.string.time_just_now)
        diffMin < 60 -> stringResource(R.string.time_minutes_short, diffMin.toInt())
        diffMin < 60 * 24 -> stringResource(R.string.time_hours_short, (diffMin / 60).toInt())
        diffMin < 60 * 24 * 7 -> stringResource(R.string.time_days_short, (diffMin / (60 * 24)).toInt())
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochMs))
    }
}
