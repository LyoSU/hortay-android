@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.lyo.hortay.ui.text

import android.net.Uri
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import dev.lyo.hortay.R

/**
 * Anti-phishing confirmation dialog for external URLs reached via a masked link span
 * (TDLib `Style.TextUrl` — where the visible text and the actual URL differ). Same
 * pattern Telegram-Android uses: surface the destination domain so the user can verify
 * before they leave the app.
 *
 * Triggered ONLY for external (non-Telegram) URLs. Inline `Url` spans where the visible
 * text already IS the URL skip this; internal `tg://` / `t.me` URIs route through
 * HortayUriHandler without a dialog because they're in-app navigation. The dialog
 * extracts and bolds the domain (host without `www.`) so the user's eye lands on it
 * instead of getting lost in a long query string.
 */
@Composable
fun ExternalLinkConfirmDialog(
    url: String,
    onDismiss: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val host = extractHost(url) ?: url
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.link_confirm_title)) },
        text = {
            Text(
                text = stringResource(R.string.link_confirm_body, host),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        },
        confirmButton = {
            TextButton(onClick = {
                runCatching { uriHandler.openUri(url) }
                onDismiss()
            }) { Text(stringResource(R.string.link_action_open)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        },
    )
}

private fun extractHost(url: String): String? {
    val parsed = runCatching { Uri.parse(url) }.getOrNull() ?: return null
    return parsed.host?.lowercase()?.removePrefix("www.")
}
