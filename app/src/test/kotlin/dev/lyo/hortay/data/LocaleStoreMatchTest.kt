package dev.lyo.hortay.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LocaleStoreMatchTest {

    private val offered = listOf("ar", "en", "id", "pt-BR", "uk")

    @Test
    fun regionalTag_matchesExactEntry() {
        assertEquals("pt-BR", LocaleStore.matchOffered("pt-BR", offered))
        assertEquals("pt-BR", LocaleStore.matchOffered("pt-br", offered))
        assertEquals("pt-BR", LocaleStore.matchOffered("pt_BR", offered))
    }

    @Test
    fun otherRegionOfRegionalEntry_doesNotMatch() {
        assertNull(LocaleStore.matchOffered("pt-PT", offered))
        assertNull(LocaleStore.matchOffered("pt", offered))
    }

    @Test
    fun regionOnRegionlessEntry_fallsBackToLanguage() {
        assertEquals("uk", LocaleStore.matchOffered("uk-UA", offered))
        assertEquals("en", LocaleStore.matchOffered("en-US", offered))
    }

    @Test
    fun legacyIndonesianCode_mapsToId() {
        assertEquals("id", LocaleStore.matchOffered("in", offered))
        assertEquals("id", LocaleStore.matchOffered("id-ID", offered))
    }

    @Test
    fun nullOrUnknown_isNull() {
        assertNull(LocaleStore.matchOffered(null, offered))
        assertNull(LocaleStore.matchOffered("", offered))
        assertNull(LocaleStore.matchOffered("ja", offered))
    }
}
