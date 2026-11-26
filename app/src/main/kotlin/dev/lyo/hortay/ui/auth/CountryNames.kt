package dev.lyo.hortay.ui.auth

import dev.lyo.hortay.data.Country
import java.text.Collator
import java.util.Locale

/**
 * Country name for [iso] in [locale], from the platform's own locale data. [fallback]
 * (TDLib's English name) is kept when the code is not a region the platform knows, which
 * the platform signals by echoing the code back.
 */
internal fun localizedCountryName(iso: String, fallback: String, locale: Locale): String {
    if (iso.length != 2 || !iso.all { it.isLetter() && it.code < 128 }) return fallback
    val name = Locale("", iso.uppercase(Locale.ROOT)).getDisplayCountry(locale)
    return if (name.isBlank() || name.equals(iso, ignoreCase = true)) fallback else name
}

/**
 * The picker's list re-labelled in [locale] and re-sorted by that locale's collation.
 * The pinned rows (Fragment numbers, "Other country") keep their place at the top and
 * their own string-resource names.
 */
internal fun localizeCountries(countries: List<Country>, locale: Locale): List<Country> {
    val collator = Collator.getInstance(locale)
    val (pinned, real) = countries.partition { it.isPinned }
    return pinned + real
        .map { it.copy(name = localizedCountryName(it.iso, it.name, locale)) }
        .sortedWith(compareBy(collator) { it.name })
}

/**
 * The country the phone form shows: the one the user picked (saved by ISO), else the
 * carrier/IP guess, else Ukraine — this app's audience is Ukraine-first, and a sensible
 * default beats a blank dial-code field — else the first row. Null until the catalogue
 * has loaded.
 */
internal fun pickCountry(countries: List<Country>, savedIso: String?, detectedIso: String?): Country? {
    if (countries.isEmpty()) return null
    fun find(iso: String?): Country? =
        iso?.let { wanted -> countries.firstOrNull { it.iso.equals(wanted, ignoreCase = true) } }
    return find(savedIso) ?: find(detectedIso) ?: find(DEFAULT_ISO) ?: countries.first()
}

private const val DEFAULT_ISO = "UA"

/** Rows that are not a country: Fragment's anonymous numbers and the free-form "Other" row. */
internal val Country.isPinned: Boolean
    get() = isCustom || iso == FRAGMENT_ISO

private const val FRAGMENT_ISO = "FT"
