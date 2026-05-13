// CSAE-COMPLIANCE: Google Play Child Safety Standards
// Policy: https://support.google.com/googleplay/android-developer/answer/14747720
// Hortay published standards: BuildConfig.CHILD_SAFETY_POLICY_URL
// Architecture: delegation to Telegram moderation via TDLib reportChat dynamic flow

package dev.lyo.hortay.ui.report

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import dev.lyo.hortay.data.report.ReportLogEntry
import dev.lyo.hortay.data.report.ReportLogStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Handles reporting from guest (anonymous) mode where no TDLib session is present.
 *
 * Delegation try-chain:
 *  1. tg:// deep link → any installed Telegram-compatible client.
 *  2. Custom Tab → web.telegram.org (t.me/<user>/<post>).
 *  3. mailto: intent → abuse@telegram.org as last resort.
 *
 * Each step logs to [ReportLogStore] with the appropriate deliveryMethod /
 * deliveryStatus so compliance audits can verify every report was attempted.
 *
 * [report] is intentionally NOT a suspend fun — it is called from a Composable
 * click handler via a plain coroutineScope.launch. All I/O (log writes) is
 * dispatched to [Dispatchers.IO] inside [ReportLogStore.log].
 */
class GuestReportDelegator(
    private val context: Context,
    private val log: ReportLogStore,
    private val logScope: CoroutineScope,
) {
    enum class Outcome {
        OpenedTelegram,
        OpenedWeb,
        OpenedEmail,
        AllFailed,
    }

    /**
     * Try to hand the report off to Telegram or a fallback surface.
     * Returns the [Outcome] so callers can decide whether to show
     * [ReportInstructionDialog].
     */
    fun report(channelUsername: String?, postId: Long?): Outcome {
        if (channelUsername.isNullOrBlank()) {
            logOutcome(channelUsername, postId, "delegated", "no_handle")
            return Outcome.AllFailed
        }
        // 1. Try tg:// with any installed Telegram-compatible client.
        if (isAnyTelegramClientInstalled()) {
            val uri = deepLink(channelUsername, postId)
            return try {
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                logOutcome(channelUsername, postId, "deep_link", "delegated")
                Outcome.OpenedTelegram
            } catch (_: ActivityNotFoundException) {
                // PackageManager said "yes" but startActivity failed — rare race.
                openWebFallback(channelUsername, postId)
            }
        }
        return openWebFallback(channelUsername, postId)
    }

    // -------------------------------------------------------------------------

    private fun openWebFallback(channelUsername: String, postId: Long?): Outcome {
        // 2. Custom Tab pointing at t.me/<user> or t.me/<user>/<post>.
        val encodedUser = Uri.encode(channelUsername)
        val webUri = if (postId != null && postId != 0L) {
            Uri.parse("https://t.me/$encodedUser/$postId")
        } else {
            Uri.parse("https://t.me/$encodedUser")
        }
        return try {
            CustomTabsIntent.Builder().build().launchUrl(context, webUri)
            logOutcome(channelUsername, postId, "web", "delegated")
            Outcome.OpenedWeb
        } catch (_: ActivityNotFoundException) {
            openEmailFallback(channelUsername, postId)
        } catch (_: Exception) {
            openEmailFallback(channelUsername, postId)
        }
    }

    private fun openEmailFallback(channelUsername: String, postId: Long?): Outcome {
        // 3. Email intent to abuse@telegram.org.
        val subject = "Report: Telegram channel @$channelUsername"
        val body = buildString {
            append("I would like to report the following Telegram channel for potential violations:\n\n")
            append("Channel: @$channelUsername\n")
            if (postId != null && postId != 0L) append("Post ID: $postId\n")
            append("\nPlease review this channel for content that may violate Telegram's Terms of Service and child safety standards.\n")
        }
        return try {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:")
                putExtra(Intent.EXTRA_EMAIL, arrayOf("abuse@telegram.org"))
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT, body)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            logOutcome(channelUsername, postId, "email", "delegated")
            Outcome.OpenedEmail
        } catch (_: ActivityNotFoundException) {
            logOutcome(channelUsername, postId, "email", "failed")
            Outcome.AllFailed
        }
    }

    private fun deepLink(username: String, postId: Long?): Uri {
        val encoded = Uri.encode(username)
        return if (postId != null && postId != 0L) {
            Uri.parse("tg://resolve?domain=$encoded&post=$postId")
        } else {
            Uri.parse("tg://resolve?domain=$encoded")
        }
    }

    private fun isAnyTelegramClientInstalled(): Boolean =
        TELEGRAM_PACKAGES.any { pkg ->
            runCatching {
                context.packageManager.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES)
                true
            }.getOrDefault(false)
        }

    private fun logOutcome(
        channelUsername: String?,
        postId: Long?,
        method: String,
        status: String,
    ) {
        logScope.launch(Dispatchers.IO) {
            runCatching {
                log.log(
                    ReportLogEntry(
                        timestamp = System.currentTimeMillis(),
                        mode = "guest",
                        channelUsername = channelUsername,
                        chatId = null,
                        messageId = postId,
                        deliveryMethod = method,
                        deliveryStatus = status,
                    ),
                )
            }
        }
    }

    companion object {
        val TELEGRAM_PACKAGES = listOf(
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "org.telegram.plus",
            "nekox.messenger",
            "org.thunderdog.challegram",
        )
    }
}
