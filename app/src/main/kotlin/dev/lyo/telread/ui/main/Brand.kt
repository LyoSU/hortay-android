package dev.lyo.telread.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * App brand mark used in the top bar. A rounded square with a gradient backplate and the
 * "T" glyph — recognisable, lightweight, no asset dependency.
 */
@Composable
fun BrandMark(modifier: Modifier = Modifier, size: Int = 36) {
    val gradient = Brush.linearGradient(
        listOf(
            MaterialTheme.colorScheme.primary,
            MaterialTheme.colorScheme.tertiary,
        ),
    )
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(RoundedCornerShape(percent = 28))
            .background(gradient),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "t",
            fontSize = (size * 0.6).sp,
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onPrimary,
            style = MaterialTheme.typography.headlineSmall,
        )
    }
}

@Composable
fun BrandRow(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrandMark(size = 36)
        Spacer(Modifier.width(10.dp))
        Text(
            text = "Telread",
            style = MaterialTheme.typography.headlineLarge.copy(letterSpacing = (-0.8).sp),
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}
