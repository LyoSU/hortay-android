package dev.lyo.hortay.ui.timeline

import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.R
import dev.lyo.hortay.data.CallbackAnswer
import dev.lyo.hortay.data.InlineButton
import dev.lyo.hortay.data.InlineButtonAction
import dev.lyo.hortay.data.InlineButtonStyle
import dev.lyo.hortay.data.InlineKeyboard
import dev.lyo.hortay.ui.components.copyPlainText
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.media.CustomEmojiInlineView
import dev.lyo.hortay.ui.text.LocalLinkConfirm
import dev.lyo.hortay.ui.text.Unhandled
import dev.lyo.hortay.ui.users.LocalUserProfileOpener
import kotlinx.coroutines.launch

/**
 * Sends a callback query for an inline-keyboard button — (chatId, messageId, data) — and
 * returns the bot's answer; null when it failed or timed out.
 */
typealias InlineCallbackPresser = suspend (chatId: Long, messageId: Long, data: ByteArray) -> CallbackAnswer?

/**
 * Callback-button handler. Provided by the signed-in scaffold only; null in guest mode and
 * previews, where a callback button opens the post in Telegram instead.
 */
val LocalInlineCallbackPresser = staticCompositionLocalOf<InlineCallbackPresser?> { null }

/** Gap between buttons, both ways — Telegram's inline keyboard is a tight grid. */
private val BUTTON_GAP = 4.dp
private val BUTTON_SHAPE = RoundedCornerShape(12.dp)

/**
 * A bot's inline keyboard under a post: rows of equal-width buttons, like Telegram's.
 *
 *  - URL buttons open through the app's link path ([LocalLinkConfirm]: Telegram links open
 *    in-app, other destinations ask first — a button label hides its URL just like a masked
 *    link does), and carry a small corner glyph saying so.
 *  - Callback buttons ask the bot ([LocalInlineCallbackPresser]); the button shows a spinner
 *    while it waits. A short answer arrives as a snackbar (posted by the repository), an
 *    alert answer as a dialog, an answer URL opens through the same link path.
 *  - Copy buttons copy their text, with the same "Copied" toast the post menu uses below
 *    Android 13 (13+ shows its own clipboard confirmation).
 *  - User buttons open the profile sheet.
 *  - Everything Hortay doesn't host (login, web app, inline switch, payment, game, password
 *    callback) opens the post in Telegram via [onOpenInSource] — the Invoice / Game card path.
 *
 * Quiet surface (ARCHITECTURE.md → intensity budget): tonal fills, no motion beyond the
 * press ripple. Colours follow the button's Telegram style, mapped to theme roles.
 */
@Composable
internal fun InlineKeyboardBlock(
    keyboard: InlineKeyboard,
    chatId: Long,
    onOpenInSource: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val presser = LocalInlineCallbackPresser.current
    val confirmLink = LocalLinkConfirm.current
    val uriHandler = LocalUriHandler.current
    val profileOpener = LocalUserProfileOpener.current
    val context = LocalContext.current
    val copiedToast = stringResource(R.string.post_copied_toast)
    val scope = rememberCoroutineScope()
    // One press at a time per keyboard: the pending cell is keyed by row/column.
    var pending by remember(keyboard) { mutableStateOf<Pair<Int, Int>?>(null) }
    var alert by remember(keyboard) { mutableStateOf<String?>(null) }

    val openLink: (String) -> Unit = remember(confirmLink, uriHandler) {
        { url ->
            if (confirmLink !== Unhandled) confirmLink(url) else runCatching { uriHandler.openUri(url) }
        }
    }

    fun onPress(row: Int, col: Int, button: InlineButton) {
        when (val action = button.action) {
            is InlineButtonAction.Url -> openLink(action.url)
            is InlineButtonAction.CopyText -> {
                context.copyPlainText(button.text, action.text)
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    Toast.makeText(context, copiedToast, Toast.LENGTH_SHORT).show()
                }
            }
            is InlineButtonAction.User -> profileOpener.open(action.userId)
            is InlineButtonAction.Callback -> {
                if (presser == null) {
                    onOpenInSource()
                    return
                }
                if (pending != null) return
                pending = row to col
                scope.launch {
                    try {
                        val answer = presser(chatId, keyboard.messageId, action.data) ?: return@launch
                        if (answer.showAlert && answer.text.isNotBlank()) alert = answer.text
                        answer.url?.let(openLink)
                    } finally {
                        pending = null
                    }
                }
            }
            InlineButtonAction.OpenInTelegram -> onOpenInSource()
        }
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(BUTTON_GAP),
    ) {
        keyboard.rows.forEachIndexed { r, row ->
            // IntrinsicSize.Min lets a one-line button stretch to the height of a two-line
            // neighbour, so a row reads as one band.
            Row(
                modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                horizontalArrangement = Arrangement.spacedBy(BUTTON_GAP),
            ) {
                row.forEachIndexed { c, button ->
                    InlineKeyboardButton(
                        button = button,
                        isPending = pending == (r to c),
                        onClick = { onPress(r, c, button) },
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }
    }

    alert?.let { text ->
        AlertDialog(
            onDismissRequest = { alert = null },
            text = { Text(text) },
            confirmButton = {
                TextButton(onClick = { alert = null }) { Text(stringResource(R.string.action_close)) }
            },
        )
    }
}

@Immutable
private data class ButtonColors(val container: Color, val content: Color)

@Composable
private fun InlineButtonStyle.colors(): ButtonColors {
    val scheme = MaterialTheme.colorScheme
    return when (this) {
        InlineButtonStyle.Default -> ButtonColors(scheme.secondaryContainer, scheme.onSecondaryContainer)
        InlineButtonStyle.Primary -> ButtonColors(scheme.primaryContainer, scheme.onPrimaryContainer)
        InlineButtonStyle.Danger -> ButtonColors(scheme.errorContainer, scheme.onErrorContainer)
        // The scheme has no green role; the app's status palette does, light and dark.
        InlineButtonStyle.Success -> {
            val success = dev.lyo.hortay.ui.theme.LocalHortayStatusColors.current.success
            ButtonColors(success.copy(alpha = SUCCESS_TINT_ALPHA).compositeOver(scheme.surface), success)
        }
    }
}

private const val SUCCESS_TINT_ALPHA = 0.16f

@Composable
private fun InlineKeyboardButton(
    button: InlineButton,
    isPending: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = button.style.colors()
    val pendingDescription = stringResource(R.string.inline_button_pending)
    val cornerGlyph = when (button.action) {
        is InlineButtonAction.Url, InlineButtonAction.OpenInTelegram -> "open_in_new"
        is InlineButtonAction.CopyText -> "content_copy"
        else -> null
    }
    // Surface(onClick) gives the Button role and pads the touch target to 48 dp.
    Surface(
        onClick = onClick,
        enabled = !isPending,
        shape = BUTTON_SHAPE,
        color = colors.container,
        contentColor = colors.content,
        modifier = modifier
            .heightIn(min = 44.dp)
            .semantics { if (isPending) stateDescription = pendingDescription },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    // Kept laid out (not removed) while pending so the button keeps its size.
                    .then(if (isPending) Modifier.alpha(0f) else Modifier),
            ) {
                button.iconCustomEmojiId?.let { id ->
                    CustomEmojiInlineView(customEmojiId = id, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    text = button.text,
                    style = MaterialTheme.typography.labelLarge,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (isPending) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = colors.content,
                    strokeWidth = 2.dp,
                )
            }
            if (cornerGlyph != null) {
                Symbol(
                    name = cornerGlyph,
                    tint = colors.content.copy(alpha = 0.7f),
                    size = 12.dp,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 5.dp, end = 5.dp),
                )
            }
        }
    }
}
