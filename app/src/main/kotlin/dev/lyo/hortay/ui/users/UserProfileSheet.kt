package dev.lyo.hortay.ui.users

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import dev.lyo.hortay.R
import dev.lyo.hortay.data.ChannelActionsRepository
import dev.lyo.hortay.data.PersonalChannelLink
import dev.lyo.hortay.data.PresenceStatus
import dev.lyo.hortay.data.SenderVerification
import dev.lyo.hortay.data.UserProfile
import dev.lyo.hortay.ui.components.PremiumStatusBadge
import dev.lyo.hortay.ui.theme.profileAccentBrush
import dev.lyo.hortay.ui.theme.profileRingBrush
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.media.TdAvatar
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * User-profile bottom sheet. Renders the same surface for every author entry point
 * the reader app exposes:
 *
 *   - comment authors in the discussion thread;
 *   - personal-author posts in a channel (admin posting under their own identity);
 *   - the "forwarded from <user>" chip on top of a forwarded post;
 *   - in-text user mentions that carry a [TdApi.TextEntityTypeMentionName.userId].
 *
 * Resolves [UserProfile] from [ChannelActionsRepository.userProfile] on entry; the
 * sheet stays visible during the loading window so the trigger feels instant — fields
 * populate inline as TDLib responds (mirrors the [dev.lyo.hortay.ui.channels.ChannelInfoSheet]
 * pattern by design, so users get one consistent affordance for both kinds of header).
 *
 * Visual structure (top → bottom):
 *   1. **Hero header** — 88dp avatar centred on a tonal disc, name + verification mark,
 *      `@handle` (tap to copy), presence line ("у мережі" / "нещодавно в мережі" / "у мережі Х тому").
 *   2. **Action chips row** — Material 3 `FilledTonalButton` pair. "Написати" opens the
 *      official Telegram app on a `tg://user?id=…` deep link (falls back to https://t.me/<handle>
 *      if the handle is set, or to the raw `tg://user` URL on the rare handle-less user).
 *      "Скопіювати @ʼ" copies the username (only if present).
 *   3. **Bio card** — surfaceContainerHigh tonal card, plain text. Bots: short/long description.
 *   4. **Personal channel** — tappable [ListItem] with channel avatar + title + handle +
 *      subscriber count. Tap drills into [dev.lyo.hortay.ui.main.MainScaffold]'s channel
 *      overlay via the [LocalChannelOpener] callback — same path as a regular subscribed
 *      channel tap; sheet auto-dismisses so the focus shifts cleanly.
 *   5. **Meta rows** — birthdate (when visible), groups-in-common count.
 *
 * The hero verification glyph mirrors the feed `VerificationBadge`: blue check for
 * Verified, red SCAM pill, amber FAKE pill. Bots and support accounts surface their own
 * small chip below the name so the user is never surprised by "why does this user have
 * a `/help` command".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserProfileSheet(
    userId: Long,
    actions: ChannelActionsRepository,
    /** Optional name we already know from the trigger (sender row, mention, forward chip).
     *  Used as the visible label until the resolver lands so the hero is never blank. */
    seedName: String? = null,
    seedAvatarThumb: ByteArray? = null,
    seedAvatarFileId: Int? = null,
    onDismiss: () -> Unit,
    onOpenChannel: (chatId: Long) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var profile by remember(userId) { mutableStateOf<UserProfile?>(null) }

    LaunchedEffect(userId) {
        profile = actions.userProfile(userId)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // Slightly larger top corner radius than the default to lean into M3 Expressive's
        // "more shape" stance — matches the channel-info / report sheets in this project.
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
        ) {
            ProfileHero(
                profile = profile,
                seedName = seedName,
                seedAvatarThumb = seedAvatarThumb,
                seedAvatarFileId = seedAvatarFileId,
            )
            Spacer(Modifier.height(20.dp))

            MessageAction(
                onMessage = { openInTelegram(it, profile, userId) },
            )

            profile?.bio?.let { bio ->
                Spacer(Modifier.height(20.dp))
                SectionLabel(text = stringResource(R.string.user_profile_bio))
                BioCard(text = bio)
            }
            profile?.botDescription?.takeIf { profile?.bio == null }?.let { desc ->
                Spacer(Modifier.height(20.dp))
                SectionLabel(text = stringResource(R.string.user_profile_bot_about))
                BioCard(text = desc)
            }

            profile?.personalChannel?.let { ch ->
                Spacer(Modifier.height(20.dp))
                SectionLabel(text = stringResource(R.string.user_profile_personal_channel))
                PersonalChannelRow(
                    channel = ch,
                    onClick = {
                        onOpenChannel(ch.chatId)
                        onDismiss()
                    },
                )
            }

            val meta = profile?.let { profileMetaRows(it) }.orEmpty()
            if (meta.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                SectionLabel(text = stringResource(R.string.user_profile_about))
                meta.forEach { row ->
                    MetaRow(symbol = row.symbol, label = row.label, value = row.value)
                }
            }
        }
    }
}

@Composable
private fun ProfileHero(
    profile: UserProfile?,
    seedName: String?,
    seedAvatarThumb: ByteArray?,
    seedAvatarFileId: Int?,
) {
    val resolvedName = profile?.displayName?.takeUnless { it.isBlank() }
        ?: seedName?.takeUnless { it.isBlank() }
        ?: stringResource(R.string.user_profile_loading_name)
    val avatarThumb = profile?.avatarThumb ?: seedAvatarThumb
    val avatarFileId = profile?.avatarFileId ?: seedAvatarFileId

    val accentId = profile?.profileAccentColorId ?: -1
    val accentBrush = profileAccentBrush(accentId)
    val ringBrush = profileRingBrush(accentId)

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Accent band behind the avatar — the user's own profile gradient (or the brand
        // fallback). Fades into the sheet surface so the name below stays on plain surface.
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .background(accentBrush),
            )
            // Faint scrim so a bright user-chosen accent never fights the avatar ring.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(96.dp)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, MaterialTheme.colorScheme.surface),
                        ),
                    ),
            )
            Box(
                modifier = Modifier
                    .padding(top = 48.dp)
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .border(width = 2.5.dp, brush = ringBrush, shape = CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                TdAvatar(
                    name = resolvedName,
                    thumb = avatarThumb,
                    fileId = avatarFileId,
                    size = 88.dp,
                    textStyle = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.SemiBold),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = resolvedName,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            profile?.verification?.let {
                Spacer(Modifier.width(6.dp))
                VerificationGlyph(it)
            }
            if (profile?.isPremium == true || profile?.emojiStatusId != null) {
                Spacer(Modifier.width(6.dp))
                PremiumStatusBadge(
                    isPremium = profile?.isPremium == true,
                    emojiStatusId = profile?.emojiStatusId,
                    size = 16.dp,
                )
            }
        }
        profile?.handle?.let { handle ->
            // Handle reads like a Telegram blue link, but the action it offers ISN'T
            // navigation — it's "copy to clipboard". Mirroring Telegram-Android: tap
            // the @username row in a profile card and the username lands on the
            // clipboard. Avoids the dedicated Copy button (read as visual clutter
            // when the @handle is already on screen one row above).
            val context = LocalContext.current
            Spacer(Modifier.height(2.dp))
            Text(
                text = handle,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { copyHandle(context, handle) }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        val statusLine = profile?.let { presenceLabel(it.status) }
        if (statusLine != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = statusLine,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Bot / Support chip stack — Telegram surfaces these as small badges next to the
        // name; we drop a row of tonal mini-chips so the user sees them at a glance.
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (profile?.isBot == true) {
                Chip(label = stringResource(R.string.user_profile_chip_bot))
            }
            if (profile?.isSupport == true) {
                Chip(
                    label = stringResource(R.string.user_profile_chip_support),
                    tint = MaterialTheme.colorScheme.secondaryContainer,
                )
            }
        }
        }
    }
}

/**
 * Sole action chip in the sheet. Was a two-button row (Message + Copy handle) — the
 * Copy half got folded into the `@handle` row above (Telegram-Android idiom: tap the
 * handle to copy). Single FilledTonalButton at full width is the MD3E pattern for a
 * "one primary action" header — Telegram-X's user-info card uses the same shape.
 */
@Composable
private fun MessageAction(onMessage: (Context) -> Unit) {
    val context = LocalContext.current
    FilledTonalButton(
        onClick = { onMessage(context) },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
    ) {
        Symbol(
            // No bundled `sym_send`; the `ios_share` glyph (arrow-out-of-box) carries
            // the same "send / outbound" semantic visually and is already in the pack.
            name = "ios_share",
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            size = 18.dp,
        )
        Spacer(Modifier.width(8.dp))
        Text(stringResource(R.string.user_profile_action_message))
    }
}

@Composable
private fun BioCard(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(16.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PersonalChannelRow(
    channel: PersonalChannelLink,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onClick),
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        leadingContent = {
            TdAvatar(
                name = channel.title.ifBlank { "?" },
                thumb = channel.avatarThumb,
                fileId = channel.avatarFileId,
                size = 44.dp,
                textStyle = MaterialTheme.typography.titleSmall,
            )
        },
        headlineContent = {
            Text(
                text = channel.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            val handle = channel.handle
            val subs = channel.subscribers
            val parts = listOfNotNull(
                handle,
                subs?.let { stringResource(R.string.channels_subscribers, formatThousands(it)) },
            )
            if (parts.isNotEmpty()) {
                Text(
                    text = parts.joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        trailingContent = {
            Symbol(
                name = "chevron_right",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                size = 20.dp,
            )
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MetaRow(symbol: String, label: String, value: String) {
    ListItem(
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .fillMaxWidth(),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Symbol(
                    name = symbol,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    size = 18.dp,
                )
            }
        },
        headlineContent = {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
        },
        trailingContent = {
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun VerificationGlyph(v: SenderVerification) {
    when (v) {
        SenderVerification.Verified -> Symbol(
            name = "verified",
            contentDescription = stringResource(R.string.cd_verified_badge),
            tint = MaterialTheme.colorScheme.primary,
            size = 18.dp,
        )
        SenderVerification.Scam -> Chip(
            label = "SCAM",
            tint = MaterialTheme.colorScheme.errorContainer,
            textColor = MaterialTheme.colorScheme.onErrorContainer,
        )
        SenderVerification.Fake -> Chip(
            label = "FAKE",
            tint = MaterialTheme.colorScheme.tertiaryContainer,
            textColor = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}

@Composable
private fun Chip(
    label: String,
    tint: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(tint)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = textColor,
        )
    }
}

private data class MetaRowSpec(val symbol: String, val label: String, val value: String)

@Composable
private fun profileMetaRows(profile: UserProfile): List<MetaRowSpec> = buildList {
    val birthLabel = formatBirthdate(profile)
    if (birthLabel != null) {
        add(
            MetaRowSpec(
                // `redeem` (gift box) is the closest semantic glyph in the bundled pack —
                // birthdays are a "gift" affordance in Telegram-Android.
                symbol = "redeem",
                label = stringResource(R.string.user_profile_birthday),
                value = birthLabel,
            ),
        )
    }
    if (profile.groupsInCommon > 0) {
        add(
            MetaRowSpec(
                symbol = "person",
                label = stringResource(R.string.user_profile_groups_in_common),
                value = formatThousands(profile.groupsInCommon),
            ),
        )
    }
}

@Composable
private fun formatBirthdate(profile: UserProfile): String? {
    val month = profile.birthMonth ?: return null
    val day = profile.birthDay ?: return null
    // LocalConfiguration is observable — Compose re-renders when the user flips the
    // per-app language picker in Settings → About. Locale.getDefault() looks correct
    // but is invisible to recomposition (lint flags it as NonObservableLocale).
    val locale = LocalConfiguration.current.locales[0]
    val monthName = java.text.DateFormatSymbols(locale).months
        .getOrNull(month - 1)
        ?.takeUnless { it.isBlank() } ?: month.toString()
    val year = profile.birthYear
    return if (year != null) "$day $monthName $year" else "$day $monthName"
}

@Composable
private fun presenceLabel(status: PresenceStatus): String? = when (status) {
    PresenceStatus.Online -> stringResource(R.string.user_profile_status_online)
    PresenceStatus.Recently -> stringResource(R.string.user_profile_status_recently)
    PresenceStatus.LastWeek -> stringResource(R.string.user_profile_status_last_week)
    PresenceStatus.LastMonth -> stringResource(R.string.user_profile_status_last_month)
    is PresenceStatus.Offline -> formatOfflineLabel(status.wasOnlineSeconds)
    PresenceStatus.Empty -> null
}

@Composable
private fun formatOfflineLabel(wasOnlineSec: Long): String {
    val now = System.currentTimeMillis() / 1000L
    val delta = (now - wasOnlineSec).coerceAtLeast(0)
    return when {
        delta < TimeUnit.MINUTES.toSeconds(1) ->
            stringResource(R.string.user_profile_status_just_now)
        delta < TimeUnit.HOURS.toSeconds(1) -> {
            val mins = TimeUnit.SECONDS.toMinutes(delta).toInt().coerceAtLeast(1)
            pluralStringResource(R.plurals.user_profile_status_was_minutes_ago, mins, mins)
        }
        delta < TimeUnit.DAYS.toSeconds(1) -> {
            val hours = TimeUnit.SECONDS.toHours(delta).toInt().coerceAtLeast(1)
            pluralStringResource(R.plurals.user_profile_status_was_hours_ago, hours, hours)
        }
        delta < TimeUnit.DAYS.toSeconds(7) -> {
            val days = TimeUnit.SECONDS.toDays(delta).toInt().coerceAtLeast(1)
            pluralStringResource(R.plurals.user_profile_status_was_days_ago, days, days)
        }
        else -> stringResource(R.string.user_profile_status_was_long_ago)
    }
}

private fun formatThousands(n: Int): String =
    NumberFormat.getNumberInstance(Locale.forLanguageTag("uk")).format(n)

/**
 * Hand the OS the right Telegram URL for "open a chat with this user". Prefers the
 * https://t.me/<handle> form when a handle exists — same logic as [dev.lyo.hortay.ui.actions.PostActions]:
 * the https scheme survives "no client installed" by deferring to the OS chooser,
 * whereas tg:// throws ActivityNotFoundException when no handler is registered.
 * Falls back to `tg://user?id=<id>` when the user has no public handle (works in
 * the official Telegram client; no-op everywhere else, but at least it doesn't crash).
 */
private fun openInTelegram(context: Context, profile: UserProfile?, fallbackUserId: Long) {
    val handle = profile?.handle?.removePrefix("@")?.takeUnless { it.isBlank() }
    val uri = if (handle != null) {
        "https://t.me/$handle".toUri()
    } else {
        "tg://user?id=$fallbackUserId".toUri()
    }
    val intent = Intent(Intent.ACTION_VIEW, uri).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }
}

private fun copyHandle(context: Context, handle: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
    cm.setPrimaryClip(ClipData.newPlainText(handle, handle))
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(
            context,
            context.getString(R.string.user_profile_handle_copied),
            Toast.LENGTH_SHORT,
        ).show()
    }
}
