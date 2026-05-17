package dev.lyo.hortay.testutil

import dev.lyo.hortay.data.ConnectionStatus
import dev.lyo.hortay.data.FakeStrings
import dev.lyo.hortay.data.FakeTdSender
import dev.lyo.hortay.data.MessageMapper
import dev.lyo.hortay.data.posts.PostsRepository
import dev.lyo.hortay.data.TimelineSnapshotStore
import dev.lyo.hortay.data.UserMessageBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.drinkless.tdlib.TdApi

/**
 * Wires a [PostsRepository] up against a [FakeTdSender] for unit testing. All collaborators
 * are stubs / in-memory implementations — no Android Context, no DataStore I/O, no real
 * coroutine scope. Exposed builders ([fakeChannel], [fakeChannelMessage]) match the
 * minimum shape `MessageMapper.toChannelPost` needs to build a valid `TimelinePost`.
 *
 * Use via:
 * ```
 * val harness = PostsRepositoryTestHarness(this)  // `this` is the TestScope from runTest
 * harness.td.emitUpdate(TdApi.UpdateNewChat(harness.fakeChannel(...)))
 * harness.runCurrent()
 * ```
 *
 * The single shared instance lives for the whole test; build a fresh one per test method.
 *
 * `resolveChannelHandle` inside `MessageMapper.toChannelPost` issues a `GetSupergroup` RPC
 * the first time it maps a post from a given channel. The harness registers a default
 * responder returning an empty `Supergroup` (no usernames, no verification) so the mapper
 * doesn't throw "Unexpected TdSender.send: GetSupergroup". Tests that need a real handle
 * can override via `td.onNext { ... }` before the mapping call.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PostsRepositoryTestHarness(private val outerScope: TestScope) {
    /**
     * Scope for the repository's long-lived coroutines. Uses [UnconfinedTestDispatcher] so
     * `.launchIn` subscribers run eagerly (inline) whenever the upstream SharedFlow emits —
     * no scheduler tick is needed between `emitUpdate(...)` and the side-effect being visible
     * in `chatCache` / `posts`. The scope is a child of the outer test scope so cancellation
     * propagates, but it is created via [CoroutineScope] (not the outer TestScope directly)
     * to avoid adding uncompleted active jobs to the test's `runTest` join set.
     */
    private val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(outerScope.testScheduler))

    val td: FakeTdSender = FakeTdSender().apply {
        // Default responder for GetSupergroup — returns an empty Supergroup so
        // resolveChannelHandle succeeds without a network round-trip. The no-username
        // result is cached by the mapper after the first call, so subsequent posts from
        // the same channel don't require additional responders.
        onAny("GetSupergroup") { _ ->
            TdApi.Supergroup().apply {
                usernames = null
                verificationStatus = null
            }
        }
        // Default responder for GetChat — ingest falls back to this when chatCache
        // misses (e.g. UpdateChatLastMessage before UpdateNewChat). Return a basic
        // channel-type Chat so isChannel() returns true and the post is accepted.
        // Tests that seed via UpdateNewChat won't hit this path.
        onAny("GetChat") { req ->
            val chatId = (req as TdApi.GetChat).chatId
            TdApi.Chat().apply {
                id = chatId
                title = "Chat $chatId"
                type = TdApi.ChatTypeSupergroup(1L, true)
                positions = emptyArray()
                permissions = TdApi.ChatPermissions()
            }
        }
    }

    private val foregroundState = MutableStateFlow(true)

    /** Flip foreground → background to trigger PostsRepository.saveSnapshotNow. */
    fun goBackground() {
        foregroundState.value = false
    }

    /** Flip background → foreground (no-op for save; symmetric helper). */
    fun goForeground() {
        foregroundState.value = true
    }

    private val connection = MutableStateFlow(ConnectionStatus.Ready)
    private val userMessages = UserMessageBus()
    private val mapper = MessageMapper(td, FakeStrings)

    /**
     * Exposed for tests that drive the snapshot upgrade path directly —
     * pre-populating the snapshot lets [PostsRepository.restoreFromSnapshot]
     * be exercised without needing to chain a save round-trip first.
     */
    val snapshotStore: InMemoryTimelineSnapshotStore = InMemoryTimelineSnapshotStore()

    val repo: PostsRepository = PostsRepository(
        td = td,
        mapper = mapper,
        scope = scope,
        userMessages = userMessages,
        connection = connection,
        snapshotStore = snapshotStore,
        foreground = foregroundState,
        res = FakeStrings,
    )

    fun runCurrent() {
        outerScope.testScheduler.runCurrent()
    }

    /** Drain all pending coroutine work from all scopes sharing this harness's scheduler. */
    fun advanceUntilIdle() {
        outerScope.advanceUntilIdle()
    }

    /**
     * Build a minimal channel-type [TdApi.Chat]. Supergroup with `isChannel=true` is what
     * Hortay's [PostsRepository.isChannel] extension filters for; `lastMessage` is the
     * load-bearing field for the lastMessage-harvest cold-start path.
     */
    fun fakeChannel(
        id: Long,
        title: String = "Channel $id",
        lastMessage: TdApi.Message? = null,
        // Default = 1 mirrors "active subscription with at least one unread post"
        // — the common case `refreshLocked` is designed for. Tests that want to
        // exercise the caught-up skip path (`unreadCount == 0`) pass 0 explicitly.
        unreadCount: Int = 1,
    ): TdApi.Chat = TdApi.Chat().apply {
        this.id = id
        this.title = title
        this.type = TdApi.ChatTypeSupergroup(1L, true)
        this.lastMessage = lastMessage
        this.positions = emptyArray()
        this.permissions = TdApi.ChatPermissions()
        this.unreadCount = unreadCount
    }

    /**
     * Build a minimal channel post [TdApi.Message]. Text content is the simplest the
     * mapper accepts. `senderId` is set to a chat sender so the mapper treats it as a
     * channel post rather than a user-authored message.
     */
    fun fakeChannelMessage(
        chatId: Long,
        messageId: Long,
        date: Int = 1_700_000_000,
        mediaAlbumId: Long = 0L,
        text: String = "msg $messageId",
    ): TdApi.Message = TdApi.Message().apply {
        this.id = messageId
        this.chatId = chatId
        this.date = date
        this.mediaAlbumId = mediaAlbumId
        this.senderId = TdApi.MessageSenderChat(chatId)
        this.content = TdApi.MessageText(
            TdApi.FormattedText(text, emptyArray()),
            null,
            null,
        )
    }

    class InMemoryTimelineSnapshotStore : TimelineSnapshotStore {
        private var entries: List<Pair<Long, Long>> = emptyList()
        override suspend fun load(): List<Pair<Long, Long>> = entries
        override suspend fun save(entries: List<Pair<Long, Long>>) { this.entries = entries }
        override suspend fun clear() { entries = emptyList() }
        /** Test-only seeder so tests can simulate "previous healthy session" state. */
        fun seed(entries: List<Pair<Long, Long>>) { this.entries = entries }
    }
}
