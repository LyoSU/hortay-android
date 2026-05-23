package dev.lyo.hortay.ui.main

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri

/**
 * Launch the official Telegram client by package, fall back to https://t.me/ if no
 * Telegram-compatible client is installed. Package list mirrors
 * [dev.lyo.hortay.ui.report.GuestReportDelegator].
 *
 * Single helper used by:
 *   - The bus-driven snackbar action dispatcher (auth dead-ends → "Open Telegram").
 *   - [dev.lyo.hortay.ui.timeline.EmptyState] CTA when the user has no subscriptions.
 *   - Long-press explainer for paid reactions.
 *   - [dev.lyo.hortay.ui.auth.AuthScreen] dead-end error states.
 *
 * Centralising the package list avoids drift between callers — a future Telegram
 * fork goes in once and lights up everywhere.
 */
internal fun openTelegramApp(context: Context) {
    val pm = context.packageManager
    val packages = listOf(
        "org.telegram.messenger",
        "org.telegram.messenger.web",
        "org.telegram.plus",
        "nekox.messenger",
    )
    for (pkg in packages) {
        val intent = pm.getLaunchIntentForPackage(pkg) ?: continue
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(intent) }.isSuccess) return
    }
    val webIntent = Intent(Intent.ACTION_VIEW, "https://t.me/".toUri())
        .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    runCatching { context.startActivity(webIntent) }
}

internal fun openExternalUrl(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, url.toUri())
        .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    runCatching { context.startActivity(intent) }
}
