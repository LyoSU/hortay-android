package dev.lyo.hortay.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class ThemeMode { System, Light, Dark }

/**
 * How the merged feed is ordered. `Newest` is the canonical Twitter / Telegram
 * top-down chronology — newest posts at the top, scroll DOWN for older.
 * `OldestUnreadFirst` is the reverse-feed / chat-app idiom — strict ascending
 * by date, so OLDEST posts on top and NEWEST at the bottom; scrolling DOWN
 * advances forward in time. On cold start the screen lands the user at the
 * first unread post (= where to resume reading) when there's a backlog,
 * otherwise at the bottom (= newest). The toggle is a global preference the
 * user picks once in Settings; switching mid-session reshuffles the feed
 * and TimelineScreen scrolls back to the home target so the new order has
 * a clear starting point. Default is [OldestUnreadFirst] — matches the
 * Telegram-channel reading idiom (open at the unread boundary, read forward
 * in time). Users who explicitly picked a value in Settings keep it; the
 * fallback applies only when the DataStore key is unset.
 */
enum class FeedOrder { Newest, OldestUnreadFirst }

/**
 * User preferences persisted across launches: theme, notifications opt-in, etc.
 * Backed by Preferences DataStore for async, blocking-free reads/writes.
 */
class SettingsStore(context: Context) {

    private val dataStore = context.applicationContext.settingsDataStore

    val themeMode: Flow<ThemeMode> = dataStore.data.map { prefs ->
        prefs[KEY_THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.System
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[KEY_THEME] = mode.name }
    }

    val feedOrder: Flow<FeedOrder> = dataStore.data.map { prefs ->
        prefs[KEY_FEED_ORDER]?.let { runCatching { FeedOrder.valueOf(it) }.getOrNull() } ?: FeedOrder.OldestUnreadFirst
    }

    suspend fun setFeedOrder(order: FeedOrder) {
        dataStore.edit { it[KEY_FEED_ORDER] = order.name }
    }

    /**
     * Reels-style snap-fling toggle. When enabled, fling gestures on the
     * timeline LazyColumn settle on the nearest item boundary — each post
     * "clicks into place" at the top of the viewport instead of free-coasting
     * past. Implementation uses `rememberSnapFlingBehavior(SnapPosition.Start)`,
     * so drag scrolling continues to work naturally (free movement until the
     * user releases). Default off — opt-in via Settings.
     *
     * Applies to BOTH FeedOrder.Newest and FeedOrder.OldestUnreadFirst — the
     * snap is a presentation mode independent of ordering semantics.
     */
    val snapScroll: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_SNAP_SCROLL] ?: false
    }

    suspend fun setSnapScroll(enabled: Boolean) {
        dataStore.edit { it[KEY_SNAP_SCROLL] = enabled }
    }

    /**
     * Silent inline autoplay of short videos in the feed. Only videos already on
     * disk (TDLib mode: [MediaState.Ready], i.e. fetched by the user's configured
     * [AutoDownloadStore] policy) auto-play; non-cached posts keep the static
     * poster + play-badge until tapped. The toggle is the on/off switch around
     * that whole behaviour — set to false and even cached short videos stay
     * still until the user opens them.
     *
     * Web (anonymous) mode has no on-disk cache concept, so the toggle simply
     * gates whether ExoPlayer streams inline from the t.me/s/ URL or waits for
     * an explicit tap.
     *
     * Default true — matches the existing shipped behaviour ("short videos
     * autoplay silently") for the common case where auto-download keeps the
     * cache full of recent clips.
     */
    val inlineVideoAutoplay: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_INLINE_VIDEO_AUTOPLAY] ?: true
    }

    suspend fun setInlineVideoAutoplay(enabled: Boolean) {
        dataStore.edit { it[KEY_INLINE_VIDEO_AUTOPLAY] = enabled }
    }

    /**
     * Epoch-ms of the last successful TDLib [TdApi.OptimizeStorage] sweep. Read by
     * [TdClient] to throttle the cleanup to once per [STORAGE_OPTIMIZE_INTERVAL_MS] —
     * scanning a multi-GB tdlib-files directory on every cold start used to add real
     * latency to the splash → feed handoff.
     */
    suspend fun lastStorageOptimizeAt(): Long =
        dataStore.data.first()[KEY_LAST_STORAGE_OPTIMIZE_AT] ?: 0L

    suspend fun setLastStorageOptimizeAt(epochMs: Long) {
        dataStore.edit { it[KEY_LAST_STORAGE_OPTIMIZE_AT] = epochMs }
    }

    private companion object {
        val KEY_THEME = stringPreferencesKey("theme_mode")
        val KEY_LAST_STORAGE_OPTIMIZE_AT = longPreferencesKey("last_storage_optimize_at")
        val KEY_FEED_ORDER = stringPreferencesKey("feed_order")
        val KEY_SNAP_SCROLL = booleanPreferencesKey("snap_scroll")
        val KEY_INLINE_VIDEO_AUTOPLAY = booleanPreferencesKey("inline_video_autoplay")
    }
}

private val Context.settingsDataStore by preferencesDataStore(name = "settings")
