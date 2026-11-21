package dev.lyo.hortay.data.archive.diff

import androidx.compose.runtime.Immutable
import com.github.difflib.DiffUtils

/**
 * Word-level diff between two versions of a post's text that keeps exact UTF-16 offsets
 * into both strings, so a renderer can re-apply each version's own entities (bold, links,
 * custom emoji) on top of the diff instead of drawing it as plain text.
 *
 * Replaces the earlier adaptive line / sentence / word diff (`PostDiff`). That one split on
 * whitespace and re-joined tokens with a single separator, so its output was not the
 * original text — no offset could be mapped back and the sheet had to throw formatting
 * away. Its line mode also marked a whole paragraph removed + re-added for a one-word fix.
 *
 * Tokens: runs of letters / digits, runs of whitespace, and runs of other symbols. Combining
 * marks, variation selectors and zero-width joiners stick to the token before them, and a
 * surrogate pair is never split — so an emoji or a ZWJ sequence is diffed as a whole.
 *
 * A number separator — `.`, `,`, `:`, or the Arabic decimal / thousands separators U+066B /
 * U+066C — that sits directly between two digits belongs to the word around it, so
 * `405,7K`, `12,345.67` and `21:49` are one token each and a changed counter reads as one
 * replacement instead of "405 → 602, plain comma, 7K → 2K". The same character anywhere
 * else (`end. Next`, `a,b`, `1, 2`, a trailing `5.`) stays a symbol token. Space-like group
 * separators (`12 345`, U+00A0 / U+202F) are deliberately not joined: they are whitespace,
 * and folding them into words would also glue ordinary space-separated numbers together.
 */
object TextDiff {

    enum class Op { Equal, Insert, Delete }

    /** One run of the diff: `old[oldStart, oldEnd)` and / or `new[newStart, newEnd)`. */
    @Immutable
    data class Run(val op: Op, val oldStart: Int, val oldEnd: Int, val newStart: Int, val newEnd: Int)

    /**
     * Runs in reading order. Concatenating the Equal + Delete runs' old ranges gives [old];
     * Equal + Insert runs' new ranges give [new]. Within one change the Delete run comes
     * before its Insert run. A whitespace-only Equal run squeezed between two changes is
     * folded into them, so "a b" → "c d" reads as one replacement instead of two.
     */
    fun compute(old: String, new: String): List<Run> {
        val oldTokens = tokenize(old)
        val newTokens = tokenize(new)
        val patch = DiffUtils.diff(oldTokens.map { it.text }, newTokens.map { it.text })

        // Token-index runs first.
        val raw = mutableListOf<TokenRun>()
        var oi = 0
        var ni = 0
        for (delta in patch.deltas.sortedBy { it.source.position }) {
            val gap = delta.source.position - oi
            if (gap > 0) {
                raw += TokenRun(Op.Equal, oi, oi + gap, ni, ni + gap)
                oi += gap
                ni += gap
            }
            val srcSize = delta.source.size()
            val tgtSize = delta.target.size()
            if (srcSize > 0) raw += TokenRun(Op.Delete, oi, oi + srcSize, ni, ni)
            if (tgtSize > 0) raw += TokenRun(Op.Insert, oi + srcSize, oi + srcSize, ni, ni + tgtSize)
            oi += srcSize
            ni += tgtSize
        }
        if (oi < oldTokens.size) raw += TokenRun(Op.Equal, oi, oldTokens.size, ni, newTokens.size)

        val folded = foldWhitespaceBridges(raw, oldTokens)
        return mergeChanges(folded).map { r ->
            Run(
                op = r.op,
                oldStart = charStart(oldTokens, r.oldFrom, old.length),
                oldEnd = charStart(oldTokens, r.oldTo, old.length),
                newStart = charStart(newTokens, r.newFrom, new.length),
                newEnd = charStart(newTokens, r.newTo, new.length),
            )
        }
    }

    private data class Token(val text: String, val start: Int)

    private data class TokenRun(val op: Op, val oldFrom: Int, val oldTo: Int, val newFrom: Int, val newTo: Int)

    private enum class Kind { Word, Space, Symbol }

    private fun charStart(tokens: List<Token>, index: Int, length: Int): Int =
        if (index < tokens.size) tokens[index].start else length

    private fun tokenize(text: String): List<Token> {
        val out = mutableListOf<Token>()
        var i = 0
        var tokenStart = 0
        var kind: Kind? = null
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val size = Character.charCount(cp)
            val k = if (isNumberSeparatorBetweenDigits(text, i, cp)) Kind.Word else kindOf(cp)
            if (k != null && k != kind) {
                if (kind != null) out += Token(text.substring(tokenStart, i), tokenStart)
                tokenStart = i
                kind = k
            } else if (kind == null) {
                // A joiner / mark with nothing before it opens a symbol token of its own.
                kind = Kind.Symbol
            }
            i += size
        }
        if (kind != null) out += Token(text.substring(tokenStart), tokenStart)
        return out
    }

    /** [cp] at [i] is a number separator with a digit right before and right after it. */
    private fun isNumberSeparatorBetweenDigits(text: String, i: Int, cp: Int): Boolean {
        if (cp !in NUMBER_SEPARATORS || i == 0) return false
        val next = i + Character.charCount(cp)
        return next < text.length &&
            Character.isDigit(text.codePointBefore(i)) &&
            Character.isDigit(text.codePointAt(next))
    }

    private val NUMBER_SEPARATORS = setOf('.'.code, ','.code, ':'.code, 0x066B, 0x066C)

    /** Null = attaches to the current token (combining marks, VS16, ZWJ, other format chars). */
    private fun kindOf(cp: Int): Kind? = when {
        Character.isWhitespace(cp) || Character.isSpaceChar(cp) -> Kind.Space
        Character.isLetterOrDigit(cp) || cp == '_'.code -> Kind.Word
        when (Character.getType(cp)) {
            Character.NON_SPACING_MARK.toInt(),
            Character.ENCLOSING_MARK.toInt(),
            Character.COMBINING_SPACING_MARK.toInt(),
            Character.FORMAT.toInt(),
            -> true
            else -> false
        } -> null
        else -> Kind.Symbol
    }

    private fun foldWhitespaceBridges(runs: List<TokenRun>, oldTokens: List<Token>): List<TokenRun> {
        val out = mutableListOf<TokenRun>()
        for ((i, r) in runs.withIndex()) {
            val bridge = r.op == Op.Equal &&
                i > 0 && i < runs.lastIndex &&
                runs[i - 1].op != Op.Equal && runs[i + 1].op != Op.Equal &&
                (r.oldFrom until r.oldTo).all { oldTokens[it].text.isBlank() }
            if (bridge) {
                out += TokenRun(Op.Delete, r.oldFrom, r.oldTo, r.newFrom, r.newFrom)
                out += TokenRun(Op.Insert, r.oldTo, r.oldTo, r.newFrom, r.newTo)
            } else {
                out += r
            }
        }
        return out
    }

    /** Collapse each block of consecutive non-Equal runs into one Delete + one Insert. */
    private fun mergeChanges(runs: List<TokenRun>): List<TokenRun> {
        val out = mutableListOf<TokenRun>()
        var i = 0
        while (i < runs.size) {
            val r = runs[i]
            if (r.op == Op.Equal) {
                out += r
                i++
                continue
            }
            var j = i
            while (j < runs.size && runs[j].op != Op.Equal) j++
            val block = runs.subList(i, j)
            val oldFrom = block.minOf { it.oldFrom }
            val oldTo = block.maxOf { it.oldTo }
            val newFrom = block.minOf { it.newFrom }
            val newTo = block.maxOf { it.newTo }
            if (oldTo > oldFrom) out += TokenRun(Op.Delete, oldFrom, oldTo, newFrom, newFrom)
            if (newTo > newFrom) out += TokenRun(Op.Insert, oldTo, oldTo, newFrom, newTo)
            i = j
        }
        return out
    }
}
