package dev.lyo.hortay.ui.auth

import dev.lyo.hortay.data.Country
import kotlinx.collections.immutable.persistentListOf
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.Locale

class CountryNamesTest {

    private val uk = Locale.forLanguageTag("uk")

    private fun country(iso: String, name: String, custom: Boolean = false) = Country(
        iso = iso,
        name = name,
        dialCode = "+1",
        allDialCodes = persistentListOf(),
        isCustom = custom,
    )

    private val fragment = country("FT", "Анонімні номери")
    private val other = country("??", "Інша країна", custom = true)

    @Test
    fun `a TDLib English name is re-labelled in the app's language`() {
        assertEquals("Україна", localizedCountryName("UA", "Ukraine", uk))
        assertEquals("Ukraine", localizedCountryName("UA", "Ukraine", Locale.ENGLISH))
        assertEquals("Ukraine", localizedCountryName("ua", "Ukraine", Locale.ENGLISH))
    }

    @Test
    fun `codes the platform does not know keep the fallback`() {
        assertEquals("Анонімні номери", localizedCountryName("FT", "Анонімні номери", uk))
        assertEquals("Інша країна", localizedCountryName("??", "Інша країна", uk))
        assertEquals("Somewhere", localizedCountryName("XYZ", "Somewhere", uk))
    }

    @Test
    fun `pinned rows stay on top and the rest sort in the locale's order`() {
        val tdlib = listOf(
            fragment,
            other,
            country("AT", "Austria"),
            country("UA", "Ukraine"),
            country("PL", "Poland"),
        )
        val result = localizeCountries(tdlib, uk)
        assertEquals(listOf("FT", "??"), result.take(2).map { it.iso })
        assertEquals(listOf("Австрія", "Польща", "Україна"), result.drop(2).map { it.name })
    }

    @Test
    fun `the saved pick wins, then the detected country, then Ukraine`() {
        val list = listOf(fragment, other, country("PL", "Poland"), country("UA", "Ukraine"))
        assertEquals("PL", pickCountry(list, savedIso = "pl", detectedIso = "UA")?.iso)
        assertEquals("PL", pickCountry(list, savedIso = null, detectedIso = "PL")?.iso)
        assertEquals("UA", pickCountry(list, savedIso = null, detectedIso = null)?.iso)
        assertEquals("UA", pickCountry(list, savedIso = "DE", detectedIso = "FR")?.iso)
        assertEquals("FT", pickCountry(listOf(fragment), savedIso = null, detectedIso = null)?.iso)
        assertNull(pickCountry(emptyList(), savedIso = "UA", detectedIso = "UA"))
    }
}
