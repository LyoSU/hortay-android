package dev.lyo.hortay.data.subscriptions

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.lyo.hortay.data.warnUnlessCancelled
import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * A public channel the signed-in account reads without joining it ("read without joining").
 *
 * [readCursor] is the LOCAL read position — the highest message id the user has read here.
 * Telegram keeps no read cursor or unread count for a chat the account is not a member of,
 * so this is the only one there is; it moves only up, like `ReadCursors`. [lastSeenMessageId]
 * is the newest message id the app has fetched for the channel: the refresher compares a
 * fresh page against it, and the cold start restores that one message from TDLib's local
 * store so the channel has a post in the feed before any network call.
 */
@Immutable
data class FollowedChannel(
    val chatId: Long,
    val username: String?,
    val title: String,
    val addedAtMs: Long,
    val readCursor: Long,
    val lastSeenMessageId: Long,
    val status: Status,
) {
    /**
     * [Unavailable]: the last check found the channel private, deleted or banning us, and a
     * handle re-resolve did not recover it. It leaves the normal schedule but is re-checked
     * on pull-to-refresh and once per [FOLLOWED_UNAVAILABLE_RECHECK_MS]; a check that works
     * again flips it back to [Ok].
     */
    enum class Status { Ok, Unavailable }
}

/** Outcome of [FollowedChannelsStore.add]. */
enum class AddResult { Added, AlreadyFollowed, LimitReached }

/**
 * Where [FollowedChannelsStore] keeps its encoded list. An interface so the store's
 * debounce / logout-race logic is testable on virtual time without DataStore's own I/O
 * threads; production uses [DataStoreFollowedChannelsDisk].
 */
interface FollowedChannelsDisk {
    /** The last [save]d value, or null when nothing was saved (or the file was reset). */
    suspend fun load(): String?
    suspend fun save(encoded: String)
}

/**
 * DataStore-backed [FollowedChannelsDisk]. A corrupted file is replaced by an empty one
 * ([followedChannelsDataStore]'s [ReplaceFileCorruptionHandler]), so a broken write never
 * crashes the app on launch — the list starts empty instead, same as every other store.
 */
class DataStoreFollowedChannelsDisk(private val dataStore: DataStore<Preferences>) : FollowedChannelsDisk {
    override suspend fun load(): String? = dataStore.data.first()[KEY]

    override suspend fun save(encoded: String) {
        dataStore.edit { it[KEY] = encoded }
    }

    private companion object {
        val KEY = stringPreferencesKey("followed_channels_v1")
    }
}

/** The production DataStore for [FollowedChannelsStore]. */
fun followedChannelsDataStore(context: Context): DataStore<Preferences> =
    context.applicationContext.followedChannelsPrefs

private val Context.followedChannelsPrefs by preferencesDataStore(
    name = "followed_channels",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

/**
 * Persistent list of [FollowedChannel]s for the signed-in account. Account-bound: wiped by
 * [clear] on logout (registered as a session `onEnd` hook in `AppGraph`).
 *
 * In memory first: [channels] / [ids] are [StateFlow]s the feed filter and the refresher
 * read synchronously; the disk copy follows. Membership and status changes ([add],
 * [remove], [markStatus], [updateMeta]) are written through at once. Read-cursor and
 * last-seen moves ([advanceReadCursor], [markSeen]) fire on every read ack and refresh, so
 * they are coalesced into one write at most every [writeDebounceMs] — losing the last
 * second of read progress to a process kill costs a post re-showing its unread strip, not
 * a lost channel.
 *
 * Logout race: [clear] cancels a pending debounced write before it wipes, and every write
 * encodes the in-memory list as it is when the write takes [writeLock] — not a copy taken
 * when it was scheduled. So a write of account A that is already running finishes before
 * the wipe, one still waiting sees the wiped list, and nothing of A lands on disk after
 * [clear] returns.
 *
 * [ids] re-emits only when membership changes, not on every cursor move, so the feed's
 * subscription filter is not recomputed per read ack.
 */
class FollowedChannelsStore(
    private val disk: FollowedChannelsDisk,
    private val scope: CoroutineScope,
    private val writeDebounceMs: Long = CURSOR_WRITE_DEBOUNCE_MS,
) {
    private val _channels = MutableStateFlow<PersistentMap<Long, FollowedChannel>>(persistentMapOf())
    val channels: StateFlow<PersistentMap<Long, FollowedChannel>> = _channels.asStateFlow()

    private val _ids = MutableStateFlow<Set<Long>>(emptySet())
    val ids: StateFlow<Set<Long>> = _ids.asStateFlow()

    /** Highest read position per channel — the shape `PostsRepository` max-merges into its cursors. */
    private val _readCursors = MutableStateFlow<Map<Long, Long>>(emptyMap())
    val readCursors: StateFlow<Map<Long, Long>> = _readCursors.asStateFlow()

    private val loaded = CompletableDeferred<Unit>()
    private val writeLock = Mutex()
    private val pendingLock = Any()

    /** Debounced cursor write; guarded by [pendingLock]. */
    private var pendingWrite: Job? = null

    /**
     * Bumped by [clear]. A disk load that started before a logout must not install the
     * previous account's list after the wipe. Guarded by [pendingLock].
     */
    private var generation = 0L

    init {
        scope.launch {
            val startGeneration = synchronized(pendingLock) { generation }
            val decoded = runCatching { decode(disk.load()) }
                .warnUnlessCancelled(TAG, "load")
                .getOrDefault(persistentMapOf())
            synchronized(pendingLock) {
                if (generation == startGeneration) publish(decoded)
            }
            loaded.complete(Unit)
        }
    }

    /** Suspends until the disk copy has been read (immediately after the first time). */
    suspend fun awaitLoaded() = loaded.await()

    /**
     * Add [entry]; refused when [FOLLOWED_CHANNELS_MAX] are followed already or the channel
     * is in the list. Written through before returning.
     */
    suspend fun add(entry: FollowedChannel): AddResult {
        awaitLoaded()
        var result = AddResult.Added
        _channels.update { current ->
            result = when {
                entry.chatId in current -> AddResult.AlreadyFollowed
                current.size >= FOLLOWED_CHANNELS_MAX -> AddResult.LimitReached
                else -> AddResult.Added
            }
            if (result == AddResult.Added) current.put(entry.chatId, entry) else current
        }
        if (result == AddResult.Added) commit()
        return result
    }

    /** Remove [chatId]; returns the removed entry (for an undo) or null when it wasn't followed. */
    suspend fun remove(chatId: Long): FollowedChannel? {
        awaitLoaded()
        var removed: FollowedChannel? = null
        _channels.update { current ->
            removed = current[chatId]
            current.remove(chatId)
        }
        if (removed != null) commit()
        return removed
    }

    /** Monotonic: a [messageId] at or below the stored cursor is ignored. Debounced write. */
    fun advanceReadCursor(chatId: Long, messageId: Long) {
        if (mutate(chatId) { if (messageId > it.readCursor) it.copy(readCursor = messageId) else it }) {
            scheduleWrite()
        }
    }

    /** Monotonic newest fetched id for [chatId]. Debounced write. */
    fun markSeen(chatId: Long, newestId: Long) {
        if (mutate(chatId) { if (newestId > it.lastSeenMessageId) it.copy(lastSeenMessageId = newestId) else it }) {
            scheduleWrite()
        }
    }

    suspend fun markStatus(chatId: Long, status: FollowedChannel.Status) {
        awaitLoaded()
        if (mutate(chatId) { if (it.status != status) it.copy(status = status) else it }) commit()
    }

    suspend fun updateMeta(chatId: Long, title: String, username: String?) {
        awaitLoaded()
        val changed = mutate(chatId) {
            if (it.title == title && it.username == username) it else it.copy(title = title, username = username)
        }
        if (changed) commit()
    }

    /**
     * Logout wipe. Cancels the pending debounced write synchronously, before anything else,
     * then writes the empty list. See the class KDoc for why no write of the previous
     * account can land after this returns.
     */
    suspend fun clear() {
        synchronized(pendingLock) {
            pendingWrite?.cancel()
            pendingWrite = null
            generation++
            publish(persistentMapOf())
        }
        persistNow()
    }

    /** Apply [change] to [chatId]'s entry; true when it produced a different entry. */
    private inline fun mutate(chatId: Long, crossinline change: (FollowedChannel) -> FollowedChannel): Boolean {
        var changed = false
        _channels.update { current ->
            val entry = current[chatId]
            val next = entry?.let(change)
            changed = next != null && next != entry
            if (next != null && changed) current.put(chatId, next) else current
        }
        if (changed) syncDerived(_channels.value)
        return changed
    }

    private fun publish(map: PersistentMap<Long, FollowedChannel>) {
        _channels.value = map
        syncDerived(map)
    }

    private fun syncDerived(map: Map<Long, FollowedChannel>) {
        _ids.value = map.keys.toSet()
        _readCursors.value = map.mapValues { it.value.readCursor }
    }

    /** Write-through for membership / status changes; also supersedes a pending cursor write. */
    private suspend fun commit() {
        syncDerived(_channels.value)
        synchronized(pendingLock) {
            pendingWrite?.cancel()
            pendingWrite = null
        }
        persistNow()
    }

    private fun scheduleWrite() {
        synchronized(pendingLock) {
            // Keep a running timer rather than restarting it: a steady stream of read acks
            // still reaches disk every [writeDebounceMs] instead of never.
            if (pendingWrite?.isActive == true) return
            val writeGeneration = generation
            pendingWrite = scope.launch {
                delay(writeDebounceMs)
                if (synchronized(pendingLock) { generation } == writeGeneration) persistNow()
            }
        }
    }

    private suspend fun persistNow() {
        writeLock.withLock {
            // Encoded under the lock from the CURRENT list — see the class KDoc.
            val encoded = encode(_channels.value)
            runCatching { disk.save(encoded) }.warnUnlessCancelled(TAG, "save")
        }
    }

    @Serializable
    private data class Entry(
        val chatId: Long,
        val username: String? = null,
        val title: String = "",
        val addedAtMs: Long = 0L,
        val readCursor: Long = 0L,
        val lastSeenMessageId: Long = 0L,
        val status: String = FollowedChannel.Status.Ok.name,
    )

    internal companion object {
        private const val TAG = "FollowedChannelsStore"
        private val JSON = Json { ignoreUnknownKeys = true }
        private val SERIALIZER = ListSerializer(Entry.serializer())

        /**
         * Decode a stored list. Unreadable JSON resets to empty (logged) instead of throwing
         * — the same "corruption resets, never crashes" contract the DataStore file has.
         */
        internal fun decode(raw: String?): PersistentMap<Long, FollowedChannel> {
            if (raw.isNullOrBlank()) return persistentMapOf()
            val entries = runCatching { JSON.decodeFromString(SERIALIZER, raw) }
                .onFailure { e -> runCatching { Log.w(TAG, "Unreadable followed-channels list, resetting", e) } }
                .getOrDefault(emptyList())
            return entries.asSequence()
                .filter { it.chatId != 0L }
                .map { e ->
                    FollowedChannel(
                        chatId = e.chatId,
                        username = e.username,
                        title = e.title,
                        addedAtMs = e.addedAtMs,
                        readCursor = e.readCursor,
                        lastSeenMessageId = e.lastSeenMessageId,
                        status = FollowedChannel.Status.entries.firstOrNull { it.name == e.status }
                            ?: FollowedChannel.Status.Ok,
                    )
                }
                .take(FOLLOWED_CHANNELS_MAX)
                .associateBy { it.chatId }
                .toPersistentMap()
        }

        internal fun encode(map: Map<Long, FollowedChannel>): String =
            JSON.encodeToString(
                SERIALIZER,
                map.values.map {
                    Entry(it.chatId, it.username, it.title, it.addedAtMs, it.readCursor, it.lastSeenMessageId, it.status.name)
                },
            )
    }
}

/**
 * Most channels one account may read without joining. The bound is the refresh budget, not
 * storage: [FollowedChannelsRefresher] polls each channel with one networked
 * `GetChatHistory`, at most [FOLLOWED_WINDOW_CEILING] of the server's ~30 per 30 s window.
 * 100 channels at that pace take about five minutes to sweep once — the first backoff step,
 * so a fresh channel's next check is never later than its schedule says. More would make
 * the first sweep after launch outlast its own step and the schedule fall behind.
 */
const val FOLLOWED_CHANNELS_MAX = 100

/** Coalescing window for read-cursor / last-seen writes in [FollowedChannelsStore]. */
const val CURSOR_WRITE_DEBOUNCE_MS = 1_000L
