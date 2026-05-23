@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
)

package dev.lyo.hortay.ui.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.R
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.main.openTelegramApp
import dev.lyo.hortay.ui.theme.asComposeShape

/**
 * Distinct empty-state variants surfaced inside the timeline. Each carries its
 * own M3E hero (polygon + glyph) and copy so the UI reads the moment as either
 * "you haven't followed anyone yet" (Default), "you haven't saved anything yet"
 * (Saved), or "you're all caught up on unread" (CaughtUp).
 */
internal enum class EmptyKind { Default, Saved, CaughtUp }

/**
 * Telegram-Android-style "New messages" rule — a horizontal divider with a
 * centered tonal label, drawn above the first unread post in
 * [dev.lyo.hortay.data.FeedOrder.OldestUnreadFirst]. Structural separation reads
 * stronger than opacity: the read-history block above is visibly closed off
 * from the unread queue below, so the user navigating up into history doesn't
 * confuse what's been seen with what hasn't.
 *
 * Style: ~28 dp tall, two `primary`-tinted divider lines flanking a
 * `labelSmall` "Нові пости" pill in `primary` color. No background fill —
 * the rule reads as a typographic boundary inside the feed, not as a heavy
 * separator card. Restrained on purpose: this is peripheral orientation
 * ("you came in here"), not a CTA — Telegram-Android's same rule is
 * similarly understated. Padding matches PostCard horizontal padding so
 * the lines extend edge-to-edge of the column.
 */
@Composable
internal fun UnreadBoundaryRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
        HorizontalDivider(modifier = Modifier.weight(1f), color = tint)
        Spacer(Modifier.width(10.dp))
        Text(
            text = stringResource(R.string.timeline_unread_boundary),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.width(10.dp))
        HorizontalDivider(modifier = Modifier.weight(1f), color = tint)
    }
}

@Composable
internal fun EmptyState(kind: EmptyKind) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Bookmark hero uses the Heart polygon (matches the bookmark-active morph token);
        // empty-feed hero uses Flower for a friendly "nothing here yet, but pleasantly";
        // caught-up hero reuses the Flower for a celebratory "all done, take a break".
        val (symbol, shape) = when (kind) {
            EmptyKind.Saved -> "bookmark" to dev.lyo.hortay.ui.theme.HortayExpressive.BookmarkSelected
            EmptyKind.CaughtUp -> "check_box" to dev.lyo.hortay.ui.theme.HortayExpressive.EmptyStateMask
            EmptyKind.Default -> "forum" to dev.lyo.hortay.ui.theme.HortayExpressive.EmptyStateMask
        }
        ExpressiveEmptyHero(symbol = symbol, shape = shape)
        Spacer(Modifier.height(20.dp))
        val titleRes = when (kind) {
            EmptyKind.Saved -> R.string.timeline_empty_saved_title
            EmptyKind.CaughtUp -> R.string.timeline_empty_caught_up_title
            EmptyKind.Default -> R.string.timeline_empty_default_title
        }
        val helperRes = when (kind) {
            EmptyKind.Saved -> R.string.timeline_empty_saved_helper
            EmptyKind.CaughtUp -> R.string.timeline_empty_caught_up_helper
            EmptyKind.Default -> R.string.timeline_empty_default_helper
        }
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(helperRes),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        // Default empty (no subscriptions): turn the dead-end into a conversion
        // point by launching the official Telegram client. Subscriptions made
        // there land in Hortay via the existing UpdateNewChat stream — no
        // explicit refresh needed. Saved + CaughtUp don't add a CTA: Saved
        // already explains the action ("tap the bookmark"), and CaughtUp is a
        // feel-good "all done" moment that a CTA would cheapen.
        if (kind == EmptyKind.Default) {
            Spacer(Modifier.height(20.dp))
            val context = LocalContext.current
            Button(onClick = { openTelegramApp(context) }) {
                Text(text = stringResource(R.string.empty_action_open_telegram))
            }
        }
    }
}

/**
 * Expressive empty-state hero badge: a 96 dp polygon-masked tile in `secondaryContainer`
 * with a 40 dp glyph centred inside. The polygon (Flower / Heart / Cookie7) is the
 * single largest expressive form on screen at this moment, which is the canonical use
 * Google designers reach for in the M3 Expressive guidelines: hero for personality,
 * dense surfaces stay calm. Pre-allocates the Compose Shape via `asComposeShape` so
 * recompositions don't allocate a fresh PolygonShape per frame.
 */
@Composable
internal fun ExpressiveEmptyHero(
    symbol: String,
    shape: androidx.graphics.shapes.RoundedPolygon,
) {
    val composeShape = shape.asComposeShape()
    // No clip — Flower / Heart / Cookie polygons have dips that would slice the
    // central glyph. Painting the polygon as the backdrop only keeps the icon
    // fully visible while the silhouette reads as the expressive shape.
    Box(
        modifier = Modifier
            .size(96.dp)
            .background(MaterialTheme.colorScheme.secondaryContainer, composeShape),
        contentAlignment = Alignment.Center,
    ) {
        Symbol(
            name = symbol,
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            size = 40.dp,
        )
    }
}
