@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package dev.lyo.hortay.ui.text

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.R

/**
 * Anti-phishing confirmation dialog for external URLs reached via a masked link span
 * (TDLib `Style.TextUrl` — where the visible text and the actual URL differ). Same
 * pattern Telegram-Android uses: surface the full destination URL with the host part
 * bolded so the user's eye lands on the security-relevant slice of the URL.
 *
 * Triggered ONLY for external (non-Telegram) URLs. Inline `Url` spans where the visible
 * text already IS the URL skip this; internal `tg://` / `t.me` URIs route through
 * HortayUriHandler without a dialog because they're in-app navigation. The full URL
 * is rendered via [prettyLinkPreview] (same helper the long-press action sheet uses)
 * so the typography stays consistent across both anti-phishing surfaces.
 */
@Composable
fun ExternalLinkConfirmDialog(
    url: String,
    onDismiss: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val pretty = remember(url) { prettyLinkPreview(url) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.link_confirm_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.link_confirm_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(androidx.compose.ui.Modifier.height(8.dp))
                Text(
                    text = pretty,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
