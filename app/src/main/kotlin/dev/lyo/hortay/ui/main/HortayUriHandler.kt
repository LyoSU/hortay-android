package dev.lyo.hortay.ui.main

import android.net.Uri
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
        if (scheme !in ALLOWED_SCHEMES) return
        scope.launch {
            val link = resolver.resolve(parsed)
            when (link) {
                null,
                is DeepLink.External -> runCatching { delegate.openUri(uri) }
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

    private companion object {
        // Explicit allowlist: HTTP(S) for real web links, Telegram's own scheme for
        // `tg://resolve` / `tg://join` etc., and the two non-web schemes inline post
        // text legitimately carries (`mailto:` press contacts, `tel:` for phone
        // numbers in service messages). Anything else is rejected before it can
        // reach ACTION_VIEW. Mirrors Telegram-Android's BrowserLink allowlist.
        val ALLOWED_SCHEMES = setOf("http", "https", "tg", "mailto", "tel")
    }
}
