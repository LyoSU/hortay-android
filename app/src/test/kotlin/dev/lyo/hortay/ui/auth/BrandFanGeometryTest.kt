package dev.lyo.hortay.ui.auth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

class BrandFanGeometryTest {

    private val g = BrandFanGeometry
    private val eps = 1e-4f
    private val slots = listOf(g.BACK_SLOT, g.ACCENT_SLOT, g.FRONT_SLOT)

    @Test
    fun `fanned pose is the static logo's pose for every card`() {
        for (card in listOf(g.BACK, g.ACCENT, g.FRONT)) {
            assertEquals(card.x, g.centerX(card, 1f), eps)
            assertEquals(card.y, g.centerY(card, 1f), eps)
            assertEquals(card.degrees, g.rotation(card, 1f), eps)
        }
    }

    @Test
    fun `stacked pose puts every card at the front card's pose`() {
        for (card in listOf(g.BACK, g.ACCENT, g.FRONT)) {
            assertEquals(g.FRONT.x, g.centerX(card, 0f), eps)
            assertEquals(g.FRONT.y, g.centerY(card, 0f), eps)
            assertEquals(g.FRONT.degrees, g.rotation(card, 0f), eps)
        }
    }

    @Test
    fun `half way is the midpoint between stack and fan`() {
        assertEquals((g.FRONT.degrees + g.BACK.degrees) / 2f, g.rotation(g.BACK, 0.5f), eps)
        assertEquals((g.FRONT.x + g.ACCENT.x) / 2f, g.centerX(g.ACCENT, 0.5f), eps)
    }

    @Test
    fun `leaf-through starts at each card's own slot and ends at the next one`() {
        for (slot in slots) {
            val from = g.pose(slot)
            val to = g.pose(g.nextSlot(slot))
            assertEquals(from.x, g.leafX(slot, 0f), eps)
            assertEquals(from.y, g.leafY(slot, 0f), eps)
            assertEquals(from.degrees, g.leafDegrees(slot, 0f), eps)
            assertEquals(to.x, g.leafX(slot, 1f), eps)
            assertEquals(to.y, g.leafY(slot, 1f), eps)
            assertEquals(to.degrees, g.leafDegrees(slot, 1f), eps)
        }
    }

    @Test
    fun `slots cycle front to back, back to accent, accent to front`() {
        assertEquals(g.ACCENT_SLOT, g.nextSlot(g.BACK_SLOT))
        assertEquals(g.FRONT_SLOT, g.nextSlot(g.ACCENT_SLOT))
        assertEquals(g.BACK_SLOT, g.nextSlot(g.FRONT_SLOT))
    }

    @Test
    fun `the departing card is invisible when it changes layer and colour`() {
        assertEquals(0f, g.leafAlpha(g.FRONT_SLOT, 0.5f), eps)
        assertTrue(g.departingOnTop(0.49f))
        assertFalse(g.departingOnTop(0.5f))
        assertEquals(g.OUT.x, g.leafX(g.FRONT_SLOT, 0.5f), eps)
        assertEquals(1f - g.DEPART_APEX_SHRINK, g.leafScale(g.FRONT_SLOT, 0.5f), eps)
        // Full size and opacity at both ends; the other cards never fade or shrink.
        for (p in listOf(0f, 1f)) {
            assertEquals(1f, g.leafAlpha(g.FRONT_SLOT, p), eps)
            assertEquals(1f, g.leafScale(g.FRONT_SLOT, p), eps)
        }
        for (slot in listOf(g.BACK_SLOT, g.ACCENT_SLOT)) {
            assertEquals(1f, g.leafAlpha(slot, 0.5f), eps)
            assertEquals(1f, g.leafScale(slot, 0.5f), eps)
        }
    }

    @Test
    fun `the floating frame holds the fanned cards at about 58 percent of its height`() {
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (card in listOf(g.BACK, g.ACCENT, g.FRONT)) {
            val rad = Math.toRadians(card.degrees.toDouble())
            val ex = (g.CARD_WIDTH / 2f * abs(cos(rad)) + g.CARD_HEIGHT / 2f * abs(sin(rad))).toFloat()
            val ey = (g.CARD_WIDTH / 2f * abs(sin(rad)) + g.CARD_HEIGHT / 2f * abs(cos(rad))).toFloat()
            minX = minOf(minX, card.x - ex)
            maxX = maxOf(maxX, card.x + ex)
            minY = minOf(minY, card.y - ey)
            maxY = maxOf(maxY, card.y + ey)
        }
        val half = g.FLOAT_FRAME_SIZE / 2f
        assertTrue(minX >= g.FLOAT_FRAME_CENTER_X - half && maxX <= g.FLOAT_FRAME_CENTER_X + half)
        assertTrue(minY >= g.FLOAT_FRAME_CENTER_Y - half && maxY <= g.FLOAT_FRAME_CENTER_Y + half)
        val share = (maxY - minY) / g.FLOAT_FRAME_SIZE
        assertTrue(share in 0.55f..0.6f, "cards fill $share of the frame height")
        // Centred on the cluster, within a unit.
        assertEquals((minX + maxX) / 2f, g.FLOAT_FRAME_CENTER_X, 1f)
        assertEquals((minY + maxY) / 2f, g.FLOAT_FRAME_CENTER_Y, 1f)
    }

    @Test
    fun `the front look passes from the departing card to the arriving one`() {
        assertEquals(1f, g.frontWeight(g.FRONT_SLOT, 0f), eps)
        assertEquals(1f, g.frontWeight(g.FRONT_SLOT, 0.49f), eps)
        assertEquals(0f, g.frontWeight(g.FRONT_SLOT, 0.5f), eps)
        assertEquals(0f, g.frontWeight(g.ACCENT_SLOT, 0f), eps)
        assertEquals(1f, g.frontWeight(g.ACCENT_SLOT, 1f), eps)
        assertEquals(0f, g.frontWeight(g.BACK_SLOT, 0.7f), eps)
        // A spring overshoot past 1 never pushes the blend past full.
        assertEquals(1f, g.lookProgress(g.ACCENT_SLOT, 1.08f), eps)
    }

    @Test
    fun `the end of a leaf-through draws the same frame as its start`() {
        // Card that ends in slot s matches the card that started there: same pose, and
        // its look has fully become that slot's.
        for (slot in slots) {
            val next = g.nextSlot(slot)
            assertEquals(g.pose(next).x, g.leafX(slot, 1f), eps)
            assertEquals(1f, g.lookProgress(slot, 1f), eps)
            assertEquals(0f, g.lookProgress(slot, 0f), eps)
        }
    }

    @Test
    fun `the post silhouette fits inside the front card`() {
        val halfW = g.CARD_WIDTH / 2f
        val halfH = g.CARD_HEIGHT / 2f
        val top = -halfH + g.POST_PADDING
        val avatarBottom = top + g.POST_AVATAR_RADIUS * 2f
        assertTrue(avatarBottom < g.POST_PICTURE_TOP)
        assertTrue(g.POST_PICTURE_BOTTOM < g.POST_TEXT_LINE_1 - g.POST_LINE_HEIGHT / 2f)
        assertTrue(g.POST_TEXT_LINE_2 + g.POST_LINE_HEIGHT / 2f < halfH - g.POST_PADDING)
        val nameRight = -halfW + g.POST_PADDING + g.POST_AVATAR_RADIUS * 2f + g.POST_LINE_HEIGHT + g.POST_NAME_WIDTH
        assertTrue(nameRight < halfW - g.POST_PADDING)
    }

    @Test
    fun `the visible window is the central square of the adaptive canvas`() {
        assertEquals(18f, g.VISIBLE_INSET, eps)
        assertEquals(g.CANVAS, g.VISIBLE_INSET * 2 + g.VISIBLE_SIZE, eps)
    }
}
