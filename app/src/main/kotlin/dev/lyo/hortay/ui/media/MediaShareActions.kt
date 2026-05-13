package dev.lyo.hortay.ui.media

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.StringRes
import androidx.core.content.FileProvider
import androidx.core.content.getSystemService
import dev.lyo.hortay.R
import dev.lyo.hortay.data.AlbumItem
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Save" / "Copy" actions for the fullscreen media viewer. The fileId TDLib
 * downloads into [Context.getFilesDir]/tdlib-files/... is opaque to the rest
 * of the device — we cannot just hand the raw path to a `Uri.fromFile()` or
 * a MediaStore row (Android Q+ scoped storage would reject the foreign path
 * on insert, and any pre-Q app that received a `file://` URI would see
 * FileUriExposedException). So both flows route through a copy:
 *
 *   • Save → bytes are streamed through MediaStore's relative-path API into
 *     `Pictures/Hortay/...` (photos) or `Movies/Hortay/...` (videos). The
 *     Q+ path uses the `IS_PENDING` two-phase write so a half-streamed file
 *     never becomes visible to gallery apps mid-copy. Pre-Q falls back to
 *     `Environment.getExternalStoragePublicDirectory(...)` + `MediaScanner`.
 *
 *   • Copy → bytes stay where TDLib put them; we mint a temporary read URI
 *     through the app's [FileProvider] (authority
 *     `${applicationId}.fileprovider`) and put it into [ClipData]. Other apps
 *     paste the URI and resolve it back to bytes through ContentResolver. We
 *     only enable copy for photos: most chat apps and document editors accept
 *     image clipboard items, almost none accept video clipboard items, and a
 *     ClipData of a 200 MB video URI is a UX trap (the user pastes it into
 *     WhatsApp and the upload silently starts).
 *
 * Telegram-Android places its save folder at `Pictures/Telegram` /
 * `Movies/Telegram`; we use `Pictures/Hortay` / `Movies/Hortay` so the saved
 * media is recognisably ours in the gallery and doesn't mix with the user's
 * official Telegram screenshots.
 */
object MediaShareActions {

    private const val TAG = "MediaShareActions"
    private const val SUBFOLDER = "Hortay"

    sealed interface Result {
        data object Success : Result
        /**
         * Localised failure payload. [reasonResId] is the user-facing string,
         * [args] feed any `%1$s` / `%2$d` format-args (currently only the
         * mkdir branch carries one — the directory path). The call site does
         * `context.getString(reasonResId, *args)` to resolve.
         *
         * [debugDetail] is an English diagnostic string surfaced to logcat /
         * crash reports only — never to UI. Captures the underlying exception
         * message when a `try/catch` collapsed an arbitrary throwable into a
         * generic "save failed" result, so the localised toast stays clean
         * but bug-report digging still has the original cause.
         */
        data class Failure(
            @StringRes val reasonResId: Int,
            val args: List<Any> = emptyList(),
            val debugDetail: String? = null,
        ) : Result
    }

    /**
     * True when [Result] of a save/copy is meaningful for the given item — i.e.
     * the file is actually local. Callers gate their button enabled state on
     * this so the affordance does not appear on a Failed / Downloading slot.
     */
    fun isPersistable(localPath: String?): Boolean =
        !localPath.isNullOrBlank() && File(localPath).run { exists() && length() > 0L }

    /**
     * Save the bytes at [localPath] into the public gallery. [item] decides
     * the MediaStore collection (Images vs Video) and the display name
     * extension. Runs on the calling thread — call from a coroutine on
     * Dispatchers.IO; the actual byte copy is the only blocking step and
     * is bounded by the source file size (single-digit MB for photos,
     * tens of MB for videos).
     */
    fun saveToGallery(
        context: Context,
        item: AlbumItem,
        localPath: String,
    ): Result {
        val src = File(localPath)
        if (!src.exists()) return Result.Failure(R.string.media_share_error_source_missing)

        val isVideo = item !is AlbumItem.Photo
        val mime = item.guessMimeType()
        val displayName = buildDisplayName(mime, isVideo)

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(context, src, displayName, mime, isVideo)
            } else {
                saveViaLegacyPath(context, src, displayName, mime, isVideo)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "saveToGallery failed", t)
            Result.Failure(
                reasonResId = R.string.media_share_error_insert_failed,
                debugDetail = t.message ?: t.javaClass.simpleName,
            )
        }
    }

    /**
     * Photo-only. Puts a [ClipData] item carrying a [FileProvider] URI onto
     * the system clipboard. The grant flag is set so any app reading the
     * clipboard can resolve the URI through ContentResolver.openInputStream;
     * without it Android 10+ refuses the read at the IPC boundary.
     */
    fun copyToClipboard(
        context: Context,
        item: AlbumItem,
        localPath: String,
    ): Result {
        if (item !is AlbumItem.Photo) return Result.Failure(R.string.media_share_error_only_photos)
        val src = File(localPath)
        if (!src.exists()) return Result.Failure(R.string.media_share_error_source_missing)
        val mime = item.guessMimeType()

        return try {
            val uri = FileProvider.getUriForFile(
                context,
                context.packageName + ".fileprovider",
                src,
            )
            val clip = ClipData.newUri(context.contentResolver, "Hortay image", uri).apply {
                description.extras = android.os.PersistableBundle().apply {
                    putString("mimeType", mime)
                }
            }
            val cm = context.getSystemService<ClipboardManager>()
                ?: return Result.Failure(R.string.media_share_error_clipboard_unavailable)
            cm.setPrimaryClip(clip)
            // Without the read-grant flag a paster on Q+ receives a SecurityException
            // when resolving the URI. ClipData.newUri does NOT auto-grant on its own;
            // the grant rides through the activity-token of the *consumer*, which is
            // why a global grant via context.grantUriPermission with FLAG_READ is the
            // pragmatic move for clipboard contents (the alternative — granting per
            // foreground process — is racy across the clipboard hand-off). The grant
            // is scoped to the file's content URI only.
            context.grantUriPermission(
                "android",
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            Result.Success
        } catch (t: Throwable) {
            Log.w(TAG, "copyToClipboard failed", t)
            Result.Failure(
                reasonResId = R.string.media_share_error_clipboard_unavailable,
                debugDetail = t.message ?: t.javaClass.simpleName,
            )
        }
    }

    private fun saveViaMediaStore(
        context: Context,
        src: File,
        displayName: String,
        mime: String,
        isVideo: Boolean,
    ): Result {
        val resolver = context.contentResolver
        val collection = if (isVideo) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val relative = (if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) +
            "/" + SUBFOLDER

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            put(MediaStore.MediaColumns.SIZE, src.length())
        }

        val uri = resolver.insert(collection, values)
            ?: return Result.Failure(R.string.media_share_error_insert_failed)
        try {
            resolver.openOutputStream(uri, "w")?.use { out ->
                src.inputStream().use { it.copyTo(out) }
            } ?: error("openOutputStream returned null")

            val finalise = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, finalise, null, null)
            return Result.Success
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
    }

    @Suppress("DEPRECATION")
    private fun saveViaLegacyPath(
        context: Context,
        src: File,
        displayName: String,
        mime: String,
        isVideo: Boolean,
    ): Result {
        val rootName = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
        val dir = File(Environment.getExternalStoragePublicDirectory(rootName), SUBFOLDER)
        if (!dir.exists() && !dir.mkdirs()) {
            return Result.Failure(
                reasonResId = R.string.media_share_error_mkdir,
                args = listOf(dir.toString()),
            )
        }
        val dest = File(dir, displayName)
        src.inputStream().use { input ->
            dest.outputStream().use { out -> input.copyTo(out) }
        }
        android.media.MediaScannerConnection.scanFile(
            context,
            arrayOf(dest.absolutePath),
            arrayOf(mime),
            null,
        )
        return Result.Success
    }

    /** "Hortay_20260511_142233.jpg" — sortable in any file manager. */
    private fun buildDisplayName(mime: String, isVideo: Boolean): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val ext = extensionFor(mime, isVideo)
        return "Hortay_$ts.$ext"
    }

    private fun extensionFor(mime: String, isVideo: Boolean): String =
        when (mime.lowercase(Locale.US)) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "video/mp4" -> "mp4"
            "video/webm" -> "webm"
            "video/quicktime" -> "mov"
            else -> if (isVideo) "mp4" else "jpg"
        }

    /**
     * Best-effort MIME for the gallery / clipboard payload. TDLib does not
     * propagate `mime_type` through to the [AlbumItem] graph (it lives on
     * the TdApi document/video payload that MessageContentMapper consumes);
     * for photos this is always JPEG (Telegram re-encodes uploads server-side)
     * and for videos / animations overwhelmingly MP4. WebM animated stickers
     * never reach the fullscreen viewer (they render in their own player
     * surface), so the per-kind default is safe.
     */
    private fun AlbumItem.guessMimeType(): String = when (this) {
        is AlbumItem.Photo -> "image/jpeg"
        is AlbumItem.Video -> "video/mp4"
        is AlbumItem.Animation -> "video/mp4"
    }
}

/**
 * Resolve the local path to copy/save for [item]. For [AlbumItem.Photo] this
 * is the fullscreen variant the viewer is painting; for [AlbumItem.Video] the
 * playback file the user is actually watching (picked quality, falls back to
 * default); for [AlbumItem.Animation] the playback file. Web-mode placeholders
 * (fileId == 0) and items without a Ready slot return null.
 */
internal fun AlbumItem.viewerFileId(activeQuality: Int? = null): Int? = when (this) {
    is AlbumItem.Photo -> fullscreen.fileId.takeIf { it != null && it != 0 }
    is AlbumItem.Video -> activeQuality ?: qualities.defaultPick.fileId.takeIf { it != 0 }
    is AlbumItem.Animation -> playbackFileId.takeIf { it != 0 }
}

/** True when the viewer should expose Save/Copy chrome for this item. */
internal fun AlbumItem.canBePersisted(): Boolean = viewerFileId() != null
