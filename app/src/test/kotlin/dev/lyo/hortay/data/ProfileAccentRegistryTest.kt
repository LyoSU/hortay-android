package dev.lyo.hortay.data

import org.drinkless.tdlib.TdApi
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProfileAccentRegistryTest {

    private fun colors(palette: IntArray, background: IntArray, story: IntArray) =
        TdApi.ProfileAccentColors(palette, background, story)

    private fun accent(
        id: Int,
        light: TdApi.ProfileAccentColors,
        dark: TdApi.ProfileAccentColors,
    ) = TdApi.ProfileAccentColor(id, light, dark, 0, 0)

    @Test
    fun `backgroundArgb picks light variant and forces opaque alpha`() {
        val c = accent(
            id = 5,
            light = colors(intArrayOf(0x112233), intArrayOf(0x112233, 0x445566), intArrayOf(0x778899, 0xAABBCC)),
            dark = colors(intArrayOf(0x010101), intArrayOf(0x020202), intArrayOf(0x030303, 0x040404)),
        )
        assertArrayEquals(
            intArrayOf(0xFF112233.toInt(), 0xFF445566.toInt()),
            c.backgroundArgb(dark = false),
        )
    }

    @Test
    fun `backgroundArgb picks dark variant when dark`() {
        val c = accent(
            id = 5,
            light = colors(intArrayOf(0x112233), intArrayOf(0x112233), intArrayOf(0x778899, 0xAABBCC)),
            dark = colors(intArrayOf(0x010101), intArrayOf(0x222222, 0x333333), intArrayOf(0x030303, 0x040404)),
        )
        assertArrayEquals(
            intArrayOf(0xFF222222.toInt(), 0xFF333333.toInt()),
            c.backgroundArgb(dark = true),
        )
    }

    @Test
    fun `ringArgb prefers storyColors`() {
        val c = accent(
            id = 5,
            light = colors(intArrayOf(0x112233), intArrayOf(0x112233), intArrayOf(0x778899, 0xAABBCC)),
            dark = colors(intArrayOf(0x010101), intArrayOf(0x020202), intArrayOf(0x030303, 0x040404)),
        )
        assertArrayEquals(
            intArrayOf(0xFF778899.toInt(), 0xFFAABBCC.toInt()),
            c.ringArgb(dark = false),
        )
    }

    @Test
    fun `ringArgb picks dark variant story colours`() {
        val c = accent(
            id = 5,
            light = colors(intArrayOf(0x112233), intArrayOf(0x112233), intArrayOf(0x778899, 0xAABBCC)),
            dark = colors(intArrayOf(0x010101), intArrayOf(0x020202), intArrayOf(0x0A0B0C, 0x0D0E0F)),
        )
        assertArrayEquals(
            intArrayOf(0xFF0A0B0C.toInt(), 0xFF0D0E0F.toInt()),
            c.ringArgb(dark = true),
        )
    }

    @Test
    fun `ringArgb falls back to paletteColors when story empty`() {
        val c = accent(
            id = 5,
            light = colors(intArrayOf(0xABCDEF), intArrayOf(0x112233), intArrayOf()),
            dark = colors(intArrayOf(0x010101), intArrayOf(0x020202), intArrayOf()),
        )
        assertArrayEquals(intArrayOf(0xFFABCDEF.toInt()), c.ringArgb(dark = false))
    }

    @Test
    fun `empty background yields empty array`() {
        val c = accent(
            id = 5,
            light = colors(intArrayOf(), intArrayOf(), intArrayOf()),
            dark = colors(intArrayOf(), intArrayOf(), intArrayOf()),
        )
        assertTrue(c.backgroundArgb(dark = false).isEmpty())
    }

    @Test
    fun `resolver returns null for unset id -1`() {
        val reg = ProfileAccentRegistry()
        reg.ingest(arrayOf(accent(5,
            colors(intArrayOf(), intArrayOf(0x112233), intArrayOf()),
            colors(intArrayOf(), intArrayOf(0x112233), intArrayOf()))))
        assertNull(reg.backgroundArgb(accentId = -1, dark = false))
    }

    @Test
    fun `resolver returns null on map miss`() {
        val reg = ProfileAccentRegistry()
        assertNull(reg.backgroundArgb(accentId = 5, dark = false))
    }

    @Test
    fun `resolver returns colours after ingest`() {
        val reg = ProfileAccentRegistry()
        reg.ingest(arrayOf(accent(5,
            colors(intArrayOf(), intArrayOf(0x112233, 0x445566), intArrayOf()),
            colors(intArrayOf(), intArrayOf(0x112233), intArrayOf()))))
        assertArrayEquals(
            intArrayOf(0xFF112233.toInt(), 0xFF445566.toInt()),
            reg.backgroundArgb(accentId = 5, dark = false),
        )
    }

    @Test
    fun `clear empties the map`() {
        val reg = ProfileAccentRegistry()
        reg.ingest(arrayOf(accent(5,
            colors(intArrayOf(), intArrayOf(0x112233), intArrayOf()),
            colors(intArrayOf(), intArrayOf(0x112233), intArrayOf()))))
        reg.clear()
        assertNull(reg.backgroundArgb(accentId = 5, dark = false))
    }

    @Test
    fun `empty background after ingest resolves to null not empty array`() {
        val reg = ProfileAccentRegistry()
        reg.ingest(arrayOf(accent(5,
            colors(intArrayOf(), intArrayOf(), intArrayOf()),
            colors(intArrayOf(), intArrayOf(), intArrayOf()))))
        assertNull(reg.backgroundArgb(accentId = 5, dark = false))
    }
}
