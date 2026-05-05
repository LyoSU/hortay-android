package dev.lyo.hortay.data.web

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Persists the user's choice to use the app *without* Telegram authentication.
 *
 * State machine:
 *   - false (default) — first launch, OR user has signed in / requested sign-in.
 *     [MainActivity] routes auth.Ready → TDLib UI, anything else → [AuthScreen].
 *   - true — user explicitly tapped "Continue without sign-in" on the auth landing.
 *     [MainActivity] routes through to [WebTimelineScreen] regardless of TDLib's
 *     auth state. The user can flip back from the in-app settings.
 *
 * Why a separate store from [SettingsStore]: SettingsStore concerns itself with
 * TDLib-mode preferences (mute list, font scale, etc). The guest-mode flag has
 * a higher-priority lifecycle — read on every cold start *before* any TDLib
 * call, including `tdClient.start()`. Sharing a DataStore would couple read
 * paths that need to be independent for performance.
 *
 * Privacy note: this flag is local-only. Nothing about it is sent to Telegram.
 * Even when set to true, the app never registers the device with Telegram in
 * any form — no MTProto session, no `RegisterDevice` call.
 */
class GuestModeStore(context: Context) {

    private val dataStore = context.applicationContext.guestModeDataStore

    val isGuest: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[KEY_GUEST_MODE] ?: false
    }

    suspend fun current(): Boolean = isGuest.first()

    suspend fun setGuest(enabled: Boolean) {
        dataStore.edit { prefs -> prefs[KEY_GUEST_MODE] = enabled }
    }

    private companion object {
        val KEY_GUEST_MODE = booleanPreferencesKey("guest_mode")
    }
}

private val Context.guestModeDataStore by preferencesDataStore(name = "guest_mode")
