package dev.lyo.hortay.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Material 3 shape tokens — fixed corner radii indexed by component scale. The values map
 * directly into `MaterialTheme.shapes.*`, so call sites just say `shape = MaterialTheme.shapes.large`
 * instead of recomputing dp's. Pill-shaped buttons (M3 Expressive default) use [CircleShape]
 * locally; the FAB uses `shapes.large` (16 dp) per the spec.
 */
val HortayShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)
