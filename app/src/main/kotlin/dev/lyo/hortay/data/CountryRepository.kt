package dev.lyo.hortay.data

import dev.lyo.hortay.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.drinkless.tdlib.TdApi

/**
 * One country row as the picker sees it. The TDLib payload (`TdApi.CountryInfo`) carries
 * an array of dialing codes per country (the US/Caribbean NANP cluster shares "+1" with a
 * slew of area-code-specific entries) — we keep the first as the display dial code which
 * matches what official Telegram does, but the whole array is preserved so a future
 * `GetPhoneNumberInfo` lookup can identify every variant.
 */
data class Country(
    val iso: String,
    val name: String,
    val dialCode: String,
    val flag: String,
    val allDialCodes: List<String>,
    /**
     * True for the synthetic "Інша країна" row — selecting it unlocks the dial-code field
     * for free typing in [PhoneForm], so a user with an exotic carrier code can punch in
     * whatever they need without us shipping the entire E.164 plan in resources.
     */
    val isCustom: Boolean = false,
)

/**
 * Telegram's Fragment platform issues anonymous numbers under the `+888` reserved prefix
 * (ITU's "global services" range). They aren't a country, so [TdApi.GetCountries] doesn't
 * return them — every official Telegram client pins them at the top of the picker as a
 * dedicated entry. We do the same; the pirate flag is a friendly nod to the user's own
 * phrasing ("піратські") and matches what Fragment-bought numbers actually feel like.
 *
 * Names are resolved through [Resources] so the picker label tracks the active locale.
 */
internal fun fragmentAnonymousNumbers(res: StringResolver): Country = Country(
    iso = "FT",
    name = res.getString(R.string.country_anonymous_numbers),
    dialCode = "+888",
    flag = "🏴‍☠️",
    allDialCodes = listOf("888"),
)

internal fun customCountryEntry(res: StringResolver): Country = Country(
    iso = "??",
    name = res.getString(R.string.country_other),
    dialCode = "+",
    flag = "🌐",
    allDialCodes = emptyList(),
    isCustom = true,
)

/**
 * Backed by TDLib's [TdApi.GetCountries] (localized to systemLanguageCode = "uk", so names
 * arrive already translated) and [TdApi.GetCountryCode] (carrier/IP-based ISO guess for
 * the initial selection). Falls back to Ukraine when detection fails — the realistic
 * audience of this app is UA-first, and a sensible default is better than a blank header.
 *
 * Lazy on purpose: the AuthScreen calls [load] when it composes the phone form, so during
 * the splash → auth handoff we don't fire unnecessary RPCs while TDLib is still bringing
 * up its DC connection.
 */
class CountryRepository(private val sender: TdSender, private val res: StringResolver) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _countries = MutableStateFlow<List<Country>>(emptyList())
    val countries: StateFlow<List<Country>> = _countries.asStateFlow()

    private val _detectedIso = MutableStateFlow<String?>(null)
    val detectedIso: StateFlow<String?> = _detectedIso.asStateFlow()

    @Volatile private var loaded = false
    private val loadMutex = Mutex()

    /**
     * Lazily fetch the country catalogue + carrier-detected ISO. Idempotent: safe to call
     * from each [LaunchedEffect] that mounts a phone form.
     *
     * Resilience to pre-auth races: TDLib rejects [TdApi.GetCountries] with `[400]
     * Initialization parameters are needed` while it is still in
     * [TdApi.AuthorizationStateWaitTdlibParameters]. AuthScreen calls [load] from a
     * `LaunchedEffect(Unit)` which only fires once per composition — if that single call
     * lost the race, the picker would stay empty for the whole session. We retry with
     * linear back-off until [TdApi.GetCountries] succeeds, capping at [MAX_ATTEMPTS]
     * tries so a real failure (no network at all) doesn't loop forever. The `loaded`
     * flag is only set on success, so the next call into [load] kicks the retry chain
     * back off.
     */
    fun load() {
        if (loaded) return
        scope.launch {
            loadMutex.withLock {
                if (loaded) return@withLock
                for (attempt in 0 until MAX_ATTEMPTS) {
                    val res = runCatching { sender.send(TdApi.GetCountries()) }
                    val payload = res.getOrNull()
                    if (payload != null) {
                        _countries.value = payload.countries.toCountries()
                        loaded = true
                        break
                    }
                    delay(BACKOFF_BASE_MS * (attempt + 1))
                }
                if (loaded) {
                    runCatching { sender.send(TdApi.GetCountryCode()) }
                        .onSuccess { _detectedIso.value = it.text.takeIf(String::isNotEmpty) }
                }
            }
        }
    }

    private fun Array<TdApi.CountryInfo>.toCountries(): List<Country> {
        val real = this
            .filterNot { it.isHidden }
            .mapNotNull { info ->
                val dial = info.callingCodes.firstOrNull()?.takeIf(String::isNotEmpty)
                    ?: return@mapNotNull null
                Country(
                    iso = info.countryCode,
                    name = info.name.ifEmpty { info.englishName },
                    dialCode = "+$dial",
                    flag = isoToFlagEmoji(info.countryCode),
                    allDialCodes = info.callingCodes.toList(),
                )
            }
            .sortedBy { it.name.lowercase() }
        // Pinned entries surface above the alphabetic list — the bottom-sheet renders the
        // list verbatim and a header divider in CountryPickerSheet visually separates the
        // pinned block from the rest.
        return listOf(fragmentAnonymousNumbers(res), customCountryEntry(res)) + real
    }

    private companion object {
        // 5 attempts × linear back-off (300, 600, 900, 1200, 1500 ms) covers the entire
        // expected window of TDLib bringing up its session — usually <1s on a warm DB,
        // up to ~3s on a cold start with a slow filesystem. Past that, TDLib has bigger
        // problems than country names.
        const val MAX_ATTEMPTS = 5
        const val BACKOFF_BASE_MS = 300L
    }
}

/**
 * Convert an ISO-3166-1 alpha-2 country code ("UA") into its regional-indicator flag
 * emoji ("🇺🇦"). Each letter maps to U+1F1E6 + (letter - 'A'); two of them rendered side
 * by side become the flag glyph on every modern Android. No image assets needed.
 */
internal fun isoToFlagEmoji(iso: String): String {
    if (iso.length != 2) return ""
    val a = iso[0].uppercaseChar()
    val b = iso[1].uppercaseChar()
    if (a !in 'A'..'Z' || b !in 'A'..'Z') return ""
    val base = 0x1F1E6
    val first = base + (a - 'A')
    val second = base + (b - 'A')
    return String(Character.toChars(first)) + String(Character.toChars(second))
}
