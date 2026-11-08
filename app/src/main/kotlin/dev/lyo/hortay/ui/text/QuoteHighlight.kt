package dev.lyo.hortay.ui.text

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * A hand-picked quote the reader just tapped: the post it came from ([chatId] /
 * [messageId]) and the quoted [text]. [token] makes a repeated tap on the same quote a
 * new highlight.
 */
@Immutable
data class QuoteTarget(
    val chatId: Long,
    val messageId: Long,
    val text: String,
    val token: Long = System.nanoTime(),
)

/**
 * The one quote highlight in flight. Set by a quote tap before navigating; whichever
 * card shows the target post marks the quoted fragment, and the fade-out clears it.
 * Process-wide UI state on purpose: the tap and the landing card can sit on different
 * screens (feed → channel, comment → anchor post), and threading a quote through every
 * navigation key would touch each route for a cosmetic.
 */
object QuoteHighlights {
    var target: QuoteTarget? by mutableStateOf(null)
        private set

    fun show(chatId: Long, messageId: Long, text: String) {
        if (text.isBlank()) return
        target = QuoteTarget(chatId, messageId, text.trim())
    }

    /** Clears [done] once its highlight has faded, unless a newer one replaced it. */
    fun finished(done: QuoteTarget) {
        if (target == done) target = null
    }

    /** The highlight for a card showing [chatId]/[messageIds], or null. */
    fun forPost(chatId: Long, messageIds: Collection<Long>): QuoteTarget? =
        target?.takeIf { it.chatId == chatId && it.messageId in messageIds }
}

/** The quote to mark inside the text under this provider (see [LinkAwareText]). */
val LocalQuoteHighlight = staticCompositionLocalOf<QuoteTarget?> { null }

/**
 * Where [quote] sits in [text]: an exact match first, then the quote's first words (a
 * quote keeps only some entities and may trim whitespace, so the exact string can miss).
 */
internal fun quoteRange(text: String, quote: String): IntRange? {
    if (quote.isEmpty()) return null
    val exact = text.indexOf(quote)
    if (exact >= 0) return exact until exact + quote.length
    val head = quote.take(QUOTE_HEAD_CHARS).trim()
    if (head.length < QUOTE_HEAD_MIN) return null
    val start = text.indexOf(head)
    if (start < 0) return null
    return start until (start + quote.length).coerceAtMost(text.length)
}

private const val QUOTE_HEAD_CHARS = 32
private const val QUOTE_HEAD_MIN = 8
