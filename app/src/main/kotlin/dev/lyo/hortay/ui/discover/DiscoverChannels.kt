@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.lyo.hortay.ui.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.data.discover.ChannelCardData
import dev.lyo.hortay.data.discover.SuggestedGroup
import dev.lyo.hortay.ui.media.TdAvatar

/**
 * Shared building blocks for the "discover / add channels" surfaces in both guest
 * (t.me/s) and authenticated (TDLib) modes. The catalog (sections + handles) and
 * the row chrome are identical across modes; only the hydration source and the
 * subscribe action differ, so those arrive as a [hydrated] lookup map and an
 * [onAdd] callback.
 *
 * Emitted into the caller's own `LazyColumn` so each sheet can prepend its search
 * field / preview card and keep one scroll container.
 */
fun LazyListScope.discoverSuggestions(
    groups: List<SuggestedGroup>,
    hydrated: Map<String, ChannelCardData>,
    addLabel: String,
    onAdd: (username: String) -> Unit,
) {
    groups.forEach { group ->
        item(key = "header_${group.categoryId}", contentType = "discover_header") {
            SectionHeader(group.title)
        }
        items(
            items = group.channels,
            key = { "ch_${group.categoryId}_${it.username}" },
            contentType = { "discover_row" },
        ) { channel ->
            val card = hydrated[channel.username.lowercase()]
            ChannelDiscoverRow(
                name = card?.title?.takeIf { it.isNotBlank() }
                    ?: channel.titleOverride
                    ?: "@${channel.username}",
                handle = "@${channel.username}",
                subscribers = card?.subscribersText,
                description = channel.description,
                avatarThumb = card?.avatarThumb,
                avatarFileId = card?.avatarFileId,
                avatarUrl = card?.avatarUrl,
                actionLabel = addLabel,
                actionEnabled = true,
                onAction = { onAdd(channel.username) },
            )
        }
    }
}

@Composable
fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 4.dp),
    )
}

/**
 * One channel card: avatar (TDLib minithumb→file ladder or a web CDN URL), display
 * name, `@handle · subscribers`, an editorial blurb, and a trailing action button.
 * While [loading] (hydration in flight) the avatar falls back to the initial-letter
 * disc and the subscriber line is omitted — no skeleton flicker, the row just fills
 * in when the live data lands.
 */
@Composable
fun ChannelDiscoverRow(
    name: String,
    handle: String,
    subscribers: String?,
    description: String?,
    avatarThumb: ByteArray?,
    avatarFileId: Int?,
    avatarUrl: String?,
    actionLabel: String,
    actionEnabled: Boolean,
    onAction: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TdAvatar(
            name = name,
            thumb = avatarThumb,
            fileId = avatarFileId,
            size = 44.dp,
            remoteUrl = avatarUrl,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val secondary = buildString {
                append(handle)
                if (!subscribers.isNullOrBlank()) {
                    append("  ·  ")
                    append(subscribers)
                }
            }
            Text(
                text = secondary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        FilledTonalButton(
            onClick = onAction,
            enabled = actionEnabled,
            shapes = ButtonDefaults.shapes(),
        ) {
            Text(actionLabel)
        }
    }
}
