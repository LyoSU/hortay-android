package dev.lyo.hortay.ui.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import dev.lyo.hortay.R
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.data.ChannelActionsRepository
import dev.lyo.hortay.data.ChannelInfo
import dev.lyo.hortay.ui.icons.Symbol
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.util.Locale

/**
 * Bottom sheet rendered when the user taps a channel header in filtered-feed mode. Pulls
 * fresh metadata (title, handle, description, subscriber count, mute/member state) from
 * [ChannelActionsRepository] on entry; the sheet itself stays visible during the loading
 * window so the trigger feels instant — fields populate inline as TDLib responds.
 *
 * Mute / Leave toggles are optimistic: the local state flips first, then the call goes
 * out. Errors fall back to the server state on the next refresh; given how rare a write
 * failure is for these endpoints, the optimism reads as snappy without misleading the
 * user for long.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChannelInfoSheet(
    chatId: Long,
    actions: ChannelActionsRepository,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var info by remember(chatId) { mutableStateOf<ChannelInfo?>(null) }

    // First open or chatId switch → fetch fresh.
    LaunchedEffect(chatId) {
        info = actions.channelInfo(chatId)
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        val current = info
        Column(modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 24.dp)) {
            if (current == null) {
                Text(
                    stringResource(R.string.channels_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
                return@Column
            }
            Text(
                text = current.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            current.handle?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(8.dp))
            current.subscribers?.let {
                Text(
                    text = stringResource(R.string.channels_subscribers, formatThousands(it)),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            current.description?.let { desc ->
                Spacer(Modifier.height(16.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(22.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(16.dp),
                ) {
                    Text(
                        text = desc,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            ActionRow(
                symbol = if (current.isMuted) "notifications_active" else "notifications_off",
                label = stringResource(if (current.isMuted) R.string.channels_unmute else R.string.channels_mute),
                onClick = {
                    val target = !current.isMuted
                    info = current.copy(isMuted = target)
                    scope.launch { actions.setMuted(chatId, target) }
                },
            )
            if (current.isMember) {
                ActionRow(
                    symbol = "logout",
                    label = stringResource(R.string.channels_leave),
                    tint = MaterialTheme.colorScheme.error,
                    onClick = {
                        info = current.copy(isMember = false)
                        scope.launch { actions.leaveChat(chatId) }
                    },
                )
            } else {
                ActionRow(
                    symbol = "add",
                    label = stringResource(R.string.channels_join),
                    tint = MaterialTheme.colorScheme.primary,
                    onClick = {
                        info = current.copy(isMember = true)
                        scope.launch { actions.joinChat(chatId) }
                    },
                )
            }
        }
    }
}

@Composable
private fun ActionRow(
    symbol: String,
    label: String,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Symbol(
                name = symbol,
                tint = tint,
                size = 20.dp,
            )
        }
        Spacer(Modifier.width(16.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = tint,
        )
    }
}

private fun formatThousands(n: Int): String =
    NumberFormat.getNumberInstance(Locale.forLanguageTag("uk")).format(n)
