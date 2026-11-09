package dev.lyo.hortay.ui.main

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.compose.ui.platform.UriHandler
import dev.lyo.hortay.data.DeepLink
import dev.lyo.hortay.data.DeepLinkRouter
import dev.lyo.hortay.data.TelegramLinkResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * In-app interceptor for [UriHandler.openUri] calls. Routes Telegram URIs through the
 * process-wide [TelegramLinkResolver] + [DeepLinkRouter] so the app opens the channel /
 * post inside Hortay instead of punting out to the official Telegram client (or worse,
 * a browser t.me preview).
 *
 * Provided via `CompositionLocalProvider(LocalUriHandler provides ...)` at both scaffold
 * roots, so EVERY descendant call — post body link taps, web preview cards,
 * AddChannelSheet affordances, settings author rows — is intercepted uniformly through
 * one entry point.
 *
 * Three outcomes after resolver consultation:
 *   - Actionable Telegram link → submitted to the router; the scaffold collector picks
 *     it up and navigates inside the app.
 *   - [DeepLink.External] (Telegram URL we don't natively support: invite, gift, bot
 *     start, …) → forwarded to the platform [delegate] so the OS dispatches it to the
 *     official Telegram client.
 *   - Non-Telegram URL (https://example.com, mailto:, tel:) → resolver returns null,
 *     forwarded to [delegate] untouched.
 *
 * Resolution is suspending (TDLib JNI call), so we fire-and-forget into [scope]. The
 * scope is the application-wide [dev.lyo.hortay.AppGraph.appScope], chosen over a
 * composition-local scope because the link tap shouldn't be cancelled if the user
 * navigates away mid-resolve (sub-100ms anyway). Uri.parse failures (rare, malformed
 * input from a wild paste) drop straight to the delegate via runCatching.
 */
class HortayUriHandler(
    private val delegate: UriHandler,
    private val resolver: TelegramLinkResolver,
    private val router: DeepLinkRouter,
    private val scope: CoroutineScope,
    /** When set, Telegram URLs the app can't open natively are handed to a foreign handler
     *  only (see [openUriOutsideHortay]) instead of the platform [delegate], which would
     *  resolve back to Hortay's own tg:// / t.me filters. */
    private val context: Context? = null,
) : UriHandler {
    override fun openUri(uri: String) {
        val parsed = runCatching { Uri.parse(uri) }.getOrNull()
        if (parsed == null) return
        val scheme = parsed.scheme?.lowercase()
        // Scheme allowlist before EITHER resolver consultation OR delegate punt.
        // A masked-link span in post text can carry any string the publisher
        // wrote — including `file:///`, `content://`, `javascript:`, `intent://`,
        // and similar. Telegram-Android's own link handler enforces an explicit
        // allowlist for the same reason. Without this gate, a malicious post
        // could hand `file:///sdcard/...` to ACTION_VIEW via our delegate.
        if (!isAllowedLinkScheme(scheme)) return
        scope.launch {
            val link = resolver.resolve(parsed)
            when (link) {
                null,
                is DeepLink.External -> runCatching {
                    if (context != null) openUriOutsideHortay(context, delegate, uri) else delegate.openUri(uri)
                }
                // HashtagSearch is a known Telegram-internal feature we render
                // in-app; we don't punt `tg://search?query=...` URLs to the OS
                // because ACTION_VIEW would feel external to a user who just
                // tapped `#foo`. Scaffolds collect and surface an in-app snackbar.
                is DeepLink.HashtagSearch,
                is DeepLink.PublicChannel,
                is DeepLink.PrivateChannel,
                is DeepLink.ChatInvite,
                is DeepLink.Message -> router.submit(link)
            }
        }
    }
}

// Explicit allowlist: HTTP(S) for real web links, Telegram's own scheme for
// `tg://resolve` / `tg://join` etc., and the two non-web schemes inline post
// text legitimately carries (`mailto:` press contacts, `tel:` for phone
// numbers in service messages). Anything else is rejected before it can
// reach ACTION_VIEW. Mirrors Telegram-Android's BrowserLink allowlist.
private val ALLOWED_LINK_SCHEMES = setOf("http", "https", "tg", "mailto", "tel")

/**
 * Whether a link with [scheme] may be opened at all. Shared by [HortayUriHandler] and the
 * masked-link confirm path in [LinkAwareScaffold], so a `javascript:` / `intent:` /
 * `file:` masked link is rejected before it gets a confirmation dialog whose confirm
 * would then silently no-op. Case-insensitive; null (no scheme) is rejected.
 */
internal fun isAllowedLinkScheme(scheme: String?): Boolean =
    scheme?.lowercase() in ALLOWED_LINK_SCHEMES

/**
 * Opens [url] via [delegate], except for Telegram URLs (`tg:` scheme or host `t.me`):
 * Hortay itself registers for those, so the platform dispatch would relaunch the app on
 * the very link it just declined, forever, whenever no Telegram client is installed (or
 * Hortay was picked as "always"). For them, a handler outside this package is resolved
 * and targeted explicitly.
 *
 * @return false only when [url] is a Telegram URL and no other app can open it.
 */
internal fun openUriOutsideHortay(context: Context, delegate: UriHandler, url: String): Boolean {
    val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return false
    val isTelegramUrl = uri.scheme.equals("tg", ignoreCase = true) ||
        uri.host.equals("t.me", ignoreCase = true)
    if (!isTelegramUrl) {
        delegate.openUri(url)
        return true
    }
    val probe = Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE)
    val pm = context.packageManager
    val handlers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        pm.queryIntentActivities(probe, PackageManager.ResolveInfoFlags.of(0L))
    } else {
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(probe, 0)
    }
    val foreign = handlers.firstOrNull { it.activityInfo.packageName != context.packageName }
        ?: return false
    probe.setClassName(foreign.activityInfo.packageName, foreign.activityInfo.name)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching { context.startActivity(probe) }.isSuccess
}
