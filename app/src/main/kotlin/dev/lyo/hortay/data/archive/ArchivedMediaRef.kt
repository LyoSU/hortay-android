@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package dev.lyo.hortay.data.archive

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * Snapshot of a single media attachment captured alongside a [TdlibContentMeta] VERSION.
 *
 * Two redundant identity systems live here on purpose, each addressing a different
 * failure mode (per tdlib/td#1025, levlam):
 *
 *  - **`remoteUniqueId`** — content-addressed; stable across application launches and
 *    across users. Cannot be used to download by itself; serves as the persistent
 *    primary key for diffing "is this the same physical file across two revisions?"
 *
 *  - **`remoteId`** — TDLib's reusable identifier. Can be fed back to
 *    [org.drinkless.tdlib.TdApi.GetRemoteFile] to mint a fresh `int file.id`, then
 *    [org.drinkless.tdlib.TdApi.DownloadFile]. Mutates over time even within one
 *    launch — captured value is a *snapshot*, may have rotated server-side.
 *    Per Lev Lam: "ID is guaranteed to be usable only if the corresponding file is
 *    still accessible to the user and known to TDLib."
 *
 *  - **`localArchiveSha`** — `Tier 2`. When the file was already on disk at capture
 *    time we copy it into the archive's permanent storage, indexed by SHA-256.
 *    Survives TDLib LRU eviction (`message_unload_delay`), post deletion, and
 *    even logout. The corresponding payload lives in `ArchivedMediaFile`.
 *
 *  - **`minithumbBytes`** — Telegram's inline 40-ish px JPEG preview embedded
 *    directly in `TdApi.Minithumbnail`. ~700-1500 bytes. Renders instantly and
 *    is the only universally-available fallback.
 *
 * ProtoBuf field numbers are pinned so a future field addition stays backward
 * compatible (existing rows decoded without breakage).
 */
@Immutable
@Serializable
data class ArchivedMediaRef(
    @ProtoNumber(1) val type: String,
    @ProtoNumber(2) val width: Int = 0,
    @ProtoNumber(3) val height: Int = 0,
    @ProtoNumber(4) val durationMs: Long = 0L,
    @ProtoNumber(5) val sizeBytes: Long = 0L,
    @ProtoNumber(6) val mimeType: String? = null,
    @ProtoNumber(7) val fileName: String? = null,
    @ProtoNumber(8) val remoteId: String = "",
    @ProtoNumber(9) val uniqueId: String = "",
    @ProtoNumber(10) val minithumbBytes: ByteArray? = null,
    @ProtoNumber(11) val localArchiveSha: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ArchivedMediaRef) return false
        return type == other.type &&
            width == other.width && height == other.height &&
            durationMs == other.durationMs && sizeBytes == other.sizeBytes &&
            mimeType == other.mimeType && fileName == other.fileName &&
            remoteId == other.remoteId && uniqueId == other.uniqueId &&
            (minithumbBytes ?: ByteArray(0)).contentEquals(other.minithumbBytes ?: ByteArray(0)) &&
            localArchiveSha == other.localArchiveSha
    }

    override fun hashCode(): Int {
        var r = type.hashCode()
        r = 31 * r + width; r = 31 * r + height
        r = 31 * r + durationMs.hashCode(); r = 31 * r + sizeBytes.hashCode()
        r = 31 * r + (mimeType?.hashCode() ?: 0)
        r = 31 * r + (fileName?.hashCode() ?: 0)
        r = 31 * r + remoteId.hashCode(); r = 31 * r + uniqueId.hashCode()
        r = 31 * r + (minithumbBytes?.contentHashCode() ?: 0)
        r = 31 * r + (localArchiveSha?.hashCode() ?: 0)
        return r
    }
}
