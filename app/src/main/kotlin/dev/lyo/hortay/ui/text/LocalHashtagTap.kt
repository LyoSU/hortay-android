package dev.lyo.hortay.ui.text

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * In-app dispatch for a hashtag tap. The renderer marks `#foo` spans as Compose
 * `LinkAnnotation.Clickable` (NOT `LinkAnnotation.Url`) and routes the tap through
 * this hook instead of fabricating a `tg://search?query=#foo` URL.
 *
 * Two bugs the URL approach used to produce, both fixed by the Clickable + hook design:
 *
 *  1. **`#` is a URI fragment delimiter.** `tg://search?query=#foo` is parsed as
 *     `tg://search?query=` with fragment `foo`, so TDLib `GetInternalLinkType` doesn't
 *     recognise it as a search link, falls through to External, and the OS chooser
 *     pops up with "Open with another app" — the very behaviour the resolver was
 *     supposed to prevent.
 *
 *  2. **`tg://` leaks to user-visible surfaces.** Long-press menu shows the raw URL
 *     via [prettyLinkPreview]; the Copy / Share actions write the same string to the
 *     clipboard / SEND intent. Both are confusing to a user who tapped a normal-
 *     looking `#hashtag`. Telegram-Android doesn't show a copy menu for hashtags at
 *     all (they're an in-app feature, not a link); we follow that — by NOT adding
 *     hashtag spans to `RenderableText.linkRanges`, the long-press hit-tester skips
 *     them and no sheet appears.
 *
 * Default value is [HashtagUnhandled], a reference-equality sentinel — scaffolds
 * install a real implementation that routes the tap through [DeepLinkRouter] as
 * an `UnsupportedFeature(HashtagSearch)` so the existing collectors surface the
 * `link_unsupported_hashtag` snackbar. When in-app hashtag search ships, swapping
 * the scaffold implementation flips the behaviour everywhere without a renderer
 * change. The parameter is the raw tag text with the leading `#` included (so the
 * future search call site doesn't have to re-prepend it).
 */
val LocalHashtagTap = staticCompositionLocalOf<(String) -> Unit> { HashtagUnhandled }

/** Reference-equality sentinel — see [LocalHashtagTap]. */
val HashtagUnhandled: (String) -> Unit = { /* no-op */ }
