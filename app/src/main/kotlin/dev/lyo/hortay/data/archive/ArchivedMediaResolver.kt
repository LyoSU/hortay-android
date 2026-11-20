package dev.lyo.hortay.data.archive

import androidx.compose.runtime.Immutable
import dev.lyo.hortay.data.TdSender
import dev.lyo.hortay.data.suspendRunCatching
import org.drinkless.tdlib.TdApi
import java.io.File

/**
 * Where the revision sheet can get a sharp picture for an archived version's media.
 *
 * Tried in order:
 *  1. **Archive copy** — the bytes [ArchivedMediaStore] copied at capture time
 *     ([ArchivedMediaRef.localArchiveSha]). Survives deletion, eviction and logout.
 *  2. **TDLib file** — photos only: `getRemoteFile` (an offline lookup, td_api.tl) turns the
 *     stored persistent remote id back into a file id, accepted only when TDLib reports the
 *     same `remote.uniqueId` as the snapshot, i.e. it is the very file that version showed.
 *     The sheet then renders it through the feed's own `TdMediaImage`, which downloads it on
 *     demand like a feed photo. For a deleted post this is used only when the file is
 *     already on disk: a download would hit an expired file reference.
 *     Videos / GIFs are not resolved here — their stored remote id is the playback file,
 *     not a poster, and `TdMediaImage` never decodes a playback file as an image.
 *  3. Otherwise the caller falls back to the inline minithumb ("preview only").
 *
 * Holds no state; the lookups are cheap enough to run per opened version.
 */
class ArchivedMediaResolver(
    private val td: TdSender?,
    private val store: ArchivedMediaStore?,
) {

    suspend fun resolve(ref: ArchivedMediaRef, postDeleted: Boolean): ArchivedMediaSource {
        archivedPath(ref)?.let { return ArchivedMediaSource.ArchivedFile(it) }
        liveFileId(ref, postDeleted)?.let { return ArchivedMediaSource.TdlibFile(it) }
        return ArchivedMediaSource.PreviewOnly
    }

    private suspend fun archivedPath(ref: ArchivedMediaRef): String? {
        val sha = ref.localArchiveSha ?: return null
        val s = store ?: return null
        return suspendRunCatching { s.pathFor(sha) }.getOrNull()?.takeIf { File(it).exists() }
    }

    private suspend fun liveFileId(ref: ArchivedMediaRef, postDeleted: Boolean): Int? {
        val client = td ?: return null
        if (ref.type != TYPE_PHOTO || ref.remoteId.isEmpty() || ref.uniqueId.isEmpty()) return null
        val file = suspendRunCatching { client.send(TdApi.GetRemoteFile(ref.remoteId, TdApi.FileTypePhoto())) }
            .getOrNull() ?: return null
        if (file.remote?.uniqueId != ref.uniqueId) return null
        if (postDeleted && file.local?.isDownloadingCompleted != true) return null
        return file.id
    }

    private companion object {
        const val TYPE_PHOTO = "photo"
    }
}

/** Result of [ArchivedMediaResolver.resolve]. */
@Immutable
sealed interface ArchivedMediaSource {
    /** The archive's own copy at [path]. */
    @Immutable
    data class ArchivedFile(val path: String) : ArchivedMediaSource

    /** A TDLib file id proven to be the same remote file the version showed. */
    @Immutable
    data class TdlibFile(val fileId: Int) : ArchivedMediaSource

    /** Only the inline minithumb is available. */
    data object PreviewOnly : ArchivedMediaSource
}
