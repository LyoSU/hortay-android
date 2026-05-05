package dev.lyo.hortay.data.web

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Persists the state of the guest→TDLib subscription migration proposal so it
 * doesn't re-show every time the user reaches [AuthStage.Ready].
 *
 * Two pieces of state:
 *   - [proposalShown] — bumped once when the proposal sheet has been displayed
 *     to the user. The sheet renders then dismisses; a "skip" still counts as
 *     shown. We don't reopen on every auth round-trip.
 *   - [migratedUsernames] — the bare-username set the user actually approved
 *     for migration. Used to skip them on a future re-migration (rare, e.g.
 *     after re-installing the app and re-adding a few usernames in guest mode
 *     before signing in).
 *
 * Why a separate store from [SubscriptionsStore]: that store tracks user
 * intent to read; this one tracks per-account migration history. Keeping
 * them apart means a sign-out doesn't accidentally erase migration history.
 */
class MigrationStore(context: Context) {

    private val ds = context.applicationContext.migrationDataStore

    val proposalShown: Flow<Boolean> = ds.data.map { it[KEY_PROPOSAL_SHOWN] ?: false }

    suspend fun isProposalShown(): Boolean = proposalShown.first()

    suspend fun markProposalShown() {
        ds.edit { it[KEY_PROPOSAL_SHOWN] = true }
    }

    val migratedUsernames: Flow<Set<String>> = ds.data.map { it[KEY_MIGRATED] ?: emptySet() }

    suspend fun addMigrated(usernames: Iterable<String>) {
        val incoming = usernames.toSet()
        if (incoming.isEmpty()) return
        ds.edit { prefs ->
            val current = prefs[KEY_MIGRATED] ?: emptySet()
            prefs[KEY_MIGRATED] = current + incoming
        }
    }

    /** Used by the "clear all data" debug entry so the proposal can re-run. */
    suspend fun reset() {
        ds.edit { it.clear() }
    }

    private companion object {
        val KEY_PROPOSAL_SHOWN = booleanPreferencesKey("guest_migration_proposal_shown")
        val KEY_MIGRATED = stringSetPreferencesKey("guest_migration_migrated_usernames")
    }
}

private val Context.migrationDataStore by preferencesDataStore(name = "guest_migration")
