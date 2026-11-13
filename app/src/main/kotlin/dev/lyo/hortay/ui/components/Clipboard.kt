package dev.lyo.hortay.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/** Puts [text] on the system clipboard as plain text; a no-op when the service is unavailable. */
internal fun Context.copyPlainText(label: String, text: String) {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}
