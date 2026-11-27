package dev.lyo.hortay.data.web

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.transformations
import coil3.toBitmap
import coil3.transform.CircleCropTransformation
import dev.lyo.hortay.MainActivity
import dev.lyo.hortay.R
import dev.lyo.hortay.data.ReplyMediaKind
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Posts guest new-post notifications: one per channel (tag = handle, replaced on update)
 * under one group summary, in the single "New posts" channel.
 *
 *  - Title = channel title, text = the newest post's first line (or its media kind),
 *    with "N new posts" as the sub-text and an InboxStyle list once there are two or more.
 *  - What is on screen is carried in the notification's own extras, so the next batch adds
 *    to it ([shown]) and a notification the reader swiped away starts over.
 *  - Tap: `https://t.me/<handle>` to [MainActivity], the app's own link path; the guest
 *    scaffold opens a followed channel's screen for it.
 *  - Large icon: the channel avatar through Coil's singleton loader — the one wired to the
 *    proxy-routed web client — best effort under [AVATAR_TIMEOUT_MS].
 */
class WebPostNotifier(context: Context) : WebPostNotifications {

    private val context = context.applicationContext
    private val manager = NotificationManagerCompat.from(this.context)

    override fun shown(username: String): WebNotifySelection.Shown? =
        activeNotification(username)?.let { extras ->
            val lines = extras.getStringArrayList(EXTRA_LINES) ?: return@let null
            WebNotifySelection.Shown(
                count = extras.getInt(EXTRA_COUNT),
                lines = lines,
                maxSeq = extras.getLong(EXTRA_MAX_SEQ),
            )
        }

    @SuppressLint("MissingPermission") // areNotificationsEnabled() is the runtime check.
    override suspend fun show(channel: WebNotifyStore.NotifyChannel, shown: WebNotifySelection.Shown) {
        if (!manager.areNotificationsEnabled()) return
        ensureChannel()
        val res = context.resources
        val title = channel.title.ifBlank { "@${channel.username}" }
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(title)
            .setContentText(shown.lines.firstOrNull())
            .setContentIntent(openChannelIntent(channel.username))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setGroup(GROUP)
            .addExtras(
                Bundle().apply {
                    putInt(EXTRA_COUNT, shown.count)
                    putStringArrayList(EXTRA_LINES, ArrayList(shown.lines))
                    putLong(EXTRA_MAX_SEQ, shown.maxSeq)
                },
            )
        if (shown.count >= 2) {
            val countText = res.getQuantityString(R.plurals.new_posts, shown.count, shown.count)
            builder.setSubText(countText).setNumber(shown.count)
            builder.setStyle(
                NotificationCompat.InboxStyle()
                    .setBigContentTitle(title)
                    .setSummaryText(countText)
                    .also { style -> shown.lines.forEach(style::addLine) },
            )
        }
        loadAvatar(channel.avatarUrl)?.let(builder::setLargeIcon)
        runCatching {
            manager.notify(channel.username, NOTIFICATION_ID, builder.build())
            manager.notify(SUMMARY_TAG, NOTIFICATION_ID, summary())
        }.onFailure { Log.w(TAG, "notify failed", it) }
    }

    override fun cancel(username: String) {
        runCatching {
            manager.cancel(username, NOTIFICATION_ID)
            // The summary outlives its last child otherwise.
            val anyLeft = manager.activeNotifications.any {
                it.id == NOTIFICATION_ID && it.tag != SUMMARY_TAG && it.notification.group == GROUP
            }
            if (!anyLeft) manager.cancel(SUMMARY_TAG, NOTIFICATION_ID)
        }.onFailure { Log.w(TAG, "cancel failed", it) }
    }

    override fun cancelAll() {
        runCatching {
            manager.activeNotifications
                .filter { it.id == NOTIFICATION_ID && it.notification.group == GROUP }
                .forEach { manager.cancel(it.tag, NOTIFICATION_ID) }
            manager.cancel(SUMMARY_TAG, NOTIFICATION_ID)
        }.onFailure { Log.w(TAG, "cancelAll failed", it) }
    }

    override fun label(kind: ReplyMediaKind): String = context.getString(
        when (kind) {
            ReplyMediaKind.Photo -> R.string.reply_kind_photo
            ReplyMediaKind.Video -> R.string.reply_kind_video
            ReplyMediaKind.Animation -> R.string.reply_kind_animation
            ReplyMediaKind.Document -> R.string.reply_kind_document
            ReplyMediaKind.Audio -> R.string.reply_kind_audio
            ReplyMediaKind.VoiceNote -> R.string.reply_kind_voice
            ReplyMediaKind.VideoNote -> R.string.reply_kind_video_note
            ReplyMediaKind.Sticker -> R.string.reply_kind_sticker
            ReplyMediaKind.Poll -> R.string.reply_kind_poll
            ReplyMediaKind.None -> R.string.web_notify_new_post
        },
    )

    private fun activeNotification(username: String): Bundle? = runCatching {
        manager.activeNotifications
            .firstOrNull { it.id == NOTIFICATION_ID && it.tag == username }
            ?.notification?.extras
    }.getOrNull()

    private fun summary() = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_notify)
        .setContentTitle(context.getString(R.string.web_notify_channel_name))
        .setContentIntent(openAppIntent())
        .setAutoCancel(true)
        .setGroup(GROUP)
        .setGroupSummary(true)
        // The children alert; the summary only groups them.
        .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
        .build()

    private fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.web_notify_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply { description = context.getString(R.string.web_notify_channel_description) }
        // Idempotent; also refreshes the name after a language change.
        manager.createNotificationChannel(channel)
    }

    private fun openChannelIntent(username: String): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://t.me/$username"), context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context,
            username.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private suspend fun loadAvatar(url: String?): Bitmap? {
        if (url.isNullOrBlank()) return null
        val size = context.resources.getDimensionPixelSize(android.R.dimen.notification_large_icon_width)
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(size)
            .allowHardware(false)
            .transformations(CircleCropTransformation())
            .build()
        return runCatching {
            withTimeoutOrNull(AVATAR_TIMEOUT_MS) { SingletonImageLoader.get(context).execute(request) }
        }.getOrNull()
            .let { it as? SuccessResult }
            ?.image
            ?.toBitmap()
    }

    companion object {
        private const val TAG = "WebPostNotifier"
        const val CHANNEL_ID = "web_new_posts"
        private const val GROUP = "dev.lyo.hortay.web_new_posts"
        // "#" can't occur in a Telegram username, so no channel tag can collide with it.
        private const val SUMMARY_TAG = "#summary"
        private const val NOTIFICATION_ID = 1
        private const val EXTRA_COUNT = "dev.lyo.hortay.notify.count"
        private const val EXTRA_LINES = "dev.lyo.hortay.notify.lines"
        private const val EXTRA_MAX_SEQ = "dev.lyo.hortay.notify.max_seq"
        private const val AVATAR_TIMEOUT_MS = 5_000L
    }
}
