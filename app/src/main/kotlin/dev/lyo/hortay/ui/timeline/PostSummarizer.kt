package dev.lyo.hortay.ui.timeline

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.R
import dev.lyo.hortay.data.TimelinePost
import dev.lyo.hortay.ui.components.rememberExpandedSheetState
import dev.lyo.hortay.ui.icons.Symbol

/** Fetches Telegram's summary of a post; returns null when it couldn't be made. */
fun interface PostSummarizer {
    suspend fun summarize(post: TimelinePost): String?
}

/**
 * Summary source for the post action sheet. Provided by the signed-in scaffold only, so
 * guest mode (no TDLib) never offers "Summarize".
 */
val LocalPostSummarizer = staticCompositionLocalOf<PostSummarizer?> { null }

private sealed interface SummaryState {
    data object Loading : SummaryState
    data class Ready(val text: String) : SummaryState
    data object Failed : SummaryState
}

/**
 * Bottom sheet with Telegram's AI summary of a long post. The summary is fetched when the
 * sheet opens; a failure has already been reported through the message bus, so the
 * sheet just closes instead of showing a second error.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun PostSummarySheet(
    post: TimelinePost,
    summarizer: PostSummarizer,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberExpandedSheetState()
    val state by produceState<SummaryState>(SummaryState.Loading, post.chatId, post.id) {
        value = summarizer.summarize(post)?.let { SummaryState.Ready(it) } ?: SummaryState.Failed
    }
    if (state == SummaryState.Failed) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Symbol(name = "description", tint = MaterialTheme.colorScheme.primary, size = 20.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.post_summary_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(16.dp))
            when (val s = state) {
                SummaryState.Loading -> Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    LoadingIndicator(Modifier.size(40.dp))
                }
                is SummaryState.Ready -> SelectionContainer {
                    Text(text = s.text, style = MaterialTheme.typography.bodyLarge)
                }
                SummaryState.Failed -> Unit
            }
        }
    }
}
