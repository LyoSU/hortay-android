package dev.lyo.hortay.data.archive

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.collections.immutable.toPersistentSet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ArchiveSettingsStore(private val context: Context) {

    private val dataStore = context.applicationContext.archiveSettingsDataStore

    val flow: Flow<ArchiveSettings> = dataStore.data.map { prefs ->
        ArchiveSettings(
            enabled = prefs[K_ENABLED] ?: false,
            onboardingSeen = prefs[K_ONBOARDING_SEEN] ?: false,
            retentionDays = prefs[K_RETENTION_DAYS] ?: 30,
            maxRecords = prefs[K_MAX_RECORDS] ?: 5000,
            excludedChats = (prefs[K_EXCLUDED] ?: emptySet())
                .mapNotNull { decode(it) }.toPersistentSet(),
            captureEdits = prefs[K_CAPTURE_EDITS] ?: true,
            captureDeletes = prefs[K_CAPTURE_DELETES] ?: true,
        )
    }

    suspend fun setEnabled(v: Boolean) = update { it[K_ENABLED] = v }
    suspend fun setOnboardingSeen(v: Boolean) = update { it[K_ONBOARDING_SEEN] = v }
    suspend fun setRetentionDays(v: Int) = update { it[K_RETENTION_DAYS] = v }
    suspend fun setMaxRecords(v: Int) = update { it[K_MAX_RECORDS] = v }
    suspend fun setCaptureEdits(v: Boolean) = update { it[K_CAPTURE_EDITS] = v }
    suspend fun setCaptureDeletes(v: Boolean) = update { it[K_CAPTURE_DELETES] = v }
    suspend fun setExcludedChats(refs: Collection<ChatRef>) = update {
        it[K_EXCLUDED] = refs.map(::encode).toSet()
    }

    private suspend fun update(block: (androidx.datastore.preferences.core.MutablePreferences) -> Unit) {
        dataStore.edit(block)
    }

    private fun encode(ref: ChatRef): String = "${ref.kind.name}|${ref.key}"
    private fun decode(raw: String): ChatRef? {
        val parts = raw.split('|', limit = 2)
        if (parts.size != 2) return null
        val kind = runCatching { SourceKind.valueOf(parts[0]) }.getOrNull() ?: return null
        return ChatRef(kind, parts[1])
    }

    private companion object {
        val K_ENABLED = booleanPreferencesKey("enabled")
        val K_ONBOARDING_SEEN = booleanPreferencesKey("onboarding_seen")
        val K_RETENTION_DAYS = intPreferencesKey("retention_days")
        val K_MAX_RECORDS = intPreferencesKey("max_records")
        val K_EXCLUDED = stringSetPreferencesKey("excluded_chats")
        val K_CAPTURE_EDITS = booleanPreferencesKey("capture_edits")
        val K_CAPTURE_DELETES = booleanPreferencesKey("capture_deletes")
    }
}

private val Context.archiveSettingsDataStore by preferencesDataStore(name = "archive_settings")
