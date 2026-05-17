package dev.lyo.hortay.data

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

/**
 * Per-app language override that bridges Hortay's Compose-only, ComponentActivity-based
 * single-Activity setup to Android's per-app language picker.
 *
 * Why not [androidx.appcompat.app.AppCompatDelegate.setApplicationLocales]: that helper
 * is a no-op when no [androidx.appcompat.app.AppCompatActivity] has been created in the
 * process — both its pre-T and post-T branches dispatch through the internal
 * `sActivityDelegates` set, which `AppCompatActivity` populates and `ComponentActivity`
 * does not. The symptom (reported by users): pick a language, dialog dismisses, nothing
 * else happens. CLAUDE.md pins MainActivity to ComponentActivity, so we roll our own
 * thin bridge instead of dragging the AppCompat theme parent into the project.
 *
 * API 33+: the platform [LocaleManager] is the single source of truth — the system
 * Settings → Apps → Hortay → Language picker writes here too, and the system recreates
 * the activity stack on change. We do not store anything ourselves on this path.
 *
 * API 26-32: persist the BCP-47 tag in [Context.getSharedPreferences] (synchronous read
 * is mandatory because [wrap] runs inside [android.app.Activity.attachBaseContext], well
 * before any DataStore coroutine could complete). [wrap] folds the stored tag into a
 * [Configuration] via [Context.createConfigurationContext] before the resource lookup
 * chain sees the activity / application context; callers issue [android.app.Activity.recreate]
 * after [write] so the new wrapper takes effect.
 *
 * The locales offered must stay aligned with `res/xml/locales_config.xml`.
 */
object LocaleStore {

    private const val PREFS = "hortay_locale"
    private const val KEY_TAG = "app_locale_tag"

    /** `null` = "follow system" / "App default". */
    fun read(context: Context): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val lm = context.getSystemService(LocaleManager::class.java) ?: return null
            val locales = lm.applicationLocales
            return if (locales.isEmpty) null else locales[0].language
        }
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TAG, null)
    }

    /**
     * Persist the choice. On API 33+ the platform recreates the activity stack; on
     * older API levels the caller must call [android.app.Activity.recreate] so the
     * [attachBaseContext] [wrap] picks the new tag up.
     */
    fun write(context: Context, tag: String?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val lm = context.getSystemService(LocaleManager::class.java) ?: return
            lm.applicationLocales = if (tag.isNullOrEmpty()) {
                LocaleList.getEmptyLocaleList()
            } else {
                LocaleList.forLanguageTags(tag)
            }
            return
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (tag.isNullOrEmpty()) remove(KEY_TAG) else putString(KEY_TAG, tag)
            apply()
        }
    }

    /**
     * Wrap a base [Context] with the persisted locale. No-op on API 33+ (LocaleManager
     * already applied the override before [attachBaseContext] fires) and when the user
     * picked "follow system".
     */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = read(base) ?: return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        }
        return base.createConfigurationContext(config)
    }
}
