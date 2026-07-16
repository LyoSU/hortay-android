package dev.lyo.hortay.data.rich

import androidx.compose.runtime.Immutable
import dev.lyo.hortay.data.TdMedia
import dev.lyo.hortay.data.VideoQualities
import kotlinx.collections.immutable.ImmutableList

/**
 * TDLib-independent mirror of the `PageBlock` constructors that can arrive inside a
 * `richMessage` (TDLib 1.8.66). Every instant-view-only block (Title, Subtitle, Cover,
 * Embedded, RelatedArticles, …) folds to [Unknown] at map time and is not modelled here.
 *
 * Media-bearing blocks reuse the existing message-content descriptors ([TdMedia],
 * [VideoQualities]) so the feed's media composables and `MediaCache` flows work unchanged.
 * Media handles are nullable: `null` means TDLib delivered the block without a resolvable
 * file (the renderer shows an "unavailable" placeholder rather than crashing).
 */
@Immutable
sealed interface RichBlock {

    /** `pageBlockSectionHeading` — [size] 1–6, 1 being the largest. */
    @Immutable
    data class SectionHeading(val text: RichInline, val size: Int) : RichBlock

    /** `pageBlockParagraph`. */
    @Immutable
    data class Paragraph(val text: RichInline) : RichBlock

    /** `pageBlockPreformatted` — code block; [language] may be empty. */
    @Immutable
    data class Preformatted(val text: RichInline, val language: String) : RichBlock

    /** `pageBlockFooter`. */
    @Immutable
    data class Footer(val text: RichInline) : RichBlock

    /** `pageBlockDivider` — horizontal rule, renders no text. */
    @Immutable
    data object Divider : RichBlock

    /** `pageBlockMathematicalExpression` — block-level math; [expression] is the raw source. */
    @Immutable
    data class Math(val expression: String) : RichBlock

    /** `pageBlockAnchor` — invisible scroll target. */
    @Immutable
    data class Anchor(val name: String) : RichBlock

    /** `pageBlockList` — ordered / unordered / checkbox list. */
    @Immutable
    data class ListBlock(val items: ImmutableList<RichListItem>) : RichBlock

    /** `pageBlockBlockQuote` — quote with optional [credit] attribution. */
    @Immutable
    data class BlockQuote(
        val blocks: ImmutableList<RichBlock>,
        val credit: RichInline?,
    ) : RichBlock

    /** `pageBlockPullQuote`. */
    @Immutable
    data class PullQuote(val text: RichInline, val credit: RichInline?) : RichBlock

    /**
     * `pageBlockPhoto` (instant-view-only `url` dropped).
     *
     * [media] is the inline tier (~1280 px) the reader/feed renders; [fullscreen] is the
     * largest tier in TDLib's size pyramid, handed to the [dev.lyo.hortay.ui.media.FullScreenMediaViewer]
     * so pinch-zoom decodes real pixels instead of upscaling the 1280 px inline variant.
     * Mirrors the feed's photo mapping ([dev.lyo.hortay.data.AlbumItem.Photo.fullscreen]).
     * Both are null when TDLib delivered no resolvable file.
     */
    @Immutable
    data class Photo(
        val media: TdMedia?,
        val fullscreen: TdMedia?,
        val caption: RichCaption?,
        val hasSpoiler: Boolean,
    ) : RichBlock

    /** `pageBlockVideo`. */
    @Immutable
    data class Video(
        val media: TdMedia?,
        val playbackFileId: Int,
        val qualities: VideoQualities?,
        val durationSec: Int,
        val caption: RichCaption?,
        val needAutoplay: Boolean,
        val isLooped: Boolean,
        val hasSpoiler: Boolean,
    ) : RichBlock

    /** `pageBlockAnimation`. */
    @Immutable
    data class Animation(
        val media: TdMedia?,
        val playbackFileId: Int,
        val caption: RichCaption?,
        val needAutoplay: Boolean,
        val hasSpoiler: Boolean,
    ) : RichBlock

    /**
     * `pageBlockAudio`. Mirrors the fields of [dev.lyo.hortay.data.PostContent.Audio]
     * (TDLib ships no standalone reusable audio descriptor).
     */
    @Immutable
    data class Audio(
        val fileId: Int?,
        val title: String,
        val performer: String,
        val durationSec: Int,
        val caption: RichCaption?,
    ) : RichBlock

    /**
     * `pageBlockVoiceNote`. Mirrors the fields of
     * [dev.lyo.hortay.data.PostContent.VoiceNote].
     */
    @Immutable
    data class VoiceNote(
        val fileId: Int?,
        val durationSec: Int,
        val waveform: ByteArray?,
        val caption: RichCaption?,
    ) : RichBlock {
        override fun equals(other: Any?): Boolean =
            other is VoiceNote && other.fileId == fileId && other.caption == caption
        override fun hashCode(): Int = (fileId ?: 0) * 31 + caption.hashCode()
    }

    /** `pageBlockCollage` — all children shown at once (album-style grid). */
    @Immutable
    data class Collage(
        val items: ImmutableList<RichBlock>,
        val caption: RichCaption?,
    ) : RichBlock

    /** `pageBlockSlideshow` — children shown one at a time (swipeable). */
    @Immutable
    data class Slideshow(
        val items: ImmutableList<RichBlock>,
        val caption: RichCaption?,
    ) : RichBlock

    /** `pageBlockTable`. */
    @Immutable
    data class Table(
        val caption: RichInline?,
        val rows: ImmutableList<RichTableRow>,
        val isBordered: Boolean,
        val isStriped: Boolean,
    ) : RichBlock

    /** `pageBlockDetails` — collapsible section. */
    @Immutable
    data class Details(
        val header: RichInline,
        val blocks: ImmutableList<RichBlock>,
        val isOpen: Boolean,
    ) : RichBlock

    /** `pageBlockMap` — static map preview. */
    @Immutable
    data class MapPreview(
        val latitude: Double,
        val longitude: Double,
        val zoom: Int,
        val width: Int,
        val height: Int,
        val caption: RichCaption?,
    ) : RichBlock

    /**
     * Any `PageBlock` constructor the domain deliberately doesn't model (instant-view-only
     * blocks or a future upstream addition). Carries the mapper's best-effort [plainText].
     */
    @Immutable
    data class Unknown(val plainText: String) : RichBlock
}

/**
 * `pageBlockListItem`. [type] is one of `"a"`, `"A"`, `"i"`, `"I"`, `"1"`, or empty for an
 * unordered list; [value] is the item's ordinal (0 for unordered). [label] is TDLib's
 * pre-rendered marker text — kept for fidelity, but the plain-text projector derives its
 * own marker from [type] / [value] / [hasCheckbox].
 */
@Immutable
data class RichListItem(
    val label: String,
    val blocks: ImmutableList<RichBlock>,
    val hasCheckbox: Boolean,
    val isChecked: Boolean,
    val value: Int,
    val type: String,
)

/** One row of a [RichBlock.Table]. */
@Immutable
data class RichTableRow(val cells: ImmutableList<RichTableCell>)

/**
 * `pageBlockTableCell`. [text] is `null` for an invisible cell covered by a neighbour's
 * `colspan` / `rowspan`.
 */
@Immutable
data class RichTableCell(
    val text: RichInline?,
    val isHeader: Boolean,
    val colspan: Int,
    val rowspan: Int,
    val align: RichHorizontalAlignment,
    val valign: RichVerticalAlignment,
)

/** `PageBlockHorizontalAlignment`. */
enum class RichHorizontalAlignment { Left, Center, Right }

/** `PageBlockVerticalAlignment`. */
enum class RichVerticalAlignment { Top, Middle, Bottom }

/**
 * `pageBlockCaption` — shared caption struct. Both fields are `null` when TDLib delivered
 * an empty plain `""` (the mapper normalises empty captions to `null` so the renderer can
 * skip the caption slot entirely).
 */
@Immutable
data class RichCaption(val text: RichInline?, val credit: RichInline?)
