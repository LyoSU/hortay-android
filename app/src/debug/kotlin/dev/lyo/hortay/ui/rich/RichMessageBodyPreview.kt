package dev.lyo.hortay.ui.rich

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.data.CustomEmojiRepository
import dev.lyo.hortay.data.TdSender
import dev.lyo.hortay.data.rich.RichBlock
import dev.lyo.hortay.data.rich.RichCaption
import dev.lyo.hortay.data.rich.RichDocument
import dev.lyo.hortay.data.rich.RichInline
import dev.lyo.hortay.data.rich.RichListItem
import dev.lyo.hortay.ui.media.LocalCustomEmoji
import dev.lyo.hortay.ui.theme.HortayTheme
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableSharedFlow
import org.drinkless.tdlib.TdApi

private fun seq(vararg parts: RichInline): RichInline = RichInline.Sequence(persistentListOf(*parts))
private fun plain(text: String): RichInline = RichInline.Plain(text)

private val sampleDocument = RichDocument(
    isRtl = false,
    isFull = true,
    blocks = persistentListOf(
        RichBlock.SectionHeading(plain("Rich message renderer"), size = 2),
        RichBlock.Paragraph(
            seq(
                plain("A paragraph with "),
                RichInline.Bold(plain("bold")),
                plain(", a "),
                RichInline.Url(plain("link"), "https://example.com"),
                plain(", a "),
                RichInline.Spoiler(plain("hidden phrase")),
                plain(", an emoji "),
                RichInline.CustomEmoji(customEmojiId = 42L, alternativeText = "🎉"),
                plain(", and H"),
                RichInline.Subscript(plain("2")),
                plain("O with a "),
                RichInline.Marked(plain("marked")),
                plain(" run."),
            ),
        ),
        RichBlock.ListBlock(
            persistentListOf(
                RichListItem(
                    label = "",
                    blocks = persistentListOf(RichBlock.Paragraph(plain("Done"))),
                    hasCheckbox = true,
                    isChecked = true,
                    value = 0,
                    type = "",
                ),
                RichListItem(
                    label = "",
                    blocks = persistentListOf(
                        RichBlock.Paragraph(plain("Pending, with a nested list")),
                        RichBlock.ListBlock(
                            persistentListOf(
                                RichListItem(
                                    label = "",
                                    blocks = persistentListOf(RichBlock.Paragraph(plain("First"))),
                                    hasCheckbox = false, isChecked = false, value = 1, type = "1",
                                ),
                                RichListItem(
                                    label = "",
                                    blocks = persistentListOf(RichBlock.Paragraph(plain("Second"))),
                                    hasCheckbox = false, isChecked = false, value = 2, type = "1",
                                ),
                            ),
                        ),
                    ),
                    hasCheckbox = true,
                    isChecked = false,
                    value = 0,
                    type = "",
                ),
            ),
        ),
        RichBlock.BlockQuote(
            blocks = persistentListOf(RichBlock.Paragraph(plain("The quote body reads at full width with an accent bar."))),
            credit = plain("— A. Author"),
        ),
        RichBlock.PullQuote(text = plain("A centred pull quote."), credit = plain("— Editor")),
        RichBlock.Details(
            header = plain("Expandable details"),
            blocks = persistentListOf(RichBlock.Paragraph(plain("Hidden until expanded."))),
            isOpen = false,
        ),
        RichBlock.Preformatted(plain("fun main() = println(\"hi\")"), language = "kotlin"),
        RichBlock.Math(expression = "E = mc^2"),
        RichBlock.Photo(
            media = null,
            caption = RichCaption(text = plain("A photo caption"), credit = null),
            hasSpoiler = false,
        ),
    ),
)

/**
 * Fake custom-emoji repository whose `send` never returns, so inline custom emoji sit on
 * their loading placeholder rather than crashing the preview on a missing TDLib client.
 */
@Composable
private fun PreviewCustomEmoji(): CustomEmojiRepository {
    val scope = rememberCoroutineScope()
    return remember {
        CustomEmojiRepository(
            td = object : TdSender {
                override suspend fun <T : TdApi.Object> send(query: TdApi.Function<T>): T = awaitCancellation()
                override val updates = MutableSharedFlow<TdApi.Update>()
            },
            scope = scope,
        )
    }
}

@Preview(name = "Rich body — light", showBackground = true)
@Composable
private fun RichMessageBodyPreviewLight() {
    HortayTheme(darkTheme = false, dynamicColor = false) {
        CompositionLocalProvider(LocalCustomEmoji provides PreviewCustomEmoji()) {
            Surface {
                RichMessageBody(document = sampleDocument, modifier = Modifier.padding(16.dp))
            }
        }
    }
}

@Preview(name = "Rich body — dark", showBackground = true)
@Composable
private fun RichMessageBodyPreviewDark() {
    HortayTheme(darkTheme = true, dynamicColor = false) {
        CompositionLocalProvider(LocalCustomEmoji provides PreviewCustomEmoji()) {
            Surface(color = MaterialTheme.colorScheme.background) {
                RichMessageBody(document = sampleDocument, modifier = Modifier.padding(16.dp))
            }
        }
    }
}
