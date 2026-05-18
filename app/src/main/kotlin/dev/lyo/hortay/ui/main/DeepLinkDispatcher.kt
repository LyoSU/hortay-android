@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.lyo.hortay.ui.main

import android.content.res.Resources
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import dev.lyo.hortay.R
import dev.lyo.hortay.data.ChatInvitePreview
import dev.lyo.hortay.data.DeepLink
import dev.lyo.hortay.data.DeepLinkRouter
import dev.lyo.hortay.data.InviteLinkKind
import dev.lyo.hortay.data.LinkDialogState
import dev.lyo.hortay.data.posts.PublicHandleKind
import dev.lyo.hortay.data.posts.PublicHandleResult
import dev.lyo.hortay.data.UserMessageBus

/**
 * TDLib encodes message ids as `serverPostId shl 20` internally (MTProto convention; see
 * tdlib/td#946). Deep-link variants carrying a server-side post number need this shift
 * before being handed to TimelineScreen's scroll-to-message dispatcher. [DeepLink.Message]
 * skips the shift because TDLib's own `GetMessageLinkInfo` returns the id already in
 * internal form.
 */
private const val SERVER_TO_TD_SHIFT = 20

internal fun unsupportedHandleMessageId(kind: PublicHandleKind): Int = when (kind) {
    PublicHandleKind.Group -> R.string.link_unsupported_group
    PublicHandleKind.Unknown -> R.string.link_unsupported_other
}

/**
 * Deep-link dispatcher. Resolved Telegram links arrive here typed; each maps to
 * (chatId, optional TDLib-shaped messageId) and a channel push. [DeepLink.Message]
 * already carries the TDLib-internal `messageId` (resolved server-side via
 * `GetMessageLinkInfo`); the public/private channel variants still carry a server-
 * side post number which we shift here before handing to TimelineScreen.
 *
 * Failure isolation: every link is dispatched inside a `runCatching` so one bad
 * input (TDLib throw on a malformed handle, transient FLOOD_WAIT inside
 * resolvePublicHandle, etc.) cannot kill the entire collector — without this
 * wrapper a single uncaught throw inside `collect { }` permanently silences every
 * future deep-link tap until the process restarts. `CancellationException` is
 * re-thrown so the LaunchedEffect can be cancelled cleanly when the scaffold
 * leaves composition.
 */
@Composable
internal fun DeepLinkDispatcher(
    router: DeepLinkRouter,
    userMessages: UserMessageBus,
    linkDialogs: LinkDialogState,
    resolvePublicHandle: suspend (String) -> PublicHandleResult,
    resolveChatKind: suspend (Long) -> PublicHandleResult,
    previewChatInvite: suspend (String) -> ChatInvitePreview?,
    onPushChannel: (chatId: Long, scrollTo: Long?) -> Unit,
    /** Open the in-app user-profile sheet for an `@handle` that resolves to a 1:1
     *  user / bot. Same surface as in-text `TextEntityTypeMentionName` taps — an
     *  `@username` mention shouldn't bounce out to the official Telegram client. */
    onOpenUser: (userId: Long) -> Unit,
) {
    val systemUriHandler: UriHandler = LocalUriHandler.current
    val res: Resources = LocalContext.current.resources
    LaunchedEffect(router) {
        router.events.collect { link ->
            try {
                val targetChat: Long
                val tdMessageId: Long?
                when (link) {
                    is DeepLink.PublicChannel -> {
                        when (val resolved = resolvePublicHandle(link.handle)) {
                            is PublicHandleResult.Channel -> {
                                targetChat = resolved.chatId
                                tdMessageId = link.serverPostId?.let { it shl SERVER_TO_TD_SHIFT }
                            }
                            is PublicHandleResult.User -> {
                                // `@handle` resolves to a 1:1 user / bot — open the
                                // in-app profile sheet instead of punting to Telegram.
                                // Matches the `TextEntityTypeMentionName` flow so both
                                // mention shapes land on the same surface.
                                onOpenUser(resolved.userId)
                                return@collect
                            }
                            is PublicHandleResult.Unsupported -> {
                                userMessages.post(
                                    res.getString(unsupportedHandleMessageId(resolved.kind)),
                                    UserMessageBus.Severity.Info,
                                )
                                runCatching { systemUriHandler.openUri(link.originalUrl) }
                                return@collect
                            }
                            is PublicHandleResult.NotFound -> {
                                userMessages.post(res.getString(R.string.link_not_found))
                                return@collect
                            }
                        }
                    }
                    is DeepLink.PrivateChannel -> {
                        // Same kind-gate as Message — `t.me/c/<rawId>/...` *should* point
                        // at a channel but TDLib can have the chat cached as a private
                        // supergroup-chat (the user is in the group but it's not a
                        // broadcast channel). Without the gate we would push a chatId
                        // onto the nav stack that loadChannelHistory short-circuits on,
                        // leaving an empty skeleton with no error path.
                        when (val resolved = resolveChatKind(link.chatId)) {
                            is PublicHandleResult.Channel -> {
                                targetChat = resolved.chatId
                                tdMessageId = link.serverPostId?.let { it shl SERVER_TO_TD_SHIFT }
                            }
                            is PublicHandleResult.User -> {
                                // `t.me/c/<userId>/...` never legitimately addresses a
                                // 1:1 chat, but if TDLib reclassifies the cached chat
                                // we still route to the user sheet rather than crash.
                                onOpenUser(resolved.userId)
                                return@collect
                            }
                            is PublicHandleResult.Unsupported -> {
                                userMessages.post(
                                    res.getString(unsupportedHandleMessageId(resolved.kind)),
                                    UserMessageBus.Severity.Info,
                                )
                                runCatching { systemUriHandler.openUri(link.originalUrl) }
                                return@collect
                            }
                            is PublicHandleResult.NotFound -> {
                                userMessages.post(res.getString(R.string.link_not_found))
                                return@collect
                            }
                        }
                    }
                    is DeepLink.Message -> {
                        // Same kind-gate. `GetMessageLinkInfo` happily returned a
                        // (chatId, message) pair, but the chat could be a basic group
                        // or 1:1 DM the user is in — neither has a feed surface in
                        // Hortay. Without the gate, the nav stack would land on a chat
                        // whose loadChannelHistory returns false, freezing the user.
                        when (val resolved = resolveChatKind(link.chatId)) {
                            is PublicHandleResult.Channel -> {
                                targetChat = resolved.chatId
                                tdMessageId = link.messageId
                            }
                            is PublicHandleResult.User -> {
                                // Per-message DM link — drop the message anchor and
                                // open the user sheet; Hortay has no DM surface.
                                onOpenUser(resolved.userId)
                                return@collect
                            }
                            is PublicHandleResult.Unsupported -> {
                                userMessages.post(
                                    res.getString(unsupportedHandleMessageId(resolved.kind)),
                                    UserMessageBus.Severity.Info,
                                )
                                runCatching { systemUriHandler.openUri(link.originalUrl) }
                                return@collect
                            }
                            is PublicHandleResult.NotFound -> {
                                userMessages.post(res.getString(R.string.link_not_found))
                                return@collect
                            }
                        }
                    }
                    is DeepLink.External -> {
                        runCatching { systemUriHandler.openUri(link.originalUrl) }
                        return@collect
                    }
                    is DeepLink.HashtagSearch -> {
                        // No in-app hashtag-search UI yet — surface a snackbar that
                        // tells the user what scope was inferred. Scoped form
                        // ("Пошук #foo у @channel") arrives when:
                        //   - the user tapped a `#tag@channel` entity (suffix carried
                        //     the scope), or
                        //   - PostBody's scoped LocalHashtagTap captured the
                        //     enclosing channel handle for a bare `#tag`.
                        // Unscoped form ("Пошук #foo") arrives for global taps —
                        // Comments thread bodies, settings text, and `tg://search`
                        // URLs (no channel scope is documented for that shape).
                        val msg = if (link.channelHandle != null) {
                            res.getString(
                                R.string.link_hashtag_search_in_channel,
                                link.tag,
                                "@${link.channelHandle}",
                            )
                        } else {
                            res.getString(R.string.link_hashtag_search, link.tag)
                        }
                        userMessages.post(msg, UserMessageBus.Severity.Info)
                        return@collect
                    }
                    is DeepLink.ChatInvite -> {
                        val preview = previewChatInvite(link.inviteLink)
                        when {
                            preview == null -> {
                                userMessages.post(res.getString(R.string.link_not_found))
                            }
                            preview.chatId != null -> {
                                // Already a member — drill into the channel via the back-stack
                                // so Back returns the user to where they came from rather than
                                // clearing the filter to all-feed.
                                onPushChannel(preview.chatId, null)
                            }
                            preview.kind == InviteLinkKind.Channel -> {
                                linkDialogs.showInvitePreview(preview)
                            }
                            else -> {
                                userMessages.post(
                                    res.getString(R.string.link_unsupported_group),
                                    UserMessageBus.Severity.Info,
                                )
                                runCatching { systemUriHandler.openUri(link.originalUrl) }
                            }
                        }
                        return@collect
                    }
                }
                // Deep-link to a specific channel: push a Channel entry — Back
                // returns the user to whichever tab they were on, not a forced
                // reset. The optional message anchor rides
                // [NavEntry.Channel.scrollToMessageId] so the channel screen
                // lands on the linked post on first frame.
                onPushChannel(targetChat, tdMessageId)
            } catch (t: Throwable) {
                if (t is kotlin.coroutines.cancellation.CancellationException) throw t
                Log.w("MainScaffold", "deep-link dispatch failed for $link", t)
            }
        }
    }
}
