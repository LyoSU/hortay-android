package dev.lyo.hortay.ui.main

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lyo.hortay.AppGraph
import dev.lyo.hortay.data.DeepLink
import dev.lyo.hortay.data.FeedOrder
import dev.lyo.hortay.ui.onboarding.FeedOrderOnboardingSheet
import dev.lyo.hortay.ui.text.ExternalLinkConfirmDialog
import dev.lyo.hortay.ui.text.LocalHashtagTap
import dev.lyo.hortay.ui.text.LocalLinkConfirm
import dev.lyo.hortay.ui.text.parseHashtagWithScope
import kotlinx.coroutines.launch

/**
 * Single source of truth for the in-app link plumbing both scaffolds need:
 *
 *   - [HortayUriHandler] installed as [LocalUriHandler] so every descendant
 *     `openUri(...)` (post body links, web preview cards, settings author rows,
 *     forward-source chips, AddChannelSheet affordances) routes through
 *     [AppGraph.linkResolver] before falling out to ACTION_VIEW.
 *
 *   - [LocalLinkConfirm] hook for masked links (TDLib `Style.TextUrl`): the
 *     callback resolves the destination first; Telegram-internal targets jump
 *     in-app via the deep-link router with no friction, only genuine external
 *     destinations surface the [ExternalLinkConfirmDialog] showing the bolded
 *     host for anti-phishing review.
 *
 *   - The confirmation dialog itself, rendered from [AppGraph.linkDialogs]'s
 *     process-wide state so a rotation mid-decision doesn't drop the prompt.
 *     See [dev.lyo.hortay.data.LinkDialogState] for the lifecycle rationale.
 *
 * Both [MainScaffold] (TDLib mode) and [WebModeScaffold] (guest mode) used to
 * duplicate ~25 lines of this plumbing — extracting it here keeps the contract
 * in one place and means any future link-handling refinement lands once instead
 * of twice (the previous duplication had already drifted slightly between the
 * two sites).
 */
@Composable
fun LinkAwareScaffold(graph: AppGraph, content: @Composable () -> Unit) {
    val systemUriHandler = LocalUriHandler.current
    val appContext = LocalContext.current.applicationContext
    val hortayUriHandler = remember(graph, systemUriHandler, appContext) {
        HortayUriHandler(
            delegate = systemUriHandler,
            resolver = graph.linkResolver,
            router = graph.deepLinkRouter,
            scope = graph.appScope,
            context = appContext,
        )
    }
    // Confirmation callback for masked-link spans (TDLib `Style.TextUrl`). Suspending
    // resolution happens on the long-lived [AppGraph.appScope] — composition lifecycle
    // is decoupled from the call so a mid-resolve rotation doesn't lose the dialog.
    // The result is written to [AppGraph.linkDialogs.maskedLink], a MutableStateFlow,
    // which survives configuration changes. Previously the result was written to a
    // `rememberSaveable` inside this composable — the saveable bag was packed up
    // before the suspending call returned on rotation and the dialog disappeared.
    val confirmMaskedLink = remember(graph) {
        { url: String ->
            // Scheme gate BEFORE the resolver and the dialog — same allowlist as
            // [HortayUriHandler]. Otherwise a `javascript:` / `intent:` masked link got
            // an anti-phishing prompt whose Open button then silently did nothing.
            val parsed = runCatching { Uri.parse(url) }.getOrNull()
            if (parsed != null && isAllowedLinkScheme(parsed.scheme)) {
                graph.appScope.launch {
                    when (val link = graph.linkResolver.resolve(parsed)) {
                        null, is DeepLink.External -> graph.linkDialogs.showMaskedLink(url)
                        else -> graph.deepLinkRouter.submit(link)
                    }
                }
            }
            Unit
        }
    }
    // Hashtag tap → route through the same DeepLinkRouter both scaffolds already
    // observe, as a typed [DeepLink.HashtagSearch]. The default lambda here serves
    // surfaces with no surrounding channel context (Comments, future settings /
    // about screens). The PostBody composable installs a scoped override via
    // [CompositionLocalProvider] so feed-card taps carry the post's channel handle.
    //
    // We always run the tag through [parseHashtagWithScope] first so the canonical
    // `#tag@channel` text-entity form (per TDLib's `TextEntityTypeHashtag` docs:
    // *"optionally containing a chat username at the end"*) decomposes into
    // (`#tag`, `channel`) — overriding any scope captured by composition, since
    // the entity is self-describing.
    val hashtagTap = remember(graph) {
        { tag: String ->
            val (cleanTag, suffixHandle) = parseHashtagWithScope(tag)
            graph.deepLinkRouter.submit(
                DeepLink.HashtagSearch(
                    tag = cleanTag,
                    channelHandle = suffixHandle,
                    originalUrl = tag,
                ),
            )
            Unit
        }
    }
    val pendingMaskedLink by graph.linkDialogs.maskedLink.collectAsStateWithLifecycle()
    CompositionLocalProvider(
        LocalUriHandler provides hortayUriHandler,
        LocalLinkConfirm provides confirmMaskedLink,
        LocalHashtagTap provides hashtagTap,
    ) {
        content()
        pendingMaskedLink?.let { url ->
            ExternalLinkConfirmDialog(
                url = url,
                onDismiss = { graph.linkDialogs.dismissMaskedLink() },
            )
        }
        FeedOrderOnboardingHost(graph)
    }
}

/**
 * Hosts the one-time feed-order question. This composable lives inside [LinkAwareScaffold],
 * which only wraps the two post-auth scaffolds, so it never shows over the sign-in screen.
 * Any dismissal marks the flag, so the question is never asked twice.
 */
@Composable
private fun FeedOrderOnboardingHost(graph: AppGraph) {
    // Default true while the flag loads so the sheet never flashes for users who answered.
    val chosen by graph.onboardingStore.feedOrderChosen.collectAsStateWithLifecycle(initialValue = true)
    if (chosen) return
    val current by graph.settingsStore.feedOrder.collectAsStateWithLifecycle(
        initialValue = FeedOrder.OldestUnreadFirst,
    )
    FeedOrderOnboardingSheet(
        current = current,
        onConfirm = { order ->
            graph.appScope.launch {
                graph.settingsStore.setFeedOrder(order)
                graph.onboardingStore.setFeedOrderChosen()
            }
        },
        onDismiss = { graph.appScope.launch { graph.onboardingStore.setFeedOrderChosen() } },
    )
}
