package dev.lyo.hortay.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asComposePath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath

/**
 * Two-layer shape system, mapped to the three reading-density tiers used in this app:
 *
 *  - **Tier C (dense reading — PostCard, RichText containers):** `HortayShapes` — flat
 *    rounded rectangles. Expressive geometry (Cookie/Burst/Clover) inside dense feed
 *    cards costs scan-rhythm and breaks Compose's @Immutable shape memoisation —
 *    intentionally kept off this surface.
 *  - **Tier A (hero / personality — reactions, FAB, loading, empty states):**
 *    `HortayExpressive` — MaterialShapes presets and Morph factories.
 */
// M3 Expressive default shape scale — softer than the M3-stable defaults (which
// run 4 / 8 / 12 / 16 / 28). The Expressive scale doubles down on the "containers
// feel padded" cue: an 8 dp button corner reads as legacy, an 18 dp button
// corner reads as the new design language at a glance. Applies to every
// component that reads `MaterialTheme.shapes.*` (Card, Surface, Button, Sheet,
// Dialog, FAB, …) — single token bump, ripples through the whole UI.
val HortayShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

/**
 * Expressive shape registry. All values are derived from `MaterialShapes` so the app
 * stays inside Google's curated polygon set.
 *
 * **Coordinate system note (load-bearing):** Every `MaterialShapes.*` polygon is
 * post-`normalized()` — i.e. its `bounds` is exactly `[0, 0, 1, 1]`. This is unlike
 * raw `RoundedPolygon(numVertices = N, centerX = 0f, centerY = 0f, radius = 1f)`
 * polygons used in the official Compose graphics-shapes intro samples, which sit in
 * `[-1, 1]`. The `MorphShape` / `PolygonShape` mapping below relies on this
 * normalisation: `scale(size.width, size.height)` is enough, no `translate(1, 1)`.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Immutable
object HortayExpressive {
    /** Default avatar / circular thumbnail. */
    val Avatar: RoundedPolygon = MaterialShapes.Circle

    /** Reaction chip — selected state. The Cookie9 reads as "celebratory" without
     *  being aggressively spiky; Cookie12 felt fussy at chip scale, Burst was too
     *  loud for an inline component. */
    val ReactionSelected: RoundedPolygon = MaterialShapes.Cookie9Sided
    val ReactionRest: RoundedPolygon = MaterialShapes.Square

    /** Bookmark toggle — selected state uses Heart for personality. */
    val BookmarkSelected: RoundedPolygon = MaterialShapes.Heart
    val BookmarkRest: RoundedPolygon = MaterialShapes.Square

    /** Folder/filter chip in the channel filter row. Cookie7 has an asymmetry that
     *  reads as "active" without competing with the reaction Cookie9 in the same view. */
    val FolderSelected: RoundedPolygon = MaterialShapes.Cookie7Sided
    val FolderRest: RoundedPolygon = MaterialShapes.Square

    /** FAB resting + pressed states (compose new post / search). */
    val FabRest: RoundedPolygon = MaterialShapes.Circle
    val FabPressed: RoundedPolygon = MaterialShapes.Burst

    /** "New posts" floating pill: micro-decoration around the count badge. */
    val Pill: RoundedPolygon = MaterialShapes.Pill

    /** Connection status / warning chip. Bun reads as "alert pillow". */
    val ConnectionBanner: RoundedPolygon = MaterialShapes.Bun

    /** Empty-state hero mask — Flower for "nothing here yet, but pleasantly". */
    val EmptyStateMask: RoundedPolygon = MaterialShapes.Flower

    /** Loading indicator polygon cycle — geometric→organic→geometric→tubular→starlike. */
    val LoadingPolygons: List<RoundedPolygon> = listOf(
        MaterialShapes.Circle,
        MaterialShapes.SoftBurst,
        MaterialShapes.Cookie9Sided,
        MaterialShapes.Pill,
        MaterialShapes.Sunny,
    )

    /** Pre-built morphs — `Morph` allocates float arrays per polygon, so cache. */
    val ReactionMorph: Morph = Morph(ReactionRest, ReactionSelected)
    val BookmarkMorph: Morph = Morph(BookmarkRest, BookmarkSelected)
    val FolderMorph: Morph = Morph(FolderRest, FolderSelected)
    val FabPressMorph: Morph = Morph(FabRest, FabPressed)
}

/**
 * A Compose [Shape] that interpolates between two `RoundedPolygon`s. `progress` ∈ [0, 1].
 *
 * `MaterialShapes` polygons are normalised to bounds `[0, 0, 1, 1]`, so the mapping
 * to a Compose box is a single `scale(width, height)` — no translation needed.
 *
 * Earlier iterations of this code used the `[-1, 1]` mapping documented in the
 * Compose graphics-shapes intro samples (`scale(w/2).translate(1)`). That works for
 * raw polygons constructed with `centerX = 0f, centerY = 0f, radius = 1f`, but
 * silently double-sizes any `MaterialShapes` polygon and offsets it by half its
 * width — visible as a polygon backdrop sitting in the bottom-right corner with
 * everything else missing. Stays load-bearing: do **not** add a translate here
 * unless you are also normalising the polygon yourself.
 */
@Immutable
class MorphShape(
    private val morph: Morph,
    private val progress: Float,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val composePath: Path = morph.toPath(progress).asComposePath()
        val matrix = Matrix()
        matrix.scale(size.width, size.height)
        composePath.transform(matrix)
        return Outline.Generic(composePath)
    }
}

/**
 * Shape wrapper for a single static [RoundedPolygon]. Same coordinate-mapping rules
 * as [MorphShape]: see that class' KDoc for why a translate is *not* needed.
 */
@Immutable
class PolygonShape(private val polygon: RoundedPolygon) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val composePath: Path = polygon.toPath().asComposePath()
        val matrix = Matrix()
        matrix.scale(size.width, size.height)
        composePath.transform(matrix)
        return Outline.Generic(composePath)
    }
}

/** Convenience: turn a polygon into a Compose Shape without instantiating a class
 *  literal at every call site. */
fun RoundedPolygon.asComposeShape(): Shape = PolygonShape(this)
