package dev.lyo.hortay.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class ThemeMode { System, Light, Dark }

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
    }
}

private val Context.settingsDataStore by preferencesDataStore(name = "settings")
