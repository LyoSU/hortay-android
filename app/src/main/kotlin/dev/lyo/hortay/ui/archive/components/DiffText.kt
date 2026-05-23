package dev.lyo.hortay.ui.archive.components

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.data.archive.diff.PostDiffResult
import dev.lyo.hortay.data.archive.diff.PostDiffSegment

/**
 * Renders a [PostDiffResult] as a single annotated paragraph:
 * - deletions get errorContainer background + strikethrough
 * - insertions get tertiaryContainer background
 * - unchanged segments render in the default body style
 */
@Composable
fun DiffText(result: PostDiffResult, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    val annotated = buildAnnotatedString {
        result.segments.forEach { seg ->
            when (seg) {
                is PostDiffSegment.Unchanged -> append(seg.text)
                is PostDiffSegment.Inserted -> withStyle(
                    SpanStyle(background = cs.tertiaryContainer, color = cs.onTertiaryContainer)
                ) { append(seg.text) }
                is PostDiffSegment.Deleted -> withStyle(
                    SpanStyle(
                        background = cs.errorContainer,
                        color = cs.onErrorContainer,
                        textDecoration = TextDecoration.LineThrough,
                    )
                ) { append(seg.text) }
            }
        }
    }
    Text(annotated, modifier = modifier.padding(8.dp), style = MaterialTheme.typography.bodyMedium)
}
