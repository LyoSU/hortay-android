package dev.lyo.hortay.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * One-shot "have we already run the first-sign-in history backfill for this
 * auth session?" flag. See [dev.lyo.hortay.data.posts.PostsRepository.runFirstSignInBackfill]
 * for the backfill itself.
 *
 * Why a flag and not "did we already see >1 post for top-K channels?": the
 * goal-state is hard to reify (top-K shifts as chat positions move; some
 * channels never have ≥N posts at all). A boolean trades that for the simple
 * invariant "we made the attempt; we will not make another until the user
 * signs out". If the app is killed mid-backfill the next launch retries
 * (GetChatHistory is read-only and idempotent under our
 * [dev.lyo.hortay.data.posts.PostsRepository.ingest] dedupe by message id),
 * at the cost of a few duplicate RPCs.
 *
 * Reset on TDLib `loggedOut` so a fresh sign-in (different account, or same
 * account after logout) triggers the backfill again — that's the cold-start
 * UX we're protecting.
 *
 * Exposed as an interface so unit tests can substitute an in-memory variant
 * without booting a real Android Context / DataStore. The production wiring
 * uses [ColdStartBackfillStoreImpl].
 */
interface ColdStartBackfillStore {
    suspend fun isDone(): Boolean
    suspend fun markDone()
    suspend fun reset()
}

class ColdStartBackfillStoreImpl(context: Context) : ColdStartBackfillStore {

    private val dataStore = context.applicationContext.coldStartBackfillDataStore

    override suspend fun isDone(): Boolean = dataStore.data.map { it[KEY_DONE] ?: false }.first()

    override suspend fun markDone() {
        dataStore.edit { it[KEY_DONE] = true }
    }

    override suspend fun reset() {
        dataStore.edit { it.remove(KEY_DONE) }
    }

    private companion object {
        val KEY_DONE = booleanPreferencesKey("first_sign_in_backfill_done")
    }
}

private val Context.coldStartBackfillDataStore by preferencesDataStore(name = "cold_start_backfill")
