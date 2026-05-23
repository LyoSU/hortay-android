# Post archive: edit history and deleted posts

**Status:** approved (brainstorm 2026-05-23)
**Scope:** TDLib mode + guest mode
**Default:** disabled (opt-in)

## Motivation

Telegram channels routinely edit posts (price changes, typo fixes, news updates) and occasionally delete them. The platform exposes neither edit history nor a delete log to readers. For a feed-style reader like Hortay this is a real gap — a user sees the new number but not what was there before, or a post they read in the morning is silently gone by evening.

The feature captures these events locally on the device, with no upload anywhere, and surfaces them through a visual diff and a dedicated archive screen.

## Constraints

- **No new RPCs.** Capture from updates already arriving for ingest (`UpdateMessageContent`, `UpdateDeleteMessages`). Hortay's FLOOD_WAIT discipline is non-negotiable.
- **No full media.** Snapshots store text, entities, media metadata, and `Minithumbnail` blobs only. No photo/video files.
- **Local only.** Archive lives in a new SQLDelight DB, never uploaded.
- **Cleared on logout.** Tied to `TdClient.loggedOut` like every other session-scoped state holder.
- **Off by default.** Onboarding sheet explains what is captured and where it lives before first enable.

## Architecture

### New module surface (still in `:app`)

```
data/archive/
├── ArchiveRepository.kt          -- single writer, capture API
├── ArchiveSettingsStore.kt       -- DataStore-backed config
├── ArchiveSweep.kt               -- TTL + cap enforcement
├── PostSnapshot.kt               -- @Immutable model
└── diff/
    ├── PostDiff.kt               -- line-level + media diff
    └── PostDiffRenderer.kt       -- Compose helpers

ui/archive/
├── ArchiveScreen.kt              -- list with filters
├── ArchiveSettingsScreen.kt
├── PostRevisionSheet.kt          -- bottom sheet, also used from feed chip
└── components/
    ├── EditedChip.kt
    ├── DeletedBadge.kt
    └── RevisionTimeline.kt

app/src/main/sqldelight/dev/lyo/hortay/data/archive/db/
├── PostSnapshot.sq
├── ArchivedChannel.sq
└── 1.sqm                          -- initial schema
```

### Database (`archive.db`, SQLDelight 2.3)

A separate DB from `web.db`. Different retention semantics, different lifecycle. Reuses the same SQLDelight + portability rules (Android 8/9 SQLite < 3.24 → `INSERT OR IGNORE` + `UPDATE`, no `ON CONFLICT DO UPDATE`, no FTS5).

```sql
CREATE TABLE PostSnapshot (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    chat_id       INTEGER NOT NULL,                -- TDLib chatId, or hash(username) for guest
    message_id    INTEGER NOT NULL,                -- TDLib msgId, or t.me/s/ message no.
    album_id      INTEGER,                         -- nullable
    kind          TEXT NOT NULL,                   -- EDIT | DELETE | EDIT_BASELINE
    seen_at_ms    INTEGER NOT NULL,
    edited_at_ms  INTEGER,                         -- TDLib message.editDate, nullable
    text          TEXT NOT NULL DEFAULT '',
    entities_json TEXT,                            -- FormattedText entities serialised
    media_summary_json TEXT,                       -- type, count, w, h, durationMs
    media_minithumb BLOB,                          -- composite for albums: first member only
    poll_json     TEXT,
    forward_json  TEXT,
    reply_json    TEXT,
    is_comment    INTEGER NOT NULL DEFAULT 0,
    text_hash     TEXT NOT NULL                    -- for dedup
);

CREATE INDEX idx_PostSnapshot_chat_msg ON PostSnapshot(chat_id, message_id, seen_at_ms DESC);
CREATE INDEX idx_PostSnapshot_seen ON PostSnapshot(seen_at_ms DESC);

CREATE TABLE ArchivedChannel (
    chat_id            INTEGER PRIMARY KEY,
    title              TEXT NOT NULL,
    handle             TEXT,
    photo_minithumb    BLOB,
    is_verified        INTEGER NOT NULL DEFAULT 0,
    last_snapshot_at_ms INTEGER NOT NULL
);
```

### Settings (`ArchiveSettingsStore` over DataStore)

```kotlin
@Immutable data class ArchiveSettings(
    val enabled: Boolean = false,
    val retentionDays: Int = 30,
    val maxRecords: Int = 5000,
    val excludedChats: PersistentSet<Long> = persistentSetOf(),
    val captureEdits: Boolean = true,
    val captureDeletes: Boolean = true,
)
```

## Capture pipeline

### TDLib mode

Hook points (already isolated from UI per repository contract):

1. **`PostsRepository.handleContentChanged(UpdateMessageContent)`** at PostsRepository.kt:1882
   - **Before** `_posts.update {}` mutation: read existing `TimelinePost` from `_posts.value`, call `archiveRepository.captureEdit(existing, update.newContent)`.
   - Must execute **outside** the `update {}` lambda to keep the CAS-loop contract pure (ARCHITECTURE.md: "Lambdas to `_posts.update {}` MUST be pure functions of the snapshot").

2. **`PostsRepository.handleDeleted(UpdateDeleteMessages)`** at PostsRepository.kt:1837
   - Same pattern: capture from `_posts.value` snapshot, then mutate.
   - Album members arrive as separate `UpdateDeleteMessages` events — debounce 200 ms by `(chatId, albumId)` and write one composite `DELETE` snapshot with `messageIds` as JSON array. Same window the project already uses for `UpdateMessageInteractionInfo` coalescing.

3. **`CommentsRepository`** at CommentsRepository.kt:564 (`UpdateMessageContent`) and :572 (`UpdateDeleteMessages`)
   - Same contract. `is_comment=1` flag keeps comment snapshots out of the feed's deleted-post view but visible in the archive screen.

### Guest mode

`WebFeedSource` already refreshes `t.me/s/` HTML on a schedule. Diff-on-refresh:

1. Read previous snapshot of the channel from `web.Post.sq`.
2. For each `messageId` present in both old and new HTML: if `(text, entities, mediaIdsByOrder)` differs → write `EDIT` snapshot. `mediaIdsByOrder` uses CDN URL **filename** only, stripping query/token (URL rotation is not an edit).
3. For each `messageId` in old but not in new **AND** `messageDate > now − 30 min`: write `DELETE` snapshot. Older absences are age-out, not deletion.
4. New posts → write to `web.Post.sq` as before; archive untouched.

### Deduplication

Before write: `SELECT seen_at_ms, text_hash FROM PostSnapshot WHERE chat_id=? AND message_id=? ORDER BY seen_at_ms DESC LIMIT 1`. If `now − last_seen < 500 ms` **and** `text_hash == new_hash` → skip. Closes the back-to-back-update window described in ARCHITECTURE.md for interaction info.

### Cold-start race

`captureEdit` / `captureDelete` ignore events where `existing == null` in `_posts`. Otherwise the first-sign-in backfill (which legitimately replaces TDLib-cached lastMessage with fresh content) would write false `DELETE` snapshots.

## UI

### 1. Edited chip (feed + channel screen)

`SuggestionChip` placed under the timestamp on posts with `revisionCount > 0`:

- `revisionCount == 1` → label `ред.` / `edited`
- `revisionCount > 1` → label `ред. ×N` / `edited ×N`

Tap → `PostRevisionSheet`.

### 2. Deleted post in feed

When `isDeleted == true`:

- Card alpha 0.55, `MotionScheme.fastEffectsSpec()` crossfade on transition.
- Timestamp row replaced by `видалено HH:mm · було опубліковано {ago}` (errorContainer label).
- Reactions, view count, replies hidden. Author chip stays active (channel drill works).
- Inline `TextButton`: `Переглянути в архіві ›` → `ArchiveScreen` prefiltered to this chat+message.
- Tap on body → `PostRevisionSheet` in "final-only" mode.
- Long-press → `ContextMenu`: remove from archive / share snapshot.

### 3. `PostRevisionSheet` (`ModalBottomSheet`)

Layout:

```
─ Edit history ──────────────────── ✕
●━━━━━━━━━━━━━━●━━━━━━━━━━━━━━●         ← timeline (filled = selected)
13:42         13:51          14:07

← 13:42 → 13:51   ·   9 min later

  Розпродаж до 3000̶ 5000 ₴               ← line-level diff
                                          deletions: errorContainer + lineThrough
                                          insertions: tertiaryContainer

  📷 фото → 📷 фото                       ← media diff row
   [thumb]   [thumb]

  Reactions: 👍 12 → 14 (info)            ← live state, separated visually

[ Go to current ›  ]              [ Telegram ↗ ]
```

**Diff implementation:**

- Library: `io.github.java-diff-utils:java-diff-utils:4.12` (Apache 2.0, ~80 KB). Stable LCS; alternative would be a 100-line hand-rolled implementation — picking the library is the clean choice, not a crutch.
- Granularity: **line-level**. Word-level breaks formatting (`entities`) and adds zero value on canonical Telegram edits (price change, typo, line append). Each revision keeps full `(text, entities)` so spans render correctly inside the diff highlight.
- Entities preserved per revision side; diff styling applied as background + decoration on top.

**Media diff:** compare `mediaSummary` JSON. If type/count/dimensions differ → render `MediaRow(old) → MediaRow(new)` using `media_minithumb`.

**Polls:** show both option lists side-by-side, mark renamed/added/removed.

**Empty/missing baseline:** if first capture was an `EDIT_BASELINE` (post existed before feature enabled, this is the first version Hortay saw), the sheet shows that as the start of the timeline with a one-line caveat.

### 4. `ArchiveScreen` (push via `NavStack`)

- `LazyColumn` with `stickyHeader` date groups (`Сьогодні`, `Вчора`, `dd MMM`).
- Filter chips: channel (multi-select sheet with minithumb avatars), type (`Усі / Видалені / Редаговані`).
- Local LIKE-based search, debounce 250 ms. No FTS.
- Swipe-to-purge with undo snackbar (4 s) via `UserMessageBus`.
- Overflow `⋮`: export JSON (`Intent.ACTION_CREATE_DOCUMENT`), clear all (confirm dialog), info sheet.
- Channel filter integrates with `ChannelInfoSheet` → row "Архів цього каналу".

### 5. `ArchiveSettingsScreen`

Sections: master toggle, retention (`7 / 30 / 90 / ∞` dropdown), max records (`1000 / 5000 / 10000 / ∞`), excluded channels list, event-type toggles (edits / deletes), storage usage label, "Clear archive" destructive row.

**Master toggle off-flow:**
`AlertDialog` with three options: keep archive but stop writing, delete archive and disable, cancel. Default = first option.

**First enable:**
Onboarding sheet describes what is captured, where it lives, that it clears on logout.

### 6. Settings → Privacy

Shortcut row "Архів постів" → same screen, for users searching from the privacy angle.

## Data model additions

```kotlin
@Immutable data class TimelinePost(
    /* existing fields */,
    val isDeleted: Boolean = false,
    val revisionCount: Int = 0,
)

@Immutable data class PostSnapshot(
    val chatId: Long,
    val messageId: Long,
    val albumId: Long?,
    val kind: SnapshotKind,
    val seenAtMs: Long,
    val editedAtMs: Long?,
    val text: String,
    val entities: ImmutableList<TextEntity>,
    val mediaSummary: MediaSummary?,
    val mediaMinithumb: ByteArray?,
    val poll: PollSnapshot?,
    val forward: ForwardSnapshot?,
    val reply: ReplySnapshot?,
    val isComment: Boolean,
)

enum class SnapshotKind { EDIT, DELETE, EDIT_BASELINE }
```

`ArchiveRepository` API (single writer, IO scope):

```kotlin
suspend fun captureEdit(old: TimelinePost, newContent: TdApi.MessageContent)
suspend fun captureDelete(posts: List<TimelinePost>)
fun observe(filter: ArchiveFilter): Flow<ImmutableList<PostSnapshot>>
fun observeRevisions(chatId: Long, messageId: Long): Flow<ImmutableList<PostSnapshot>>
suspend fun purge(snapshotIds: List<Long>)
suspend fun clear()
suspend fun export(): ByteArray
```

## Lifecycle hooks

- **`AppGraph`** constructs `ArchiveRepository` once, injects via constructor into `PostsRepository`, `CommentsRepository`, `WebFeedSource`. Not a CompositionLocal — repository-tier, follows existing DI pattern.
- **`TdClient.loggedOut.collect { archiveRepository.clear() }`** — registered in `AppGraph.init`, same as other session-scoped clears.
- **`AppGraph.runLogoutCleanup`** — explicit `archiveDb.clearAll()` for the synchronous path.
- **Daily storage sweep** — adds `ArchiveSweep.run()` step (TTL + cap enforcement). On-write enforcement also runs each insert so cap is never exceeded by more than 1.
- **`ArchiveSettings.retentionDays` change** — triggers immediate `ArchiveSweep.run()` so new limits apply without waiting for the daily window.

## i18n

All new strings in `values/strings.xml` (en) + `values-uk/strings.xml` (uk), same commit. UK plurals (one/few/many/other). String identifiers prefixed `archive_*`, `revision_*`, `post_edited_*`, `post_deleted_*`. No hardcoded strings; UI tests added for plural forms.

## Edge cases (exhaustive)

| Case | Behaviour |
|---|---|
| Album partial delete (3 of 5) | One `DELETE` snapshot with those 3 messageIds; UI: "Deleted 2 photos from album". |
| Album caption edit | Main message_id update; snapshot whole album. |
| TDLib emits two back-to-back `UpdateMessageContent` | 500 ms dedup window; second skipped if text hash matches. |
| Guest CDN URL rotation | Not an edit — diff uses filename only, strips query token. |
| Guest post falls off (`messageDate > 30 min`) | Not a delete — age-out ignored. |
| Hidden channel | Archived by default; user can exclude. `HiddenChannelsScreen` shows info link to this. |
| Logout during pending capture | `archiveScope` cancelled, `clear()` issued. Inviolable. |
| Channel banned / kicked, snapshots remain | `ArchivedChannel` denormalised (title, handle, minithumb stored). Read-only, drill disabled. |
| First edit ever for a pre-feature post | `EDIT_BASELINE` snapshot, displayed with "original not captured" caveat. |
| Reactions, views | Never trigger a snapshot. Shown as info in revision sheet, not as a revision. |
| Poll edits | Captured (option text, correctness for quiz); user's vote is live state, not archived. |
| Forwarded posts | `forward` field captured. Source delete is independent message; that channel's archive captures it separately if subscribed. |
| Storage cap race | On every insert: `COUNT() > cap` → delete oldest. Daily sweep is belt-and-braces. |
| Cold-start backfill | `captureEdit`/`captureDelete` skip when `existing == null` in `_posts`. |
| TDLib `EDIT_BASELINE` for own message vs channel | Capture works the same; UI labels via standard `is_outgoing` check on `TimelinePost`. |

## Tests (JUnit 5)

- `ArchiveRepositoryTest`
  - `captureEdit_dedupesWithin500ms`
  - `captureDelete_groupsAlbumMembers`
  - `captureSkippedWhenExistingNull` (cold-start)
  - `respectsExcludedChats`
  - `respectsMasterToggle`
  - `loggedOut_clearsAllSnapshots`
- `WebFeedDiffTest`
  - `urlRotation_isNotTreatedAsEdit`
  - `ageOutOlderThan30min_doesNotMarkDeleted`
  - `textChange_isDetectedAsEdit`
- `PostDiffTest`
  - `lineLevelDiff_preservesEntities`
  - `mediaSwap_detected`
  - `pollOptionsRenamed_detected`
- `ArchiveSweepTest`
  - `sweep_purgesOlderThanRetention`
  - `sweep_keepsNewestUpToCap`
  - `onWriteEnforcement_neverExceedsCap`

## Dependencies added

- `io.github.java-diff-utils:java-diff-utils:4.12` (~80 KB, Apache 2.0).

No other new dependencies. SQLDelight, DataStore, kotlinx.collections.immutable, Coil — already in stack.

## Out of scope (explicitly not built)

- Snapshotting reactions or view counts (live state, not edits).
- Storing full photo/video bytes (use minithumb only).
- Cross-device archive sync (local only, by design).
- FTS-backed search (Android 8/9 SQLite constraint; LIKE is adequate at 5000-record cap).
- Per-channel exclude UI in `HiddenChannelsScreen` (separate concern; only an info link).
- Capturing pre-feature post originals (impossible — `EDIT_BASELINE` is the honest answer).

## Rollout

1. SQLDelight schema + `ArchiveRepository` + `ArchiveSettingsStore` (no UI yet, tests green).
2. Capture hooks in `PostsRepository`, `CommentsRepository`, `WebFeedSource`. Manual smoke via debug logging.
3. `PostRevisionSheet` + line-level diff.
4. Feed integration: edited chip, deleted card rendering.
5. `ArchiveScreen` + filters + search.
6. `ArchiveSettingsScreen` + onboarding sheet + export.
7. Daily sweep integration.
8. CHANGELOG bullet under `[Unreleased]`.

Each phase is a commit with conventional-commit scope (`feat(archive):`, `feat(timeline):` for the chip, etc.).

## Changelog entry

```
### Added
- Post archive: edited and deleted channel posts are saved locally, so you can see what changed (or what was removed) — disabled by default, configurable in Settings → Post archive.
```
