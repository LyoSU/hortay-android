package dev.lyo.hortay.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import java.util.concurrent.ConcurrentHashMap

/**
 * Session-scoped mirror of the TDLib users and supergroups the update stream has
 * announced. TDLib guarantees `updateUser` / `updateSupergroup` arrive before any id
 * that references them, and every later change re-sends the whole object — so a map
 * fed ONLY by those updates is always as fresh as TDLib's own store.
 *
 * This is deliberately not an LRU of RPC results: the old mapper-side LRU went stale on
 * renames / avatar changes because it was filled from `GetUser` answers and never
 * refreshed. Here the update stream owns every entry. A miss (the update landed before
 * this collector subscribed, or a different collector of the same SharedFlow ran first)
 * falls back to the offline `GetUser` / `GetSupergroup` RPC, whose answer is stored only
 * if no update has filled the slot meanwhile.
 */
class ChatRegistry(private val td: TdSender) {
    private val users = ConcurrentHashMap<Long, TdApi.User>()
    private val supergroups = ConcurrentHashMap<Long, TdApi.Supergroup>()

    /** Collect user/supergroup updates and the logout signal. Call once at graph build. */
    fun bind(loggedOut: SharedFlow<Unit>, scope: CoroutineScope) {
        scope.launch { td.updates.collect(::onUpdate) }
        scope.launch { loggedOut.collect { clear() } }
    }

    internal fun onUpdate(update: TdApi.Update) {
        when (update) {
            is TdApi.UpdateUser -> users[update.user.id] = update.user
            is TdApi.UpdateSupergroup -> supergroups[update.supergroup.id] = update.supergroup
            else -> Unit
        }
    }

    suspend fun user(userId: Long): TdApi.User? =
        users[userId] ?: runCatching { td.send(TdApi.GetUser(userId)) }
            .warnUnlessCancelled("ChatRegistry", "getUser")
            .getOrNull()
            ?.let { fetched -> users.putIfAbsent(userId, fetched) ?: fetched }

    suspend fun supergroup(supergroupId: Long): TdApi.Supergroup? =
        supergroups[supergroupId] ?: runCatching { td.send(TdApi.GetSupergroup(supergroupId)) }
            .warnUnlessCancelled("ChatRegistry", "getSupergroup")
            .getOrNull()
            ?.let { fetched -> supergroups.putIfAbsent(supergroupId, fetched) ?: fetched }

    fun clear() {
        users.clear()
        supergroups.clear()
    }
}
