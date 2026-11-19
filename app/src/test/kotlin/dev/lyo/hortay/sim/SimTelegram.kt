package dev.lyo.hortay.sim

import dev.lyo.hortay.data.TdClient
import dev.lyo.hortay.data.TdSender
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.drinkless.tdlib.TdApi
import java.util.TreeMap

/**
 * Knobs for TDLib behaviour that is documented as "chosen by TDLib" or known from field
 * reports. Each one is a behaviour the app must survive either way, so the scenario tests
 * run with several combinations instead of the single shape a canned fake encodes.
 *
 * @property chatPageSize chats announced per `loadChats` call — the docs allow fewer than
 *   the requested limit even before the end of the list.
 * @property maxHistoryPage cap on one `getChatHistory` answer — the docs allow fewer than
 *   the limit even before the end of history.
 * @property historyInclusiveBoundary whether `getChatHistory(from, offset = 0)` includes
 *   the `from` message itself.
 * @property coldFirstPassLocalOnly the first networked `getChatHistory` of a chat whose
 *   history list is not built yet answers with the locally cached part of the window only;
 *   the server slice is available from the next call.
 * @property lastMessageBeforeNewChat `updateChatLastMessage` is delivered before the chat's
 *   `updateNewChat` during a chat-list load.
 * @property latencyMs virtual time every request takes, so updates can land mid-request.
 */
data class SimQuirks(
    val chatPageSize: Int = 3,
    val maxHistoryPage: Int = Int.MAX_VALUE,
    val historyInclusiveBoundary: Boolean = false,
    val coldFirstPassLocalOnly: Boolean = true,
    val lastMessageBeforeNewChat: Boolean = false,
    val latencyMs: Long = 0L,
)

class SimMessage(val id: Long, val chatId: Long, val date: Int, val albumId: Long, val text: String)

class SimChannel(val id: Long, val title: String, var order: Long, val archived: Boolean) {
    /** Server-side history, keyed by message id (ascending). */
    val messages = TreeMap<Long, SimMessage>()
    var nextServerId: Long = 1L
    var readInbox: Long = 0L

    fun unreadCount(): Int = messages.tailMap(readInbox, false).size
}

/** One Telegram account's server-side world: the channels it follows and their posts. */
class SimAccount(val name: String) {
    val channels = LinkedHashMap<Long, SimChannel>()

    fun channel(id: Long, order: Long = id, archived: Boolean = false): SimChannel =
        channels.getOrPut(id) { SimChannel(id, "Channel $id", order, archived) }
}

/** One recorded request, for RPC-budget assertions. */
class SimCall(val atMs: Long, val name: String, val chatId: Long?, val networked: Boolean)

/**
 * A stateful model of a TDLib client talking to a Telegram server, behind [TdSender].
 *
 * Unlike a canned fake, nothing here is scripted per test: the server world ([SimAccount])
 * and the client's local database ([local]) are state, requests are answered from that
 * state by TDLib's documented rules, and world changes ([post], [delete]) produce the
 * updates TDLib would deliver. The split between "exists on the server" and "is in the
 * local database" is deliberate — the app's album rehydration and paging rules live on
 * exactly that difference.
 *
 * Rules modelled (see the TDLib class docs):
 *  - `loadChats`: announces chats through `updateNewChat` in `(order, id)` descending
 *    order, a page at a time, and answers 404 once the list is exhausted.
 *  - `getChatHistory`: newest first, possibly fewer than `limit`, `only_local` restricted
 *    to the local database.
 *  - `getMessageLocally`: 404 unless the message is in the local database.
 *  - `viewMessages`: marks read only for an open chat, or with `force_read`.
 *  - after [logOut]: local data is destroyed and every request answers 500.
 *
 * Message ids follow the channel stride (`serverId shl 20`), which the app's local album
 * rehydration relies on.
 */
class SimTelegram(
    private val nowMs: () -> Long,
    var quirks: SimQuirks = SimQuirks(),
) : TdSender {
    private val _updates = MutableSharedFlow<TdApi.Update>(extraBufferCapacity = 1024)
    override val updates: SharedFlow<TdApi.Update> = _updates.asSharedFlow()

    var account: SimAccount? = null
        private set
    private var closed = true

    /** Chats the client already knows (their `updateNewChat` was delivered). */
    private val announced = HashSet<Long>()

    /** Message ids present in the client's local database, per chat. */
    private val local = HashMap<Long, MutableSet<Long>>()

    /** Chats whose in-memory history list is built (a networked history call ran). */
    private val historyWarm = HashSet<Long>()
    private val openChats = HashSet<Long>()
    private val injected = HashMap<String, ArrayDeque<TdClient.TdException>>()
    private var nextAlbumId = 1L

    val calls = ArrayList<SimCall>()

    fun callCount(name: String, networkedOnly: Boolean = false): Int =
        calls.count { it.name == name && (!networkedOnly || it.networked) }

    /** Make the next [name] request fail with [code] (e.g. 420 / 429 for FLOOD_WAIT). */
    fun failNext(name: String, code: Int, message: String) {
        injected.getOrPut(name) { ArrayDeque() }.addLast(TdClient.TdException(code, message))
    }

    fun signIn(account: SimAccount) {
        this.account = account
        closed = false
    }

    /** `logOut`: all local data is destroyed; the closed client answers 500 to everything. */
    fun logOut() {
        closed = true
        account = null
        announced.clear()
        local.clear()
        historyWarm.clear()
        openChats.clear()
    }

    fun isAnnounced(chatId: Long): Boolean = chatId in announced
    fun isLocal(chatId: Long, messageId: Long): Boolean = local[chatId]?.contains(messageId) == true

    // ---- World actions -----------------------------------------------------------

    /** Publish a post ([albumSize] > 1 = an album) and deliver the updates TDLib would. */
    suspend fun post(chatId: Long, albumSize: Int = 1): List<Long> {
        val channel = requireNotNull(account?.channels?.get(chatId)) { "no channel $chatId" }
        val albumId = if (albumSize > 1) nextAlbumId++ else 0L
        val date = (nowMs() / 1000L).toInt() + BASE_DATE
        val added = (0 until albumSize).map {
            val id = channel.nextServerId++ shl ID_SHIFT
            SimMessage(id, chatId, date, albumId, "msg $id").also { channel.messages[id] = it }
        }
        channel.order = added.last().id
        if (chatId in announced) {
            added.forEach {
                markLocal(chatId, it.id)
                _updates.emit(TdApi.UpdateNewMessage(it.toTd()))
            }
            _updates.emit(TdApi.UpdateChatLastMessage(chatId, added.last().toTd(), channel.positions()))
        }
        return added.map { it.id }
    }

    /** Delete posts on the server and deliver `updateDeleteMessages` for a known chat. */
    suspend fun delete(chatId: Long, ids: List<Long>) {
        val channel = requireNotNull(account?.channels?.get(chatId)) { "no channel $chatId" }
        val lastBefore = channel.messages.lastEntry()?.key
        val removed = ids.filter { channel.messages.remove(it) != null }
        if (removed.isEmpty()) return
        local[chatId]?.removeAll(removed.toSet())
        if (chatId !in announced) return
        _updates.emit(TdApi.UpdateDeleteMessages(chatId, removed.toLongArray(), true, false))
        val last = channel.messages.lastEntry()?.value
        if (last?.id != lastBefore) {
            last?.let { markLocal(chatId, it.id) }
            _updates.emit(TdApi.UpdateChatLastMessage(chatId, last?.toTd(), channel.positions()))
        }
    }

    // ---- TdSender ----------------------------------------------------------------

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : TdApi.Object> send(query: TdApi.Function<T>): T {
        if (closed) throw closedError()
        if (quirks.latencyMs > 0) delay(quirks.latencyMs)
        if (closed) throw closedError()
        val name = query::class.simpleName ?: "Unknown"
        injected[name]?.removeFirstOrNull()?.let {
            record(name, null, networked = true)
            throw it
        }
        return answer(name, query) as T
    }

    private suspend fun answer(name: String, query: TdApi.Function<*>): TdApi.Object = when (query) {
        is TdApi.LoadChats -> {
            record(name, null, networked = true)
            loadChats(query)
        }
        is TdApi.GetChat -> {
            record(name, query.chatId, networked = false)
            channelOrThrow(query.chatId).toTd()
        }
        is TdApi.GetSupergroup -> {
            record(name, null, networked = false)
            TdApi.Supergroup().apply {
                usernames = null
                verificationStatus = null
            }
        }
        is TdApi.GetChatHistory -> {
            record(name, query.chatId, networked = !query.onlyLocal)
            history(query)
        }
        is TdApi.GetMessageLocally -> {
            record(name, query.chatId, networked = false)
            localMessage(query.chatId, query.messageId) ?: throw notFound("Message not found")
        }
        is TdApi.GetMessage -> {
            val known = localMessage(query.chatId, query.messageId)
            record(name, query.chatId, networked = known == null)
            known ?: channelOrThrow(query.chatId).messages[query.messageId]
                ?.also { markLocal(query.chatId, it.id) }?.toTd()
                ?: throw notFound("Message not found")
        }
        is TdApi.OpenChat -> {
            record(name, query.chatId, networked = false)
            channelOrThrow(query.chatId)
            openChats += query.chatId
            TdApi.Ok()
        }
        is TdApi.CloseChat -> {
            record(name, query.chatId, networked = false)
            openChats -= query.chatId
            TdApi.Ok()
        }
        is TdApi.ViewMessages -> {
            record(name, query.chatId, networked = true)
            viewMessages(query)
        }
        else -> error("SimTelegram does not model $name yet")
    }

    private suspend fun loadChats(query: TdApi.LoadChats): TdApi.Object {
        val wantArchive = query.chatList is TdApi.ChatListArchive
        val pending = currentAccount().channels.values
            .filter { it.archived == wantArchive && it.id !in announced }
            .sortedWith(compareByDescending<SimChannel> { it.order }.thenByDescending { it.id })
        if (pending.isEmpty()) throw notFound("Not Found")
        pending.take(minOf(quirks.chatPageSize, query.limit).coerceAtLeast(1)).forEach { announce(it) }
        return TdApi.Ok()
    }

    private suspend fun announce(channel: SimChannel) {
        announced += channel.id
        val last = channel.messages.lastEntry()?.value
        // Only the last message reaches the local database on a chat-list load — one
        // album member, never its siblings.
        last?.let { markLocal(channel.id, it.id) }
        if (quirks.lastMessageBeforeNewChat && last != null) {
            _updates.emit(TdApi.UpdateChatLastMessage(channel.id, last.toTd(), channel.positions()))
        }
        _updates.emit(TdApi.UpdateNewChat(channel.toTd()))
    }

    private fun history(query: TdApi.GetChatHistory): TdApi.Messages {
        val channel = channelOrThrow(query.chatId)
        val newestFirst = channel.messages.descendingMap().values.toList()
        val inclusive = query.offset < 0 || quirks.historyInclusiveBoundary
        val anchor = when {
            query.fromMessageId == 0L -> 0
            inclusive -> newestFirst.indexOfFirst { it.id <= query.fromMessageId }
            else -> newestFirst.indexOfFirst { it.id < query.fromMessageId }
        }
        if (anchor < 0) return messagesOf(emptyList())
        val start = (anchor + query.offset).coerceAtLeast(0)
        // Assumption: a short page still makes progress. When the answer starts with the
        // boundary message itself, the cap applies to what follows it — otherwise an
        // inclusive boundary with a one-message page would return the anchor forever.
        val leadsWithAnchor = query.fromMessageId != 0L && newestFirst[start].id >= query.fromMessageId
        val cap = if (leadsWithAnchor && quirks.maxHistoryPage < Int.MAX_VALUE) quirks.maxHistoryPage + 1
        else quirks.maxHistoryPage
        val window = newestFirst.drop(start).take(minOf(query.limit, cap))
        if (query.onlyLocal) return messagesOf(window.filter { isLocal(channel.id, it.id) })
        val cold = quirks.coldFirstPassLocalOnly && historyWarm.add(channel.id)
        val cachedPart = window.filter { isLocal(channel.id, it.id) }
        window.forEach { markLocal(channel.id, it.id) }
        historyWarm += channel.id
        return messagesOf(if (cold && cachedPart.isNotEmpty()) cachedPart else window)
    }

    private suspend fun viewMessages(query: TdApi.ViewMessages): TdApi.Object {
        val channel = channelOrThrow(query.chatId)
        if (query.chatId !in openChats && !query.forceRead) return TdApi.Ok()
        val newest = query.messageIds.filter { it in channel.messages }.maxOrNull() ?: return TdApi.Ok()
        if (newest > channel.readInbox) {
            channel.readInbox = newest
            _updates.emit(TdApi.UpdateChatReadInbox(channel.id, newest, channel.unreadCount()))
        }
        return TdApi.Ok()
    }

    // ---- Helpers -----------------------------------------------------------------

    private fun record(name: String, chatId: Long?, networked: Boolean) {
        calls += SimCall(nowMs(), name, chatId, networked)
    }

    private fun currentAccount(): SimAccount = account ?: throw closedError()

    private fun channelOrThrow(chatId: Long): SimChannel =
        currentAccount().channels[chatId] ?: throw TdClient.TdException(400, "Chat not found")

    private fun localMessage(chatId: Long, messageId: Long): TdApi.Message? =
        if (!isLocal(chatId, messageId)) null else account?.channels?.get(chatId)?.messages?.get(messageId)?.toTd()

    private fun markLocal(chatId: Long, messageId: Long) {
        local.getOrPut(chatId) { HashSet() } += messageId
    }

    private fun messagesOf(list: List<SimMessage>): TdApi.Messages =
        TdApi.Messages(list.size, list.map { it.toTd() }.toTypedArray())

    private fun closedError() = TdClient.TdException(500, "Request aborted")
    private fun notFound(message: String) = TdClient.TdException(404, message)

    private fun SimChannel.positions(): Array<TdApi.ChatPosition> = arrayOf(
        TdApi.ChatPosition(
            if (archived) TdApi.ChatListArchive() else TdApi.ChatListMain(),
            order,
            false,
            null,
        ),
    )

    private fun SimChannel.toTd(): TdApi.Chat = TdApi.Chat().also { chat ->
        chat.id = id
        chat.title = title
        chat.type = TdApi.ChatTypeSupergroup(id, true)
        chat.lastMessage = messages.lastEntry()?.value?.toTd()
        chat.positions = positions()
        chat.permissions = TdApi.ChatPermissions()
        chat.unreadCount = unreadCount()
        chat.lastReadInboxMessageId = readInbox
    }

    private fun SimMessage.toTd(): TdApi.Message = TdApi.Message().also { message ->
        message.id = id
        message.chatId = chatId
        message.date = date
        message.mediaAlbumId = albumId
        message.senderId = TdApi.MessageSenderChat(chatId)
        message.content = TdApi.MessageText(TdApi.FormattedText(text, emptyArray()), null, null)
    }

    private companion object {
        /** Channel message ids are `serverId shl 20` (the app's ALBUM_ID_STRIDE). */
        const val ID_SHIFT = 20
        const val BASE_DATE = 1_700_000_000
    }
}
