package dev.lyo.hortay.data.archive.diff

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList

@Immutable
sealed interface PostDiffSegment {
    val text: String
    @Immutable data class Unchanged(override val text: String) : PostDiffSegment
    @Immutable data class Inserted(override val text: String) : PostDiffSegment
    @Immutable data class Deleted(override val text: String) : PostDiffSegment
}

@Immutable
data class PostDiffResult(
    val granularity: PostDiff.Granularity,
    val segments: ImmutableList<PostDiffSegment>,
)
