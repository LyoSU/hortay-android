package dev.lyo.hortay.data

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Covers the stored-value mapping and pure resolution rules behind Settings → Appearance / Reading. */
class AppearancePrefsTest {

    @Test
    fun `theme mode round-trips through its stored name`() {
        ThemeMode.entries.forEach { assertEquals(it, ThemeMode.fromStored(it.name)) }
    }

    @Test
    fun `missing or unknown theme mode falls back to System`() {
        assertEquals(ThemeMode.System, ThemeMode.fromStored(null))
        assertEquals(ThemeMode.System, ThemeMode.fromStored("Sepia"))
        assertEquals(ThemeMode.System, ThemeMode.fromStored("dark"), "stored names are case-sensitive")
    }

    @Test
    fun `only System consults the platform night mode`() {
        assertTrue(ThemeMode.System.isDark(systemDark = true))
        assertFalse(ThemeMode.System.isDark(systemDark = false))
        assertFalse(ThemeMode.Light.isDark(systemDark = true))
        assertTrue(ThemeMode.Dark.isDark(systemDark = false))
    }

    @Test
    fun `pure black is offered only for modes that can render dark`() {
        assertTrue(ThemeMode.System.canBeDark)
        assertTrue(ThemeMode.Dark.canBeDark)
        assertFalse(ThemeMode.Light.canBeDark)
    }

    @Test
    fun `text size round-trips and falls back to Default`() {
        ReadingTextSize.entries.forEach { assertEquals(it, ReadingTextSize.fromStored(it.name)) }
        assertEquals(ReadingTextSize.Default, ReadingTextSize.fromStored(null))
        assertEquals(ReadingTextSize.Default, ReadingTextSize.fromStored("Huge"))
    }

    @Test
    fun `text size steps grow monotonically around an identity default`() {
        assertEquals(1f, ReadingTextSize.Default.scale)
        val scales = ReadingTextSize.entries.map { it.scale }
        assertEquals(scales.sorted(), scales, "entries are declared smallest to largest")
        assertEquals(scales.size, scales.toSet().size, "every step is distinct")
    }

    @Test
    fun `default prefs reproduce the pre-setting behaviour`() {
        val prefs = AppearancePrefs()
        assertEquals(ThemeMode.System, prefs.themeMode)
        assertFalse(prefs.pureBlack)
        assertEquals(ReadingTextSize.Default, prefs.textSize)
    }
}
