package dev.lyo.hortay.ui.auth

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.Dp
import dev.lyo.hortay.R
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Geometry of the fan logo, on the launcher icon's 108-unit adaptive canvas. Mirrors
 * `res/drawable/ic_brand_logo.xml` (and `ic_launcher_foreground.xml`): three rounded
 * cards, back to front, each separated from the card above it by a [SEAM]-wide gap.
 *
 * Two framings of the same cards:
 * - [BrandFanStyle.Tile]: the app icon — the central [VISIBLE_SIZE]-unit square (offset by
 *   [VISIBLE_INSET]) over the brand gradient, which also shows through the seams.
 * - [BrandFanStyle.Floating]: the cards alone on the screen background, framed by the
 *   [FLOAT_FRAME_SIZE]-unit square around the fanned cluster, so the cards fill about
 *   58 % of the box height.
 *
 * Two motions share these poses:
 * - Entrance: "stacked" (t = 0) puts every card at the front card's pose, so the back
 *   cards hide behind it, seams included; "fanned" (t = 1) is the static artwork.
 * - Leaf-through, phase p in 0..1: every card moves one slot on. The front card travels
 *   out toward [OUT] in the first half and tucks in behind the others to the back slot in
 *   the second; the accent card comes forward and the back card takes the accent's place
 *   over the whole beat. On its way the departing card fades and shrinks a little, to
 *   nothing at the apex (p = 0.5) — and that is where it changes layer and colour, so
 *   neither swap can be seen, with or without a clipping tile. Colours and the post
 *   belong to the slot, not the card, so p = 1 draws exactly the p = 0 frame and the beat
 *   ends at rest.
 */
internal object BrandFanGeometry {
    const val CANVAS = 108f
    const val VISIBLE_SIZE = 72f
    const val VISIBLE_INSET = (CANVAS - VISIBLE_SIZE) / 2f
    const val CARD_WIDTH = 27.22f
    const val CARD_HEIGHT = 32.32f
    const val CARD_RADIUS = 7.66f
    const val SEAM = 3.0f

    /** Card centres and rotations (degrees, clockwise) in the fanned pose. */
    val BACK = Pose(43.21f, 55.95f, -22f)
    val ACCENT = Pose(52.67f, 53.51f, -7f)
    val FRONT = Pose(63.08f, 53.68f, 9f)

    const val BACK_SLOT = 0
    const val ACCENT_SLOT = 1
    const val FRONT_SLOT = 2

    /**
     * Frame of the floating style: centred on the fanned cluster's bounding box
     * (x 24.5..79.1, y 35.6..76.0), sized so the cluster's 40.4-unit height is ~58 % of it.
     */
    const val FLOAT_FRAME_CENTER_X = 51.8f
    const val FLOAT_FRAME_CENTER_Y = 55.8f
    const val FLOAT_FRAME_SIZE = 70f

    /** Where the departing front card is half way through a leaf-through: up and outward. */
    val OUT = Pose(73f, 46f, 20f)

    /** The departing card fades over this share of each half of the beat, ending at the apex. */
    const val DEPART_FADE_SPAN = 0.5f

    /** How much the departing card has shrunk at the apex. */
    const val DEPART_APEX_SHRINK = 0.12f

    /** Hairline around the front card in the floating style, so white does not vanish on white. */
    const val FRONT_OUTLINE_WIDTH = 0.3f

    /**
     * The post drawn on the front card, in card-local units (origin at the card's centre,
     * unrotated): an avatar dot with a name line beside it, a picture block, two text lines.
     */
    const val POST_PADDING = 3.4f
    const val POST_AVATAR_RADIUS = 2.1f
    const val POST_LINE_HEIGHT = 1.7f
    const val POST_NAME_WIDTH = 9.5f
    const val POST_PICTURE_TOP = -6.9f
    const val POST_PICTURE_BOTTOM = 4.4f
    const val POST_PICTURE_RADIUS = 2.6f
    const val POST_TEXT_LINE_1 = 7.7f
    const val POST_TEXT_LINE_2 = 11.4f
    const val POST_TEXT_LINE_2_FRACTION = 0.62f

    fun centerX(fanned: Pose, t: Float): Float = lerp(FRONT.x, fanned.x, t)
    fun centerY(fanned: Pose, t: Float): Float = lerp(FRONT.y, fanned.y, t)
    fun rotation(fanned: Pose, t: Float): Float = lerp(FRONT.degrees, fanned.degrees, t)

    fun pose(slot: Int): Pose = when (slot) {
        BACK_SLOT -> BACK
        ACCENT_SLOT -> ACCENT
        else -> FRONT
    }

    /** The slot a card at [slot] moves to during a leaf-through. */
    fun nextSlot(slot: Int): Int = (slot + 1) % 3

    fun leafX(slot: Int, p: Float): Float = leaf(slot, p, BACK.x, OUT.x, pose(slot).x, pose(nextSlot(slot)).x)

    fun leafY(slot: Int, p: Float): Float = leaf(slot, p, BACK.y, OUT.y, pose(slot).y, pose(nextSlot(slot)).y)

    fun leafDegrees(slot: Int, p: Float): Float =
        leaf(slot, p, BACK.degrees, OUT.degrees, pose(slot).degrees, pose(nextSlot(slot)).degrees)

    /** Opacity of the card that started in [slot]: only the departing card fades, to 0 at the apex. */
    fun leafAlpha(slot: Int, p: Float): Float =
        if (slot != FRONT_SLOT) 1f else (apexDistance(p) / DEPART_FADE_SPAN).coerceIn(0f, 1f)

    /** Scale of the card that started in [slot]: only the departing card shrinks, most at the apex. */
    fun leafScale(slot: Int, p: Float): Float =
        if (slot != FRONT_SLOT) 1f else 1f - DEPART_APEX_SHRINK * (1f - apexDistance(p).coerceIn(0f, 1f))

    /**
     * How far the card that started in [slot] has taken on the look (colour, post) of the
     * slot it is heading to, at phase [p]. The departing card switches at once at the
     * apex, where it is invisible; the other two blend over the whole beat.
     */
    fun lookProgress(slot: Int, p: Float): Float =
        if (slot == FRONT_SLOT) {
            if (departingOnTop(p)) 0f else 1f
        } else {
            p.coerceIn(0f, 1f)
        }

    /** How much of the front slot's look (post, outline) the card that started in [slot] shows. */
    fun frontWeight(slot: Int, p: Float): Float = when (slot) {
        FRONT_SLOT -> 1f - lookProgress(slot, p)
        ACCENT_SLOT -> lookProgress(slot, p)
        else -> 0f
    }

    /** The departing card is over the others until the apex, then under them. */
    fun departingOnTop(p: Float): Boolean = p < 0.5f

    /** 1 at the start and end of the beat, 0 at its apex. */
    private fun apexDistance(p: Float): Float = abs(2f * p - 1f)

    /**
     * One coordinate of a card at phase [p]: the departing front card goes from its pose
     * out to [OUT] and on to the back pose ([backValue]) in two halves; the others move
     * straight from [from] to [to].
     */
    private fun leaf(slot: Int, p: Float, backValue: Float, outValue: Float, from: Float, to: Float): Float =
        if (slot != FRONT_SLOT) {
            lerp(from, to, p)
        } else if (departingOnTop(p)) {
            lerp(from, outValue, p * 2f)
        } else {
            lerp(outValue, backValue, p * 2f - 1f)
        }

    private fun lerp(from: Float, to: Float, t: Float): Float = from + (to - from) * t

    data class Pose(val x: Float, val y: Float, val degrees: Float)
}

/** How [BrandFanLogo] is framed; see [BrandFanGeometry]. */
enum class BrandFanStyle { Tile, Floating }

/**
 * Animation state of [BrandFanLogo]. Hoisted so the logo can leave and re-enter the
 * composition (step switches on the auth screen) without replaying its entrance.
 *
 * Every value is read in the draw phase only, so an animating fan redraws without
 * recomposing; at rest nothing runs.
 */
@Stable
class BrandFanState(fanned: Boolean) {
    internal val accent = Animatable(if (fanned) 1f else 0f)
    internal val back = Animatable(if (fanned) 1f else 0f)
    internal val leaf = Animatable(0f)

    /** Leaf the stack out into the fan: the accent card first, the back card [staggerMs] later. */
    suspend fun leafOut(spec: FiniteAnimationSpec<Float>, staggerMs: Long) {
        coroutineScope {
            launch { accent.animateTo(1f, spec) }
            launch {
                delay(staggerMs)
                back.animateTo(1f, spec)
            }
        }
    }

    /**
     * One leaf-through, ending at rest. If cancelled half way, the fan snaps back to rest
     * rather than staying frozen mid-beat for its next appearance.
     */
    suspend fun leafThrough(spec: FiniteAnimationSpec<Float>) {
        try {
            leaf.snapTo(0f)
            leaf.animateTo(1f, spec)
        } finally {
            withContext(NonCancellable) { leaf.snapTo(0f) }
        }
    }
}

/**
 * Colours and scratch objects for one [BrandFanLogo]; remembered, so a frame allocates
 * nothing. [tile] is the icon's gradient (null when floating); [seam] is what a seam is
 * painted with — the tile gradient, or the screen background the floating cards sit on.
 */
private class FanPaint(
    val tile: Brush?,
    val seam: Brush,
    val backColor: Color,
    val accentColor: Color,
    val frontColor: Color,
    val frontOutline: Color?,
    val postShapeColor: Color,
    val postLineColor: Color,
    val frameCenterX: Float,
    val frameCenterY: Float,
    val frameSize: Float,
) {
    val path = Path()
    val matrix = android.graphics.Matrix()
    val outlineStroke = Stroke(width = BrandFanGeometry.FRONT_OUTLINE_WIDTH)

    fun slotColor(slot: Int): Color = when (slot) {
        BrandFanGeometry.BACK_SLOT -> backColor
        BrandFanGeometry.ACCENT_SLOT -> accentColor
        else -> frontColor
    }
}

/**
 * The fan logo drawn in Compose, so its cards can move.
 *
 * [BrandFanStyle.Tile] (default) is the app icon: at rest, without [showPost], it matches
 * `ic_brand_logo.xml`, with colours from the same resources, so the beta flavour's
 * recolour applies here too. Unclipped — the caller clips it to its own shape.
 *
 * [BrandFanStyle.Floating] is the hero: the cards alone on [background] (the colour the
 * logo actually sits on), in theme colours — the back card between primaryContainer and
 * primary, so it reads on light and dark surfaces alike; the brand orange accent; a
 * surface-bright front card with a hairline outline, so it does not vanish on a
 * near-white screen.
 *
 * With [showPost] the front card carries a tiny post (avatar, name, picture, two lines
 * of text), so the mark reads as "a feed" at hero size.
 *
 * Seams are painted, not punched: before each upper card, its outline grown by
 * [BrandFanGeometry.SEAM] is filled with what lies behind the logo (the tile gradient, or
 * the opaque screen background), then the card on top. That is pixel-equivalent to
 * clearing the seam in an offscreen layer — without the layer, so it renders the same on
 * every API level (no saveLayer / BlendMode.Clear differences on API 26-28) and costs no
 * extra buffer. Card outlines are built as absolute (pre-rotated) paths for that reason:
 * a shape drawn inside a `rotate {}` would rotate the gradient with it.
 */
@Composable
fun BrandFanLogo(
    state: BrandFanState,
    size: Dp,
    modifier: Modifier = Modifier,
    style: BrandFanStyle = BrandFanStyle.Tile,
    background: Color = MaterialTheme.colorScheme.background,
    showPost: Boolean = false,
) {
    val gradientTop = colorResource(R.color.brand_gradient_top)
    val gradientBottom = colorResource(R.color.brand_gradient_bot)
    val tileBack = colorResource(R.color.launcher_card_back)
    val brandAccent = colorResource(R.color.launcher_card_accent)
    val tileFront = colorResource(R.color.launcher_card_front)
    val scheme = MaterialTheme.colorScheme
    val paint = remember(style, background, gradientTop, gradientBottom, tileBack, brandAccent, tileFront, scheme) {
        when (style) {
            BrandFanStyle.Tile -> {
                val gradient = Brush.linearGradient(
                    colors = listOf(gradientTop, gradientBottom),
                    start = Offset.Zero,
                    end = Offset(BrandFanGeometry.CANVAS, BrandFanGeometry.CANVAS),
                )
                FanPaint(
                    tile = gradient,
                    seam = gradient,
                    backColor = tileBack,
                    accentColor = brandAccent,
                    frontColor = tileFront,
                    frontOutline = null,
                    postShapeColor = tileBack,
                    postLineColor = gradientBottom.copy(alpha = TILE_POST_LINE_ALPHA),
                    frameCenterX = BrandFanGeometry.CANVAS / 2f,
                    frameCenterY = BrandFanGeometry.CANVAS / 2f,
                    frameSize = BrandFanGeometry.VISIBLE_SIZE,
                )
            }
            BrandFanStyle.Floating -> FanPaint(
                tile = null,
                seam = SolidColor(background),
                backColor = lerp(scheme.primaryContainer, scheme.primary, FLOAT_BACK_TOWARD_PRIMARY),
                accentColor = brandAccent,
                frontColor = scheme.surfaceBright,
                frontOutline = scheme.outlineVariant,
                postShapeColor = scheme.primary,
                postLineColor = scheme.outlineVariant,
                frameCenterX = BrandFanGeometry.FLOAT_FRAME_CENTER_X,
                frameCenterY = BrandFanGeometry.FLOAT_FRAME_CENTER_Y,
                frameSize = BrandFanGeometry.FLOAT_FRAME_SIZE,
            )
        }
    }
    Spacer(
        modifier
            .size(size)
            .drawBehind {
                val unit = this.size.minDimension / paint.frameSize
                val left = paint.frameCenterX - paint.frameSize / 2f
                val top = paint.frameCenterY - paint.frameSize / 2f
                val leaf = state.leaf.value
                scale(unit, unit, pivot = Offset.Zero) {
                    translate(-left, -top) {
                        val tile = paint.tile
                        if (tile != null) {
                            // The icon is a square; whatever moves past its edge is cut off.
                            clipRect(left, top, left + paint.frameSize, top + paint.frameSize) {
                                drawRect(brush = tile, size = Size(BrandFanGeometry.CANVAS, BrandFanGeometry.CANVAS))
                                drawFan(paint, state, leaf, showPost)
                            }
                        } else {
                            drawFan(paint, state, leaf, showPost)
                        }
                    }
                }
            },
    )
}

private fun DrawScope.drawFan(paint: FanPaint, state: BrandFanState, leaf: Float, showPost: Boolean) {
    if (leaf == 0f) {
        drawEntrance(paint, state.back.value, state.accent.value, showPost)
    } else {
        drawLeafThrough(paint, leaf, showPost)
    }
}

private fun DrawScope.drawEntrance(paint: FanPaint, back: Float, accent: Float, showPost: Boolean) {
    val g = BrandFanGeometry
    drawCard(
        paint, g.centerX(g.BACK, back), g.centerY(g.BACK, back), g.rotation(g.BACK, back),
        paint.backColor, frontLook = 0f, showPost = showPost, seam = false,
    )
    drawCard(
        paint, g.centerX(g.ACCENT, accent), g.centerY(g.ACCENT, accent), g.rotation(g.ACCENT, accent),
        paint.accentColor, frontLook = 0f, showPost = showPost, seam = true,
    )
    drawCard(
        paint, g.FRONT.x, g.FRONT.y, g.FRONT.degrees,
        paint.frontColor, frontLook = 1f, showPost = showPost, seam = true,
    )
}

/**
 * Z order through the beat: the departing front card is on top until the apex, then at
 * the very back. The first card drawn needs no seam; every later one does.
 */
private fun DrawScope.drawLeafThrough(paint: FanPaint, p: Float, showPost: Boolean) {
    val g = BrandFanGeometry
    val onTop = g.departingOnTop(p)
    if (!onTop) drawLeafCard(paint, g.FRONT_SLOT, p, showPost, seam = false)
    drawLeafCard(paint, g.BACK_SLOT, p, showPost, seam = !onTop)
    drawLeafCard(paint, g.ACCENT_SLOT, p, showPost, seam = true)
    if (onTop) drawLeafCard(paint, g.FRONT_SLOT, p, showPost, seam = true)
}

private fun DrawScope.drawLeafCard(paint: FanPaint, slot: Int, p: Float, showPost: Boolean, seam: Boolean) {
    val g = BrandFanGeometry
    val q = g.lookProgress(slot, p)
    val from = paint.slotColor(slot)
    val to = paint.slotColor(g.nextSlot(slot))
    // Plain sRGB channel lerp: builds the packed colour value without the colour-space
    // conversion objects Compose's perceptual `lerp` goes through.
    val color = Color(
        red = from.red + (to.red - from.red) * q,
        green = from.green + (to.green - from.green) * q,
        blue = from.blue + (to.blue - from.blue) * q,
        alpha = 1f,
    )
    drawCard(
        paint, g.leafX(slot, p), g.leafY(slot, p), g.leafDegrees(slot, p),
        color, frontLook = g.frontWeight(slot, p), showPost = showPost, seam = seam,
        alpha = g.leafAlpha(slot, p), scale = g.leafScale(slot, p),
    )
}

/**
 * One card centred at ([cx], [cy]), turned [degrees] clockwise and scaled by [scale]: its
 * seam outline first when [seam], then its face, then — weighted by [frontLook] — the
 * front card's outline and, with [showPost], the post silhouette. Everything at [alpha],
 * the seam included, so a fading card leaves no ghost outline behind.
 */
private fun DrawScope.drawCard(
    paint: FanPaint,
    cx: Float,
    cy: Float,
    degrees: Float,
    color: Color,
    frontLook: Float,
    showPost: Boolean,
    seam: Boolean,
    alpha: Float = 1f,
    scale: Float = 1f,
) {
    if (alpha <= 0f) return
    val g = BrandFanGeometry
    paint.matrix.setRotate(degrees, cx, cy)
    paint.matrix.postScale(scale, scale, cx, cy)
    if (seam) {
        cardOutline(paint, cx, cy, g.SEAM)
        drawPath(paint.path, paint.seam, alpha = alpha)
    }
    cardOutline(paint, cx, cy, 0f)
    drawPath(paint.path, color, alpha = alpha)
    val look = frontLook * alpha
    if (look <= 0f) return
    paint.frontOutline?.let { drawPath(paint.path, it, alpha = look, style = paint.outlineStroke) }
    if (!showPost) return

    val left = cx - g.CARD_WIDTH / 2f + g.POST_PADDING
    val right = cx + g.CARD_WIDTH / 2f - g.POST_PADDING
    val top = cy - g.CARD_HEIGHT / 2f + g.POST_PADDING
    val half = g.POST_LINE_HEIGHT / 2f
    val avatarY = top + g.POST_AVATAR_RADIUS

    val shapes = paint.path.asAndroidPath()
    paint.path.reset()
    shapes.addCircle(left + g.POST_AVATAR_RADIUS, avatarY, g.POST_AVATAR_RADIUS, CW)
    shapes.addRoundRect(
        left, cy + g.POST_PICTURE_TOP, right, cy + g.POST_PICTURE_BOTTOM,
        g.POST_PICTURE_RADIUS, g.POST_PICTURE_RADIUS, CW,
    )
    shapes.transform(paint.matrix)
    drawPath(paint.path, paint.postShapeColor, alpha = look)

    val nameLeft = left + g.POST_AVATAR_RADIUS * 2f + g.POST_LINE_HEIGHT
    val line2Right = left + (right - left) * g.POST_TEXT_LINE_2_FRACTION
    paint.path.reset()
    shapes.addRoundRect(nameLeft, avatarY - half, nameLeft + g.POST_NAME_WIDTH, avatarY + half, half, half, CW)
    shapes.addRoundRect(left, cy + g.POST_TEXT_LINE_1 - half, right, cy + g.POST_TEXT_LINE_1 + half, half, half, CW)
    shapes.addRoundRect(left, cy + g.POST_TEXT_LINE_2 - half, line2Right, cy + g.POST_TEXT_LINE_2 + half, half, half, CW)
    shapes.transform(paint.matrix)
    drawPath(paint.path, paint.postLineColor, alpha = look)
}

/** Rebuilds [FanPaint.path] as the card outline grown by [grow], transformed by the paint's matrix. */
private fun cardOutline(paint: FanPaint, cx: Float, cy: Float, grow: Float) {
    val g = BrandFanGeometry
    val halfW = g.CARD_WIDTH / 2f + grow
    val halfH = g.CARD_HEIGHT / 2f + grow
    val radius = g.CARD_RADIUS + grow
    paint.path.reset()
    val platformPath = paint.path.asAndroidPath()
    platformPath.addRoundRect(cx - halfW, cy - halfH, cx + halfW, cy + halfH, radius, radius, CW)
    platformPath.transform(paint.matrix)
}

private val CW = android.graphics.Path.Direction.CW

/** Text lines on the tile's post read as a quiet tint of the brand's deep purple. */
private const val TILE_POST_LINE_ALPHA = 0.3f

/** Floating back card: this far from primaryContainer toward primary, to read on any surface. */
private const val FLOAT_BACK_TOWARD_PRIMARY = 0.45f
