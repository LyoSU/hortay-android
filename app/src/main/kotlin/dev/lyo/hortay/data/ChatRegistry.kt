package dev.lyo.hortay.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import java.util.concurrent.ConcurrentHashMap

/**
 * Session-scoped mirror of the TDLib users, supergroups and chats the update stream has
 * announced — the one place per-account entity metadata is cached.
 *
 * **Users / supergroups** are fed by this class's own collector. TDLib guarantees `updateUser` / `updateSupergroup` arrive before any id
 * that references them, and every later change re-sends the whole object — so a map
 * fed ONLY by those updates is always as fresh as TDLib's own store.
 *
 * This is deliberately not an LRU of RPC results: the old mapper-side LRU went stale on
 * renames / avatar changes because it was filled from `GetUser` answers and never
 * refreshed. Here the update stream owns every entry. A miss (the update landed before
 * this collector subscribed, or a different collector of the same SharedFlow ran first)
 * falls back to the offline `GetUser` / `GetSupergroup` RPC, whose answer is stored only
 * if no update has filled the slot meanwhile.
 *
 * **Chats** are written by `PostsRepository` ([rememberChat] on `UpdateNewChat`,
 * [forgetChat] on `UpdateChatTitle` / `UpdateChatPhoto`), not by a collector here. Its
 * `UpdateChatLastMessage` race gate checks "is the chat known yet?" and relies on the
 * write landing in the SAME coroutine that then flushes the parked last-messages; a
 * second collector here would reopen that window (a last-message parked after the flush
 * stays parked forever). Title / photo changes evict rather than mutate: [TdApi.Chat] is
 * an unsynchronised POJO read by concurrent workers, and the next [chat] call re-reads a
 * fresh object from TDLib's local store.
 *
 * Because of that eviction, "is the chat cached?" is NOT "has `UpdateNewChat` landed?".
 * The race gate asks [isKnownChat] instead: a chat id stays known from its first
 * [rememberChat] until [clear], surviving [forgetChat]. Gating on [cachedChat] parked every
 * last-message of a renamed / re-photographed channel forever — `UpdateNewChat` never
 * fires again for a known chat, so nothing flushed the park.
 */
class ChatRegistry(private val td: TdSender) {
    private val users = ConcurrentHashMap<Long, TdApi.User>()
    private val supergroups = ConcurrentHashMap<Long, TdApi.Supergroup>()
    private val chats = ConcurrentHashMap<Long, TdApi.Chat>()
    private val knownChatIds: MutableSet<Long> = ConcurrentHashMap.newKeySet()

    /**
     * Collect user/supergroup updates. Call once at graph build; [clear] is registered
     * as an [AccountSession] end hook.
     */
    fun bind(scope: CoroutineScope) {
        scope.launch { td.updates.collect(::onUpdate) }
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

    /** Synchronous supergroup read — no RPC; `null` until `UpdateSupergroup` lands. */
    fun cachedSupergroup(supergroupId: Long): TdApi.Supergroup? = supergroups[supergroupId]

    /** Synchronous chat read — no RPC. */
    fun cachedChat(chatId: Long): TdApi.Chat? = chats[chatId]

    /**
     * Cached chat, else the offline `GetChat` (TDLib serves it from its local store once
     * the chat has been seen). A fetched chat is stored unless a fresher one landed
     * meanwhile. Failures are logged and read as `null`.
     */
    suspend fun chat(chatId: Long): TdApi.Chat? =
        chats[chatId] ?: runCatching { td.send(TdApi.GetChat(chatId)) }
            .warnUnlessCancelled("ChatRegistry", "getChat")
            .getOrNull()
            ?.let { fetched -> chats.putIfAbsent(chatId, fetched) ?: fetched }

    fun rememberChat(chat: TdApi.Chat) {
        chats[chat.id] = chat
        knownChatIds += chat.id
    }

    /** True once [rememberChat] ran for [chatId] this session; [forgetChat] keeps it known. */
    fun isKnownChat(chatId: Long): Boolean = chatId in knownChatIds

    fun forgetChat(chatId: Long) {
        chats.remove(chatId)
    }

    fun clear() {
        users.clear()
        supergroups.clear()
        chats.clear()
        knownChatIds.clear()
    }
}
