package dev.lyo.hortay.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Per-network media auto-download policy. Mirrors Telegram's "Data and Storage" model:
 * three independent profiles keyed by the active network class, each profile a small
 * tuple of toggles + a video size cap. Photos are tiny enough that there is no per-size
 * gate; videos and animations honour [videoMaxBytes] / [animationMaxBytes] respectively.
 *
 * Defaults follow Telegram-Android's shipping values: liberal on Wi-Fi, conservative on
 * mobile, off on roaming. Users that change them are persisted byte-for-byte across
 * launches via [AutoDownloadStore].
 */
@Serializable
data class AutoDownloadPolicy(
    val photos: Boolean,
    val videos: Boolean,
    val videoMaxBytes: Long,
    val animations: Boolean,
    val animationMaxBytes: Long,
) {
    companion object {
        // Round numbers chosen to match what Telegram-Android's slider snaps to (the
        // labels are 1/2/5/10/30/50/100/200/300/500 MB), so a returning Telegram user
        // sees familiar buckets. We expose the same set in [VIDEO_SIZE_STEPS] for the UI.
        const val DEFAULT_VIDEO_MAX_WIFI: Long = 100L * 1024 * 1024     // 100 MB
        const val DEFAULT_VIDEO_MAX_MOBILE: Long = 10L * 1024 * 1024    // 10 MB
        const val DEFAULT_ANIMATION_MAX: Long = 10L * 1024 * 1024       // 10 MB

        val DEFAULT_WIFI = AutoDownloadPolicy(
            photos = true,
            videos = true,
            videoMaxBytes = DEFAULT_VIDEO_MAX_WIFI,
            animations = true,
            animationMaxBytes = DEFAULT_ANIMATION_MAX,
        )
        val DEFAULT_MOBILE = AutoDownloadPolicy(
            photos = true,
            videos = true,
            videoMaxBytes = DEFAULT_VIDEO_MAX_MOBILE,
            animations = true,
            animationMaxBytes = DEFAULT_ANIMATION_MAX,
        )
        val DEFAULT_ROAMING = AutoDownloadPolicy(
            photos = false,
            videos = false,
            videoMaxBytes = 0L,
            animations = false,
            animationMaxBytes = 0L,
        )

        /**
         * Slider snap points in bytes. Single-source-of-truth — the UI binds the
         * Slider's `steps` parameter to `size - 2` and emits the value at the closest
         * step on release. Mirrors Telegram-Android's bucket set so the experience is
         * intuitive for any returning Telegram user.
         */
        val VIDEO_SIZE_STEPS: List<Long> = listOf(
            1L, 2L, 5L, 10L, 30L, 50L, 100L, 200L, 300L, 500L,
        ).map { it * 1024 * 1024 }
    }
}

/**
 * Three-network bundle. The active profile is resolved at runtime from
 * [TdLifecycleBridge.networkType]; this class only stores the user's choices and
 * doesn't know about the device's current network.
 */
@Serializable
data class AutoDownloadSettings(
    val onWifi: AutoDownloadPolicy = AutoDownloadPolicy.DEFAULT_WIFI,
    val onMobile: AutoDownloadPolicy = AutoDownloadPolicy.DEFAULT_MOBILE,
    val onRoaming: AutoDownloadPolicy = AutoDownloadPolicy.DEFAULT_ROAMING,
) {
    companion object {
        val DEFAULT = AutoDownloadSettings()
    }
}

/** Three buckets of an [AutoDownloadSettings] addressable as a typed key. */
enum class AutoDownloadCategory { Wifi, Mobile, Roaming }

fun AutoDownloadSettings.policy(category: AutoDownloadCategory): AutoDownloadPolicy = when (category) {
    AutoDownloadCategory.Wifi -> onWifi
    AutoDownloadCategory.Mobile -> onMobile
    AutoDownloadCategory.Roaming -> onRoaming
}

fun AutoDownloadSettings.withPolicy(
    category: AutoDownloadCategory,
    policy: AutoDownloadPolicy,
): AutoDownloadSettings = when (category) {
    AutoDownloadCategory.Wifi -> copy(onWifi = policy)
    AutoDownloadCategory.Mobile -> copy(onMobile = policy)
    AutoDownloadCategory.Roaming -> copy(onRoaming = policy)
}

fun AutoDownloadCategory.defaultPolicy(): AutoDownloadPolicy = when (this) {
    AutoDownloadCategory.Wifi -> AutoDownloadPolicy.DEFAULT_WIFI
    AutoDownloadCategory.Mobile -> AutoDownloadPolicy.DEFAULT_MOBILE
    AutoDownloadCategory.Roaming -> AutoDownloadPolicy.DEFAULT_ROAMING
}

/**
 * DataStore-backed persistence for [AutoDownloadSettings]. Stored as a single JSON blob
 * under one preference key — schema is small (5 booleans + 2 longs × 3 profiles), and
 * a single atomic write means a profile edit can never tear (split between fields)
 * across a process kill.
 *
 * The JSON shape is purposely lenient: missing fields fall back to defaults, so adding
 * a new toggle in a future version doesn't trip up users coming from older builds.
 */
class AutoDownloadStore(context: Context) {

    private val dataStore = context.applicationContext.autoDownloadDataStore
    private val json = Json {
        ignoreUnknownKeys = true
        // Serializing with defaults present keeps the on-disk JSON self-describing —
        // useful when poking the file from `adb shell` to debug a user-reported issue.
        encodeDefaults = true
    }

    val settings: Flow<AutoDownloadSettings> = dataStore.data.map { prefs ->
        prefs[KEY_SETTINGS]?.let { raw ->
            runCatching { json.decodeFromString(AutoDownloadSettings.serializer(), raw) }.getOrNull()
        } ?: AutoDownloadSettings.DEFAULT
    }

    suspend fun update(transform: (AutoDownloadSettings) -> AutoDownloadSettings) {
        dataStore.edit { prefs ->
            val current = prefs[KEY_SETTINGS]?.let { raw ->
                runCatching { json.decodeFromString(AutoDownloadSettings.serializer(), raw) }.getOrNull()
            } ?: AutoDownloadSettings.DEFAULT
            val next = transform(current)
            prefs[KEY_SETTINGS] = json.encodeToString(AutoDownloadSettings.serializer(), next)
        }
    }

    suspend fun resetAll() {
        update { AutoDownloadSettings.DEFAULT }
    }

    private companion object {
        val KEY_SETTINGS = stringPreferencesKey("auto_download_settings_v1")
    }
}

private val Context.autoDownloadDataStore by preferencesDataStore(name = "auto_download")
