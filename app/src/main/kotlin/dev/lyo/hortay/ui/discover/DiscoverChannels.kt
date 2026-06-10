@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.lyo.hortay.ui.discover

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.data.animatorDurationScale
import dev.lyo.hortay.data.discover.ChannelCardData
import dev.lyo.hortay.data.discover.SuggestedGroup
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.media.TdAvatar
import kotlinx.coroutines.launch

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
    subscribedLabel: String,
    onAdd: suspend (username: String) -> Boolean,
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
            val username = channel.username
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
                subscribedLabel = subscribedLabel,
                actionEnabled = true,
                onAction = remember(username, onAdd) { { onAdd(username) } },
            )
        }
    }
}

@Composable
fun SectionHeader(title: String) {
    // Clean canvas: section labels read as quiet structure, not links. `primary`
    // here registered as tappable; `onSurfaceVariant` keeps it a header (DESIGN_POLISH G3).
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 4.dp),
    )
}

/**
 * Stadium "search bar" idiom shared by the add-channel sheets (DESIGN_POLISH H2):
 * a `surfaceContainerHigh` fill (one of the few legitimate fills — it's an input),
 * full corner radius, leading `search` glyph, no outline/underline. Replaces the
 * boxy `OutlinedTextField` that read as a form field rather than a search affordance.
 *
 * Built on M3 `TextField` with all indicator lines made transparent and the
 * container coloured directly so the stadium shape reads as one solid pill rather
 * than a filled box with an underline.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoverSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    onSearch: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        shape = CircleShape,
        placeholder = { Text(hint, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = {
            Symbol(name = "search", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
        trailingIcon = trailing,
        keyboardOptions = KeyboardOptions(
            imeAction = if (onSearch != null) ImeAction.Search else ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(
            onSearch = { onSearch?.invoke() },
        ),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            disabledIndicatorColor = Color.Transparent,
            errorIndicatorColor = Color.Transparent,
        ),
        modifier = modifier.fillMaxWidth(),
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
    subscribedLabel: String,
    actionEnabled: Boolean,
    onAction: suspend () -> Boolean,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TdAvatar(
            // Strip the "@" before the initial-letter fallback kicks in: while the row
            // is un-hydrated `name` falls back to "@handle", and an "@" disc next to an
            // "@handle" title read as a broken placeholder, not a channel.
            name = name.removePrefix("@"),
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
            // Un-hydrated rows fall back to name == "@handle"; repeating the same
            // string on the second line read as a rendering bug. Show the handle
            // only when it adds information beyond the title.
            val secondary = buildString {
                if (!name.equals(handle, ignoreCase = true)) append(handle)
                if (!subscribers.isNullOrBlank()) {
                    if (isNotEmpty()) append("  ·  ")
                    append(subscribers)
                }
            }
            if (secondary.isNotBlank()) {
                Text(
                    text = secondary,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
        SubscribeButton(
            label = actionLabel,
            subscribedLabel = subscribedLabel,
            enabled = actionEnabled,
            onAction = onAction,
        )
    }
}

/**
 * Per-row subscribe button with a disciplined, layout-stable state machine
 * (DESIGN_POLISH H1 + M2). 36 dp compact height; the WIDTH is pinned by invisible
 * sizing ghosts of the widest states (the idle label and the check+[subscribedLabel]
 * row composed at alpha 0 under the live content), so the button cannot change size
 * across Idle → InFlight → Subscribed in ANY locale — doctrine rule 3, "nothing jumps
 * under the finger". (`widthIn(min)` alone was not enough: `AnimatedContent` resizes
 * to its content, so a wide "Підписатися" visibly shrank to the spinner mid-flight.)
 *
 *  - **Idle** → tonal label.
 *  - **InFlight** → an inline spinner replaces the label the instant the row is
 *    tapped (optimistic: the press is acknowledged before the network answers).
 *  - **Subscribed** → on success the button shows a `check_circle` glyph +
 *    [subscribedLabel] in a disabled state for ~1.5 s before the parent drops the
 *    row from the list. On failure it rolls straight back to Idle (the caller
 *    surfaces the error snackbar) — reversible, so no confirm.
 *
 * The label↔spinner↔check swap crossfades via `fastEffectsSpec`; under reduced
 * motion (`animatorDurationScale() == 0`) the crossfade collapses to an instant
 * swap so the state still changes visibly without animation.
 */
@Composable
private fun SubscribeButton(
    label: String,
    subscribedLabel: String,
    enabled: Boolean,
    onAction: suspend () -> Boolean,
) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(SubscribeState.IDLE) }
    val reducedMotion = animatorDurationScale() == 0f
    val effectsSpec = MaterialTheme.motionScheme.fastEffectsSpec<Float>()

    // External "already subscribed" signal (e.g. membership re-resolve from the
    // search path) — reflect it without a fake animation when the user didn't tap.
    val resolvedState = if (!enabled && state == SubscribeState.IDLE) {
        SubscribeState.SUBSCRIBED
    } else {
        state
    }

    FilledTonalButton(
        onClick = {
            if (state != SubscribeState.IDLE) return@FilledTonalButton
            state = SubscribeState.IN_FLIGHT
            scope.launch {
                val ok = onAction()
                state = if (ok) SubscribeState.SUBSCRIBED else SubscribeState.IDLE
            }
        },
        enabled = enabled && resolvedState == SubscribeState.IDLE,
        shapes = ButtonDefaults.shapes(),
        // Compact content padding so the 36 dp fixed height isn't over-constrained
        // (the default 24×8 dp leaves no room for the label at this height).
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
        modifier = Modifier
            .widthIn(min = 96.dp)
            .height(36.dp),
    ) {
        Box(contentAlignment = Alignment.Center) {
            // Invisible sizing ghosts: the two widest states reserve the button's
            // final width so the AnimatedContent swap below can never resize it.
            // Alpha 0 keeps them out of sight; the live content is the a11y node.
            SubscribedContent(subscribedLabel, modifier = Modifier.alpha(0f))
            Text(label, maxLines = 1, modifier = Modifier.alpha(0f))
            AnimatedContent(
                targetState = resolvedState,
                transitionSpec = {
                    if (reducedMotion) {
                        fadeIn(animationSpec = snapEffects) togetherWith
                            fadeOut(animationSpec = snapEffects)
                    } else {
                        fadeIn(animationSpec = effectsSpec) togetherWith
                            fadeOut(animationSpec = effectsSpec)
                    }
                },
                label = "subscribe_state",
            ) { s ->
                when (s) {
                    SubscribeState.IDLE -> Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    SubscribeState.IN_FLIGHT -> Box(
                        modifier = Modifier.height(20.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        LoadingIndicator(modifier = Modifier.size(16.dp))
                    }
                    SubscribeState.SUBSCRIBED -> SubscribedContent(subscribedLabel)
                }
            }
        }
    }
}

/** The "✓ Subscribed" confirmation row — also composed invisibly as a sizing ghost. */
@Composable
private fun SubscribedContent(label: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Symbol(name = "check_circle", size = 16.dp)
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private enum class SubscribeState { IDLE, IN_FLIGHT, SUBSCRIBED }

// A zero-duration spec stand-in for reduced motion. `AnimatedContent` needs a
// FiniteAnimationSpec; a snap-equivalent fade is the documented reduced-motion
// collapse used across the app (see effectiveSkeletonGrace).
private val snapEffects = snap<Float>()
