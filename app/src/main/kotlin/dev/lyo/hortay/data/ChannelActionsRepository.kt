package dev.lyo.hortay.data

import dev.lyo.hortay.R
import kotlinx.coroutines.flow.StateFlow
import org.drinkless.tdlib.TdApi

/**
 * Stateless TDLib write-actions for channel/chat operations: react, mute, join, leave.
 *
 * Each method is a thin coroutine wrapper around a single TDLib call wrapped in
 * [warnUnlessCancelled] so the UI can fire-and-forget without crashing on rare TDLib
 * errors (FLOOD_WAIT, restricted reactions, channel left mid-tap…). Repositories that
 * own the resulting state ([PostsRepository] for reactions, this layer for chat info)
 * pick up the change via TDLib's update stream — no need to round-trip the result here.
 *
 * User-initiated actions (react / mute / join / leave) surface failures via
 * [UserMessageBus]; cosmetic reads (channelInfo) stay silent because the sheet shows
 * its own loading/empty state.
 */
class ChannelActionsRepository(
    private val td: TdSender,
    private val userMessages: UserMessageBus,
    private val connection: StateFlow<ConnectionStatus>,
    private val res: StringResolver,
) {

    /**
     * Toggle one of the user's reactions on a message. `isChosen` is the value as the user
     * sees it BEFORE tapping; we flip the side. Passing `isBig=false` and
     * `updateRecentReactions=true` floats the user's most-used reactions to the top of
     * TDLib's reaction picker — same behaviour the official client ships.
     *
     * Custom-emoji reactions go through the same path: TDLib gates them server-side (only
     * Premium users can add custom-emoji reactions to most chats), and on rejection it
     * just no-ops with an error we log and discard. The UI chip still toggles optimistic
     * if the caller wants — TDLib will reconcile via UpdateMessageInteractionInfo.
     */
    suspend fun toggleReaction(
        chatId: Long,
        messageId: Long,
        kind: ReactionKind,
        isChosen: Boolean,
    ) {
        val type = kind.toTd()
        runCatching {
            if (isChosen) {
                td.send(TdApi.RemoveMessageReaction(chatId, messageId, type))
            } else {
                td.send(
                    TdApi.AddMessageReaction(
                        chatId,
                        messageId,
                        type,
                        /* isBig */ false,
                        /* updateRecentReactions */ true,
                    ),
                )
            }
        }
            .warnUnlessCancelled(TAG, "toggleReaction(${kind.stableKey}, isChosen=$isChosen)")
            .onFailure { it.surfaceTo(userMessages, res, R.string.op_change_reaction, connection.value) }
    }

    private fun ReactionKind.toTd(): TdApi.ReactionType = when (this) {
        is ReactionKind.Emoji -> TdApi.ReactionTypeEmoji(text)
        is ReactionKind.CustomEmoji -> TdApi.ReactionTypeCustomEmoji(customEmojiId)
    }

    /**
     * Mute / unmute a chat. `muteFor` is seconds; 0 = unmuted, very large = "forever"
     * which Telegram represents as the special INT_MAX-ish sentinel. We use 365 days
     * as a pragmatic "forever" — matches the official client's longest preset and avoids
     * the user being surprised by reactivated notifications a year out.
     */
    suspend fun setMuted(chatId: Long, muted: Boolean) {
        val current = runCatching { td.send(TdApi.GetChat(chatId)) }
            .warnUnlessCancelled(TAG, "setMuted/getChat")
            .getOrNull()?.notificationSettings ?: TdApi.ChatNotificationSettings()
        val updated = TdApi.ChatNotificationSettings().apply {
            useDefaultMuteFor = false
            muteFor = if (muted) MUTE_FOREVER_SECONDS else 0
            useDefaultSound = current.useDefaultSound
            soundId = current.soundId
            useDefaultShowPreview = current.useDefaultShowPreview
            showPreview = current.showPreview
            useDefaultMuteStories = current.useDefaultMuteStories
            muteStories = current.muteStories
            useDefaultStorySound = current.useDefaultStorySound
            storySoundId = current.storySoundId
            useDefaultShowStoryPoster = current.useDefaultShowStoryPoster
            showStoryPoster = current.showStoryPoster
            useDefaultDisablePinnedMessageNotifications = current.useDefaultDisablePinnedMessageNotifications
            disablePinnedMessageNotifications = current.disablePinnedMessageNotifications
            useDefaultDisableMentionNotifications = current.useDefaultDisableMentionNotifications
            disableMentionNotifications = current.disableMentionNotifications
        }
        runCatching { td.send(TdApi.SetChatNotificationSettings(chatId, updated)) }
            .warnUnlessCancelled(TAG, "setMuted($muted)")
            .onFailure { it.surfaceTo(userMessages, res, if (muted) R.string.op_mute else R.string.op_unmute, connection.value) }
    }

    suspend fun isMuted(chatId: Long): Boolean {
        val chat = runCatching { td.send(TdApi.GetChat(chatId)) }
            .warnUnlessCancelled(TAG, "isMuted")
            .getOrNull() ?: return false
        return chat.notificationSettings?.muteFor.let { it != null && it > 0 }
    }

    suspend fun joinChat(chatId: Long) {
        runCatching { td.send(TdApi.JoinChat(chatId)) }
            .warnUnlessCancelled(TAG, "joinChat")
            .onFailure { it.surfaceTo(userMessages, res, R.string.op_join_channel, connection.value) }
    }

    suspend fun leaveChat(chatId: Long) {
        runCatching { td.send(TdApi.LeaveChat(chatId)) }
            .warnUnlessCancelled(TAG, "leaveChat")
            .onFailure { it.surfaceTo(userMessages, res, R.string.op_leave_channel, connection.value) }
    }

    /**
     * Resolve a channel info bundle for the bottom sheet: title, handle, description,
     * subscriber count, mute state. Several TDLib calls coalesced — kept on this single
     * suspend method so the UI fires one coroutine and lays out when everything is in.
     */
    suspend fun channelInfo(chatId: Long): ChannelInfo? {
        val chat = runCatching { td.send(TdApi.GetChat(chatId)) }
            .warnUnlessCancelled(TAG, "channelInfo/getChat")
            .getOrNull() ?: return null
        val supergroupId = (chat.type as? TdApi.ChatTypeSupergroup)?.supergroupId
        val supergroup = supergroupId?.let {
            runCatching { td.send(TdApi.GetSupergroup(it)) }
                .warnUnlessCancelled(TAG, "channelInfo/getSupergroup")
                .getOrNull()
        }
        val full = supergroupId?.let {
            runCatching { td.send(TdApi.GetSupergroupFullInfo(it)) }
                .warnUnlessCancelled(TAG, "channelInfo/getSupergroupFullInfo")
                .getOrNull()
        }
        val isMember = supergroup?.status?.let { status ->
            status !is TdApi.ChatMemberStatusLeft && status !is TdApi.ChatMemberStatusBanned
        } ?: false
        return ChannelInfo(
            chatId = chatId,
            title = chat.title.orEmpty(),
            handle = supergroup?.usernames?.activeUsernames?.firstOrNull()?.let { "@$it" },
            description = full?.description?.takeUnless { it.isNullOrBlank() },
            subscribers = supergroup?.memberCount?.takeIf { it > 0 },
            isMuted = chat.notificationSettings?.muteFor.let { it != null && it > 0 },
            isMember = isMember,
        )
    }

    /**
     * Preview a chat invite link via TDLib's `CheckChatInviteLink`. Returns a
     * snapshot — title, member count, chat kind — that the UI surfaces in a
     * confirmation dialog before joining. Telegram's own clients call this exact
     * method on every invite tap; we mirror the flow. Returns null on a malformed
     * or expired invite (TDLib answers 4xx) — caller treats as silent miss.
     */
    suspend fun previewChatInvite(inviteLink: String): ChatInvitePreview? {
        val info: TdApi.ChatInviteLinkInfo = runCatching {
            td.send(TdApi.CheckChatInviteLink(inviteLink))
        }
            .warnUnlessCancelled(TAG, "previewChatInvite")
            .getOrNull() ?: return null
        return ChatInvitePreview(
            inviteLink = inviteLink,
            chatId = info.chatId.takeIf { it != 0L },
            title = info.title.orEmpty(),
            memberCount = info.memberCount,
            kind = when (info.type) {
                is TdApi.InviteLinkChatTypeChannel -> InviteLinkKind.Channel
                else -> InviteLinkKind.Group
            },
        )
    }

    /**
     * Join a chat via its invite link. Returns the resulting chat id on success or
     * null on failure (already a member, banned, expired, FLOOD_WAIT…). The TDLib
     * call propagates a user-facing error through [UserMessageBus] on its own so
     * callers don't need to surface anything beyond the success-side navigation.
     */
    suspend fun joinByInvite(inviteLink: String): Long? {
        val chat: TdApi.Chat = runCatching {
            td.send(TdApi.JoinChatByInviteLink(inviteLink))
        }
            .warnUnlessCancelled(TAG, "joinByInvite")
            .onFailure { it.surfaceTo(userMessages, res, R.string.op_join_channel, connection.value) }
            .getOrNull() ?: return null
        return chat.id
    }

    private companion object {
        const val TAG = "ChannelActionsRepository"
        // 365 days. TDLib treats very-large positive muteFor as "muted indefinitely"; this
        // matches the official client's "Mute forever" preset.
        const val MUTE_FOREVER_SECONDS = 365 * 24 * 60 * 60
    }
}

/** Preview snapshot returned by [ChannelActionsRepository.previewChatInvite]. */
data class ChatInvitePreview(
    val inviteLink: String,
    /** Resolved chat id if the user already has access (already a member); null otherwise. */
    val chatId: Long?,
    val title: String,
    val memberCount: Int,
    val kind: InviteLinkKind,
)

/** Discriminator on [ChatInvitePreview.kind]. Hortay only joins channels natively;
 *  group invites surface a snackbar and hand off to Telegram. */
enum class InviteLinkKind { Channel, Group }

/** Data bundle backing the channel info bottom sheet. */
data class ChannelInfo(
    val chatId: Long,
    val title: String,
    val handle: String?,
    val description: String?,
    val subscribers: Int?,
    val isMuted: Boolean,
    val isMember: Boolean,
)
