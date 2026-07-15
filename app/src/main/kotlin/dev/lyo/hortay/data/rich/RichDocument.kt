package dev.lyo.hortay.data.rich

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList

/**
 * TDLib-independent mirror of `richMessage` (TDLib 1.8.66):
 * `richMessage blocks:vector<PageBlock> is_rtl:Bool is_full:Bool`.
 *
 * When [isFull] is `false` the client must call `getFullRichMessage` to fetch the rest of
 * the document — this model only carries the flag; a later task owns that fetch.
 *
 * The model is data-only: no builders, no serialization, no logic beyond
 * [RichPlainText] projection.
 */
@Immutable
data class RichDocument(
    val blocks: ImmutableList<RichBlock>,
    val isRtl: Boolean,
    val isFull: Boolean,
)
