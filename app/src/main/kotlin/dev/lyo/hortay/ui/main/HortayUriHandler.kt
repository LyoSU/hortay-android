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
        if (parsed == null) {
            runCatching { delegate.openUri(uri) }
            return
        }
        scope.launch {
            val link = resolver.resolve(parsed)
            when (link) {
                null,
                is DeepLink.External -> runCatching { delegate.openUri(uri) }
                else -> router.submit(link)
            }
        }
    }
}
