package dev.lyo.hortay.ui.main

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import dev.lyo.hortay.AppGraph
import dev.lyo.hortay.data.DeepLink
import dev.lyo.hortay.ui.text.ExternalLinkConfirmDialog
import dev.lyo.hortay.ui.text.LocalLinkConfirm
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
 *   - The confirmation dialog itself, hosted as a sibling of [content] so it
 *     overlays whatever screen is in front. State is `rememberSaveable` so a
 *     mid-decision config change doesn't dismiss the prompt.
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
    val hortayUriHandler = remember(graph, systemUriHandler) {
        HortayUriHandler(
            delegate = systemUriHandler,
            resolver = graph.linkResolver,
            router = graph.deepLinkRouter,
            scope = graph.appScope,
        )
    }
    var pendingMaskedLink by rememberSaveable { mutableStateOf<String?>(null) }
    val confirmMaskedLink = remember(graph) {
        { url: String ->
            graph.appScope.launch {
                val parsed = runCatching { Uri.parse(url) }.getOrNull()
                val link = parsed?.let { graph.linkResolver.resolve(it) }
                when (link) {
                    null, is DeepLink.External -> pendingMaskedLink = url
                    else -> graph.deepLinkRouter.submit(link)
                }
            }
            Unit
        }
    }
    CompositionLocalProvider(
        LocalUriHandler provides hortayUriHandler,
        LocalLinkConfirm provides confirmMaskedLink,
    ) {
        content()
        pendingMaskedLink?.let { url ->
            ExternalLinkConfirmDialog(url = url, onDismiss = { pendingMaskedLink = null })
        }
    }
}
