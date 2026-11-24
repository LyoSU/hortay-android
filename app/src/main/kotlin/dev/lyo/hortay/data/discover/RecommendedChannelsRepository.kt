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
import kotlinx.coroutines.delay
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
import java.util.concurrent.atomic.AtomicLong
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
 * Network budget — `getChatSimilarChats` is a server call on a bucket measured at about 30
 * calls per ~30 s window, after which the server answers FLOOD_WAIT for 26–28 s. That flood
 * arms [TdClient]'s ONE account-wide gate, freezing the user's own taps (Subscribe, opening
 * a channel) for its whole length, so this background scan must never be what trips it:
 *  - **On demand only.** [requestScan] is called once per opening of the signed-in
 *    add-channel sheet — never at cold start — and the scan first waits for
 *    [StartupCoordinator.Phase.Active]. It runs in the session scope, so logout cancels it.
 *  - **Spaced well inside the bucket.** One call at a time, started at least
 *    [SIMILAR_CALL_SPACING_MS] (2.5 s) after the previous one: at most 30 / 2.5 = 12 calls
 *    (13 counting both window edges) in any 30 s window, under half the bucket — and still
 *    under 30 if the window were twice as long. The spacing is slept OUTSIDE the shared
 *    [ChannelDiscoveryRepository.paced] throttle (≥ 1 s, shared with the curated-handle
 *    resolves), so those resolves and the user's actions are never queued behind it; the
 *    call itself still goes through `paced`, so the two never stack their bursts.
 *  - **A per-scan budget** of [MAX_CALLS_PER_SCAN] (40) calls, sources in chat-list
 *    order: with the spacing a full scan takes ~100 s (39 × 2.5 s) and peaks at 12–13 calls
 *    per window, so the budget is safe on its own; the rest resume on the next demand.
 *  - **Stop, don't retry** — the safety net if the bucket is already drained by something
 *    else. A FLOOD_WAIT (420 / 429, already armed in [TdClient]'s gate) or a send timeout
 *    ends the scan quietly with what it has and starts a cooldown of at least
 *    [FLOOD_COOLDOWN_MS] (longer if the server asked for longer, up to
 *    [MAX_FLOOD_COOLDOWN_MS]) during which [requestScan] is a no-op, so re-entering the
 *    sheet's empty state cannot restart the scan the moment the gate opens. After it the
 *    next demand resumes from the next unscanned source. No snackbar — this is background
 *    work the user didn't ask for. An error that is permanent for that chat (400 / 403 /
 *    404) records an empty entry so the source is not retried until TTL; any other error
 *    (5xx, internal) skips the source without recording anything, and
 *    [MAX_CONSECUTIVE_TRANSIENT_FAILURES] of those in a row end the scan.
 *  - **Persisted** in [SimilarChatsStore] with a [TTL_MS] lifetime: fresh entries are never
 *    refetched, entries of channels the user left are pruned (only once the chat list has
 *    fully loaded — [chatListLoaded]), and a reopened sheet paints from disk with zero
 *    network.
 *
 * Card data comes from local calls only ([ChannelDiscoveryRepository.offlineCard]); the
 * scan publishes after every source so the section fills in progressively. Channels hidden
 * from the feed do not vote and are not fetched.
 *
 * Session end ([clear]): every scan captures a session generation, and each of its
 * memory / store / state writes happens under [lock] only while that generation is still
 * current, so a scan that started before the wipe can neither publish nor write after it.
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
    /**
     * True once this session's chat-list drain (LoadChats Main + Archive) has completed, so
     * [subscribedChannels] is the whole list — the only time stored entries may be pruned.
     */
    private val chatListLoaded: StateFlow<Boolean>,
    /** Current session scope, read at launch time (replaced on logout). */
    private val sessionScope: () -> CoroutineScope,
    /** Wall clock: entry freshness ([TTL_MS]) and the flood cooldown. */
    private val clock: () -> Long = System::currentTimeMillis,
    /** Monotonic milliseconds for the call spacing; immune to the user changing the clock. */
    private val elapsedMs: () -> Long = { System.nanoTime() / NANOS_PER_MS },
) {
    private val _state = MutableStateFlow(RecommendedState())
    val state: StateFlow<RecommendedState> = _state.asStateFlow()

    private val scanJob = AtomicReference<Job?>(null)

    /** Session generation; bumped by [clear]. A scan acts only while its captured value is current. */
    private val generation = AtomicLong(0L)

    /** Set when a scan stopped on a flood / send timeout; [requestScan] is a no-op while active. */
    private val cooldown = AtomicReference<Cooldown?>(null)

    /** [elapsedMs] when the last similar-chats call was sent; null before the first one. */
    @Volatile
    private var lastSimilarCallAt: Long? = null

    // Guards [entries], every store write and every publish; cards/titles are concurrent maps
    // because [onMembershipChanged] is called from outside a coroutine.
    private val lock = Mutex()
    private var entries: MutableMap<Long, SimilarEntry>? = null
    private val cards = ConcurrentHashMap<Long, DiscoverChannel>()
    private val noCard: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    private val titles = ConcurrentHashMap<Long, String>()

    /** A stop's cooldown: [lengthMs] from [startedAtMs] (wall clock). */
    private class Cooldown(val startedAtMs: Long, val lengthMs: Long) {
        // A clock moved backwards ends it rather than stretching it.
        fun isActive(now: Long): Boolean = now - startedAtMs in 0 until lengthMs
    }

    /**
     * Start (or resume) a scan unless one is already running or a flood stop's cooldown is
     * still on. Cheap when everything is fresh.
     */
    fun requestScan() {
        if (cooldown.get()?.isActive(clock()) == true) return
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

    /**
     * Session-end wipe: stop the scan, forget memory, the persisted store and the cooldown.
     *
     * The generation is bumped first, so the running scan's writes stop even before it
     * finishes cancelling, and again inside the lock together with the wipe, so a scan
     * launched in the meantime (the sheet calling [requestScan] during logout) that read the
     * old store before the wipe is just as unable to write or publish after it. That scan is
     * cancelled last.
     */
    suspend fun clear() {
        generation.incrementAndGet()
        scanJob.getAndSet(null)?.cancelAndJoin()
        lock.withLock {
            generation.incrementAndGet()
            entries = null
            cards.clear()
            noCard.clear()
            titles.clear()
            cooldown.set(null)
            _state.value = RecommendedState()
            store.clear()
        }
        scanJob.getAndSet(null)?.cancel()
    }

    private suspend fun scan() {
        val gen = generation.get()
        startupPhase.first { it == StartupCoordinator.Phase.Active }
        // Read BEFORE the list: once the drain has completed, the list read after it is whole.
        val listComplete = chatListLoaded.value
        val ranked = subscribedChannels()
        val subscribed = ranked.toSet()
        val hidden = hiddenChannels()
        val sources = ranked.filter { it !in hidden }
        val stale = lock.withLock {
            if (gen != generation.get()) return
            val map = entries ?: store.load().toMutableMap().also { entries = it }
            // Prune only from a complete list. Startup turns Active while LoadChats (Main +
            // Archive) can still be draining, and pruning from that partial list deleted the
            // cached entries of every channel that had not arrived yet. Scanning the partial
            // list is fine — it only fetches what it already knows — so the scan does not
            // wait for the drain, which never completes offline; a later scan prunes.
            if (listComplete && map.keys.retainAll(subscribed)) store.retain(subscribed)
            sources.filter { !isFresh(map[it]) }.take(MAX_CALLS_PER_SCAN)
        }
        publish(gen, sources, subscribed, hidden, scanning = stale.isNotEmpty())
        try {
            var transientFailures = 0
            for (source in stale) {
                val result = fetch(gen, source)
                transientFailures = if (result == Fetched.Skip) transientFailures + 1 else 0
                val goOn = when (result) {
                    is Fetched.Similar -> record(gen, source, result.ids)
                    Fetched.Skip -> transientFailures < MAX_CONSECUTIVE_TRANSIENT_FAILURES
                    Fetched.Stop -> false
                }
                if (!goOn) break
                if (result is Fetched.Similar) publish(gen, sources, subscribed, hidden, scanning = true)
            }
        } finally {
            if (gen == generation.get()) _state.update { it.copy(scanning = false) }
        }
        publish(gen, sources, subscribed, hidden, scanning = false)
    }

    /** Persist [source]'s similar list; false once the session that started the scan has ended. */
    private suspend fun record(gen: Long, source: Long, similar: List<Long>): Boolean {
        val entry = SimilarEntry(similar, clock())
        return lock.withLock {
            if (gen != generation.get()) return@withLock false
            store.put(source, entry)
            entries?.put(source, entry)
            true
        }
    }

    /** What one similar-chats call means for the scan. */
    private sealed interface Fetched {
        /** Persist [ids] (empty for an error that is permanent for this source). */
        class Similar(val ids: List<Long>) : Fetched

        /** A transient failure: skip the source without persisting; the next scan retries it. */
        data object Skip : Fetched

        /** Flood, send timeout or unexpected error: end the scan. */
        data object Stop : Fetched
    }

    private suspend fun fetch(gen: Long, source: Long): Fetched {
        awaitCallSpacing()
        return try {
            Fetched.Similar(
                discovery.paced {
                    lastSimilarCallAt = elapsedMs()
                    td.send(TdApi.GetChatSimilarChats(source))
                }.chatIds.toList(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: TdClient.TdException) {
            if (TdClient.isFloodWaitCode(e.code) || e.code == TdClient.SEND_TIMEOUT_CODE) {
                val retryAfterMs = (TdClient.parseFloodWaitSeconds(e) ?: 0) * MS_PER_SECOND
                val lengthMs = maxOf(FLOOD_COOLDOWN_MS, retryAfterMs).coerceAtMost(MAX_FLOOD_COOLDOWN_MS)
                Log.i(TAG, "scan stopped at $source for ${lengthMs / MS_PER_SECOND}s: ${e.message}")
                lock.withLock {
                    if (gen == generation.get()) cooldown.set(Cooldown(clock(), lengthMs))
                }
                Fetched.Stop
            } else if (isPermanentForChat(e.code)) {
                Log.w(TAG, "similar($source) failed for good: ${e.message}")
                Fetched.Similar(emptyList())
            } else {
                Log.w(TAG, "similar($source) failed, will retry: ${e.message}")
                Fetched.Skip
            }
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            Log.w(TAG, "scan stopped at $source", e)
            Fetched.Stop
        }
    }

    /**
     * Whether a `getChatSimilarChats` error code is an answer about THIS chat that will not
     * change by asking again: 400 (invalid chat / not a channel), 403 (no access), 404 (not
     * found). Such a source is recorded as empty for [TTL_MS]. Everything else — 5xx and
     * TDLib's internal errors, 401, 406, unknown codes — says nothing about the chat, so
     * persisting an empty list would hide the source's votes for three days over a hiccup.
     */
    private fun isPermanentForChat(code: Int): Boolean =
        code == CODE_BAD_REQUEST || code == CODE_FORBIDDEN || code == CODE_NOT_FOUND

    /** Sleep until [SIMILAR_CALL_SPACING_MS] has passed since the last call, holding no lock. */
    private suspend fun awaitCallSpacing() {
        val last = lastSimilarCallAt ?: return
        val since = (elapsedMs() - last).coerceAtLeast(0L)
        if (since < SIMILAR_CALL_SPACING_MS) delay(SIMILAR_CALL_SPACING_MS - since)
    }

    private fun isFresh(entry: SimilarEntry?): Boolean =
        entry != null && clock() - entry.fetchedAtMs in 0 until TTL_MS

    private suspend fun publish(
        gen: Long,
        sources: List<Long>,
        subscribed: Set<Long>,
        hidden: Set<Long>,
        scanning: Boolean,
    ) = lock.withLock {
        if (gen != generation.get()) return@withLock
        val map = entries ?: return@withLock
        val similar = LinkedHashMap<Long, List<Long>>()
        for (s in sources) map[s]?.let { similar[s] = it.similar }
        // The vote bar follows every voting source the user has, not how many are scanned.
        val votingSources = sources.size
        val preliminary = rankRecommendations(similar, subscribed, hidden, votingSources, limit = Int.MAX_VALUE)
        // Cards are loaded a whole vote tier at a time (votes desc) and only until the tiers
        // loaded so far hold RECOMMENDED_LIMIT usable cards: within a tier the order needs
        // every member's subscriber count, and no candidate of a lower tier can outrank a
        // full set above it, so the result is the same as loading every candidate.
        val subscribers = HashMap<Long, Int>()
        val excluded = HashSet<Long>()
        var usable = 0
        var tierVotes = -1
        for (c in preliminary) {
            if (c.votes != tierVotes) {
                if (usable >= RECOMMENDED_LIMIT) {
                    excluded += c.chatId
                    continue
                }
                tierVotes = c.votes
            }
            loadCard(c.chatId)
            val card = cards[c.chatId]
            if (card == null || card.isMember) excluded += c.chatId else usable++
            card?.subscribers?.let { subscribers[c.chatId] = it }
        }
        val items = rankRecommendations(similar, subscribed, hidden, votingSources, subscribers, excluded)
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
        private const val NANOS_PER_MS = 1_000_000L
        private const val MS_PER_SECOND = 1_000L
        private const val CODE_BAD_REQUEST = 400
        private const val CODE_FORBIDDEN = 403
        private const val CODE_NOT_FOUND = 404

        /**
         * Transient failures in a row after which a scan gives up: the connection or the
         * server is having a bad moment, and walking on would only spend the call budget on
         * more of the same. Nothing is persisted for them, so the next demand retries.
         */
        const val MAX_CONSECUTIVE_TRANSIENT_FAILURES = 3

        /**
         * Similar-chats calls one scan may spend; the rest resume on the next demand. Safe on
         * its own with [SIMILAR_CALL_SPACING_MS]: 40 calls span ~100 s, ≤ 13 in any 30 s window.
         */
        const val MAX_CALLS_PER_SCAN = 40

        /**
         * Least time between two similar-chats calls, start to start: ≤ 12 calls per ~30 s
         * window (13 counting both edges), under half the ~30-call bucket.
         */
        const val SIMILAR_CALL_SPACING_MS = 2_500L

        /** Shortest pause in scanning after a flood or send-timeout stop (5 minutes). */
        const val FLOOD_COOLDOWN_MS = 5L * 60 * 1000

        /** Cap on a server-requested retry-after taken as the cooldown (1 hour). */
        const val MAX_FLOOD_COOLDOWN_MS = 60L * 60 * 1000

        /** How long a source's similar list is trusted before it is fetched again (3 days). */
        const val TTL_MS = 3L * 24 * 60 * 60 * 1000
    }
}
