package dev.lyo.hortay.ui.auth

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.LaunchedEffect

/**
 * Telegram/Apple-style segmented OTP input. A single hidden BasicTextField captures all
 * input — no per-cell focus juggling, no auto-advance race conditions, IME suggestions
 * (autofill from SMS) just work because the system sees one continuous text edit. The
 * visible row of [length] cells is rendered above and reflects the current value, with
 * a primary-coloured ring on the next-empty cell so the user knows where they are.
 *
 * On full input the field calls [onComplete] (typically used to auto-submit). Backspace
 * clears the last digit; non-digit characters are filtered out.
 */
@Composable
fun OtpInput(
    value: String,
    onValueChange: (String) -> Unit,
    length: Int = 5,
    autoFocus: Boolean = true,
    enabled: Boolean = true,
    isError: Boolean = false,
    onComplete: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    var hasFocus by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(autoFocus) {
        if (autoFocus) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }

    Box(modifier = modifier.fillMaxWidth()) {
        // Visible cells row sits underneath; the actual TextField is invisible (sized to
        // match) and stretches across the whole row so a tap anywhere brings the keyboard
        // up. We keep the cursor invisible because the cell border ring already signals
        // the active position better than a thin caret would.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            repeat(length) { index ->
                val char = value.getOrNull(index)?.toString().orEmpty()
                val active = index == value.length && hasFocus
                OtpCell(
                    char = char,
                    active = active,
                    isError = isError,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        BasicTextField(
            value = value,
            onValueChange = { raw ->
                val sanitized = raw.filter(Char::isDigit).take(length)
                onValueChange(sanitized)
                if (sanitized.length == length) onComplete(sanitized)
            },
            enabled = enabled,
            singleLine = true,
            textStyle = TextStyle(color = androidx.compose.ui.graphics.Color.Transparent),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(
                androidx.compose.ui.graphics.Color.Transparent,
            ),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.NumberPassword,
                imeAction = ImeAction.Done,
            ),
            interactionSource = remember { MutableInteractionSource() },
            modifier = Modifier
                .matchParentSize()
                .focusRequester(focusRequester)
                .focusable()
                .onFocusChanged { hasFocus = it.isFocused },
        )
    }
}

@Composable
private fun OtpCell(
    char: String,
    active: Boolean,
    isError: Boolean,
    modifier: Modifier = Modifier,
) {
    val borderColor by animateColorAsState(
        targetValue = when {
            isError -> MaterialTheme.colorScheme.error
            active -> MaterialTheme.colorScheme.primary
            char.isNotEmpty() -> MaterialTheme.colorScheme.outline
            else -> MaterialTheme.colorScheme.outlineVariant
        },
        animationSpec = tween(160),
        label = "otp-cell-border",
    )
    val containerColor by animateColorAsState(
        targetValue = if (active || char.isNotEmpty()) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        animationSpec = tween(160),
        label = "otp-cell-bg",
    )

    Box(
        modifier = modifier
            .height(64.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(containerColor)
            .border(
                width = if (active || isError) 2.dp else 1.dp,
                color = borderColor,
                shape = MaterialTheme.shapes.medium,
            ),
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.material3.Text(
            text = char,
            style = MaterialTheme.typography.headlineMedium.copy(
                fontWeight = FontWeight.SemiBold,
                fontSize = 28.sp,
            ),
            color = if (isError) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
    }
}
