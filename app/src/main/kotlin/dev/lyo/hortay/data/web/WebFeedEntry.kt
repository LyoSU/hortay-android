package dev.lyo.hortay.data.web

import androidx.compose.runtime.Immutable

/**
 * One row of the rendered web-mode feed. Combines:
 *   - the parsed post payload ([WebPost]) — verbatim from t.me/s/, no DB
 *     state mixed in;
 *   - a minimal channel header for rendering the per-row "channel name +
 *     avatar" without requiring a separate Map<String, WebChannelInfo> lookup
 *     in every Composable;
 *   - per-user state ([isRead], [isBookmarked]) — these live in the database
 *     because they outlast a single fetch result and we don't want to fan
 *     them through every reload of [WebPost].
 *
 * Kept as a separate type from [WebPost] so the parser layer stays free of
 * UI / persistence concerns. Both layers can evolve independently — for
 * example adding a "translation cached" flag to the DB shape doesn't
 * require touching [TmePageParser].
 *
 * `@Immutable` so Compose stability inference treats every field as a
 * value type and skips PostCard recomposition when the entry is unchanged.
 */
@Immutable
data class WebFeedEntry(
    val post: WebPost,
    val channel: WebChannelHeader,
    val isRead: Boolean,
    val isBookmarked: Boolean,
)

/**
 * Minimal channel info needed to render a feed row's header. Strict subset of
 * [WebChannelInfo] so we don't carry the full description / verification flag
 * down a 1000-row LazyColumn — those belong to the channel-detail screen.
 */
@Immutable
data class WebChannelHeader(
    val username: String,
    val title: String,
    val avatarUrl: String?,
)
