package dev.lyo.hortay.ui.text

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Anti-phishing hook for **masked** link spans (TDLib `Style.TextUrl`, the
 * `[visible text](actual URL)` shape). Default value is [Unhandled] — a sentinel
 * lambda that callers detect by reference equality and fall back to opening the URL
 * directly. Scaffolds install a real implementation that updates state and renders
 * the [ExternalLinkConfirmDialog].
 *
 * Why a sentinel instead of a "just open the URL" fallback inline: the fallback path
 * needs `LocalUriHandler.current`, which can only be read inside a `@Composable`
 * function. CompositionLocal defaults are non-Composable lambdas, so we expose the
 * sentinel and let the call-site (which already has the uriHandler in scope) branch.
 *
 * Why masked-only: TG-Android (and iOS) treats `[click here](https://evil.com)`
 * differently from inline `https://example.com`. The visible text vs. URL mismatch
 * is the anti-phishing signal. Inline URL spans where the display text IS the URL
 * skip this dialog because the user already sees what they're tapping.
 */
val LocalLinkConfirm = staticCompositionLocalOf<(String) -> Unit> { Unhandled }

/** Reference-equality sentinel — see [LocalLinkConfirm]. */
val Unhandled: (String) -> Unit = { /* no-op */ }
