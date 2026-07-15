package dev.lyo.hortay.data.rich

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList

/**
 * TDLib-independent mirror of the recursive `RichText` tree (TDLib 1.8.66).
 *
 * `RichText` is NOT a flat span list: styling nests (a bold link wrapping italic text is
 * `Bold(Url(Italic(Plain)))`), so every styling variant carries a single [child] and the
 * leaves are [Plain] / [CustomEmoji] / [Math] / [Anchor] / [Unknown]. [Sequence] mirrors
 * `richTexts` (concatenation of siblings).
 *
 * Instances are produced once by the mapper and never mutated, so the whole tree is
 * [Immutable] for Compose skippability — see [dev.lyo.hortay.data.PostContent] for the
 * same contract on the message-content model.
 *
 * Deliberately excluded (fold to [Unknown] at map time): `richTextIcon` (instant-view only)
 * and `richTextDiff` (only reachable via `updatePendingMessage` in bot chats — Hortay reads
 * channels, which never receive pending-message constructs).
 */
@Immutable
sealed interface RichInline {

    /** `richTextPlain` — a leaf run of text. */
    @Immutable
    data class Plain(val text: String) : RichInline

    /** `richTextBold`. */
    @Immutable
    data class Bold(val child: RichInline) : RichInline

    /** `richTextItalic`. */
    @Immutable
    data class Italic(val child: RichInline) : RichInline

    /** `richTextUnderline`. */
    @Immutable
    data class Underline(val child: RichInline) : RichInline

    /** `richTextStrikethrough`. */
    @Immutable
    data class Strikethrough(val child: RichInline) : RichInline

    /** `richTextSpoiler`. */
    @Immutable
    data class Spoiler(val child: RichInline) : RichInline

    /** `richTextSubscript`. */
    @Immutable
    data class Subscript(val child: RichInline) : RichInline

    /** `richTextSuperscript`. */
    @Immutable
    data class Superscript(val child: RichInline) : RichInline

    /** `richTextMarked` — highlighted (marked) text. */
    @Immutable
    data class Marked(val child: RichInline) : RichInline

    /** `richTextFixed` — monospace / fixed-width text. */
    @Immutable
    data class Fixed(val child: RichInline) : RichInline

    /** `richTextDateTime` — a rendered timestamp carrying its display formatting. */
    @Immutable
    data class DateTime(
        val child: RichInline,
        val unixTime: Int,
        val formatting: RichDateTimeFormat,
    ) : RichInline

    /** `richTextMention` — `@username` mention. */
    @Immutable
    data class Mention(val child: RichInline, val username: String) : RichInline

    /** `richTextMentionName` — mention resolved to a user id (no public username). */
    @Immutable
    data class MentionName(val child: RichInline, val userId: Long) : RichInline

    /** `richTextHashtag`. */
    @Immutable
    data class Hashtag(val child: RichInline, val hashtag: String) : RichInline

    /** `richTextCashtag` — `$TICKER`. */
    @Immutable
    data class Cashtag(val child: RichInline, val cashtag: String) : RichInline

    /** `richTextBotCommand`. */
    @Immutable
    data class BotCommand(val child: RichInline, val command: String) : RichInline

    /** `richTextUrl` — external link (instant-view `is_cached` dropped). */
    @Immutable
    data class Url(val child: RichInline, val url: String) : RichInline

    /** `richTextEmailAddress`. */
    @Immutable
    data class EmailAddress(val child: RichInline, val email: String) : RichInline

    /** `richTextPhoneNumber`. */
    @Immutable
    data class PhoneNumber(val child: RichInline, val phoneNumber: String) : RichInline

    /** `richTextBankCardNumber`. */
    @Immutable
    data class BankCardNumber(val child: RichInline, val bankCardNumber: String) : RichInline

    /** `richTextCustomEmoji` — leaf; [alternativeText] is the plain-text fallback glyph. */
    @Immutable
    data class CustomEmoji(val customEmojiId: Long, val alternativeText: String) : RichInline

    /** `richTextMathematicalExpression` — inline math; [expression] is the raw source. */
    @Immutable
    data class Math(val expression: String) : RichInline

    /** `richTextReference` — a named footnote/reference target carrying [child] text. */
    @Immutable
    data class Reference(val child: RichInline, val name: String) : RichInline

    /**
     * `richTextReferenceLink` — link to a [referenceName] target; [url] is the external
     * fallback when the reference can't be resolved locally.
     */
    @Immutable
    data class ReferenceLink(
        val child: RichInline,
        val referenceName: String,
        val url: String,
    ) : RichInline

    /** `richTextAnchor` — invisible scroll target (leaf, renders no text). */
    @Immutable
    data class Anchor(val name: String) : RichInline

    /**
     * `richTextAnchorLink` — scroll-to-anchor link; [url] is the external fallback when
     * the anchor isn't present in this document.
     */
    @Immutable
    data class AnchorLink(
        val child: RichInline,
        val anchorName: String,
        val url: String,
    ) : RichInline

    /** `richTexts` — ordered concatenation of sibling runs. */
    @Immutable
    data class Sequence(val parts: ImmutableList<RichInline>) : RichInline

    /**
     * Any `RichText` constructor the domain deliberately doesn't model (instant-view-only
     * icons, pending-message diffs, or a future upstream addition). Carries the mapper's
     * best-effort [plainText] so projection and search degrade gracefully.
     */
    @Immutable
    data class Unknown(val plainText: String) : RichInline
}

/**
 * Display formatting for [RichInline.DateTime], mirroring `DateTimeFormattingType`.
 *
 * The precision fields are intentionally coarse ([RichDateTimePrecision]) — a later
 * TdApi-mapping task refines them against the real upstream enum. This model only needs
 * the load-bearing semantics: relative vs absolute, and whether the day-of-week is shown.
 */
@Immutable
sealed interface RichDateTimeFormat {

    /** `dateTimeFormattingTypeRelative` — "5 minutes ago". */
    @Immutable
    data object Relative : RichDateTimeFormat

    /** `dateTimeFormattingTypeAbsolute`. */
    @Immutable
    data class Absolute(
        val timePrecision: RichDateTimePrecision,
        val datePrecision: RichDateTimePrecision,
        val showDayOfWeek: Boolean,
    ) : RichDateTimeFormat
}

/** Coarse timestamp precision unit for [RichDateTimeFormat.Absolute]. */
enum class RichDateTimePrecision { Seconds, Minutes, Hours, Day, Month, Year }
