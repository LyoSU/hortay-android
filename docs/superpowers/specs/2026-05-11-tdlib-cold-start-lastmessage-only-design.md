# TDLib cold-start: lastMessage-only, TG-style

Date: 2026-05-11
Status: Draft — awaiting user review
Scope: `:app` only. No TDLib version bump. No schema changes.

## Problem

On `AuthorizationStateReady` the app currently fires ~480 TDLib RPC for a
200-channel account inside the first 3 seconds:

| RPC | Count | File:line |
|---|---|---|
| `LoadChats` (Main + Archive) | up to 20 | `PostsRepository.kt:1340` |
| `GetChats` × 2 | 2 | `PostsRepository.kt:1197, 1199` |
| `GetChat` fan-out | 200 (Sem=16) | `PostsRepository.kt:1230` |
| `GetChatHistory` fan-out, limit=30 | 200 (Sem=4) | `PostsRepository.kt:1278` |
| `GetMessage` snapshot restore | up to 50 (Sem=8) | `PostsRepository.kt:282` |
| `goOnline` SetOption/SetNetworkType | ~5 | `TdLifecycleBridge.kt:126` |

All compete with TDLib's own internal cold-start sync (`UpdateNewChat` ×
all chats, `UpdateSupergroup` × all channels) on the same per-DC active-
slot pool ([tdlib/td#786](https://github.com/tdlib/td/issues/786)). For
power users with 500+ channels the queue saturates and `429
FLOOD_WAIT` follows within seconds of login. The user-visible symptom:
several seconds of frozen UI, missing media, and the FLOOD_WAIT banner.

## What official Telegram-Android does

A standard Telegram client renders a chat list, not a feed. Each row
shows one chat with its `Chat.lastMessage`. That field is populated
**server-side** as part of `LoadChats` / `UpdateNewChat`. Official TG
does **not** call `GetChatHistory` for every chat on login — history
loads only when the user opens an individual chat.

## Goal

Bring Hortay's cold-start RPC budget down to ~15 calls (200-channel
account), and ~25 calls (1000-channel account), while preserving the
existing on-demand pagination paths (`loadChannelHistory`,
`loadOlder`, `loadHistoryAround`).

Trade: cold-start feed shows ~1 post per active channel (= ~50-500
posts across the feed depending on subscription count) instead of up
to 1000. Deeper history per channel is opt-in (tap the channel).

## Non-goals

- Background "depth fill" RPC bursts after cold-start — explicitly out.
- Archive support on cold-start. Out of scope; archive becomes an
  opt-in filter handled in a follow-up.
- Mixed-feed "load older across all channels" pagination. Out — feed
  just ends; per-channel deep dives cover the use case.
- Any change to the live update pipeline (`UpdateNewMessage` ingest,
  reactions, comments). Unchanged.
- Web (guest) mode is unaffected. This change is TDLib-mode only.

## Design

### Data sources used on cold-start (new)

| Source | Provides | RPC cost |
|---|---|---|
| `restoreFromSnapshot` | Top-50 cached posts from previous session | up to 50 `GetMessage` (Sem=8), <100ms total |
| `LoadChats(ChatListMain, 200)` × N pages | Triggers TDLib to emit `UpdateNewChat` for every chat in the main list | up to 10 RPC |
| `GetChats(ChatListMain, Int.MAX_VALUE)` | Canonical ordered chat-id list | 1 RPC |
| `UpdateNewChat` stream (already listened to: `PostsRepository.kt:198`) | Populates `chatCache[chatId] = chat`; `chat.lastMessage` carries the most recent message | 0 RPC (server push) |
| `UpdateChatLastMessage` stream (new listener) | Updates `chatCache[chatId].lastMessage` when it changes (edit, delete, new post arriving) | 0 RPC (server push) |
| `coalesceAlbumFragments` (existing) | Pulls sibling album members for `lastMessage` whose `mediaAlbumId != 0`. Runs at the tail of `refreshLocked`, AFTER first paint. | Deferred fan-out — see budget table below |

### Total cold-start RPC budget

Split into two phases: **critical path** (what blocks first paint) and
**tail** (what runs after the user is already looking at the feed).

| Account size | Critical path BEFORE | Critical path AFTER | Tail (album coalesce) AFTER |
|---|---|---|---|
| 50 channels | ~130 | ~12 | ~5-15 |
| 200 channels | ~480 | ~15 | ~20-60 |
| 1000 channels | ~2400 | ~25 | ~100-300 |

(Plus up to 50 `GetMessage` snapshot restore in both cases; that path
is unchanged.)

Album-coalesce tail is bounded by Sem=4 and runs after the first
`_posts.update` lands, so the user sees content immediately even on
the 1000-channel end. The tail RPCs are also cheap small reads
(`offset=-5, limit=10`) that TDLib often serves from local DB —
they're not the same load as 200 separate `GetChatHistory(limit=30)`.

### Refresh flow (changed)

```
refreshLocked(limit):  // limit parameter retained for API compat, but now informs album-coalesce only
  ├─ drainChatList(ChatListMain)            // unchanged: LoadChats × N until 404
  │   (does NOT drain ChatListArchive any more)
  │
  ├─ chatIds = GetChats(ChatListMain, MAX_VALUE).chatIds
  │
  ├─ harvestLastMessages(chatIds):          // NEW
  │     for each id in chatIds:
  │       chat = chatCache[id]              // populated by UpdateNewChat listener
  │       msg  = chat?.lastMessage          // may be null for empty channels
  │       if msg != null && passesPreFilter(chat, msg):
  │         emit msg into the same `mapped` list refreshLocked already builds
  │     // Settles synchronously: chatCache is in-memory, no I/O.
  │     // PostFilterStrategy + foldRawIntoCurrent + coalesceAlbumFragments
  │     // run as today on the smaller input.
  │
  ├─ _posts.update { foldRawIntoCurrent(current, mapped, MAX_FEED_SIZE) }
  └─ lastRefreshAtMs = now
```

**What's removed:**
- `GetChat` parallel fan-out (`PostsRepository.kt:1226-1235`). `chatCache`
  is already populated by the `UpdateNewChat` listener at line 198.
- `GetChatHistory` per-channel fan-out (`PostsRepository.kt:1272-1290`).
- `drainChatList(ChatListArchive)` (`PostsRepository.kt:1189`).
- `GetChats(ChatListArchive, …)` (`PostsRepository.kt:1199`).

**What's added:**
- A `harvestLastMessages(chatIds: List<Long>): List<TdApi.Message>`
  pure function on the repository (no suspending RPC inside).
- A new `UpdateChatLastMessage` listener that updates
  `chatCache[id].lastMessage` and routes the message through the same
  `ingest()` pipeline used for `UpdateNewMessage`. This handles the
  case where TDLib later updates a chat's last message after the
  initial `UpdateNewChat` already fired.

### A race we have to handle: `UpdateNewChat` arriving after `GetChats`

`LoadChats` triggers `UpdateNewChat` for each chat; the updates may
arrive on the `td.updates` flow *after* `GetChats` returns. So at the
moment we read `chatCache`, some chats may not yet be cached. Mitigation:

- After `GetChats`, await
  `chatIds.all { id -> chatCache.containsKey(id) }` with a 2s
  timeout. Implementation: a small `suspendUntilOrTimeout` helper that
  re-evaluates the predicate on a 50ms tick (cheap ConcurrentHashMap
  containsKey on each id). If the timeout fires we proceed with
  whatever's cached — late `UpdateNewChat` arrivals are then handled
  by the new `UpdateChatLastMessage` listener that routes them through
  `ingest()` live, so no chat is permanently missed.

### `StartupCoordinator` threshold

Currently `ACTIVATE_POSTS_THRESHOLD = 20`. With a small subscription
set (~5-15 channels) the feed will never reach 20 on cold-start; the
gate waits the full 8s timeout for nothing.

Change: `ACTIVATE_POSTS_THRESHOLD = 8`. Reasoning: smallest realistic
useful feed is ~one screenful, which on a 4-inch reading view is ~3-4
cards. 8 is a comfortable margin above that and still well below the
typical 50+ that an active 50-channel account will produce.

Settle buffer `SETTLE_MS = 1500` unchanged — it's there to absorb the
tail of TDLib's internal `UpdateNewChat` / `UpdateSupergroup` burst,
which is independent of our refresh path.

### Album handling

When `chat.lastMessage.mediaAlbumId != 0`, the harvested message is a
single album member. The existing `coalesceAlbumFragments` runs at
the tail of `refreshLocked` and issues a small
`GetChatHistory(chatId, fromMessageId=anchor, offset=-5, limit=10)`
for each album whose fragments are under-counted. On the new flow
this fires only for channels whose newest post is an album — typically
20-50% of channels for a typical account, so 40-100 small RPCs deferred
to the tail of cold-start.

Trade-off accepted: this is the only RPC fan-out remaining on the
critical path. Without it, album posts would render as a single
photo in the feed until the user mounted them. Closing the album
fan-out is a smaller follow-up (lazy album resolve on viewport mount)
deliberately out of scope.

### What stays the same

- `restoreFromSnapshot` — fast-paint top-50 from disk; unchanged.
- `loadChannelHistory(chatId, 80)` — on channel-filter open; unchanged.
- `loadOlder(chatId, 30)` — on per-channel scroll-to-bottom; unchanged.
- `loadHistoryAround(chatId, anchor, 30)` — on deep link; unchanged.
- `ingest()` for `UpdateNewMessage` — unchanged, live posts merge in
  natively.
- `PostFilterStrategy` (album merge, service/expired filters,
  `mediaAlbumId` sort tie-break) — unchanged.
- `MediaAutoDownloader` gating on `StartupCoordinator.phase` —
  unchanged.
- `goOnline` / `OptimizeStorage` / `applyReadOnlyClientOptions` —
  unchanged.
- Snapshot save on `foreground=false` — unchanged.
- `_archivedChatIds` mirror via `UpdateChatAddedToList /
  UpdateChatRemovedFromList` (`PostsRepository.kt:206-220`) —
  unchanged; the scope-predicate filter still excludes archived chats
  from the "all" feed correctly.

## Edge cases

1. **`chat.lastMessage == null`** (empty channel, just joined, no
   posts ever). Result: that channel contributes zero posts to the
   cold-start feed. Correct — TG does the same.
2. **Service message as `lastMessage`** (e.g., "User joined the
   channel"). Result: `PostFilterStrategy.apply` drops it via the
   existing `Service` filter. No regression.
3. **`lastMessage` is an album member.** Resolved by
   `coalesceAlbumFragments` as described above.
4. **`lastMessage.date` is older than 30 days.** Still shown. The feed
   is sorted by `date` so it sinks naturally. No filter.
5. **Discussion-group lastMessage** (the user is in a comments group
   but it's not a channel). Result: existing scope-predicate filter
   excludes basic groups / supergroup-chats. No regression.
6. **Channel migrated to a new supergroup** (TDLib emits
   `UpdateNewChat` for the new chat, old chat's `lastMessage` becomes
   the migration marker). Result: filtered out as
   service. `UpdateChatLastMessage` on the new chat catches the
   continuation.
7. **Account switch (logout → login).** `chatCache` is wiped via
   `runLogoutCleanup` (`AppGraph.kt:412`). New account's
   `UpdateNewChat` stream populates cleanly.
8. **No subscriptions at all.** Feed empty; `StartupCoordinator`
   times out at 8s and flips to `Active`. UI shows the existing empty
   state. No regression.
9. **`UpdateChatLastMessage` arrives for a chat we've already
   ingested.** The new listener calls `ingest(newLastMessage)` which
   already de-dupes by `(chatId, messageId)`. New message → added;
   same message id → updated (e.g., edit). Safe.

## Open question (resolved)

**Should we keep album coalescing on cold-start, or defer it to
viewport mount?** Resolved: keep on cold-start. Cost is small (~40-100
deferred RPC at Sem=4 in the tail) and the alternative (single-member
albums in the feed) is a visible regression. Lazy mount-time album
resolve is a separate follow-up.

## Files changed

| File | Change |
|---|---|
| `app/src/main/kotlin/dev/lyo/hortay/data/PostsRepository.kt` | `refreshLocked` rewrite: drop GetChat/GetChatHistory fan-out and ChatListArchive drain; add `harvestLastMessages`. Add `UpdateChatLastMessage` listener. |
| `app/src/main/kotlin/dev/lyo/hortay/data/StartupCoordinator.kt` | `ACTIVATE_POSTS_THRESHOLD: 20 → 8`. |
| `app/src/test/kotlin/dev/lyo/hortay/data/PostsRepositoryRefreshTest.kt` | New: see test plan below. |
| `app/src/test/kotlin/dev/lyo/hortay/data/FakeTdSender.kt` (existing) | Add RPC-counter assertions if not already supported. |
| `CHANGELOG.md` | `## [Unreleased]` entry under Performance + Changed. |
| `CLAUDE.md` | Update the load-bearing table row for `PostsRepository concurrency` to reflect the new flow. |

Net: ~150-300 LOC change in `PostsRepository.kt`, ~7 new tests, ~10
lines of doc/changelog.

## Test plan (TDD)

Lock the contract before changing the implementation. All tests live
in a new `PostsRepositoryRefreshTest.kt` and run against
`FakeTdSender`.

1. `refreshLocked emits zero GetChat calls` — counter assertion on
   `FakeTdSender`.
2. `refreshLocked emits zero GetChatHistory calls when no album
   lastMessages are present` — counter assertion.
3. `refreshLocked emits GetChatHistory only for chats whose
   lastMessage has non-zero mediaAlbumId` — verify the call sites
   are exactly the album coalescing path.
4. `refreshLocked does not call LoadChats(ChatListArchive)` — counter
   assertion.
5. `harvestLastMessages ingests Chat.lastMessage for channels with a
   non-null lastMessage` — feed contains the expected post ids.
6. `harvestLastMessages skips chats whose lastMessage is null` — no
   crash, no spurious post.
7. `UpdateChatLastMessage routed through ingest updates the feed
   without duplicating an existing post` — id-dedup test.
8. `refreshLocked tolerates UpdateNewChat arriving after GetChats
   (race)` — feed eventually contains every chat's lastMessage within
   the 2s wait window.
9. `loadOlder still works on demand (unchanged contract)` — regression
   pin.

## Rollout

Single feature branch off `main`. No flag. The behavior change is
internal — UI surfaces and persisted data stay byte-compatible.

Sanity check before merge: install the debug build, log in to a
real account (200+ channels), confirm:
- No FLOOD_WAIT banner in the first 30s post-login.
- Feed renders within ~1s of `AuthStage.Ready`.
- Tapping into a channel still loads its 80-message head.
- Scrolling deep in a channel still paginates via `loadOlder`.
- Pull-to-refresh on the mixed feed still produces fresh posts.

## Rollback

`git revert` of the merge commit reverses the change cleanly — no
migrations, no persisted state diverges.

## Open follow-ups (deliberately deferred)

- Lazy album resolve on viewport mount (closes the residual
  `coalesceAlbumFragments` fan-out).
- Archive opt-in: a UI toggle that, when enabled, runs the same
  lastMessage harvest against `ChatListArchive`.
- Mixed-feed "load older across all channels" pagination — only if
  user feedback requests it.
- Smart per-channel depth-fill (background `loadOlder(5)` for top-N
  channels) — only if feed feels too thin in practice.
