@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
// The only top-level class here (RichReferenceSheetData) is a support type deliberately
// co-located with RichReferenceSheet, the composable this file is named for.
@file:Suppress("MatchingDeclarationName")

package dev.lyo.hortay.ui.rich

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.R
import dev.lyo.hortay.ui.text.TonalActionRow
import kotlinx.coroutines.launch

/** Content of the footnote / reference sheet: the in-document [excerpt] and the [target] its
 *  "Go to reference" action scrolls to. Deliberately co-located with [RichReferenceSheet], the
 *  composable this file is named for. */
@Immutable
internal data class RichReferenceSheetData(val excerpt: String, val target: AnchorTarget)

/**
 * Compact bottom sheet shown when a footnote / reference marker ([RichInline.ReferenceLink]) whose
 * text is resolvable IN the document is tapped (see [findReferenceExcerpt]). It previews the
 * reference excerpt inline and offers a single "Go to reference" action that dismisses the sheet
 * and runs the same in-document anchor navigation an anchor tap would (scroll + auto-open details +
 * landing highlight). An external-only reference (no in-document text) never reaches here — it
 * keeps the masked-link confirmation path instead.
 */
@Composable
internal fun RichReferenceSheet(
    data: RichReferenceSheetData,
    onGoToReference: (AnchorTarget) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Text(
                text = data.excerpt,
                style = RichTypography.paragraph.copy(color = MaterialTheme.colorScheme.onSurface),
                maxLines = 10,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(4.dp))
            Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                TonalActionRow(
                    text = stringResource(R.string.rich_go_to_reference),
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.ContextClick)
                        onGoToReference(data.target)
                        scope.launch {
                            runCatching { sheetState.hide() }
                            onDismiss()
                        }
                    },
                )
            }
        }
    }
}
