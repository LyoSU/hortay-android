package dev.lyo.hortay.data.subscriptions

import dev.lyo.hortay.data.discover.isChannelMember
import org.drinkless.tdlib.TdApi

/**
 * Whether the signed-in account already follows the channel behind a guest-mode handle,
 * answered without a network request. Feeds [ThrottledChannelJoiner]'s `isAlreadyMember`,
 * so moving guest channels into the account (or importing a list) skips the ones the
 * account follows already instead of spending a `SearchPublicChat` + `JoinChat` on each.
 *
 * Needs the channel's real id, which guest mode learns from the t.me/s/ page
 * (`channel.channel_id`, via [channelIdOf]). Without one the answer is "no" and the
 * joiner behaves exactly as before.
 *
 * Why `GetSupergroup` and not `GetChat`: `GetSupergroup` is documented offline (TDLib
 * answers from its local store and never goes to the server, so it also bypasses the
 * FLOOD_WAIT gate — see `TdClient.isOfflineRequest`) and carries the member status.
 * `GetChat` succeeds for a channel the account LEFT or only previewed, which would read
 * as "already a member". A supergroup TDLib has never seen fails the offline lookup —
 * that is a "no" as well.
 *
 * The handle must still be one of the supergroup's active usernames: a handle can move
 * to another channel, and the stored id would then name the old one.
 */
class KnownChannelMembership(
    private val channelIdOf: suspend (username: String) -> Long?,
    private val supergroup: suspend (supergroupId: Long) -> TdApi.Supergroup?,
) {
    suspend fun isMember(username: String): Boolean {
        val id = channelIdOf(username) ?: return false
        val group = supergroup(id) ?: return false
        if (!group.isChannel || !isChannelMember(group.status)) return false
        val handles = group.usernames?.activeUsernames.orEmpty()
        return handles.any { it.equals(username, ignoreCase = true) }
    }
}
