@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.lyo.hortay.ui.text

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.R
import dev.lyo.hortay.ui.icons.Symbol
import kotlinx.coroutines.launch

/**
 * Telegram-style action sheet for a long-pressed link. Three rows:
 *   - Open      → routes through [LocalUriHandler], so internal Telegram URIs land
 *                  inside Hortay via [dev.lyo.hortay.ui.main.HortayUriHandler] and
 *                  generic web URLs fall through to ACTION_VIEW.
 *   - Copy link → system clipboard. On API < 33 we surface a toast (Android 12L+
 *                  shows its own confirmation, so we suppress to avoid a double-toast).
 *   - Share     → ACTION_SEND text chooser, link-scoped (no excerpt — that's
 *                  [dev.lyo.hortay.ui.actions.PostActions.share]'s job).
 *
 * The full URL is shown in the sheet header (2-line truncated) as an anti-phishing
 * affordance: the visible link text and the actual target URL can differ for `TextUrl`
 * spans, and surfacing it gives the user a chance to verify before they act.
 */
@Composable
fun LinkActionsSheet(
    url: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()

    val dismiss: () -> Unit = {
        scope.launch {
            runCatching { sheetState.hide() }
            onDismiss()
        }
    }

    val pretty = remember(url) { prettyLinkPreview(url) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(modifier = Modifier.padding(bottom = 12.dp)) {
            Text(
                text = pretty,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            )
            Spacer(Modifier.height(4.dp))

            LinkActionRow(
                label = stringResource(R.string.link_action_open),
                icon = "open_in_new",
                onClick = {
                    uriHandler.openUri(url)
                    dismiss()
                },
            )
            LinkActionRow(
                label = stringResource(R.string.link_action_copy),
                icon = "content_copy",
                onClick = {
                    copyLink(context, url)
                    dismiss()
                },
            )
            LinkActionRow(
                label = stringResource(R.string.link_action_share),
                icon = "share",
                onClick = {
                    shareLink(context, url)
                    dismiss()
                },
            )
        }
    }
}

@Composable
private fun LinkActionRow(label: String, icon: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(label) },
        leadingContent = { Symbol(name = icon) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    )
}

/**
 * Format a URL for the sheet header: scheme + bold host + plain path/query, truncated
 * past [MAX_PRETTY_LEN] so a 2-line ellipsis row never blows up the sheet on a long
 * query string. Falling back to the raw string on any parse error keeps the user
 * informed even if the URL is malformed (the parse error itself isn't surprising —
 * the OS / browser would have rejected it anyway).
 */
private fun prettyLinkPreview(raw: String): AnnotatedString {
    val parsed = runCatching { android.net.Uri.parse(raw) }.getOrNull()
    val host = parsed?.host?.lowercase()?.removePrefix("www.")
    if (host.isNullOrBlank()) return AnnotatedString(raw.take(MAX_PRETTY_LEN))

    val scheme = parsed.scheme?.let { "$it://" }.orEmpty()
    val path = parsed.encodedPath.orEmpty()
    val query = parsed.encodedQuery?.let { "?$it" }.orEmpty()
    val fragment = parsed.encodedFragment?.let { "#$it" }.orEmpty()
    val tail = (path + query + fragment).let {
        if (scheme.length + host.length + it.length > MAX_PRETTY_LEN) {
            it.take((MAX_PRETTY_LEN - scheme.length - host.length).coerceAtLeast(0)) + "…"
        } else it
    }

    return buildAnnotatedString {
        append(scheme)
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(host) }
        append(tail)
    }
}

private const val MAX_PRETTY_LEN = 96

private fun copyLink(context: Context, url: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText("link", url))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(context, context.getString(R.string.link_copied_toast), Toast.LENGTH_SHORT).show()
    }
}

private fun shareLink(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, url)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(Intent.createChooser(intent, null))
}
