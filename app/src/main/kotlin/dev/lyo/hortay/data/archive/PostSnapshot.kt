package dev.lyo.hortay.data.archive

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

enum class SnapshotKind { VERSION, DELETED }

@Immutable
data class PostSnapshot(
    val id: Long,
    val chat: ChatRef,
    val messageKey: String,
    val albumKey: String?,
    val kind: SnapshotKind,
    val seenAtMs: Long,
    val editedAtMs: Long?,
    val content: ArchivedContent,
    val mediaMinithumb: ByteArray?,
    val deletedMessageKeys: ImmutableList<String> = persistentListOf(),
    val isComment: Boolean = false,
) {
    override fun equals(other: Any?): Boolean = other is PostSnapshot && other.id == id
    override fun hashCode(): Int = id.hashCode()
}
