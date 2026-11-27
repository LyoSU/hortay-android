package dev.lyo.hortay.ui.timeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.widget.Toast
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import dev.lyo.hortay.data.warnUnlessCancelled
import dev.lyo.hortay.ui.media.MediaShareActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Shares a picture of one post: the long-press menu's snapshot of the card (the post alone —
 * no menu, no blur, no unread strip), on an opaque [backing] in the colour the feed sits on,
 * with [paddingPx] around it so the post does not touch the image edge. Drawn and encoded off
 * the main thread; the snapshot is usually a hardware bitmap, which a software canvas cannot
 * draw, so it is copied first. A failure says so in a toast, like the viewer's own Share.
 */
internal suspend fun sharePostScreenshot(
    context: Context,
    snapshot: ImageBitmap,
    backing: Int,
    paddingPx: Int,
) {
    val file = withContext(Dispatchers.Default) {
        runCatching {
            val source = snapshot.asAndroidBitmap()
            val drawable = if (source.config == Bitmap.Config.HARDWARE) {
                checkNotNull(source.copy(Bitmap.Config.ARGB_8888, false))
            } else {
                source
            }
            val image = Bitmap.createBitmap(
                drawable.width + paddingPx * 2,
                drawable.height + paddingPx * 2,
                Bitmap.Config.ARGB_8888,
            )
            Canvas(image).apply {
                drawColor(backing)
                drawBitmap(drawable, paddingPx.toFloat(), paddingPx.toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))
            }
            MediaShareActions.writeSharedImage(context, image, "post-${System.currentTimeMillis()}.png")
        }.warnUnlessCancelled(TAG, "screenshot").getOrNull()
    }
    val result = if (file != null) {
        MediaShareActions.shareFile(context, file.path, "image/png")
    } else {
        MediaShareActions.Result.Failure(dev.lyo.hortay.R.string.media_share_error_insert_failed)
    }
    if (result is MediaShareActions.Result.Failure) {
        Toast.makeText(
            context,
            context.resources.getString(result.reasonResId, *result.args.toTypedArray()),
            Toast.LENGTH_SHORT,
        ).show()
    }
}

private const val TAG = "PostScreenshot"
