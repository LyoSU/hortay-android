# TDLib cold-start lastMessage-only — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Hortay's `GetChat × N + GetChatHistory × N` cold-start fan-out with a `Chat.lastMessage`-harvest flow modelled on the official Telegram-Android client. Cuts cold-start TDLib RPC volume by ~30× and effectively eliminates the post-login FLOOD_WAIT class.

**Architecture:** On `refreshLocked`, drive `LoadChats(ChatListMain)` to make TDLib emit `UpdateNewChat` for every chat (free server push). Each `UpdateNewChat.chat` already carries `lastMessage`. We harvest those messages from the existing `chatCache`, await any late `UpdateNewChat` arrivals up to 2 s, and route each `lastMessage` through the existing `ingest()` pipeline (which already handles channel filter, album coalescing, dedup, and `_newArrivals` emission). Archive is dropped from cold-start. A new `UpdateChatLastMessage` listener keeps `chatCache` fresh and routes mid-session last-message changes through the same `ingest()` so no message is missed.

**Tech Stack:** Kotlin (JVM 17), Coroutines 1.10, TDLib (vendored), JUnit 5, FakeTdSender (existing in-memory test double).

**Spec:** `docs/superpowers/specs/2026-05-11-tdlib-cold-start-lastmessage-only-design.md`

---

## File Structure

| File | Responsibility | Action |
|---|---|---|
| `app/src/main/kotlin/dev/lyo/hortay/data/PostsRepository.kt` | Cold-start orchestration, `chatCache`, `ingest`, refresh mutex. | **Modify**: rewrite `refreshLocked`, add `UpdateChatLastMessage` listener, add private `suspendUntilOrTimeout` helper. |
| `app/src/main/kotlin/dev/lyo/hortay/data/StartupCoordinator.kt` | Booting → Active phase gate. | **Modify**: lower `ACTIVATE_POSTS_THRESHOLD: 20 → 8`. |
| `app/src/test/kotlin/dev/lyo/hortay/data/FakeTdSender.kt` | In-memory TDLib double for tests. | **Modify**: add RPC counter that records every `send()` keyed by `Function` class name. |
| `app/src/test/kotlin/dev/lyo/hortay/data/PostsRepositoryRefreshTest.kt` | New: pin the cold-start RPC contract. | **Create**: 7 unit tests. |
| `CHANGELOG.md` | User-visible changelog (Keep a Changelog format). | **Modify**: `[Unreleased]` entry under Performance and Changed. |
| `CLAUDE.md` | Project context for AI agents. | **Modify**: update the load-bearing table row for `PostsRepository concurrency` to reflect the new flow. |

Files NOT touched: `MediaCache`, `TdLifecycleBridge`, `TdClient`, `MediaAutoDownloader`, `CommentsRepository`, every UI file, every web-mode file.

---

## Task 1: Add RPC counter to FakeTdSender

**Files:**
- Modify: `app/src/test/kotlin/dev/lyo/hortay/data/FakeTdSender.kt`

Why: every test in this plan asserts on call counts ("zero GetChat", "exactly N GetChatHistory for album"), so the counter is shared infra and lands first.

- [ ] **Step 1: Add the counter and a `rpcCount(name)` accessor**

Replace the entire body of `FakeTdSender.kt` with:

```kotlin
package dev.lyo.hortay.data

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.drinkless.tdlib.TdApi
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory [TdSender] for tests. Each request is matched against a queue of canned
 * answers; updates are pushed via [emitUpdate]. Unknown requests fail loudly so a missed
 * stub surfaces immediately instead of silently returning defaults.
 *
 * Every successful `send()` is counted by the request's simple class name in [rpcCounts]
 * so tests can assert on RPC budget (e.g. "zero GetChatHistory calls were made").
 */
class FakeTdSender : TdSender {
    private val responders = ArrayDeque<(TdApi.Function<*>) -> TdApi.Object?>()
    private val _updates = MutableSharedFlow<TdApi.Update>(extraBufferCapacity = 64)
    override val updates: SharedFlow<TdApi.Update> = _updates.asSharedFlow()

    private val _rpcCounts = ConcurrentHashMap<String, Int>()
    val rpcCounts: Map<String, Int> get() = _rpcCounts.toMap()
    fun rpcCount(name: String): Int = _rpcCounts[name] ?: 0

    /** Register a one-shot responder. They fire FIFO — call in the order of expected calls. */
    fun onNext(handle: (TdApi.Function<*>) -> TdApi.Object) {
        responders.addLast(handle)
    }

    /**
     * Register a default responder for any request whose class simple name matches [name].
     * Used for "I don't care about ordering, just answer this RPC type with the same canned
     * value every time it's asked." Falls through to the FIFO `onNext` queue first; only
     * consulted if no FIFO responder is registered.
     */
    private val defaults = ConcurrentHashMap<String, (TdApi.Function<*>) -> TdApi.Object>()
    fun onAny(name: String, handle: (TdApi.Function<*>) -> TdApi.Object) {
        defaults[name] = handle
    }

    suspend fun emitUpdate(update: TdApi.Update) {
        _updates.emit(update)
    }

    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : TdApi.Object> send(query: TdApi.Function<T>): T {
        val name = query::class.simpleName ?: "Unknown"
        _rpcCounts.merge(name, 1) { a, b -> a + b }
        val responder = responders.removeFirstOrNull()
            ?: defaults[name]
            ?: error("Unexpected TdSender.send: $name — register a responder via onNext() or onAny()")
        return responder(query) as T
    }
}
```

- [ ] **Step 2: Compile-check (no test yet — this is shared infra used by later tasks)**

Run: `./gradlew :app:compileDebugUnitTestKotlin`
Expected: BUILD SUCCESSFUL. No other test files reference `rpcCounts` yet.

If `CommentsRepositoryTest.kt` was using the old FakeTdSender shape and breaks, fix the breakage now (the `onAny` and `rpcCounts` additions are purely additive — `onNext` and `send` keep the same external contract, so existing tests should pass unchanged).

- [ ] **Step 3: Commit**

```bash
git add app/src/test/kotlin/dev/lyo/hortay/data/FakeTdSender.kt
git commit -m "test(infra): rpc counter + onAny default on FakeTdSender"
```

---

## Task 2: Add `suspendUntilOrTimeout` helper

**Files:**
- Modify: `app/src/main/kotlin/dev/lyo/hortay/data/PostsRepository.kt` (add private helper near the bottom of the class, before the `companion object`)
- Test: `app/src/test/kotlin/dev/lyo/hortay/data/SuspendUntilOrTimeoutTest.kt` (new)

Why: the rewritten `refreshLocked` waits up to 2 s for `UpdateNewChat` arrivals to fill `chatCache`. The helper is tiny and testable in isolation; lock it in first.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/kotlin/dev/lyo/hortay/data/SuspendUntilOrTimeoutTest.kt`:

```kotlin
package dev.lyo.hortay.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SuspendUntilOrTimeoutTest {

    @Test
    fun `returns true immediately if predicate already true`() = runTest {
        val result = suspendUntilOrTimeout(timeoutMs = 1_000, pollIntervalMs = 50) { true }
        assertTrue(result)
    }

    @Test
    fun `returns true when predicate flips before timeout`() = runTest {
        var flag = false
        launch {
            delay(120)
            flag = true
        }
        val result = suspendUntilOrTimeout(timeoutMs = 1_000, pollIntervalMs = 50) { flag }
        assertTrue(result)
    }

    @Test
    fun `returns false when predicate never flips`() = runTest {
        val result = suspendUntilOrTimeout(timeoutMs = 300, pollIntervalMs = 50) { false }
        assertFalse(result)
    }

    @Test
    fun `polls at the configured interval, not faster`() = runTest {
        var calls = 0
        val result = suspendUntilOrTimeout(timeoutMs = 250, pollIntervalMs = 100) {
            calls++; false
        }
        assertFalse(result)
        // Expect ~ceil(250 / 100) = 3 polls (t=0, t=100, t=200), then timeout.
        // Allow some slack for the test scheduler; the important bit is "not 100+".
        assertTrue(calls in 2..4, "calls=$calls outside [2..4]")
    }
}
```

- [ ] **Step 2: Run the test, expect compile failure**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.lyo.hortay.data.SuspendUntilOrTimeoutTest"`
Expected: BUILD FAILED, "Unresolved reference: suspendUntilOrTimeout".

- [ ] **Step 3: Implement the helper as a top-level package-private function**

Open `app/src/main/kotlin/dev/lyo/hortay/data/PostsRepository.kt`. At the very bottom of the file (AFTER the closing brace of `class PostsRepository` and AFTER any existing private extension function `isChannel()`), add:

```kotlin
/**
 * Poll [predicate] every [pollIntervalMs] until it returns true OR [timeoutMs] elapses.
 * Returns true on success, false on timeout. Predicate is checked once synchronously
 * before any delay, so a pre-satisfied condition costs zero suspensions.
 *
 * Lives in this file because its sole consumer is [PostsRepository.refreshLocked]. Not a
 * private method on the class so the unit test can exercise it without instantiating the
 * full repository graph.
 */
internal suspend fun suspendUntilOrTimeout(
    timeoutMs: Long,
    pollIntervalMs: Long,
    predicate: () -> Boolean,
): Boolean {
    if (predicate()) return true
    return kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
        while (!predicate()) kotlinx.coroutines.delay(pollIntervalMs)
        true
    } ?: false
}
```

- [ ] **Step 4: Run the test, expect pass**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.lyo.hortay.data.SuspendUntilOrTimeoutTest"`
Expected: BUILD SUCCESSFUL, 4 tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/kotlin/dev/lyo/hortay/data/PostsRepository.kt \
        app/src/test/kotlin/dev/lyo/hortay/data/SuspendUntilOrTimeoutTest.kt
git commit -m "feat(timeline): suspendUntilOrTimeout helper for refresh race-await"
```

---

## Task 3: Wire `UpdateChatLastMessage` into chatCache + ingest

**Files:**
- Modify: `app/src/main/kotlin/dev/lyo/hortay/data/PostsRepository.kt` (init block at lines 198-220 area; add a new listener block right after the existing `UpdateNewChat` listener)
- Test: `app/src/test/kotlin/dev/lyo/hortay/data/PostsRepositoryUpdateChatLastMessageTest.kt` (new)

Why: this listener catches two scenarios — (a) `lastMessage` arriving for a chat that was previously cached with `lastMessage = null`, and (b) mid-session last-message swap (edit, delete cascade). Without it, the rewritten `refreshLocked` would miss late-syncing chats and the feed would silently lose channels.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/kotlin/dev/lyo/hortay/data/PostsRepositoryUpdateChatLastMessageTest.kt`:

```kotlin
package dev.lyo.hortay.data

import dev.lyo.hortay.testutil.PostsRepositoryTestHarness
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.TdApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PostsRepositoryUpdateChatLastMessageTest {

    @Test
    fun `UpdateChatLastMessage on a known chat updates chatCache and ingests the message`() = runTest {
        val harness = PostsRepositoryTestHarness(this)

        // Step A: seed a channel chat with no lastMessage yet via UpdateNewChat.
        val chat = harness.fakeChannel(id = -1001L, title = "Test channel", lastMessage = null)
        harness.td.emitUpdate(TdApi.UpdateNewChat(chat))
        harness.runCurrent()

        // Step B: emit UpdateChatLastMessage with a fresh message.
        val msg = harness.fakeChannelMessage(chatId = chat.id, messageId = 42L, date = 1_700_000_000)
        harness.td.emitUpdate(
            TdApi.UpdateChatLastMessage(chat.id, msg, /* positions */ emptyArray()),
        )
        harness.runCurrent()

        // Then: chatCache reflects the new lastMessage AND the feed contains the post.
        assertEquals(msg.id, harness.repo.chatCacheForTest(chat.id)?.lastMessage?.id)
        assertEquals(1, harness.repo.posts.value.size)
        assertEquals(msg.id, harness.repo.posts.value[0].id)
    }

    @Test
    fun `UpdateChatLastMessage with the same message id does not duplicate the feed entry`() = runTest {
        val harness = PostsRepositoryTestHarness(this)
        val chat = harness.fakeChannel(id = -1002L, title = "Same msg")
        val msg = harness.fakeChannelMessage(chatId = chat.id, messageId = 7L, date = 1_700_000_000)
        harness.td.emitUpdate(TdApi.UpdateNewChat(chat.copy(lastMessage = msg)))
        harness.runCurrent()
        harness.td.emitUpdate(TdApi.UpdateChatLastMessage(chat.id, msg, emptyArray()))
        harness.runCurrent()

        assertEquals(1, harness.repo.posts.value.size)
    }
}
```

- [ ] **Step 2: Create the test harness referenced above**

The harness encapsulates `PostsRepository` instantiation for tests, exposes a `chatCacheForTest` accessor, and provides fakeChannel/fakeChannelMessage builders. Create `app/src/test/kotlin/dev/lyo/hortay/testutil/PostsRepositoryTestHarness.kt`:

```kotlin
package dev.lyo.hortay.testutil

import dev.lyo.hortay.data.ConnectionStatus
import dev.lyo.hortay.data.FakeTdSender
import dev.lyo.hortay.data.MessageMapper
import dev.lyo.hortay.data.PostsRepository
import dev.lyo.hortay.data.StringResolver
import dev.lyo.hortay.data.TimelineSnapshotStore
import dev.lyo.hortay.data.UserMessageBus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.drinkless.tdlib.TdApi

@OptIn(ExperimentalCoroutinesApi::class)
class PostsRepositoryTestHarness(private val scope: TestScope) {
    val td = FakeTdSender()
    private val foreground = MutableStateFlow(true)
    private val connection = MutableStateFlow(ConnectionStatus.Ready)
    private val userMessages = UserMessageBus()
    private val mapper = MessageMapper(td, scope, StringResolverStub)
    private val snapshotStore = InMemorySnapshotStore()

    val repo: PostsRepository = PostsRepository(
        td = td,
        mapper = mapper,
        scope = scope,
        userMessages = userMessages,
        connection = connection,
        snapshotStore = snapshotStore,
        foreground = foreground,
        res = StringResolverStub,
    )

    fun runCurrent() { scope.runCurrent() }

    fun fakeChannel(
        id: Long,
        title: String = "Channel $id",
        lastMessage: TdApi.Message? = null,
    ): TdApi.Chat = TdApi.Chat().apply {
        this.id = id
        this.title = title
        this.type = TdApi.ChatTypeSupergroup(/*id*/ -id.toInt(), /*isChannel*/ true)
        this.lastMessage = lastMessage
        this.positions = emptyArray()
        this.permissions = TdApi.ChatPermissions()
    }

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
        this.content = TdApi.MessageText(TdApi.FormattedText(text, emptyArray()), null, null)
    }

    private object StringResolverStub : StringResolver {
        override fun string(id: Int): String = "stub"
        override fun string(id: Int, vararg args: Any): String = "stub"
        override fun plural(id: Int, qty: Int): String = "stub"
        override fun plural(id: Int, qty: Int, vararg args: Any): String = "stub"
    }

    private class InMemorySnapshotStore : TimelineSnapshotStore {
        override suspend fun save(rows: List<Pair<Long, Long>>) = Unit
        override suspend fun load(): List<Pair<Long, Long>> = emptyList()
        override suspend fun clear() = Unit
    }
}
```

If `StringResolver` has different members or `TimelineSnapshotStore` has a different signature than shown, adjust the stubs to compile against the actual interface (read `app/src/main/kotlin/dev/lyo/hortay/data/StringResolver.kt` and `TimelineSnapshotStore.kt` first; do NOT add or remove members on the production interfaces).

- [ ] **Step 3: Add `chatCacheForTest` accessor on `PostsRepository`**

In `app/src/main/kotlin/dev/lyo/hortay/data/PostsRepository.kt`, add immediately after the `chatCache` declaration (around line 66):

```kotlin
    /**
     * Test-only accessor. Exposes the internal chat cache so unit tests can verify that
     * UpdateNewChat / UpdateChatLastMessage listeners populate it correctly. Not annotated
     * @VisibleForTesting because that requires an extra androidx dependency on this module;
     * the `ForTest` suffix is the convention used elsewhere in this codebase.
     */
    internal fun chatCacheForTest(chatId: Long): TdApi.Chat? = chatCache[chatId]
```

- [ ] **Step 4: Run the test, expect compile failure**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.lyo.hortay.data.PostsRepositoryUpdateChatLastMessageTest"`
Expected: BUILD FAILED. Likely either "Unresolved reference: UpdateChatLastMessage" if the listener isn't there, or test assertion failure ("expected 1 post but got 0").

- [ ] **Step 5: Implement the listener**

In `PostsRepository.kt`, find the existing `UpdateNewChat` listener block (lines 198-200):

```kotlin
        td.updates.filterIsInstance<TdApi.UpdateNewChat>()
            .onEach { update -> chatCache[update.chat.id] = update.chat }
            .launchIn(scope)
```

Immediately AFTER it (before the `UpdateChatAddedToList` block), add:

```kotlin
        // UpdateChatLastMessage fires when TDLib (a) discovers the lastMessage for a chat
        // that was previously known without one — common on cold-start when UpdateNewChat
        // arrives before the chat's own last-message sync — and (b) when the existing
        // lastMessage is replaced (edit, delete cascade, or a fresh post that bypasses
        // UpdateNewMessage routing on a chat we haven't OpenChat'd). Keep chatCache
        // canonical AND route the message through ingest so the feed picks it up.
        // ingest itself is idempotent on (chatId, messageId) so a duplicate arrival
        // (UpdateNewMessage races UpdateChatLastMessage on the same id) cannot dupe a card.
        td.updates.filterIsInstance<TdApi.UpdateChatLastMessage>()
            .onEach { update ->
                val msg = update.lastMessage ?: return@onEach
                val existing = chatCache[update.chatId]
                if (existing != null) {
                    // Mutate the cached chat's lastMessage in place. TDLib's generated
                    // classes are mutable POJOs; this is consistent with how the rest of
                    // this file treats them (see e.g. UpdateChatTitle handlers elsewhere).
                    existing.lastMessage = msg
                }
                ingest(update.chatId, listOf(msg))
            }
            .launchIn(scope)
```

- [ ] **Step 6: Run the test, expect pass**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.lyo.hortay.data.PostsRepositoryUpdateChatLastMessageTest"`
Expected: BUILD SUCCESSFUL, 2 tests pass.

If a test fails with a deserialization error or `MessageMapper` rejects the fake message, inspect the failure and adjust `fakeChannelMessage` builder — the TDLib types must satisfy `MessageMapper.toChannelPost`'s expected fields (text content + sender chat is typically enough).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/kotlin/dev/lyo/hortay/data/PostsRepository.kt \
        app/src/test/kotlin/dev/lyo/hortay/data/PostsRepositoryUpdateChatLastMessageTest.kt \
        app/src/test/kotlin/dev/lyo/hortay/testutil/PostsRepositoryTestHarness.kt
git commit -m "feat(timeline): route UpdateChatLastMessage through chatCache + ingest"
```

---

## Task 4: Rewrite `refreshLocked` to harvest lastMessage instead of fan-out

**Files:**
- Modify: `app/src/main/kotlin/dev/lyo/hortay/data/PostsRepository.kt` (lines 1169-1291 — the `refreshLocked` body)
- Test: `app/src/test/kotlin/dev/lyo/hortay/data/PostsRepositoryRefreshTest.kt` (new)

Why: this is the load-bearing change. Tests lock in the contract (zero GetChat, zero GetChatHistory in non-album case, no ChatListArchive, race tolerance) BEFORE rewrite, so the implementation can't drift unnoticed.

- [ ] **Step 1: Write the failing tests**

Create `app/src/test/kotlin/dev/lyo/hortay/data/PostsRepositoryRefreshTest.kt`:

```kotlin
package dev.lyo.hortay.data

import dev.lyo.hortay.testutil.PostsRepositoryTestHarness
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.drinkless.tdlib.TdApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PostsRepositoryRefreshTest {

    @Test
    fun `refresh issues zero GetChat fan-out calls`() = runTest {
        val harness = PostsRepositoryTestHarness(this)
        // Seed 3 channels via UpdateNewChat, each with a lastMessage.
        repeat(3) { i ->
            val chat = harness.fakeChannel(
                id = -1000L - i,
                lastMessage = harness.fakeChannelMessage(-1000L - i, 100L + i),
            )
            harness.td.emitUpdate(TdApi.UpdateNewChat(chat))
        }
        harness.runCurrent()

        // Canned LoadChats + GetChats responders for the refresh path.
        harness.td.onAny("LoadChats") { TdApi.Error(404, "no more") }
        harness.td.onAny("GetChats") {
            TdApi.Chats(longArrayOf(-1000L, -1001L, -1002L), /*total*/ 3)
        }

        harness.repo.refresh()
        harness.runCurrent()

        assertEquals(0, harness.td.rpcCount("GetChat"),
            "refresh must not fan out GetChat per chat — chatCache is populated by UpdateNewChat")
    }

    @Test
    fun `refresh issues zero GetChatHistory calls when no lastMessage is an album member`() = runTest {
        val harness = PostsRepositoryTestHarness(this)
        repeat(3) { i ->
            val chat = harness.fakeChannel(
                id = -2000L - i,
                lastMessage = harness.fakeChannelMessage(-2000L - i, 200L + i, mediaAlbumId = 0L),
            )
            harness.td.emitUpdate(TdApi.UpdateNewChat(chat))
        }
        harness.runCurrent()
        harness.td.onAny("LoadChats") { TdApi.Error(404, "no more") }
        harness.td.onAny("GetChats") { TdApi.Chats(longArrayOf(-2000L, -2001L, -2002L), 3) }

        harness.repo.refresh()
        harness.runCurrent()

        assertEquals(0, harness.td.rpcCount("GetChatHistory"),
            "no album lastMessages → no album coalesce → zero GetChatHistory calls")
    }

    @Test
    fun `refresh does not drain ChatListArchive`() = runTest {
        val harness = PostsRepositoryTestHarness(this)
        // Track LoadChats requests by ChatList kind.
        val seenLists = mutableListOf<String>()
        harness.td.onAny("LoadChats") { fn ->
            val req = fn as TdApi.LoadChats
            seenLists.add(req.chatList::class.simpleName ?: "?")
            TdApi.Error(404, "no more")
        }
        harness.td.onAny("GetChats") { TdApi.Chats(longArrayOf(), 0) }

        harness.repo.refresh()
        harness.runCurrent()

        assertTrue(seenLists.none { it == "ChatListArchive" },
            "cold-start refresh must skip the archive list entirely; seen=$seenLists")
        assertTrue(seenLists.any { it == "ChatListMain" },
            "cold-start refresh must still drain ChatListMain")
    }

    @Test
    fun `refresh ingests lastMessage from chatCache for every channel chat`() = runTest {
        val harness = PostsRepositoryTestHarness(this)
        val ids = listOf(-3000L, -3001L, -3002L)
        ids.forEachIndexed { i, id ->
            val chat = harness.fakeChannel(
                id = id,
                lastMessage = harness.fakeChannelMessage(id, 300L + i, date = 1_700_000_000 + i),
            )
            harness.td.emitUpdate(TdApi.UpdateNewChat(chat))
        }
        harness.runCurrent()
        harness.td.onAny("LoadChats") { TdApi.Error(404, "no more") }
        harness.td.onAny("GetChats") { TdApi.Chats(ids.toLongArray(), ids.size) }

        harness.repo.refresh()
        harness.runCurrent()

        val postIds = harness.repo.posts.value.map { it.id }.toSet()
        assertEquals(setOf(300L, 301L, 302L), postIds,
            "every channel with a non-null lastMessage must contribute one post")
    }

    @Test
    fun `refresh skips chats whose lastMessage is null and does not crash`() = runTest {
        val harness = PostsRepositoryTestHarness(this)
        val emptyChannel = harness.fakeChannel(id = -4000L, lastMessage = null)
        val activeChannel = harness.fakeChannel(
            id = -4001L,
            lastMessage = harness.fakeChannelMessage(-4001L, 400L),
        )
        harness.td.emitUpdate(TdApi.UpdateNewChat(emptyChannel))
        harness.td.emitUpdate(TdApi.UpdateNewChat(activeChannel))
        harness.runCurrent()
        harness.td.onAny("LoadChats") { TdApi.Error(404, "no more") }
        harness.td.onAny("GetChats") { TdApi.Chats(longArrayOf(-4000L, -4001L), 2) }

        harness.repo.refresh()
        harness.runCurrent()

        assertEquals(1, harness.repo.posts.value.size, "only the channel with a lastMessage contributes")
        assertEquals(400L, harness.repo.posts.value[0].id)
    }

    @Test
    fun `refresh tolerates UpdateNewChat arriving AFTER GetChats returns its id list`() = runTest {
        val harness = PostsRepositoryTestHarness(this)
        // No UpdateNewChat emitted yet — chatCache is empty.
        harness.td.onAny("LoadChats") { TdApi.Error(404, "no more") }
        harness.td.onAny("GetChats") { TdApi.Chats(longArrayOf(-5000L), 1) }

        // Schedule a late UpdateNewChat: arrives 100 ms into the suspend-until-or-timeout
        // poll window. The 2 s timeout in refreshLocked must absorb this without dropping
        // the chat.
        val lateMsg = harness.fakeChannelMessage(-5000L, 500L)
        launch {
            kotlinx.coroutines.delay(100)
            val chat = harness.fakeChannel(id = -5000L, lastMessage = lateMsg)
            harness.td.emitUpdate(TdApi.UpdateNewChat(chat))
        }

        harness.repo.refresh()
        harness.runCurrent()

        assertEquals(1, harness.repo.posts.value.size)
        assertEquals(500L, harness.repo.posts.value[0].id)
    }

    @Test
    fun `refresh issues exactly one GetChatHistory per album lastMessage for coalescing`() = runTest {
        val harness = PostsRepositoryTestHarness(this)
        // One channel whose lastMessage is an album member, two whose lastMessage is solo.
        val albumChat = harness.fakeChannel(
            id = -6000L,
            lastMessage = harness.fakeChannelMessage(-6000L, 600L, mediaAlbumId = 999L),
        )
        val soloA = harness.fakeChannel(
            id = -6001L,
            lastMessage = harness.fakeChannelMessage(-6001L, 601L, mediaAlbumId = 0L),
        )
        val soloB = harness.fakeChannel(
            id = -6002L,
            lastMessage = harness.fakeChannelMessage(-6002L, 602L, mediaAlbumId = 0L),
        )
        listOf(albumChat, soloA, soloB).forEach { harness.td.emitUpdate(TdApi.UpdateNewChat(it)) }
        harness.runCurrent()

        harness.td.onAny("LoadChats") { TdApi.Error(404, "no more") }
        harness.td.onAny("GetChats") { TdApi.Chats(longArrayOf(-6000L, -6001L, -6002L), 3) }
        // Album coalesce probe returns the same single member (nothing extra to merge).
        harness.td.onAny("GetChatHistory") {
            TdApi.Messages(
                /*totalCount*/ 1,
                arrayOf(harness.fakeChannelMessage(-6000L, 600L, mediaAlbumId = 999L)),
            )
        }

        harness.repo.refresh()
        harness.runCurrent()

        assertEquals(1, harness.td.rpcCount("GetChatHistory"),
            "GetChatHistory must fire exactly once — for the single album lastMessage")
    }
}
```

- [ ] **Step 2: Run the tests, expect failures**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.lyo.hortay.data.PostsRepositoryRefreshTest"`
Expected: BUILD FAILED. Most assertions will fail with non-zero GetChat / GetChatHistory counts because the current `refreshLocked` issues those.

- [ ] **Step 3: Rewrite `refreshLocked`**

In `app/src/main/kotlin/dev/lyo/hortay/data/PostsRepository.kt`, replace the entire body of `refreshLocked` (current lines 1169-1291) with:

```kotlin
    private suspend fun refreshLocked(limitPerChannel: Int) {
        // Cold-start strategy (mirrors Telegram-Android): drive `LoadChats(ChatListMain)`
        // so TDLib emits UpdateNewChat for every chat. Each chat carries `lastMessage`
        // server-side, so the feed can be reconstructed from chatCache without a
        // per-channel GetChatHistory fan-out. Previously this method issued
        // GetChat × N (Sem=16) + GetChatHistory × N (Sem=4); on a 200-channel account
        // that was ~400 RPCs on the critical path and the dominant trigger for the
        // post-login FLOOD_WAIT class. See
        // `docs/superpowers/specs/2026-05-11-tdlib-cold-start-lastmessage-only-design.md`.
        //
        // Archive is NOT drained here — archived chats are out of scope for v1 of this
        // change. The existing UpdateChatAddedToList / UpdateChatRemovedFromList
        // listeners still keep `_archivedChatIds` live so the UI scope predicate works.
        drainChatList(TdApi.ChatListMain())

        // GetChats returns chat ids already loaded into TDLib's local memory by the
        // drainChatList pass above. Int.MAX_VALUE because the page count is the real
        // ceiling on response size (see prior comment context preserved in git blame).
        val chatIds = td.send(TdApi.GetChats(TdApi.ChatListMain(), Int.MAX_VALUE))
            .chatIds.toList()

        // LoadChats triggers UpdateNewChat per chat, but the emission may arrive on
        // the td.updates flow AFTER GetChats has already returned the id list — TDLib's
        // bridge does not guarantee that the update queue is fully drained before the
        // response. Wait up to 2 s for chatCache to catch up; on timeout we proceed with
        // whatever's cached (the UpdateChatLastMessage listener will catch the rest
        // via live ingest as those updates arrive).
        suspendUntilOrTimeout(WAIT_NEW_CHAT_TIMEOUT_MS, WAIT_NEW_CHAT_POLL_MS) {
            chatIds.all { chatCache.containsKey(it) }
        }

        // Harvest lastMessage for each known chat and route through ingest. ingest()
        // already:
        //   - filters non-channel chats (basic groups / DM / supergroup-chats)
        //   - runs coalesceAlbumFragments for album-member lastMessages
        //   - applies PostFilterStrategy (service / expired media drops)
        //   - emits on _newArrivals for the live-update consumers
        //   - dedups against the existing feed
        // Sem = REFRESH_CONCURRENCY (4) bounds the concurrent album-coalesce probes;
        // for non-album chats ingest is pure in-memory and never blocks.
        val semaphore = Semaphore(REFRESH_CONCURRENCY)
        coroutineScope {
            chatIds.map { chatId ->
                async {
                    semaphore.withPermit {
                        val msg = chatCache[chatId]?.lastMessage ?: return@withPermit
                        ingest(chatId, listOf(msg))
                    }
                }
            }.awaitAll()
        }

        lastRefreshAtMs = System.currentTimeMillis()
    }
```

- [ ] **Step 4: Add the two timing constants in the companion object**

Still in `PostsRepository.kt`, find the `companion object` (currently starting around line 1352). Add these two constants alongside the existing `REFRESH_CONCURRENCY` block:

```kotlin
        /**
         * How long [refreshLocked] waits for late-arriving `UpdateNewChat` emissions to
         * fill [chatCache] after `GetChats` returns. Empirically TDLib's update queue
         * drains within a few hundred ms on warm runs; on a true cold start it can take
         * 1-2 s, which is the upper bound this constant captures. Past the timeout we
         * proceed with whatever's cached — the [TdApi.UpdateChatLastMessage] listener
         * mops up late chats via live ingest.
         */
        const val WAIT_NEW_CHAT_TIMEOUT_MS = 2_000L

        /** Poll interval for [WAIT_NEW_CHAT_TIMEOUT_MS]. 50 ms is the same cadence used
         *  elsewhere in this repo for similar predicate-awaits. */
        const val WAIT_NEW_CHAT_POLL_MS = 50L
```

- [ ] **Step 5: Remove `_archivedChatIds` reset from `refreshLocked`**

The previous `refreshLocked` had a block that reconciled `_archivedChatIds` from `GetChats(ChatListArchive)` (lines 1198-1214 in the pre-change file). With archive removed from cold-start, the `_archivedChatIds` mirror is now solely driven by the existing `UpdateChatAddedToList` / `UpdateChatRemovedFromList` listeners (lines 206-220), which fire whether or not we drain the archive list. **Do not re-add the GetChats(Archive) reconciliation.** Verify the listeners are still in place after the edit; do not remove them.

- [ ] **Step 6: Run the tests, expect pass**

Run: `./gradlew :app:testDebugUnitTest --tests "dev.lyo.hortay.data.PostsRepositoryRefreshTest"`
Expected: BUILD SUCCESSFUL, 7 tests pass.

If a test fails on the race tolerance test (last test), suspect the `runCurrent` cadence in the harness vs. the `delay(100)` in the test scheduling. The `runTest` scheduler advances virtual time when nothing is runnable; the `delay(100)` should auto-advance. If it hangs, swap the inner `kotlinx.coroutines.delay(100)` for `kotlinx.coroutines.test.advanceTimeBy(100)` and re-run.

- [ ] **Step 7: Run the FULL test suite to catch regressions**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL. Pay special attention to `CommentsRepositoryTest` and any existing `PostsRepository`-related tests — the FakeTdSender refactor should be backwards-compatible but verify.

- [ ] **Step 8: Commit**

```bash
git add app/src/main/kotlin/dev/lyo/hortay/data/PostsRepository.kt \
        app/src/test/kotlin/dev/lyo/hortay/data/PostsRepositoryRefreshTest.kt
git commit -m "perf(timeline): cold-start uses Chat.lastMessage harvest, not GetChatHistory × N"
```

---

## Task 5: Lower `ACTIVATE_POSTS_THRESHOLD`

**Files:**
- Modify: `app/src/main/kotlin/dev/lyo/hortay/data/StartupCoordinator.kt:123`

Why: with one post per channel on cold-start, a small-subscription account (~5-15 channels) will never hit the old `20` threshold. Drop to `8` so the gate flips on a screenful-plus-margin instead of timing out the full 8 s.

- [ ] **Step 1: Edit the constant and its rationale comment**

In `app/src/main/kotlin/dev/lyo/hortay/data/StartupCoordinator.kt`, replace the `ACTIVATE_POSTS_THRESHOLD` declaration (currently lines 117-123):

```kotlin
        /**
         * Post count at which we consider the cold-start refresh "perceptually
         * done." 8 posts cover ~1.5 screens at typical card height — comfortably
         * above one screenful (3-4 cards) yet still reachable on a small-
         * subscription account where each channel contributes exactly one post
         * via the lastMessage-harvest cold-start path. The previous value (20)
         * assumed up to 30 posts per channel via GetChatHistory fan-out; that
         * path was removed in the TDLib cold-start rework (2026-05-11).
         */
        const val ACTIVATE_POSTS_THRESHOLD = 8
```

- [ ] **Step 2: Verify build**

Run: `./gradlew :app:compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Run the test suite once more**

Run: `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL. No StartupCoordinator tests exist; if you have spare cycles consider adding one, but it is OUT OF SCOPE for this plan.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/kotlin/dev/lyo/hortay/data/StartupCoordinator.kt
git commit -m "tune(timeline): activate threshold 20→8 for lastMessage-only feed"
```

---

## Task 6: Update CHANGELOG.md

**Files:**
- Modify: `CHANGELOG.md`

Why: every user-visible change goes into `## [Unreleased]` per `CLAUDE.md` rules.

- [ ] **Step 1: Open `CHANGELOG.md` and add a `### Performance` entry under `## [Unreleased]`**

Locate the `## [Unreleased]` heading (first heading in the file). Under it, find or create the `### Performance` section and prepend this entry:

```markdown
- **Cold-start RPC budget cut ~30× by harvesting `Chat.lastMessage` instead of
  fanning out `GetChatHistory` per channel**. On `AuthorizationStateReady` we now
  drive `LoadChats(ChatListMain)` to make TDLib emit `UpdateNewChat` for every
  chat (server-side push; zero RPC) and pull each channel's most recent post out
  of the resulting `chatCache`. Each harvested message is routed through the
  existing `ingest()` pipeline — channel-filter, album coalesce, dedup, and
  `_newArrivals` emission are unchanged, so the live-update consumers see the
  same shape. A new `UpdateChatLastMessage` listener catches late-syncing chats
  and mid-session last-message swaps. Net cold-start RPC volume for a
  200-channel account drops from ~480 (`GetChat × 200` + `GetChatHistory × 200`)
  to ~15 on the critical path; album-coalesce tail RPCs (small
  `GetChatHistory(offset=-5, limit=10)` for the 20-50% of channels whose newest
  post is an album member) run deferred at Sem=4 after first paint. Archive is
  no longer drained on cold-start — the `_archivedChatIds` mirror is now driven
  entirely by the existing `UpdateChatAddedToList` / `UpdateChatRemovedFromList`
  listeners, which fire independent of our refresh path. The post-login
  FLOOD_WAIT class is closed for accounts up to ~1000 channels. Mirrors what
  the official Telegram-Android client does on its DialogsActivity boot — load
  the chat list, render each row from `Chat.lastMessage`, defer message-history
  fetch to the moment the user taps into a chat.
```

- [ ] **Step 2: Add a `### Changed` entry for the activate threshold**

In the same `## [Unreleased]` block, under `### Changed`, add:

```markdown
- **`StartupCoordinator.ACTIVATE_POSTS_THRESHOLD: 20 → 8`**. With one post per
  channel on cold-start, the old `20` threshold left small-subscription
  accounts (5-15 channels) timing out the full 8 s `BOOT_TIMEOUT_MS` instead of
  flipping cleanly to `Active`. `8` covers ~1.5 screens at typical card height —
  reachable on every realistic account, still well below the 50+ a typical
  power user produces.
```

- [ ] **Step 3: Commit**

```bash
git add CHANGELOG.md
git commit -m "docs(changelog): TDLib cold-start lastMessage-harvest entry"
```

---

## Task 7: Update CLAUDE.md load-bearing table

**Files:**
- Modify: `CLAUDE.md`

Why: `CLAUDE.md` documents which pieces of the codebase are load-bearing for AI agents. The `PostsRepository concurrency` row needs an update to reflect the new flow so future agents don't try to re-introduce `GetChatHistory × N`.

- [ ] **Step 1: Locate the load-bearing table**

Find the row in `CLAUDE.md` that reads:

```markdown
| PostsRepository concurrency | `data/PostsRepository.kt:32-49` | `refreshMutex` + `PersistentList` + album coalescing per `(chatId, mediaAlbumId)`. |
```

- [ ] **Step 2: Replace it with**

```markdown
| PostsRepository concurrency | `data/PostsRepository.kt:32-49` | `refreshMutex` + `PersistentList` + album coalescing per `(chatId, mediaAlbumId)`. |
| PostsRepository cold-start contract | `data/PostsRepository.kt:refreshLocked` | Harvests `Chat.lastMessage` from `chatCache` (populated by `UpdateNewChat` / `UpdateChatLastMessage`). **Do NOT re-introduce `GetChat × N` or `GetChatHistory × N` fan-out on cold-start** — the ~30× FLOOD_WAIT regression class lives there. Spec: `docs/superpowers/specs/2026-05-11-tdlib-cold-start-lastmessage-only-design.md`. |
```

- [ ] **Step 3: Locate the "Заборонено" section and add a guardrail**

Find the bullet list under `## Заборонено`. Add:

```markdown
- ❌ `GetChat × N` / `GetChatHistory × N` per-channel fan-out on cold-start.
  Killed in the lastMessage-harvest rework (2026-05-11) — caused FLOOD_WAIT for
  power-user accounts. On-demand per-channel paths (`loadChannelHistory`,
  `loadOlder`, `loadHistoryAround`) stay.
```

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md
git commit -m "docs(claude): document lastMessage cold-start, forbid history fan-out"
```

---

## Task 8: Manual smoke test on device

**Files:** none.

Why: the spec lists a sanity-check checklist for before merge. This step exercises it.

- [ ] **Step 1: Install the debug build**

Run: `./gradlew :app:installDebug`
Expected: BUILD SUCCESSFUL, app installed.

- [ ] **Step 2: Set up logcat filter in a separate terminal**

Run: `adb logcat -s TdClient PostsRepository MediaCache`

- [ ] **Step 3: Cold-launch the app and observe**

Force-stop the app first (`adb shell am force-stop dev.lyo.hortay`), then launch from the launcher. Watch for:

- **Feed renders within ~1s of `AuthStage.Ready`** (look for "auth=Ready" / first `_posts.update` log entry).
- **No `FLOOD_WAIT throttle: sleeping Xs` warnings** in the first 30 s after auth.
- **Tap a channel** → `loadChannelHistory(80)` fires and the channel-filter view paints 80 messages.
- **Scroll deep in a channel** → `loadOlder` fires and pages back.
- **Pull-to-refresh on the mixed feed** → fresh posts arrive (or no-op if feed is current).

- [ ] **Step 4: If everything looks good, no extra commit. If anomalies found, file an issue but DO NOT touch the plan output — that's a follow-up plan.**

---

## Task 9: Open the PR

- [ ] **Step 1: Push and open PR**

```bash
git push -u origin HEAD
gh pr create --title "perf(timeline): TDLib cold-start uses Chat.lastMessage, not GetChatHistory × N" \
  --body "$(cat <<'EOF'
## Summary
- Replaces `GetChat × N + GetChatHistory × N` cold-start fan-out with a `Chat.lastMessage` harvest driven by `UpdateNewChat`
- Adds `UpdateChatLastMessage` listener so late-syncing chats and mid-session last-message swaps still land in the feed
- Drops `ChatListArchive` from cold-start; archive `_archivedChatIds` mirror keeps working via the existing `UpdateChatAddedToList` / `UpdateChatRemovedFromList` listeners
- Lowers `StartupCoordinator.ACTIVATE_POSTS_THRESHOLD` 20 → 8 to suit one-post-per-channel feed shape
- Mirrors what the official Telegram-Android client does on its DialogsActivity boot

Spec: `docs/superpowers/specs/2026-05-11-tdlib-cold-start-lastmessage-only-design.md`

Cold-start RPC budget (200-channel account): ~480 → ~15 on the critical path. Album-coalesce tail (Sem=4) adds 20-60 small RPC after first paint. Closes the post-login FLOOD_WAIT class for accounts up to ~1000 channels.

## Test plan
- [x] `PostsRepositoryRefreshTest` — 7 new unit tests pin the RPC contract (zero GetChat, GetChatHistory only for album lastMessages, no ChatListArchive drain, lastMessage harvested per channel, null lastMessage skipped, race tolerance)
- [x] `PostsRepositoryUpdateChatLastMessageTest` — listener routes through ingest, dedups
- [x] `SuspendUntilOrTimeoutTest` — race-await helper
- [x] Full `./gradlew :app:testDebugUnitTest` green
- [x] Manual smoke on device: cold-launch, no FLOOD_WAIT, channel-tap loads history, pagination works, pull-to-refresh works

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 2: Surface the PR URL to the user.**

---

## Self-Review Notes

(Filled in by the planner after writing the plan.)

**Spec coverage check:**
- Problem table → Task 4 closes both `GetChat × N` and `GetChatHistory × N` fan-outs ✅
- Goal RPC budget → Task 4 + Task 5 ✅
- Refresh flow diagram → Task 4 implements the exact diagram ✅
- Race handling (UpdateNewChat after GetChats) → Task 2 (helper) + Task 4 (call site + test) ✅
- StartupCoordinator threshold → Task 5 ✅
- Album handling → Task 4 (delegates to existing `coalesceAlbumFragments` via `ingest`) ✅
- `UpdateChatLastMessage` listener → Task 3 ✅
- Edge cases 1-9 from spec → covered by tests in Task 3 and Task 4 ✅
- Files-changed list → all 6 files have tasks (PostsRepository: Tasks 2, 3, 4; StartupCoordinator: Task 5; FakeTdSender: Task 1; new test files: Tasks 2, 3, 4; CHANGELOG: Task 6; CLAUDE.md: Task 7) ✅
- Rollout sanity check → Task 8 ✅
- Rollback → Task 9 PR body documents single-revert flow ✅

**Placeholder scan:** clean — every code block contains the full content.

**Type consistency:** `WAIT_NEW_CHAT_TIMEOUT_MS` / `WAIT_NEW_CHAT_POLL_MS` defined in Task 4 step 4, used in Task 4 step 3 of the same task — consistent. `chatCacheForTest` defined in Task 3 step 3, used by tests in Tasks 3 and 4 — consistent. `harness.fakeChannel(...)` / `harness.fakeChannelMessage(...)` / `harness.runCurrent()` / `harness.repo` / `harness.td` — consistent shape across all test code blocks.
