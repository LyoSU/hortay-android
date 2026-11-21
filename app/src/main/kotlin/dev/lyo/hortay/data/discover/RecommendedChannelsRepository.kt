package dev.lyo.hortay.data.discover

import android.util.Log
import androidx.compose.runtime.Immutable
import dev.lyo.hortay.data.StartupCoordinator
import dev.lyo.hortay.data.TdClient
import dev.lyo.hortay.data.TdSender
import dev.lyo.hortay.data.warnUnlessCancelled
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.drinkless.tdlib.TdApi
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/** A recommended channel and the user's channels that vouch for it, most relevant first. */
@Immutable
data class RecommendedChannel(
    val channel: DiscoverChannel,
    val voters: ImmutableList<Long>,
    /** Title of `voters.first()`, for the single-voter "Recommended by <channel>" line. */
    val topVoterTitle: String?,
) {
    val votes: Int get() = voters.size
    val topVoter: Long get() = voters.first()
}

/**
 * What the "Recommended by your channels" section renders. [scanned] / [total] count the
 * voting sources with fresh similar-chat data; [complete] means every one of them has it;
 * [scanning] is true only while a scan has network work left.
 */
@Immutable
data class RecommendedState(
    val items: ImmutableList<RecommendedChannel> = persistentListOf(),
    val scanned: Int = 0,
    val total: Int = 0,
    val scanning: Boolean = false,
    val complete: Boolean = false,
)

/**
 * "Recommended by your channels": channels that several of the user's own subscriptions
 * list as similar (`getChatSimilarChats`), ranked by how many vouch for each
 * ([rankRecommendations]).
 *
 * Network budget — `getChatSimilarChats` is a server call on a bucket of ~30 calls before
 * a ~27 s FLOOD_WAIT, so the scan is deliberately timid:
 *  - **On demand only.** [requestScan] is called when the signed-in add-channel sheet shows
 *    its empty state — never at cold start — and the scan first waits for
 *    [StartupCoordinator.Phase.Active]. It runs in the session scope, so logout cancels it.
 *  - **One call at a time**, paced through [ChannelDiscoveryRepository.paced] (≥ 1 s
 *    apart, shared with the curated-handle resolves), sources in chat-list order, at most
 *    [MAX_SOURCES_PER_SCAN] network calls per scan.
 *  - **Stop, don't retry.** A FLOOD_WAIT (420 / 429, already armed in [TdClient]'s gate) or
 *    a send timeout ends the scan quietly with what it has; the next demand resumes from
 *    the next unscanned source. No snackbar — this is background work the user didn't ask
 *    for. Any other error records an empty entry so that source is not retried until TTL.
 *  - **Persisted** in [SimilarChatsStore] with a [TTL_MS] lifetime: fresh entries are never
 *    refetched, entries of channels the user left are pruned, and a reopened sheet paints
 *    from disk with zero network.
 *
 * Card data comes from local calls only ([ChannelDiscoveryRepository.offlineCard]); the
 * scan publishes after every source so the section fills in progressively. Channels hidden
 * from the feed do not vote and are not fetched.
 */
class RecommendedChannelsRepository(
    private val td: TdSender,
    private val discovery: ChannelDiscoveryRepository,
    private val store: SimilarChatsStore,
    /** Subscribed channels (main + archive), most relevant first. */
    private val subscribedChannels: () -> List<Long>,
    /** Channels hidden from the feed right now (permanent + live snoozes). */
    private val hiddenChannels: suspend () -> Set<Long>,
    private val startupPhase: StateFlow<StartupCoordinator.Phase>,
    /** Current session scope, read at launch time (replaced on logout). */
    private val sessionScope: () -> CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow(RecommendedState())
    val state: StateFlow<RecommendedState> = _state.asStateFlow()

    private val scanJob = AtomicReference<Job?>(null)

    // Guards [entries] and every publish; cards/titles are concurrent maps because
    // [onMembershipChanged] is called from outside a coroutine.
    private val lock = Mutex()
    private var entries: MutableMap<Long, SimilarEntry>? = null
    private val cards = ConcurrentHashMap<Long, DiscoverChannel>()
    private val noCard: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    private val titles = ConcurrentHashMap<Long, String>()

    /** Start (or resume) a scan unless one is already running. Cheap when everything is fresh. */
    fun requestScan() {
        val current = scanJob.get()
        if (current?.isActive == true) return
        val job = sessionScope().launch(start = CoroutineStart.LAZY) { scan() }
        if (scanJob.compareAndSet(current, job)) job.start() else job.cancel()
    }

    /**
     * Keep the list true to a join / leave that just succeeded anywhere in the app. The
     * item stays in [state] with `isMember` flipped — the sheet holds a row it just
     * subscribed for its "Subscribed" confirmation, then drops it — and later publishes
     * leave a joined channel out.
     */
    fun onMembershipChanged(chatId: Long, isMember: Boolean) {
        cards.computeIfPresent(chatId) { _, card -> card.copy(isMember = isMember) }
        _state.update { s ->
            if (s.items.none { it.channel.chatId == chatId && it.channel.isMember != isMember }) {
                s
            } else {
                s.copy(
                    items = s.items.map { item ->
                        if (item.channel.chatId == chatId) {
                            item.copy(channel = item.channel.copy(isMember = isMember))
                        } else {
                            item
                        }
                    }.toImmutableList(),
                )
            }
        }
    }

    /** Session-end wipe: stop the scan, forget memory and the persisted store. */
    suspend fun clear() {
        scanJob.getAndSet(null)?.cancelAndJoin()
        lock.withLock {
            entries = null
            cards.clear()
            noCard.clear()
            titles.clear()
            _state.value = RecommendedState()
        }
        store.clear()
    }

    private suspend fun scan() {
        startupPhase.first { it == StartupCoordinator.Phase.Active }
        val ranked = subscribedChannels()
        val subscribed = ranked.toSet()
        val hidden = hiddenChannels()
        val sources = ranked.filter { it !in hidden }
        val stale = lock.withLock {
            val map = entries ?: store.load().toMutableMap().also { entries = it }
            // Only once the subscription list is known: an empty list before the chat list
            // loaded would otherwise wipe every entry.
            if (subscribed.isNotEmpty() && map.keys.retainAll(subscribed)) store.retain(subscribed)
            sources.filter { !isFresh(map[it]) }.take(MAX_SOURCES_PER_SCAN)
        }
        publish(sources, subscribed, hidden, scanning = stale.isNotEmpty())
        try {
            for (source in stale) {
                val similar = fetch(source) ?: break
                val entry = SimilarEntry(similar, clock())
                store.put(source, entry)
                lock.withLock { entries?.put(source, entry) }
                publish(sources, subscribed, hidden, scanning = true)
            }
        } finally {
            _state.update { it.copy(scanning = false) }
        }
        publish(sources, subscribed, hidden, scanning = false)
    }

    /** Similar ids of [source]; empty on a per-source error; null means "stop the scan". */
    private suspend fun fetch(source: Long): List<Long>? = try {
        discovery.paced { td.send(TdApi.GetChatSimilarChats(source)) }.chatIds.toList()
    } catch (e: CancellationException) {
        throw e
    } catch (e: TdClient.TdException) {
        if (TdClient.isFloodWaitCode(e.code) || e.code == TdClient.SEND_TIMEOUT_CODE) {
            Log.i(TAG, "scan stopped at $source: ${e.message}")
            null
        } else {
            Log.w(TAG, "similar($source) failed: ${e.message}")
            emptyList()
        }
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        Log.w(TAG, "scan stopped at $source", e)
        null
    }

    private fun isFresh(entry: SimilarEntry?): Boolean =
        entry != null && clock() - entry.fetchedAtMs in 0 until TTL_MS

    private suspend fun publish(
        sources: List<Long>,
        subscribed: Set<Long>,
        hidden: Set<Long>,
        scanning: Boolean,
    ) = lock.withLock {
        val map = entries ?: return@withLock
        val similar = LinkedHashMap<Long, List<Long>>()
        for (s in sources) map[s]?.let { similar[s] = it.similar }
        val preliminary = rankRecommendations(similar, subscribed, hidden, limit = Int.MAX_VALUE)
        for (c in preliminary) loadCard(c.chatId)
        val subscribers = HashMap<Long, Int>()
        val excluded = HashSet<Long>()
        for (c in preliminary) {
            val card = cards[c.chatId]
            if (card == null || card.isMember) excluded += c.chatId
            card?.subscribers?.let { subscribers[c.chatId] = it }
        }
        val items = rankRecommendations(similar, subscribed, hidden, subscribers, excluded)
            .mapNotNull { c ->
                val card = cards[c.chatId] ?: return@mapNotNull null
                RecommendedChannel(card, c.voters.toImmutableList(), title(c.voters.first()))
            }
            .toImmutableList()
        val scanned = sources.count { isFresh(map[it]) }
        _state.value = RecommendedState(
            items = items,
            scanned = scanned,
            total = sources.size,
            scanning = scanning,
            complete = scanned == sources.size,
        )
    }

    private suspend fun loadCard(chatId: Long) {
        if (cards.containsKey(chatId) || chatId in noCard) return
        val card = discovery.offlineCard(chatId)
        if (card == null) noCard += chatId else cards[chatId] = card
    }

    private suspend fun title(chatId: Long): String? {
        titles[chatId]?.let { return it }
        // Local: [chatId] is one of the user's own channels.
        val chat = runCatching { td.send(TdApi.GetChat(chatId)) }
            .warnUnlessCancelled(TAG, "title/getChat")
            .getOrNull() ?: return null
        return chat.title?.takeIf { it.isNotBlank() }?.also { titles[chatId] = it }
    }

    companion object {
        private const val TAG = "RecommendedChannels"

        /** Network calls one scan may spend; the rest resume on the next demand. */
        const val MAX_SOURCES_PER_SCAN = 60

        /** How long a source's similar list is trusted before it is fetched again (3 days). */
        const val TTL_MS = 3L * 24 * 60 * 60 * 1000
    }
}
