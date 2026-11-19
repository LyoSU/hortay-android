package dev.lyo.hortay.sim

import dev.lyo.hortay.data.ChatPresence
import dev.lyo.hortay.data.ChatRegistry
import dev.lyo.hortay.data.ConnectionStatus
import dev.lyo.hortay.data.FakeStrings
import dev.lyo.hortay.data.MessageMapper
import dev.lyo.hortay.data.TdSender
import dev.lyo.hortay.data.TimelinePost
import dev.lyo.hortay.data.UserMessageBus
import dev.lyo.hortay.data.posts.PostsRepository
import dev.lyo.hortay.data.record.TdTap
import dev.lyo.hortay.testutil.PostsRepositoryTestHarness
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle

/** A [PostsRepository] with the collaborators `AppGraph` builds around it, on any [TdSender]. */
class WiredRepository(td: TdSender, scope: CoroutineScope) {
    val registry = ChatRegistry(td).also { it.bind(scope) }
    val repo = PostsRepository(
        td = td,
        mapper = MessageMapper(td, FakeStrings),
        registry = registry,
        scope = scope,
        userMessages = UserMessageBus(),
        connection = MutableStateFlow(ConnectionStatus.Ready),
        snapshotStore = PostsRepositoryTestHarness.InMemoryTimelineSnapshotStore(),
        foreground = MutableStateFlow(true),
        res = FakeStrings,
        coldStartBackfill = PostsRepositoryTestHarness.InMemoryColdStartBackfillStore(),
    )
}

/**
 * A [PostsRepository] wired to a [SimTelegram] instead of a canned fake, on the test
 * scheduler's virtual clock. With a [tap], everything the repository and the model say to
 * each other is also recorded.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SimHarness(private val outer: TestScope, quirks: SimQuirks = SimQuirks(), tap: TdTap? = null) {
    private val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(outer.testScheduler))

    val sim = SimTelegram(nowMs = { outer.testScheduler.currentTime }, quirks = quirks)
    private val wired = WiredRepository(if (tap == null) sim else RecordingSender(sim, tap, scope), scope)
    val registry = wired.registry
    val repo = wired.repo

    val posts: List<TimelinePost> get() = repo.posts.value

    fun settle() = outer.advanceUntilIdle()

    /** The logout fan-out `AppGraph.runLogoutCleanup` performs for this slice of the graph. */
    suspend fun logOut() {
        sim.logOut()
        repo.clear()
        registry.clear()
        ChatPresence.clear()
    }
}

/** An account following [channels] channels (ids `base`, `base - 1`, …), every fourth archived. */
fun seededAccount(name: String, base: Long, channels: Int, postsEach: Int): SimAccount =
    SimAccount(name).also { acc ->
        repeat(channels) { i ->
            val ch = acc.channel(base - i, archived = i % 4 == 3)
            repeat(postsEach) {
                val id = ch.nextServerId++ shl 20
                ch.messages[id] = SimMessage(id, ch.id, 1_700_000_000 + it, 0L, "seed $id")
            }
            ch.order = ch.messages.lastKey()
        }
    }

/**
 * Safety properties that must hold whenever the system is quiescent, whatever the order
 * of requests and updates was. Returns human-readable violations (empty = all hold).
 */
fun SimHarness.violations(): List<String> {
    val out = ArrayList<String>()
    val account = sim.account
    val seen = HashSet<Pair<Long, Long>>()
    for (post in posts.filter { !it.isDeleted && it.parentId == null }) {
        val channel = account?.channels?.get(post.chatId)
        if (channel == null) {
            out += "post ${post.chatId}/${post.id} belongs to no channel of the current session"
        } else {
            for (id in post.albumMessageIds.ifEmpty { listOf(post.id) }) {
                if (id !in channel.messages) {
                    out += "post ${post.chatId}/$id is not on the server (deleted or never existed)"
                }
                if (!seen.add(post.chatId to id)) out += "message ${post.chatId}/$id is rendered by two cards"
            }
        }
    }
    // Server cap on getChatHistory (30 requests / 30 s): the app must stay under it.
    val history = sim.calls.filter { it.name == "GetChatHistory" && it.networked }.map { it.atMs }
    for (i in history.indices) {
        val inWindow = history.count { it in history[i]..(history[i] + HISTORY_WINDOW_MS) }
        if (inWindow > HISTORY_BUDGET) {
            out += "$inWindow networked GetChatHistory calls within 30 s (limit $HISTORY_BUDGET)"
            break
        }
    }
    return out
}

/** Liveness at quiescence: every known channel's newest post has reached the feed. */
fun SimHarness.missingNewest(): List<String> {
    val account = sim.account ?: return emptyList()
    val rendered = posts.flatMapTo(HashSet()) { p -> p.albumMessageIds.ifEmpty { listOf(p.id) }.map { p.chatId to it } }
    return account.channels.values
        .filter { sim.isAnnounced(it.id) && it.messages.isNotEmpty() }
        .filter { (it.id to it.messages.lastKey()) !in rendered }
        .map { "channel ${it.id}: newest post ${it.messages.lastKey()} is missing from the feed" }
}

private const val HISTORY_WINDOW_MS = 30_000L
private const val HISTORY_BUDGET = 30
