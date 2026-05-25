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

**Key design decisions:**

- **Unified `source_kind + source_key` instead of `Long chat_id`.** Guest mode keys are usernames (strings); TDLib keys are int64. Hashing usernames into a `Long` is a collision-prone crutch — separate columns keep both modes first-class.
- **One `content_blob` instead of per-field JSON.** TDLib already serialises `MessageContent` to TLO; reusing that bytestring means no second serialiser, no parser drift across schema changes, and one rendering path for live posts and revisions. Guest mode stores its existing `WebPostContent` JSON in the same column with `content_kind='web'`.
- **Two snapshot kinds only.** `VERSION` (a content snapshot at `seen_at_ms`) and `DELETED` (terminal marker). The "first version Hortay saw" is just `MIN(seen_at_ms)` per `(source_kind, source_key, message_key)` — not a distinct kind.

```sql
CREATE TABLE PostSnapshot (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    source_kind     TEXT NOT NULL,                  -- 'tdlib' | 'web'
    source_key      TEXT NOT NULL,                  -- chatId.toString() | username
    message_key     TEXT NOT NULL,                  -- messageId.toString() | t.me/s/ no.
    album_key       TEXT,                           -- nullable; groups album members
    kind            TEXT NOT NULL,                  -- VERSION | DELETED
    seen_at_ms      INTEGER NOT NULL,
    edited_at_ms    INTEGER,                        -- TDLib message.editDate, nullable
    content_kind    TEXT NOT NULL,                  -- 'tdlib' | 'web'
    content_blob    BLOB NOT NULL,                  -- TLO (tdlib) or JSON (web)
    content_hash    TEXT NOT NULL,                  -- SHA-256(content_blob) — dedup key
    text_preview    TEXT NOT NULL DEFAULT '',       -- denormalised first 200 chars, for LIKE search
    media_minithumb BLOB,                           -- album: composite of up to 3 first members
    deleted_msg_keys TEXT,                          -- JSON array for composite album DELETE
    is_comment      INTEGER NOT NULL DEFAULT 0
);

CREATE INDEX idx_PostSnapshot_msg ON PostSnapshot(source_kind, source_key, message_key, seen_at_ms DESC);
CREATE INDEX idx_PostSnapshot_seen ON PostSnapshot(seen_at_ms DESC);
CREATE INDEX idx_PostSnapshot_id ON PostSnapshot(id);  -- for `id < MAX(id) - cap` cap eviction

CREATE TABLE ArchivedChannel (
    source_kind         TEXT NOT NULL,
    source_key          TEXT NOT NULL,
    title               TEXT NOT NULL,
    handle              TEXT,
    photo_minithumb     BLOB,
    is_verified         INTEGER NOT NULL DEFAULT 0,
    last_snapshot_at_ms INTEGER NOT NULL,
    PRIMARY KEY (source_kind, source_key)
);
```

**`content_blob` format per `content_kind`:**

- `tdlib`: `TdApi.MessageContent.toByteArray()` (TLO), plus a small wrapper carrying `formattedTextEntities` and `forwardInfo`/`replyInfo` summaries that don't live on `MessageContent` itself. Wrapper struct serialised with `kotlinx.serialization.protobuf` — one extra binary format, but it's already a transitive dep of TDLib JNI build.
- `web`: existing `WebPostContent` (kotlinx.serialization JSON, byte-array form).

**`content_hash` = SHA-256(content_blob).** Any formatting change → different bytes → different hash → captured. No false dedup.

**`ChatRef`:**
```kotlin
@Immutable data class ChatRef(val kind: SourceKind, val key: String)
enum class SourceKind { TDLIB, WEB }
```
Used everywhere `chat_id` would have been: `ArchiveSettings.excludedChats: PersistentSet<ChatRef>`, filter chips, `observeRevisions(ref, messageKey)`.

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

Before write: `SELECT seen_at_ms, content_hash FROM PostSnapshot WHERE source_kind=? AND source_key=? AND message_key=? ORDER BY seen_at_ms DESC LIMIT 1`. If `content_hash == new_hash` → skip unconditionally (same content, no new revision). If hashes differ but `now − last_seen < 500 ms` → skip (back-to-back TDLib emit). Closes the same window ARCHITECTURE.md documents for `UpdateMessageInteractionInfo`.

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

- Library: `io.github.java-diff-utils:java-diff-utils:4.12` (Apache 2.0, ~80 KB). Stable LCS; alternative would be a hand-rolled implementation — picking the library is the clean choice, not a crutch.
- **Adaptive granularity** (real Telegram posts are often one paragraph — pure line-level would render as "everything deleted / everything inserted"):
  1. If `text.split("\n").size >= 3` → **line-level** diff.
  2. Else split on `[.!?…]\s+` regex. If `sentences.size >= 3` → **sentence-level** diff.
  3. Else → **word-level** diff (split on `\s+`).
- Entities preserved at the revision level (each version keeps its full `(text, entities)` blob). Diff styling is `background + lineThrough` on top of the regular Compose `AnnotatedString` render — the diff library operates on string slices, not on `entities`, so spans are never touched.

**Media diff:** compare `mediaSummary` JSON. If type/count/dimensions differ → render `MediaRow(old) → MediaRow(new)` using `media_minithumb`.

**Polls:** show both option lists side-by-side, mark renamed/added/removed.

**Empty/missing baseline:** if the earliest snapshot for a message was captured AT the moment of its first observed edit (post existed before the feature was enabled), the sheet labels the first revision as `"first version Hortay saw — earlier history not captured"`. This is just the row with `seen_at_ms = MIN(...)` for that message; no separate `kind` needed.

### 4. `ArchiveScreen` (push via `NavStack`)

- `LazyColumn` with `stickyHeader` date groups (`Сьогодні`, `Вчора`, `dd MMM`).
- Filter chips: **channel** (multi-select sheet with minithumb avatars), **type** (`Усі / Видалені / Редаговані`), **scope** (`Пости / Коментарі / Усі`). Without the scope filter, deleted comments would mix into the "all deleted" view alongside channel posts and create noise.
- Local LIKE-based search over `text_preview`, debounce 250 ms. No FTS.
- Swipe-to-purge with undo snackbar (4 s) via `UserMessageBus`.
- Overflow `⋮`: export JSON (`Intent.ACTION_CREATE_DOCUMENT`) with **pre-export size warning** (raw size estimate including base64-encoded minithumbs — at 5000 records with thumbs this can reach ~25 MB), clear all (confirm dialog), info sheet.
- Channel filter integrates with `ChannelInfoSheet` → row "Архів цього каналу".

### 4a. `PostRevisionSheet` for deleted posts

When opened from a `DELETED` snapshot, the sheet renders differently:

- Header: `Post deleted at HH:mm` (errorContainer).
- Body: the final captured `VERSION` rendered with `alpha=0.7` (acknowledges the post is gone).
- If multiple `VERSION` rows exist, the timeline is still rendered so the user can step through edit history that preceded deletion.
- Action row: `[ Поділитися знімком ]` + `[ Telegram ↗ ]` (with caveat snackbar "посилання, ймовірно, не працює — пост видалено").
- No "Go to current" button — there is no current.

### 5. `ArchiveSettingsScreen`

Sections: master toggle, retention (`7 / 30 / 90 / ∞` dropdown), max records (`1000 / 5000 / 10000 / ∞`), excluded channels list, event-type toggles (edits / deletes), storage usage label, "Clear archive" destructive row.

**Master toggle ON flow (first time, gated):**
1. User taps toggle (off → on).
2. **Toggle does NOT flip yet.** Open `ModalBottomSheet` (`ArchiveOnboardingSheet`) describing:
   - What is captured (text + entities + media metadata + small thumbnails — no photo/video files)
   - Where it lives (locally on this device, never uploaded)
   - When it clears (on sign-out, by TTL, by hand)
3. Sheet has `[ Enable ]` (primary) + `[ Cancel ]`. Only `Enable` flips the toggle to ON.
4. After first ON, subsequent toggles never re-show the sheet — they flip directly (with off-flow below).

**Master toggle OFF flow:**
`AlertDialog` with three options: keep archive but stop writing (default), delete archive and disable, cancel.

**Onboarding gate state** is persisted in `ArchiveSettings.onboardingSeen: Boolean` — separate from `enabled`, because a user could enable, disable, and re-enable without needing the explainer again.

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
    val id: Long,
    val chat: ChatRef,
    val messageKey: String,
    val albumKey: String?,
    val kind: SnapshotKind,                  // VERSION | DELETED
    val seenAtMs: Long,
    val editedAtMs: Long?,
    val content: ArchivedContent,            // sealed: Tdlib(TdApi.MessageContent + wrapper) | Web(WebPostContent)
    val mediaMinithumb: ByteArray?,
    val deletedMessageKeys: ImmutableList<String>,  // album members for composite DELETED
    val isComment: Boolean,
)

enum class SnapshotKind { VERSION, DELETED }

@Immutable sealed interface ArchivedContent {
    val textPreview: String
    data class Tdlib(val content: TdApi.MessageContent, val meta: TdlibContentMeta) : ArchivedContent
    data class Web(val content: WebPostContent) : ArchivedContent
}
```

`ArchiveRepository` API (single writer, IO scope):

```kotlin
suspend fun captureEdit(old: TimelinePost, newContent: TdApi.MessageContent)
suspend fun captureDelete(posts: List<TimelinePost>)
suspend fun captureWebDiff(old: WebPost, new: WebPost?)   // null new = deleted
fun observe(filter: ArchiveFilter): Flow<ImmutableList<PostSnapshot>>
fun observeRevisions(chat: ChatRef, messageKey: String): Flow<ImmutableList<PostSnapshot>>
fun observeChannelIndex(): Flow<ImmutableList<ArchivedChannel>>
suspend fun purge(snapshotIds: List<Long>)
suspend fun clear()
suspend fun export(): ExportResult                          // includes size estimate
```

**`ArchivedChannel` UPSERT** on every snapshot write — channels rename, change handle, change photo. Pattern (SQLite < 3.24 portable):
```sql
INSERT OR IGNORE INTO ArchivedChannel(source_kind, source_key, title, handle, photo_minithumb, is_verified, last_snapshot_at_ms)
VALUES (?, ?, ?, ?, ?, ?, ?);
UPDATE ArchivedChannel SET title=?, handle=?, photo_minithumb=?, is_verified=?, last_snapshot_at_ms=?
WHERE source_kind=? AND source_key=?;
```

## Lifecycle hooks

- **`AppGraph`** constructs `ArchiveRepository` once, injects via constructor into `PostsRepository`, `CommentsRepository`, `WebFeedSource`. Not a CompositionLocal — repository-tier, follows existing DI pattern.
- **`TdClient.loggedOut.collect { archiveRepository.clear() }`** — registered in `AppGraph.init`, same as other session-scoped clears.
- **`AppGraph.runLogoutCleanup`** — explicit `archiveDb.clearAll()` for the synchronous path.
- **Daily storage sweep** — adds `ArchiveSweep.run()` step (TTL + cap enforcement). Cap eviction uses **`DELETE FROM PostSnapshot WHERE id < (SELECT MAX(id) FROM PostSnapshot) - :cap`** — cheap (uses `idx_PostSnapshot_id`), no `COUNT()` scan. `ArchiveRepository` tracks a write counter and runs the eviction `DELETE` every 100 inserts so a burst can't sustain `> cap + 100`.
- **`ArchiveSettings.retentionDays` / `maxRecords` change** — triggers immediate `ArchiveSweep.run()` so new limits apply without waiting for the daily window.

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
| First edit ever for a pre-feature post | First `VERSION` snapshot captures the *current* (post-edit) state; UI labels the earliest revision per message with "first version Hortay saw — earlier history not captured". |
| Reactions, views | Never trigger a snapshot. Shown as info in revision sheet, not as a revision. |
| Poll edits | Captured (option text, correctness for quiz); user's vote is live state, not archived. |
| Forwarded posts | `forward` field captured. Source delete is independent message; that channel's archive captures it separately if subscribed. |
| Storage cap race | On every insert: `COUNT() > cap` → delete oldest. Daily sweep is belt-and-braces. |
| Cold-start backfill | `captureEdit`/`captureDelete` skip when `existing == null` in `_posts`. |
| Own outgoing message in a channel where user is admin | Capture works the same; UI labels via standard `is_outgoing` check on `TimelinePost`. |

## Tests (JUnit 5)

- `ArchiveRepositoryTest`
  - `captureEdit_dedupesIdenticalContentHash`
  - `captureEdit_dedupesWithin500msEvenIfDifferent`
  - `captureDelete_groupsAlbumMembers`
  - `captureSkippedWhenExistingNull` (cold-start)
  - `respectsExcludedChats` (covers both `TDLIB` and `WEB` `ChatRef`)
  - `respectsMasterToggle`
  - `respectsCaptureEditsToggle` / `respectsCaptureDeletesToggle`
  - `loggedOut_clearsAllSnapshots`
  - `loggedOutMidFlight_dropsPendingCapture`
  - `concurrentCaptureFromTwoSources_writesBoth` (TDLib + Web on same `messageKey` in race)
  - `archivedChannelUpsert_updatesTitleAndHandle`
- `WebFeedDiffTest`
  - `urlRotation_isNotTreatedAsEdit` (token query stripped, filename stable)
  - `videoTokenedUrl_filenameStable_notTreatedAsEdit`
  - `ageOutOlderThan30min_doesNotMarkDeleted`
  - `textChange_isDetectedAsEdit`
  - `entityChangeWithoutTextChange_isDetectedAsEdit`
- `PostDiffTest`
  - `lineLevelDiff_picked_whenMultilinePost`
  - `sentenceLevelDiff_picked_whenSingleParagraphMultiSentence`
  - `wordLevelDiff_picked_whenShortSingleSentence`
  - `entitiesPreserved_acrossAllGranularities`
  - `mediaSwap_detected`
  - `pollOptionsRenamed_detected`
- `ArchiveSweepTest`
  - `sweep_purgesOlderThanRetention`
  - `sweep_keepsNewestUpToCap`
  - `capHoldsUnderBurst` (100 inserts/sec → never exceeds `cap + 100`)
  - `retentionChange_triggersImmediateSweep`

## Dependencies added

- `io.github.java-diff-utils:java-diff-utils:4.12` (~80 KB, Apache 2.0) — text diff.
- `org.jetbrains.kotlinx:kotlinx-serialization-protobuf` (~70 KB) — wrapper struct for TDLib content metadata that doesn't fit in `TdApi.MessageContent` (formattedText entities, forward/reply summaries). Already used transitively by other Kotlinx serialization paths; declared explicitly here.

No other new dependencies. SQLDelight, DataStore, kotlinx.collections.immutable, Coil — already in stack.

## Out of scope (explicitly not built)

- Snapshotting reactions or view counts (live state, not edits).
- Storing full photo/video bytes (use minithumb only).
- Cross-device archive sync (local only, by design).
- FTS-backed search (Android 8/9 SQLite constraint; LIKE is adequate at 5000-record cap).
- Per-channel exclude UI in `HiddenChannelsScreen` (separate concern; only an info link).
- Capturing pre-feature post originals (impossible — labelling the first observed revision is the honest answer).

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
