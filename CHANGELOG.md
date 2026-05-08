# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com),
and this project adheres to [Semantic Versioning](https://semver.org).

## [Unreleased]

### Fixed
- **"Новi пости" pill counted older pagination arrivals as new**.
  The pill state machine in `TimelineViewModel` tracked seen content
  as a per-channel set of message ids
  (`Map<chatId, Set<messageId>>`); pending = anything in livePosts
  not in that set. But `PostsRepository` writes `_posts` from five
  paths, only one of which is "actually new":
  `handleNewMessage` (UpdateNewMessage — real new posts), `refresh`
  / `refreshIfStale` (top-N per channel, immediately acked on PTR
  or seeded on bootstrap), `restoreFromSnapshot` (cold-start cache
  rehydration), `loadOlder` (pagination scroll-down — *older*
  posts) and `loadChannelHistory` (channel-filter open / fresh-join
  back-fill — also *older* posts). Paths 3 / 4 / 5 all wrote ids
  the bootstrap set didn't know about, so they surfaced under the
  pill the moment they landed — the user-reported "якось дивно,
  рандомно" symptom: scroll down a thread, suddenly the pill
  claims "12 нових постів" pointing at posts weeks old. Switched
  the model to a per-channel **date** high-water mark
  (`Map<chatId, Long>` of `max(date) the user has acked`); pending
  = posts with `date > hw[chatId]`. Pagination loads with `date <
  hw[chatId]` are now semantically invisible to the pill while
  legitimate `UpdateNewMessage` events with newer dates correctly
  register as pending. Telegram-Android, X, Mastodon all use the
  same per-channel date / id high-water pattern. Brand-new chatIds
  appearing in livePosts after bootstrap (user opens a channel
  filter that triggers `loadChannelHistory` for a channel never
  previously in the merged feed) are auto-seeded with their
  initial max-date — so those 80 back-filled posts don't flash as
  pending, while a *subsequent* UpdateNewMessage on that same
  channel still lands above the seeded mark and registers
  correctly. The partition `posts ∪ pendingNew = livePosts` is
  preserved end-to-end.
- **Floating-bar `NestedScrollConnection` extracted to
  `rememberFloatingTopBarBehavior()`** in
  `ui/main/FloatingTopBar.kt`. `TimelineScreen` and `CommentsScreen`
  previously kept identical ~30-LOC copies of the same
  scroll-driven offset logic — load-bearing patternник that any
  future bug-fix would have had to apply to both copies (and
  probably wouldn't, the second time around). Single helper now,
  with an optional `enabled = { … }` lambda for the Timeline's
  channel-filter / search-inside-filter pinning case.
- **17 new unit tests** across two pure-function targets surfaced
  by the audit fix batch:
  - `WebPostAdapterParseShortNumberTest` (11 cases) pins the
    locale-aware number parser including the comma-decimal
    regression fix ("1,5K" → 1500).
  - `WebFeedSourceBackoffTest` (6 cases) pins the adaptive-backoff
    bucket logic so a 429-burst can't silently look identical to a
    quiet sweep. The aggregator was extracted from `doRefresh`
    into a pure `nextNoOpStreak(outcomes, current)` companion fn
    so it can be tested without an HTTP / SQLite stack.
- **Several races and lifecycle leaks surfaced by a focused
  concurrency audit.** Each was a real-world hazard rather than a
  theoretical one — fixes below land before they grow into
  user-visible regressions.
  - **`MediaCache.evictTerminalSlots` TOCTOU**. The watchdog read
    `subscriptionCount.value > 0` outside the reducer loop, then
    `iter.remove()`'d the entry. Between the two, a fresh
    `observe(fileId)` could call `slot()` → `computeIfAbsent`
    return the existing flow → start collecting; the eviction
    then yanked that flow out of the map, and any subsequent
    `UpdateFile` for this id hit `states[file.id] ?: return` in
    [reduce] — the observer was silently starved of state. Replaced
    iterator+remove with `ConcurrentHashMap.compute(id) { … }` so
    the subscriptionCount probe + the entry removal happen under
    the same bin lock as any concurrent `slot()`'s
    `computeIfAbsent`. Symptom would have been a rare "media slot
    appears stuck on Idle / Downloading until the surrounding
    Composable is destroyed and re-created."
  - **`PostsRepository.refreshLocked` clobbered concurrent archive
    add/remove updates**. `_archivedChatIds.value = archiveIds.toSet()`
    was a direct assignment that stomped on whatever the parallel
    `UpdateChatAddedToList` / `UpdateChatRemovedFromList` handler
    had merged in via `update {}`. A user who archived a channel in
    the official Telegram client during the seconds-long
    `drainChatList + N×GetChat` block would lose that update until
    a TDLib reconnect re-fired it. Now uses `update {}` with a
    snapshot-plus-existing-not-in-main union, so the archive flow
    survives concurrent membership changes.
  - **`WebTelegramClient.awaitGate` rate-limit TOCTOU**. With
    `Semaphore(6)` permitting six parallel `fetchChannelPage`
    calls, all six read `gateUntilMs` BEFORE the first 429 pushed
    a fresh deadline — earning five additional 429s in the same
    rate-limit window and pushing the gate even further out. Now
    loops, re-reading `gateUntilMs` after each delay, so any push
    issued during our wait extends our sleep. One real 429 is now
    contained instead of compounding.
  - **`ChannelFetchStatus.Loading` could stick forever after a
    process kill mid-fetch**. `markFetchStatus(Loading)` was set on
    entry to `fetchOne` and cleared on exit; OOM / ANR / system
    reap killed the cleanup path, leaving the `loading` row
    permanent — Channels tab spinner forever. Wrapped the body in
    `try { … } catch (t: Throwable) { /* mark Error */ }` so any
    exception (other than CancellationException, which propagates)
    demotes the row to Error; AND added `WebRepository.clearStaleLoading()`
    that runs once at `WebFeedSource` init to demote any rows left
    over from a prior crashed fetch.
  - **Adaptive backoff conflated rate-limited with no-op sweeps**.
    A 429-burst that returned zero new content was being treated as
    "feed is quiet" — the scheduler doubled its interval, eventually
    hitting the 30-min cap. A single t.me throttle blip would cost
    an hour of update lag. Refactored to three buckets: any `Fresh`
    resets the counter; any `Transient` (RateLimited / NetworkError)
    leaves it alone; only all-`NoOp` increments it.
  - **`WebTelegramClient.lookupChannel` could freeze the
    AddChannelSheet UI for the full 120 s rate-limit gate**. Bound
    by `withTimeout(15 s)`; on timeout, surfaces
    `LookupResult.RateLimited` (with the actual remaining gate
    duration) instead of `NetworkError`, so the UI can show
    "rate limited, try in N s" rather than a generic connectivity
    blame.
  - **`MainScaffold.commentsForPost` overlay vanished after a
    process kill**. `TimelinePost` is not Parcelable (deep
    @Immutable graph with PersistentList / ByteArray fields →
    big Parcelize blast radius). Switched to a paired state:
    saveable `pendingCommentsKey: Pair<Long, Long>?` (chatId,
    post.id) survives process death; transient
    `commentsForPost: TimelinePost?` is restored from the live
    feed via a `LaunchedEffect` that does
    `posts.map { firstOrNull-by-key }.filterNotNull().first()`.
    Match works against either `post.id` or any
    `albumMessageIds` entry to survive album-anchor reshuffle on
    cold restart.
  - **`TimelineScreen.interactions` captured stale references after
    logout/login**. `val interactions = remember { … }` had no
    keys; the lambdas closed over `vm`, `viewer`, `tdlibRepo`,
    `channelActions`, `translations`, `feed`, `bookmarks` — and a
    feed-swap (logout / login on Activity-scoped VMStore) left
    these pointing at the previous account's repos. Same root
    cause as the recent per-account-state-survives-logout fix on
    the data layer, just on the UI side. Now keyed on every long-
    lived dep so a swap rebuilds the callbacks.
  - **`ChannelsScreen.ChannelSummary` lacked `@Immutable`**. Class
    contains a `ByteArray?` field, which Compose stability
    inference correctly flags as Unstable — the entire LazyColumn
    of 200 channel rows recomposed on any upstream list mutation,
    even when the row's own ChannelSummary was identical. Added
    the annotation; instances are constructed fresh by `aggregate`
    each recomposition so the contract trivially holds.

### Fixed (web mode)
- **`WebPostAdapter.parseShortNumber("1,5K") = 1500`**, not 15000.
  Telegram renders `1,5K` views in locales with comma decimals
  (uk, ru, fr); the regex `[^0-9.]` was dropping the comma before
  `toDoubleOrNull`, parsing as `15` × 1000. Now normalises comma
  → dot before the strip.
- **`POST_NOTIFICATIONS` permission removed**. Declared but never
  used — `RegisterDevice` is not wired up and TDLib's notification
  subsystem is explicitly disabled via `notification_group_count_max=0`
  in `TdLifecycleBridge`. Play Console flagged the unused permission
  as gratuitous attack-surface; the app still has zero need for it
  until push notifications land properly.
- **`WebRepository.observeFeed` switched debounce(50ms) →
  sample(150ms)**. Under a 200-channel burst sweep, individual post
  inserts can land closer than the debounce window so debounce kept
  resetting and never emitted — the feed appeared frozen until the
  entire sweep completed (3-5 s of nothing). `sample` guarantees up
  to ~7 emits/s under continuous burst, so the UI sees the feed
  grow incrementally as channels land. Bursts shorter than 150 ms
  still coalesce, which is the original goal.

### Performance
- **Skip JSON re-encoding for unchanged web posts**. Most posts
  are unchanged between sweeps; `WebRepository.ingestPost` was
  serialising four JSON blobs (media / preview / forward /
  reactions) on every incoming post anyway. New
  `selectFingerprint(channelUsername, seq)` query reads the
  existing `(text_html, views)` and skips both the four
  serialises and the UPDATE on a match. ~28 s of CPU saved per
  hour of foreground sweeping on a 200-channel set.
- **Dropped dead `post_text_plain_idx`**. Created in v1 to "speed
  up" the LIKE-based cross-channel search; in practice
  `LOWER(text_plain) LIKE '%pattern%'` defeats any B-tree index
  (functional expression on the column AND a leading wildcard,
  either of which is sufficient). EXPLAIN QUERY PLAN confirmed the
  index never participated in any query, but every INSERT / UPDATE
  paid the maintenance cost: ~40 MB disk on 5K posts and ~20 %
  INSERT overhead, all for nothing. Migration `2.sqm` (v2 → v3)
  drops it; `verifyDebugWebDatabaseMigration` round-trips cleanly.

### Architecture
- **A11y batch**: replaced 9 hard-coded `contentDescription`
  literals (`"back"`, `"close"`, `"clear"`, `"search"`, `"edited"`,
  `"pinned"`) with `stringResource(R.string.action_*)`. TalkBack now
  reads the user's locale instead of English regardless. Added 9
  `Modifier.clickable(role = Role.Button, …)` on semantically-button
  Rows / Boxes (PostCard avatar / header / forward chip / reply
  block / stat pill / sheet item; TimelineScreen channel title +
  brand row) — TalkBack now announces "button" instead of "text
  link" for those affordances.

### Build
- **Removed unused build configuration**: `POST_NOTIFICATIONS`
  permission (above), `JitPack` repository (no transitive
  dependency reaches it), `sqldelight-sqlite-driver` and
  `androidx-test-runner` library aliases (declared but never
  consumed). Trimmed `androidxTest` version constant since its
  only consumer was `androidx-test-runner`.
- **Added R8 keep rules for SQLDelight generated code**. The
  Android driver invokes `<DatabaseName>.Schema` reflectively at
  create-or-migrate time; without a keep rule, R8 may strip the
  Schema field and crash the very first SQLDelight query on
  release with `NoSuchFieldError`. Belt-and-suspenders coverage
  for both `dev.lyo.hortay.data.web.db.**` and `app.cash.sqldelight.**`.

### Fixed
- **Auto-download category summary lost the space after each comma**
  ("Фото,Відео до 10 МБ,GIF" instead of "Фото, Відео до 10 МБ, GIF").
  The list separator was a string resource declared as `<string
  name="autodownload_summary_separator">, </string>` — but `aapt2`
  trims trailing whitespace from string resources at compile time, so
  the runtime got back `","` with the space stripped. The canonical
  XML escape (`<string>", "</string>` with literal double-quotes) would
  preserve it, but `", "` is the only correct separator across every
  locale we ship, so the resource is now gone and `joinToString(", ")`
  in `AutoDownloadScreen.summarize` carries it directly. One fewer
  resource, one fewer trap for the next translator.

### Changed
- **Copy pass across all user-facing strings** (uk + en). The corpus
  had been growing at "what does this say?" pace rather than "how
  would a human say this?" pace — readable but stilted, with the
  AI-slop tells the user flagged: trailing periods on standalone short
  phrases / button labels / status chips, em-dashes splicing
  conjunctions ("Канал приватний — публічні пости недоступні" reads
  as one beat instead of two), heavy-handed openers ("Введіть номер з
  кодом країни" → "Номер з кодом країни"), and verb framings that
  read as system narration rather than direct address ("Завантаження…"
  → "Завантажуємо…", "Накопичено за весь час роботи. Не зменшується
  від очистки кешу" → "Сумарно за весь час. Очистка кешу не скидає
  лічильник"). Touched every section: auth, errors, web mode,
  timeline, channels, comments, settings, auto-download, migration.
  Plurals, plural keys, format specifiers (`%1$s`, `%1$d`, `%2$d`)
  and the EN/UK key parity all preserved — only the human-readable
  payload moved.

### Changed
- **Top-bar slides off-screen on scroll, returns when the user reaches the
  top** (Twitter / Instagram floating-bar pattern). Replaces the canonical
  M3 `exitUntilCollapsedScrollBehavior` collapse-to-compact behaviour, which
  always left 64 dp of permanent chrome the reader didn't need mid-thread.
  Now: at the top the destination-style bar is fully visible (Medium); the
  moment the user pulls the content up the bar's measured height shrinks
  in lockstep with a scroll-driven `topBarOffsetPx`, sliding the bar
  content up under a `clipToBounds` rect; once fully consumed scroll passes
  through to the LazyColumn untouched. Pulling content down at the top of
  the list reveals the bar again at the same scroll-delta rate. No timed
  animation = no reflow jolt as the bar transitions; the same scroll
  signal drives bar height AND Scaffold body padding, so the feed never
  outpaces the chrome it's replacing.

  Two-zone construction so the system status-bar area always reads cleanly
  regardless of bar offset: a persistent zone-1 strip (sized via
  `windowInsetsTopHeight(WindowInsets.statusBars)`) painted with the app's
  background colour, then a zone-2 layout-shrunk container holding the
  actual `MediumFlexibleTopAppBar` with `windowInsets = WindowInsets(0)`
  to suppress its internal status-bar padding (otherwise the bar's content
  would travel into the status-bar area as it slid up — visible as the
  word "Hortay" leaking onto the system clock).

  Tool-stage variants (channel filter, search-inside-filter — compact
  `TopAppBar` with active inputs / back arrow) deliberately stay pinned:
  the [NestedScrollConnection] gates on `channelFilter == null`, so a
  feed user mid-search keeps the input on screen at all times. The bar's
  offset also resets to 0 whenever `channelFilter` or `showOnlyBookmarked`
  flips, so navigating between top-level destinations never lands the
  user on partially-hidden chrome they didn't expect to be missing.

  Earlier iterations tried `AnimatedVisibility(visible = atTop)` around
  the topBar slot — that ran a 150 ms `shrinkVertically` tween on the
  bar's height while the user was mid-scroll. Scaffold re-measured the
  topBar slot every frame of the tween, body's padding jumped along, the
  FoldersBar / LazyColumn underneath shifted up at ~750 dp/s while the
  user's own scroll continued at ~200 dp/s. The combined velocity
  discontinuity was the visible "тримає / стрімає" jank the user
  reported. Driving the bar's exit purely by scroll delta — same signal
  that drives the body — eliminates the discontinuity at the source.
- **Top-level destinations migrate to `MediumFlexibleTopAppBar`**.
  Timeline default home, Timeline bookmarks-only mode, and the
  Comments overlay now read as M3 Expressive destinations instead
  of compact tool stages: larger title typography on first paint
  ("you are here"), auto-collapse to 64 dp on scroll via
  motion-token `scrollBehavior`. The 48 dp first-paint cost is
  recovered the moment the feed scrolls — the steady state matches
  the previous compact bar height. Drill-down stages (channel
  filter, search-inside-filter) deliberately stay on the standard
  compact `TopAppBar` because they're tool stages, not destinations
  — visually flagging the hierarchy at a glance instead of bar-
  overload everywhere.
  Comments adds a live subtitle slot driven by `ThreadState`:
  "Завантаження…" while loading, "Поки немає коментарів." when
  empty, "%d відповідей" with the row count when ready, hidden on
  error. The standalone label that used to live above the comment
  list stays put — it's the empty-state affordance for the body
  area, not a duplicate of the subtitle.
- **Inline `RoundedCornerShape(N)` call sites collapsed onto
  `MaterialTheme.shapes` tokens** across 12 files / ~40 call sites.
  Auth flow (`AuthScreen`, `OtpInput`, `CountryPickerSheet`),
  channels (`ChannelInfoSheet`, `ChannelsScreen`), comments
  (`CommentsScreen.ReplyBlock` + thumb), settings (`SettingsScreen`,
  `AutoDownloadScreen`), feed (`PostBody` 13 sites, `PostCard`
  `ReplyBlock`), and guest-mode UI (`AddChannelSheet`,
  `WebChannelsScreen`) all read radii from
  `MaterialTheme.shapes.{small, medium, large}` instead of
  hand-tuned 14 / 16 / 18 / 20 / 22 / 24 / 28 dp literals. The 18 →
  20 → 22 → 24 dp inline gradient that earlier migrations introduced
  for hierarchy is consciously flattened — at this scale the 2 dp
  steps fall below the user's perceptual threshold across surfaces,
  and a single source of truth (`HortayShapes` in `theme/Shape.kt`)
  pays back the next time the M3 Expressive scale shifts (no more
  per-call-site chase). Mapping that drove the refactor:
  `12 → small`, `14 / 16 / 18 / 20 → medium` (18 dp),
  `22 / 24 / 28 → large` (24 dp). Sub-token literals (1 / 4 / 6 / 8 dp,
  used for dividers and tiny inline thumbnails) stay raw — there is no
  shape token below 8 dp by design. Animated 3-state corner-radius
  patterns (`FoldersBar`, `FloatingNavBar`, `PostCard` reaction chips)
  also stay raw — they're keyframe-driven by interaction state, not
  static. Net effect: `Shape.kt`'s `HortayShapes` declaration is now
  the single load-bearing dial for the app's softness language.
- **Fullscreen video viewer ships its own M3 Expressive chrome**.
  The viewer used to delegate playback controls to media3's stock
  `PlayerView` (`useController = true`) — which paints a grey 2010s
  scrubber + blocky pause button via its own XML layout, visually
  inert against everything else in the app (polygon shapes, wavy
  progress, motion-token transitions). Replaced by `VideoPlayerControls`,
  a Compose chrome painted over the same `TextureView` that feed-
  preview uses. The hero choices, all canonical M3E patterns:
    - **Centre play/pause**: 72 dp filled disc with a polygon backdrop
      morphing `Square (paused) ↔ Circle (playing)`. The pair Google's
      Material 3 sample app uses for every media-state toggle (play/
      pause, mute/unmute, record start/stop) — one shape idiom for
      every "this control switches state" affordance instead of one
      per control. `Cookie` / `Burst` / `Heart` shapes intentionally
      stay reserved for hero / personality moments (reactions, empty
      states); the play button is high-frequency UI, expressive but
      calm. Glyph crossfades between `play_arrow` and `pause` while
      the shape morph runs on M3E's spatial-channel medium-bouncy
      spring.
    - **Seek bar**: standard M3 `Slider` with a visible white thumb
      against a translucent track. First cut here used
      `LinearWavyProgressIndicator` — but its canonical M3E rendering
      paints the wave only on the *completed* portion of the track,
      so at `0:00` the bar reads as a straight line and after a seek
      mid-clip it suddenly reads as wavy. The visual asymmetry is
      correct for an upload-style ongoing-process indicator but reads
      as a bug on a seekable media bar where the user expects one
      stable shape to drag. Wavy progress remains the right
      affordance elsewhere (download, migration sweep) where the bar
      fills monotonically.
    - **Mute toggle**: 40 dp `IconButton` with a `MaterialShapes.Pill`
      polygon backdrop, glyph crossfade between `volume_up` and
      `volume_off`. Same toggle vocabulary as the centre play/pause,
      visually subordinated by size.
    - **Touch model**: single tap toggles chrome, double-tap on the
      left or right half seeks ∓10 s (Telegram / YouTube canonical).
      Auto-hides 3 s after the last interaction, but only while
      playing — paused state pins the play button as the resume
      affordance.
  Two render-path benefits fall out for free: the bare
  `TextureView` (vs `PlayerView`'s `SurfaceView`-backed surface)
  blends transparently while ExoPlayer prepares, so the blurred
  poster reads through cleanly during the prepare/buffer window
  instead of getting masked by a 2-3 s opaque-black square; and
  playback / seek / mute state becomes a pure Compose concern that
  composes through the same `@Immutable` stability chain as the
  rest of the UI.
- **`QualityChip` switches to `MaterialShapes.Pill` polygon backdrop**.
  Previously rounded-rect on `RoundedCornerShape(50)` while every
  other piece of viewer chrome (close button on `Cookie9Sided`, page
  counter on `Pill`, the new mute toggle on `Pill`) spoke the
  expressive polygon vocabulary — the chip read as the one
  stock-Material rectangle in an otherwise-cohesive overlay.
- **`HortayExpressive` shape registry adds `PlayPausePaused` /
  `PlayPausePlaying` / `PlayPauseMorph`** for the centre play/pause
  control. Pre-built `Morph` (paused = `MaterialShapes.Square`,
  playing = `MaterialShapes.Circle`) avoids re-allocating the float
  arrays per frame as the morph progress animates.

### Added
- **Four new Material Symbols vector drawables** (`Rounded · weight 500
  · 24 dp`, the project's canonical pairing for M3 Expressive consumer
  apps with bold display typography): `play_arrow`, `pause`,
  `volume_up`, `volume_off`. Wired through `Symbol.kt`'s
  `name → drawable` table for use anywhere a media-toggle glyph is
  needed. Source paths use a `<group android:translateY="960">`
  wrapper so Material Symbols' canonical
  `viewBox="0 -960 960 960"` (bottom-left origin, paths in `[-960, 0]`
  Y range) maps cleanly into Android Vector Drawable's
  positive-only `viewportHeight=960` coord space without per-coord
  conversion.

### Architecture
- **All five TDLib media renderers now share one observation contract**.
  [TdMediaImage], [TdVideoPlayer], [LottieStickerView],
  [WebmStickerPlayer] and [CustomEmojiInlineView] each used to
  re-implement the same four-step ritual that every TDLib renderer
  must honour: (1) observe the file's [MediaState] as Compose state,
  (2) issue [MediaCache.ensure] keyed on
  `(fileId, priority, scroll-gate, isRemote)` so a re-mount or
  scroll-settle picks up the download immediately while a mid-fling
  mount defers to the gate, (3) issue [MediaCache.cancelDeferred] on
  Composable dispose so a scrolled-past slot is released back to
  TDLib's per-DC pool, (4) take a `web-mode no-op` shape when
  `fileId` is null and a remote URL is set instead. An audit found
  subtle drift across those copies — each renderer keyed `ensure()`
  on a slightly different tuple, two of them tracked their own
  per-instance `MutableStateFlow(Idle)` web-mode sentinel with
  slightly different keying, and the four-step contract was held
  only by convention. Centralised behind one
  [rememberMediaBinding] hook in `ui/media/MediaBinding.kt`. The
  hook returns a stable `MediaBinding` handle exposing the observed
  state plus typed helpers (`isReady`, `readyPath`, `cancelExplicit`,
  `retry`, `invalidate`) so per-renderer call sites can never drift
  again. Future renderers — animated avatar, full-screen viewer
  posters — inherit the contract for free.
- **[MediaCache.resync] — defensive mount-time state refresh**.
  Closes the long-standing user complaint *"показує що не загружено,
  але якщо проскролити вниз і знову вверх — все вже там"*. Root
  cause: a slot can drift to a stale [MediaState.Downloading] with
  [activePriority] still set (lost UpdateFile, background-while-
  completing race, debounced cancel firing right before TDLib's
  `isDownloadingCompleted=true` tail event). On re-mount, the
  existing [MediaCache.ensure] short-circuits because
  `currentPriority >= priority.tdValue` and never asks TDLib *"what
  is this file, really?"*, so the slot stays pinned at the stale
  value even though TDLib has the file Ready on disk. The new
  `resync` method unconditionally issues `GetFile` and routes the
  reply through the reducer's [fileEvents] channel — same path as
  inbound `UpdateFile`, so reducer sequencing and Ready-stick
  guarantees are preserved end-to-end. [rememberMediaBinding]
  invokes `resync` on every mount via a keyed `LaunchedEffect`, so
  the user-described symptom self-heals on every Composable
  attachment without requiring a scroll. Cost: one JNI roundtrip
  on mount per renderer (~10-50 µs); on a 30-card viewport totals
  well under a millisecond. Aligned with Levin's tdlib/td#3178
  guidance *"the local file path can become invalid in many ways.
  The app is supposed to call downloadFile before using the file"*
  — this is the read-side equivalent for state freshness.

### Performance
- **`OptimizeStorage` skipped when cache is well under the cap**.
  The daily storage sweep used to run unconditionally past its
  24 h throttle, walking TDLib's file table to enumerate files
  for eviction even when the cache was nowhere near the 500 MB
  cap. [TdApi.GetStorageStatisticsFast] is a metadata-only read
  (sub-10 ms); [TdApi.OptimizeStorage] walks the file table on a
  populated ~500 MB cache (50-300 ms). Maybe-optimize now probes
  size first via the fast read and skips the walk when usage is
  below 80 % of the cap (400 MB). Levin's recommended pattern in
  tdlib/td#667#issuecomment-521611484 — *"you can from time to
  time use getStorageStatisticsFast to quickly get size of TDLib's
  cache and run the method optimizeStorage if needed"*. Cold-start
  latency win on every day-boundary launch where the cache hasn't
  filled, plus a small battery saving (most user sessions never
  trigger a real walk).
- **`OptimizeStorage` `fileTypes` made explicit**. Previously
  passed `fileTypes = null`, which under [TdApi.OptimizeStorage]'s
  javadoc default (*"all types except thumbnails, profile photos,
  stickers and wallpapers are deleted"*) was already correct —
  stickers / avatars / wallpapers were never at risk of eviction.
  The previous shape was therefore not buggy, just opaque:
  reading the call site, you had to remember TDLib's policy to
  know what got touched. Now explicitly enumerates
  [TdApi.FileTypePhoto] / [Video] / [Animation] / [VoiceNote] /
  [Audio] / [Document] / [VideoNote] — the heavyweight media
  types we actually serve. Two forward-looking benefits over the
  equivalent null default: the set is self-documenting at the
  call site, and it stays stable across TDLib version bumps (a
  future TDLib release that adds a new "secondary" type to its
  default-exclude set wouldn't change our behaviour).
  `clearCache` (the user-initiated *"знести усе"* surface) keeps
  `fileTypes = null` because there the default semantics'
  protection of stickers / profile photos is the only thing
  standing between "clear cache" and "actually clear cache".

### Fixed
- **220 ms grey blink between minithumb and full photo**. The
  blurred minithumbnail (the inline ~150-byte JPEG TDLib ships with
  every photo / video poster) was hidden the instant the
  [MediaCache] reducer flipped the slot to [MediaState.Ready] —
  but Ready is the *download-completed* signal, not the
  *Coil-rendered-the-pixels* signal. Coil's `.crossfade(220)` on
  the file-image AsyncImage then faded the photo in from alpha 0
  over 220 ms with nothing underneath, so the entire fade window
  painted the surface-container placeholder colour, producing a
  visible "блимок" between the soft minithumb and the full image.
  Fix: keep the minithumb composed under the file image and let
  Coil's crossfade cover it naturally as the file image reaches
  full opacity. Render cost is negligible — the minithumb bitmap
  is in Coil's memory cache after first decode, so what stays
  composed is one bitmap blit + one RenderEffect blur per visible
  card per frame, well under the noise of the surrounding
  LazyColumn layout pass.
- **Mid-playback rebuffer indicator flashed during healthy
  playback**. ExoPlayer's `STATE_BUFFERING` fires for many
  sub-second reasons that aren't user-visible "the video froze"
  events: source switch on quality change (~50-200 ms), seek
  (~100-300 ms), normal chunk-boundary refills on tight buffers
  (~50-150 ms). Painting the indicator immediately on every blip
  surfaced as a visible disc-and-spinner flash during normal
  playback. The pre-Ready download path already solved this with
  [rememberDeferredLoading]; reusing the same primitive at 400 ms
  grace (tighter than the 600 ms used pre-Ready, since the user
  is mid-watch and more attentive) so a true network rebuffer
  gets feedback while every blip-and-recover stays invisible.
- **Video / animation posters didn't auto-download on metered
  networks**. The poster (the photo-thumb the feed paints behind
  the play badge) and the playback file are SEPARATE TDLib
  fileIds, but [MediaAutoDownloader.dispatchPost] gated the
  poster behind [AutoDownloadPolicy.videos]. Users who turned
  videos off on Mobile / Roaming saw bare blurred minithumbs in
  every video card until they scrolled close enough to trigger
  the viewport-driven prefetch in [TimelineScreen]'s
  `posterFileIds()` walk — significantly worse on long feeds
  with frequent-jump scrolling. Telegram-Android prefetches
  posters regardless of the video toggle, since posters are
  photo-sized (30-300 KB) and the toggle's intent is "don't burn
  bytes on multi-MB playback files", not "leave video cards
  visually empty". Decoupled: posters now ride
  [AutoDownloadPolicy.photos] (where they semantically belong),
  playback continues to ride [AutoDownloadPolicy.videos] with
  the same size cap. Same change for [PostContent.Animation],
  [AlbumItem.Video] and [AlbumItem.Animation].

### Changed
- **End-to-end M3 Expressive redesign**. Theme switches from
  `MaterialTheme` to `MaterialExpressiveTheme` with
  `MotionScheme.expressive()` — every Material component now reads
  spring-physics motion tokens (separate spatial vs effects channels),
  the canonical Google default for consumer apps as of I/O 2025.
  Container scale (`HortayShapes`) bumped to 8 / 12 / 18 / 24 / 36 dp
  (was 4 / 8 / 12 / 16 / 28) so Card / Surface / Button / Sheet /
  Dialog / FAB pick up the softer language without per-call-site
  edits. Inline radii bumped in lockstep across PostCard reply
  preview (18 dp), ChannelInfoSheet description card (22 dp),
  CountryPickerSheet rows + search field (18–22 dp), OtpInput cells
  (18 dp), AddChannelSheet card (20 dp), AutoDownloadScreen rows +
  buttons (18–22 dp), Settings clear-cache button (24 dp).
- **Hero polygon morphs across every interactive surface**. New
  `HortayExpressive` shape registry and pre-built `Morph` factories
  drive shape-morph feedback on selection / press, the canonical M3
  Expressive cue:
    - Reaction chips morph `Square ↔ Cookie9Sided` on the user's own
      reaction (the highest-touch single change in the app — readers
      see this every time they tap a heart or fire emoji).
    - Folder filter chips morph `Square ↔ Cookie7Sided` on selection.
      Cookie7's odd side-count + asymmetric ridges read as visually
      distinct from the reaction Cookie9 — both forms can sit on
      screen at once without the eye reading them as the same idiom.
    - FloatingNavBar tab morphs `Square ↔ Cookie7Sided` on selection
      AND swaps the glyph to its filled variant (`fill=1` axis); the
      active destination is heavier than its peers in two dimensions
      (shape + glyph weight), the M3 Expressive selected-state
      convention.
    - ConnectionBanner uses `MaterialShapes.Bun` — soft alert pillow,
      less alarming than a rectangle, more attention-grabbing than a
      generic rounded chip.
    - Empty states (Timeline, Saved) get 96 dp polygon hero badges:
      `Flower` for "no posts yet", `Heart` for "no bookmarks yet",
      `Cookie7Sided` for "search empty". Polygon as backdrop plus a
      40 dp glyph centered — the largest expressive form on screen at
      that moment, intentional under M3 Expressive's "hero shapes for
      personality, dense surfaces stay calm" rule.
    - StatPill (views / comments) uses `MaterialShapes.Pill` — the
      same flattened-stadium silhouette as the new-posts pill and the
      media-viewer counter, single visual idiom across all
      "stat-style" affordances.
- **NewPostsPill expressive entrance**. The "новi пости" floating
  chip pops in with a spring-driven `scale(0.85 → 1.0)` on first
  appearance only (gated by a one-shot LaunchedEffect), so it reads
  as one cue when posts become available rather than re-bouncing on
  every count tick. Container clip stays `CircleShape` — true pill
  for an arbitrary-width Row, where the stricter `MaterialShapes.Pill`
  polygon's interior corners would slice the trailing label
  characters as the Row outgrew the polygon's bounding box.
- **Loading indicators across the app**. Every
  `CircularProgressIndicator` swapped for M3 1.5's polygon-cycling
  `LoadingIndicator` (auth submit button, full-screen auth load,
  comments thread load, AddChannelSheet lookup, settings clear-cache).
  Auth's blocking loader uses `ContainedLoadingIndicator` — heavier
  visual weight that reads as "system is working" on a full-screen
  load vs the inline polygon spinner on buttons. Migration proposal
  sheet's determinate progress switched to
  `LinearWavyProgressIndicator` — sine amplitude reads as "alive"
  between integer ticks at the 1 channel / second throttle.
- **Top app bars switched to flexible variants with subtitles**.
  Settings → `LargeFlexibleTopAppBar` with subtitle "Account, traffic,
  downloads". AutoDownload list → "Photos, videos, GIFs" subtitle.
  AutoDownload per-category → "When connected to Wi-Fi" /
  "On mobile data" / "When roaming abroad" subtitles. New plumbed
  string resources (`settings_subtitle_*`, `timeline_subtitle_*`) in
  EN + UK.
- **Full-screen media viewer chrome**. Close button gets a Cookie9
  polygon backdrop, the page counter ("3 / 5") gets a Pill polygon
  backdrop — viewer chrome now speaks the same expressive vocabulary
  as the feed cards, not stock-Material glass.
  `MediaProgressIndicator` paints a Cookie9 polygon path inside its
  Canvas (visible silhouette), with a `CircleShape` clip bounding the
  click ripple — the visible disc reads as a Cookie shape while the
  click hit-test stays simple and the central icon stays unclipped.
- **Material Symbols re-pulled at Rounded · weight 500 · 24 dp**.
  All 61 bundled icons replaced with the heavier-stroke variant —
  weight 400 read thin against Plus Jakarta Sans Bold display
  typography (`displaySmall` 32 sp ExtraBold), weight 500 balances
  visually without crossing into "loud" territory. New filled
  (`FILL=1`) companion drawables shipped for the icons that have a
  selected / active state: `home`, `bookmark`, `person`, `forum`,
  `chat_bubble`, `push_pin`, `notifications_active`. The `Symbol`
  composable takes a new `filled: Boolean` parameter — silently a
  no-op when the named icon ships no `_filled` companion, so call
  sites can pass `filled = isSelected` without branching.
- **Channels tab icon**: `forum` → `dynamic_feed`. The forum
  multi-bubble glyph read as too square inside the Cookie7 polygon
  tab backdrop; the stacked-cards metaphor of `dynamic_feed` is
  also semantically tighter for "list of subscribed feeds" than
  "discussion forum".

### Build
- **material3 pinned to 1.5.0-alpha19**. The April 2026 Compose BOM
  (2026.04.01) still ships material3 1.4.0 stable, which lacks
  `MaterialShapes`, `LoadingIndicator`, `LargeFlexibleTopAppBar`,
  `MotionScheme.expressive()` and the rest of the Expressive surface.
  We override the BOM-supplied version explicitly in
  `gradle/libs.versions.toml` (Google's documented escape hatch for
  early-adopt of material3 ahead of the next BOM cut). When the next
  BOM ships >= 1.5.0, drop the override.
- **`androidx.graphics:graphics-shapes:1.1.0`** added. Pulled
  transitively by material3 1.5.x but declared explicitly so the theme
  module references `Morph` / `RoundedPolygon` / `toPath` directly
  without depending on material3's internal export surface.
- **compileSdk bumped 36 → 37**. Forced by Compose UI 1.12.0-alpha02
  (transitive dep of material3 1.5.0-alpha19). targetSdk stays at 36
  — runtime behaviour we tested + Play certified — bumping just the
  compile-time API surface is Google's documented opt-in path for a
  new Compose track without flipping app behaviour at runtime.

### Fixed
- **Per-account state survived logout (privacy bug)**. `PostsRepository`,
  `MessageMapper`, `CommentsRepository`, `MediaCache`,
  `CustomEmojiRepository`, `ChatFoldersRepository`, `TranslationsStore`,
  the on-disk `TimelineSnapshotStore` and `MigrationStore`'s
  proposal-shown flag were all process-singletons living on `AppGraph`.
  After `LogOut`, TDLib wiped its database, but our in-memory caches
  (cached posts, resolved sender names, comment-thread anchors, file
  metadata, custom-emoji stickers, folder list, translations) and the
  on-disk timeline snapshot all stayed intact — so a subsequent sign-in
  to a *different* Telegram account briefly painted account A's last
  feed inside account B's UI during the cold-restore → first-refresh
  window. `MigrationStore.proposalShown=true` also persisted across
  accounts, suppressing the migration offer when account B (which had
  never seen it) signed in. New `TdClient.loggedOut: SharedFlow<Unit>`
  fires on `AuthorizationStateLoggingOut`; `AppGraph` subscribes once
  at construction and fans out `clear()` calls across every
  per-account repo + the snapshot file + `MigrationStore.reset()`
  before TDLib's `spawnClient` rebuilds a fresh native session.
- **Cold-start feed appeared in one big batch ~3-5 s after launch
  (TDLib mode)**. `PostsRepository.refreshLocked` accumulated all
  per-channel `GetChatHistory` results via `awaitAll().flatten()`,
  then issued a single `_posts.update`. Users sat on a blank feed
  until the slowest of 200 channels' round-trips returned. Now each
  per-channel result folds into `_posts` the moment it lands, so the
  first ~10 posts surface within ~100 ms while the rest stream in
  over the same ~3-5 s. The atomic `_posts.update` CAS contract is
  preserved, so concurrent UpdateNewMessage / UpdateMessage*
  handlers still interleave cleanly with refresh writes.
- **`TimelineViewModel`'s seen-set seeded too early under the new
  streaming refresh**. The previous `livePosts.first { it.isNotEmpty() }`
  hook fired on the first per-channel batch, marking only that
  channel's posts as "seen" and tagging every later channel's
  streamed posts as `pendingNew`. Seeding now waits until both
  `livePosts` is non-empty AND `refreshing` has settled back to
  false — the canonical "bootstrap is complete" point. Warm path
  (refreshIfStale skipped because data is fresh) seeds against the
  snapshot result identically.
- **`WebFeedScheduler` polled t.me/s/ even while signed in to TDLib
  mode**. The tier-2 sweep was foreground-bound but auth-blind, so
  every 5 minutes it issued ~50 HTTPS requests to t.me/s/ for web
  subscriptions the user wasn't even seeing (TDLib UI was on top).
  Wasted traffic, wasted battery, plus a real risk of t.me-side
  rate-limit on a heavy guest set. Scheduler now combines the
  foreground signal with `authStage`; tier-2 pauses when
  `authStage == AuthStage.Ready` and resumes automatically on
  sign-out.
- **`MigrationCoordinator` left migrated channels in `webSubscriptions`
  after a successful join**. A guest-mode user with 50 subs who
  signed in and accepted migration for 30 of them ended up with 30
  TDLib chats AND those same 30 entries still in
  `webSubscriptions`. If the user later signed out, those 30
  channels surfaced as guest-mode subscriptions on top of the freshly-
  empty TDLib chat list — visible duplicates between modes. Now a
  successful join also calls `subscriptions.remove(username)`, so
  the migration is a true move. The 20 channels the user explicitly
  skipped stay in `webSubscriptions` since those are intentional
  guest-mode reads, not migration candidates.
- **`OptimizeStorage` was wiping the entire media cache every 24 h (TDLib
  mode)**. The throttled daily sweep passed `count=0` and `immunityDelay=60`
  literally — but TDLib treats `0` as the literal limit ("keep zero files /
  immunity-protect for 60 s") and `-1` as "use the default limit". The
  strictest of the simultaneous eviction limits won, so `count=0` overrode
  `size=500MB` and TDLib evicted everything that wasn't actively in flight
  on every cold-start past the 24 h throttle window. Visible symptom: avatar
  pyramid + every cached photo / video re-downloaded the next day even
  though no eviction was supposed to happen until the 500 MB cap kicked in.
  Now passes `-1` for `ttl`, `count` and `immunityDelay`, so only the
  explicit `size=500MB` cap drives eviction and freshly-downloaded media is
  immune for TDLib's default window. `chatLimit` stays `0`: per its
  javadoc it "Affects only returned statistics" (per-chat breakdown in the
  response), not eviction, and we don't read those statistics.
- **Several smaller TDLib-layer races and divergences from maintainer
  guidance**:
  - `MediaCache.invalidate` peeked `slot.value !is Ready` outside the
    reducer's single-writer loop; a concurrent UpdateFile flipping the slot
    Ready→Downloading between the peek and the launched coroutine could let
    invalidate ressetting an already-restarted download. New
    `FileEvent.ResetIfReady` runs the is-Ready check + state flip inside
    the reducer so the two steps happen against the same serial event
    stream.
  - `MediaCache.schedulePostCompletionResync` left finished Job references
    in `postCompletionResync` forever after a natural completion (the
    map.remove only fired on the reducer's terminal-transition cleanup
    path). Now the job's own try/finally compare-and-removes its entry,
    and a follow-up schedule for the same fileId can't race against a
    dead Job.
  - `loadChannelHistory` set the 60 s cooldown on every success, including
    "success-shaped no-op" (chat became inaccessible mid-load, empty
    GetChatHistory response). Now the cooldown is gated on at least one
    mapped post landing — a transient empty success no longer locks a
    channel out of refreshes for a full minute.
  - `searchInChannel` swallowed all failures into an empty result list,
    leaving the user wondering whether the channel really had no matches
    or whether the query failed (FLOOD_WAIT, transient TDLib reject).
    Errors now route through the same `UserMessageBus` other user-initiated
    operations use.
  - `TdLifecycleBridge.bind()` seeded `_networkType` after registering the
    NetworkCallback; a callback firing between register and seed could be
    overwritten by the seed's snapshot. Order swapped: seed first, then
    register, so any callback emission wins (which is the desired outcome
    — it's the more recent observation).
  - `PostsRepository.handleChatTitle` / `handleChatPhoto` now mutate the
    cached `TdApi.Chat` through `ConcurrentHashMap.compute()` rather than
    a bare `chatCache[id]?.let { it.title = … }` — bucket-locked replace
    keeps cross-field updates serialised against concurrent compute()s
    (single-field ref-writes were already atomic, this is hardening).
  - `GetChats(limit=500)` clipped power users with >500 channels;
    `Int.MAX_VALUE` is the canonical local-cache pagination since the
    actual ceiling is enforced by `LoadChats` page count above.
  - `CommentsRepository.ensureAnchor` ran a `GetMessageProperties` probe
    even for standalone (single-id) posts; the probe exists exclusively
    for album disambiguation per tdlib/td#2312. Standalone posts now go
    directly to `GetMessageThread`, saving one JNI hop on every first
    comments-open.

### Changed
- **TDLib log stream is now configured explicitly via `SetLogStream`**.
  Default TDLib behaviour is to log to stdout, which on Android disappears
  into /dev/null — making non-zero `LOG_VERBOSITY` useless for debugging.
  Debug builds now write a 5 MB rotating log under
  `filesDir/td-logs/td.log` (with stderr redirected into the same file)
  so a TDLib-internal complaint during development is actually
  observable. Release builds use `LogStreamEmpty()`, which combined with
  `LOG_VERBOSITY=0` (fatal-only) means zero log I/O on the hot path.
- **Read-only-client TDLib options applied on every goOnline**.
  `disable_top_chats=true` (Hortay never surfaces "frequently contacted
  users" UI, so TDLib stops maintaining the local heuristic) and
  `notification_group_count_max=0` (we don't run TDLib's notification
  subsystem at all). Both options are non-persistent across
  AuthorizationStateClosed → reopen, so reapplying on each goOnline
  keeps the daemon aligned without us having to track which options
  need re-application after re-auth.

### Added
- **Read-state sync with the official Telegram client (TDLib mode)**. As the user
  scrolls the merged feed, posts that stay in the viewport for ≥1 s get acked via
  `viewMessages(forceRead=true, source=MessageSourceChatHistory)` — the canonical
  closed-chat read path per tdlib/td#46 and tdlib/td#219, advancing
  `lastReadInboxMessageId` so the channel's unread badge in the official Telegram
  client clears as the user reads here. A 1 s dwell threshold (driven by
  `collectLatest { delay(1000) }`) prevents incidental flicker from zeroing
  badges; explicit taps (open comments / open media / open in Telegram) ack
  immediately, since a tap is a stronger reading signal than dwell. Comments
  overlay added the equivalent dwell-ack against the discussion thread chat,
  fixing the prior gap where comments never advanced the discussion group's read
  pointer at all. Replaces the previous "view counter only, never advance read
  state" stance — kept earlier as a load-bearing UX choice but the user
  explicitly opted into full read-state sync.
- **Realtime reactions / views / comment counts on the post under the user's
  gaze**. Per tdlib/td#2312 (Aliaksei Levin: *"if you view messages in an opened
  chat, their reactions will be eventually updated"*), live interaction-info
  updates only flow for chats currently OpenChat'd in TDLib. The merged feed
  used to hold zero chats open, leaving update delivery to TDLib's lazy baseline
  channel and producing the "reactions sometimes don't move" symptom. A new
  dwell-driven focus tracker (`FOCUS_DWELL_MS = 1500`) in `TimelineScreen` keeps
  exactly **one** merged-feed chat open — the one carrying the topmost visible
  post — and transitions it cleanly as the user scrolls between channels. Honours
  the maintainer's "usually one chat opened" invariant from tdlib/td#2695, and
  composes safely with the existing channel-filter OpenChat (refcount in
  `ChatPresence` deduplicates) and the discussion-group OpenChat in
  `CommentsRepository.threadFlow`. The open/close swap runs under
  `NonCancellable` so a fast scroll cancelling collectLatest mid-RPC can't leak
  refcount drift between TDLib and our local counter.

### Architecture
- `ChatPresence` is now the single facade for all OpenChat / CloseChat /
  ViewMessages traffic. Both `PostsRepository.viewMessages` and
  `CommentsRepository.viewMessages` route through `ChatPresence.viewMessages(...)`
  with explicit `MessageSource` and `forceRead` per use case (channel feed:
  `MessageSourceChatHistory` + `forceRead=true`; comments: 
  `MessageSourceMessageThreadHistory` + `forceRead=false` because the thread
  chat is already opened by `withOpenChat`). One log tag for read-state TDLib
  traffic, one place to audit policy, no chance of the two repos drifting on
  forceRead semantics.
- **Auto-download media settings (TDLib mode), TG-style**. New "Авто-завантаження
  медіа" section in Profile opens a two-level screen mirroring Telegram's
  "Data and Storage" UX: three category rows (Wi-Fi / Mobile / Roaming), each
  with a one-line summary like "Photos, Videos to 10 MB, GIFs". Tapping a row
  opens a sub-screen with three Material 3 toggles (Photos / Videos / GIFs) and
  a discrete-step Slider for the video size cap (1–500 MB, snapping to TG's
  bucket set). Defaults match Telegram-Android exactly: liberal on Wi-Fi
  (videos to 100 MB), 10 MB cap on mobile, all off on roaming. Persisted as a
  single JSON blob in DataStore (`AutoDownloadStore`) so a category edit can
  never tear across a process kill. Sub-screen uses M3 `LargeTopAppBar`,
  `ListItem`, `Switch`, `Slider`, with a shared-x slide between list and
  category for hierarchical depth. OS-level Data Saver is honoured: when the
  system toggle is on, the category screen surfaces a contextual banner and the
  resolver pauses videos / GIFs on metered networks regardless of per-network
  preference.
- **Background media prefetch driven by the auto-download policy**.
  `MediaAutoDownloader` observes `PostsRepository.posts`, diffs the head against
  a `Set<(chatId, messageId)>` of already-considered ids, and dispatches new
  arrivals through `MediaCache.ensure(fileId, DownloadPriority.Prefetch)`. The
  Prefetch class is priority 8 in the existing `DownloadPriority` ladder —
  never blocks visible-media downloads (16) or the fullscreen viewer (32), and
  TDLib's per-DC slot pool serialises behind both naturally. If the user
  scrolls into a prefetched card, the Composable's own `ensure(VisibleMedia)`
  upgrades the in-flight job's priority in place — `MediaCache.ensure` is
  idempotent, the file does not restart. Videos honour `videoMaxBytes` from
  the active policy; unknown-size videos (TDLib hasn't probed yet) are skipped
  conservatively to avoid leaking 100 MB clips through on mobile. Photos are
  always allowed when the toggle is on (thumbs are 30–300 KB). Settings
  changes don't cancel inflight downloads — cancelling half-finished bytes
  just wastes the trip already made.
- `HortayNetworkType` enum exposed as a `StateFlow` from `TdLifecycleBridge`,
  distinguishing `Wifi` / `Mobile` / `Roaming` / `None` (finer-grained than
  TDLib's `TdApi.NetworkType`, which doesn't model roaming). Roaming detection
  uses `NET_CAPABILITY_NOT_ROAMING` from `NetworkCapabilities`.

### Build
- **Native debug symbols for libtdjni.so now flow into release AABs
  automatically**. Play Console flagged 0.2.0 with "you have not
  uploaded debug symbols" — TDLib's vendored `.so` files were
  pre-stripped by the build pipeline, so AGP's
  `debugSymbolLevel = "FULL"` had nothing to extract and the AAB
  shipped without `BUNDLE-METADATA/com.android.tools.build.nativeDebug…`.
  `scripts/update-tdlib.sh` now defaults `KEEP_DEBUG=1` and
  additionally extracts the unstripped binaries from `tdlib-debug.zip`
  into `libtdlib/build/tdlib-unstripped/<abi>/` (gitignored). The
  `libtdlib` module's `sourceSets.main.jniLibs.srcDirs` lists this
  overlay directory AFTER the committed stripped source, so AGP's
  "last srcDir wins" merge picks unstripped libs when the overlay is
  populated and AGP pulls debug symbols from them at bundle time. Devs
  who haven't run `update-tdlib.sh` keep the existing fast `git clone`
  + build experience (stripped libs are still committed; overlay is
  optional). The 200+ MB-per-ABI unstripped binaries never enter git
  history. Release runbook is now: `./scripts/update-tdlib.sh` → commit
  → `./gradlew :app:bundleRelease` → upload — Play Console gets
  symbolicated native crash / ANR stacks for libtdjni.so end-to-end.

### Added
- **2FA password recovery in-app**. The 2-factor password screen now
  surfaces a "Не пам'ятаю пароль" / "Forgot password" link. When the
  account has a confirmed recovery email on file (TDLib's
  `hasRecoveryEmailAddress`), tapping it asks Telegram to email a reset
  code (`RequestAuthenticationPasswordRecovery`), then swaps the form
  to a one-shot recovery-code input that calls
  `RecoverAuthenticationPassword`. When no recovery email is set, the
  link surfaces a clear "use another device" info card instead of
  routing the user into a TDLib error. Previously the screen read the
  hint and nothing else — `passwordHint` was the only field plumbed
  through `AuthStage.WaitPassword`, so a user who forgot their 2FA
  password was permanently locked out from inside the app and had to
  reset on another device with no UI affordance pointing them there.

### Fixed
- **MediaCache user-action writes raced the single-coroutine reducer**.
  `cancelExplicit`, `retry`, and `invalidate` wrote
  `states[fileId].value = MediaState.Idle` directly from a fresh
  `scope.launch(ioDispatcher)`, bypassing the documented
  single-writer-via-`fileEvents`-channel invariant. A concurrent
  `UpdateFile` reducer drain on a different IO thread could land
  AFTER the user-action's Idle write and flip the slot back to
  Downloading, producing a brief flicker of the spinner the user just
  dismissed. Routes the resets through a new
  `FileEvent.Reset(fileId, target)` channel message so they execute
  inside the reducer loop in strict order against any queued
  `UpdateFile` events. The reducer's terminal-cleanup logic
  (priority/tracks reset for non-Downloading targets) is mirrored in
  the new `reduceReset` helper so subsequent `ensure()` calls start
  from a clean slate.
- **"At-top" pill behaviour broke after switching channel filter**.
  The `atTop` derivedStateOf in `TimelineScreen` had no `remember`
  key, so it captured `globalListState` on first composition and kept
  observing its scroll position even after the user entered a channel
  filter (which swaps the active list to `filterListState`). The
  result: while inside a single-channel filter, the "новi пости" pill
  auto-accept gate and pill visibility both reflected whether the
  GLOBAL feed was at the top — not the active filter. Keying on
  `listState` brings it in line with `scrollGate` and `prefetchAnchor`
  further down (which were already keyed correctly).
- **Adding a guest-mode channel kicked a full N-channel sweep**. 
  `WebFeedSource.subscribeAndRefresh` wrote the new username to the
  repository and then to DataStore via `subscriptions.add`. The
  DataStore emission landed in `handleSubscriptionsChanged`, which
  diffs against `lastKnownSubscriptionsSet` and saw the new username
  as "added" — kicking `doRefresh(force=true)` over EVERY subscribed
  channel on top of the targeted `fetchOne` we already started. On a
  200-channel set the user's "add this one" tap fired hundreds of HTTP
  requests instead of one. `subscribeAndRefresh` now pre-populates
  `lastKnownSubscriptionsSet` under the same mutex
  `handleSubscriptionsChanged` takes BEFORE the DataStore write, so
  the imminent diff observes a no-op and the targeted fetch stands
  alone.
- **Private guest-mode channels surfaced as "network error"**. With
  `followRedirects=false` (intentional, so we can detect the t.me/s/
  redirect), Telegram emits 301/302 to `t.me/<u>` for private
  channels — but the response handler only checked for 403, then fell
  through to `else → NetworkError(IOException("HTTP 302"))`. The
  comment at the redirect-disabling site even called this out as the
  intent. Now 301 / 302 / 307 / 308 with a Location pointing at bare
  `t.me/<u>` (no `/s/`) classify as `PrivateChannel`; redirects
  pointing elsewhere stay `NetworkError` so we don't silently absorb
  breaking edge changes as "private".
- **Tier-2 web-mode polling died silently on a single failed sweep**.
  `WebFeedScheduler.startTier2`'s loop body had no try/catch; one
  uncaught throw out of `feedSource.refreshAsync(...).join()` killed
  the loop without restart, and only re-fired on the next
  background→foreground transition (i.e. the user had to actively
  switch away and back). Wrapped each iteration in a defensive
  catch — only an explicit cancellation breaks the loop now, every
  other throwable logs and continues so the next adaptive-backoff
  delay still fires. Also tightened `bind()` so a duplicate call
  doesn't register a second `foreground.onEach` collector — the KDoc
  promised idempotency but the implementation didn't enforce it.
- **Empty channel title flipped a guest-mode channel to ParseFailure
  for hours**. `TmePageParser.parseChannelInfo` returned `null` when
  the title `<span>` was empty; `WebFeedSource` then stamped the
  channel with `ParseFailure` status, sticky until the next sweep
  picked up a non-empty title. Telegram briefly serves an empty title
  during channel renames (CDN-propagation gap, observed for tens of
  minutes on busy channels). Falls back to `@<username>` from the URL
  handle, which is always present on a valid `/s/` page; the next
  sweep replaces it with the real title.
- **Single malformed `published_at_ms` pinned a guest-mode post at
  epoch 1970 forever**. `WebRepository.parseIsoToMillis` returned
  `0L` on parse failure; the feed's `published_at_ms DESC` ordering
  then dragged the post to the bottom of the channel and skewed
  merged-feed chronology. Now returns null, callers fall back to
  `fetchedAtMs` (the wallclock at the moment the page was received) —
  keeps the post within minutes of its real publish time instead of
  decades off.
- **Migration partial-failure left the user permanently stranded**.
  `MigrationCoordinator.confirm` called `markProposalShown()` at the
  end of every run, including ones where some `JoinChat` /
  `SearchPublicChat` calls failed (FLOOD_WAIT, transient network).
  The failed channels were silently dropped from `migrated` and never
  re-offered — there was no UI path to retry, the proposal simply
  never reappeared. Now distinguishes "permanent skip" (TDLib reports
  no such public chat — wrong handle / deleted) from "transient
  failure" (any throw), and only suppresses the proposal when every
  requested handle either succeeded or was a permanent skip. Any
  retryable failure leaves the proposal pending so the next
  auth.Ready re-evaluates and re-offers just the failed subset
  (the existing `alreadyMigrated` filter excludes successes). Also
  added cooperative cancellation between confirm-iterations so a
  parent-scope cancel (sheet swipe-down, sign-out mid-run) bubbles
  through within one `THROTTLE_MS` window instead of waiting for the
  full ~30 s drain.
- **Album view counts could briefly tick backward as
  `UpdateMessageInteractionInfo` updates streamed in (TDLib mode)**.
  For an album, every member can fire its own
  `UpdateMessageInteractionInfo` against the merged anchor's slot
  (album-aware lookup routes them all there). The per-member
  `viewCount` can lag — TDLib catching up after a reconnect, or one
  member's counter slightly behind the aggregate. The flush used to
  blindly assign `views = info.viewCount`, so a delayed lower-count
  update could downgrade a card that already showed a higher number.
  Telegram view counts are monotonically non-decreasing per message,
  so the flush now takes `maxOf(post.views, info.viewCount)` —
  protects against per-member lag AND against burst-ordering between
  members that emit updates within the same coalesce window.
- **Album anchor flipped between sessions, breaking reactions, comments
  and feed visibility (TDLib mode)**. The merged-album anchor was picked
  via `sortedBy { it.date }` — but Telegram emits every member of a
  burst-posted album with the SAME `date` (whole-second resolution),
  so a stable sort just preserved whatever input order the upstream
  stage handed us. That order varied between refresh, snapshot restore,
  live ingest, debounce flush and pagination, so the anchor flipped
  per path. Three knock-on regressions:
  (1) `LazyColumn` re-keyed the card mid-scroll (`post.id` is the key).
  (2) The card disappeared from the visible feed —
  `TimelineViewModel.seenPostIds` tracks the previous anchor.id, and a
  flipped anchor falls out of the seen filter and shows up under the
  "новi пости" pill instead of the visible list. The user-visible
  symptom: "пропадає все окрім одного" after some refreshes.
  (3) Reactions and comments stopped working — per tdlib/td#2312
  (Aliaksei Levin: *"Only the first message in an album can receive
  reactions. Apps aren't supposed to send reactions for other album
  messages."*), TDLib silently rejects `AddMessageReaction` /
  `RemoveMessageReaction` against any non-first member. Our reaction
  toggle picked `albumMessageIds.first()`, and with the date-stable
  sort that was usually the **newest** member (GetChatHistory returns
  newest-first), so the reaction RPC went to the wrong message id, the
  server rejected it, no `UpdateMessageInteractionInfo` followed, and
  the reaction count never moved. `commentCount` aggregation worked
  but the per-card "first id" used for thread-anchor probes drifted.
  Fixed by sorting album members on `it.id` instead. TDLib message ids
  are monotonic per chat, and an album lives in one chat by
  construction (`groupBy { chatId to mediaAlbumId }`), so the lowest
  id is the canonical "first message" — same identity TDLib uses for
  reactions / replies / view receipts. Locked in
  `PostFilterStrategyTest` with a permutation-invariance test that
  exercises ascending, descending and shuffled inputs.
- **Photo albums collapsed to a single photo after restarting the app
  (TDLib mode)**. Closing and re-opening Hortay would surface a
  previously-merged 5-photo album as a 1-photo card, with the other
  members never reloading. Three pile-on bugs in the same merge logic:
  (1) `refreshLocked`'s post-update merge dropped a known-merged anchor
  whenever any of its `albumMessageIds` was in the fresh `raw` batch —
  but if `coalesceAlbumFragments` came up short (transient FLOOD_WAIT,
  members aged past TDLib's local-store window), `raw` would carry only
  one member of the album; `mergeAlbumMembers` then passed a 1-member
  group through unchanged and the user saw a 1-photo card. (2) The next
  `saveSnapshotNow` persisted the corrupted state with
  `albumMessageIds=[]`, so the next cold start restored 1 message and
  could never re-discover the missing siblings — stable visible
  corruption. (3) Pagination paths (`loadOlder`, `loadChannelHistory`)
  deduped fresh raw rows by `(chatId, anchor.id)` only; non-anchor
  album members slipped past, and PostFilterStrategy re-merged anchor
  + 4 stray fragments into a 9-item double-rendered card. All three
  collapse into one canonical merge, `foldRawIntoCurrent`: it counts
  raw album coverage against `current`'s known `albumMessageIds.size`,
  drops the partial raw fragment instead of replacing the merged
  anchor, and matches the de-dup against EVERY known member id rather
  than just the anchor. `restoreFromSnapshotInternal` now also pipes
  each chat's slice through `coalesceAlbumFragments` before mapping —
  even already-corrupted snapshots self-heal on the next cold start
  (the surviving orphan member becomes a single-member group, the
  surround fetch pulls its siblings, the merged card rebuilds). Locked
  with seven new unit tests in `FoldRawIntoCurrentTest`.
- **Reactions, view counts and comment counts stopped updating on
  photo-album posts in TDLib mode**. `flushPendingInteractionInfo`
  matched buffered `UpdateMessageInteractionInfo` events against
  `post.id` only, but for an already-merged album `post.id` is the
  anchor (oldest member) — TDLib emits these updates against any
  sibling. Non-anchor events were silently dropped; the user-visible
  symptom was reactions never appearing on photo albums (TDLib doesn't
  fill `MessageReactions` in the initial `GetChatHistory` response,
  reactions only stream in via these updates after the fact). The flush
  now builds a `(chatId, memberId) → postIdx` index covering anchors
  AND every `albumMessageIds` entry once per drain, so each event lands
  in O(1) regardless of which sibling it referenced — same album-id
  normalisation `handleEdited` / `handleIsPinnedChanged` /
  `handleContentChanged` already do via `updateOnePostByAnyMemberId`.
- **Feed stayed sparse after migrating guest subscriptions on sign-in**.
  `MigrationCoordinator.confirm()` issued `JoinChat` for each accepted
  channel but never asked `PostsRepository` to refresh, so
  `GetChatHistory` for the freshly-joined chats only ran on the next
  cold start — the user saw an empty / partial feed and had to relaunch
  the app for the migrated channels to populate. The coordinator now
  triggers a full `refresh()` once at least one chat was migrated, so
  history loads in the same session the user authenticated in.
- **Migration proposal sheet — confirm button text wrapped to two lines
  and looked clipped**. The two-button row paired
  "Більше не показувати" / "Don't show again" with "Підписатися (N)" /
  "Subscribe (N)" under `Arrangement.End`; on a 360 dp sheet the pair
  doesn't fit two-up and Compose collapsed the primary's label onto a
  second row inside the button. Switched to a Material-3 stacked layout
  (primary on top, dismiss below, both `fillMaxWidth`), which keeps the
  touch targets honest at any locale length.
- **Inline custom-emoji rendered as transparent dead air for several
  seconds in TDLib mode** while waiting on the actual sticker file. The
  `f4fe626` placeholder commit hid the loading disc the moment
  `CustomEmojiRepository` resolved metadata — fine in web mode where
  metadata-resolution dominates the wall clock (HTTP to
  `t.me/i/emoji/<id>.json`), but in TDLib mode `GetCustomEmojiStickers`
  is local-fast (~50 ms), so the disc flashed and the user then sat on
  an empty box for several more seconds while `MediaCache` actually
  downloaded the .tgs / WEBP-thumb file. `CustomEmojiInlineView` now
  observes the first user-visible file's `MediaState` and keeps the
  placeholder mounted as an overlay until that file lands as `Ready` —
  the underlying renderer (LottieStickerView / TdMediaImage) stays
  composed underneath so its download keeps running. Web mode is
  unchanged because URL-only fileIds short-circuit to "ready as soon as
  the resolver lands" (Coil and `LottieUrlStore` own their loading
  visuals from there). Also fixes a separate dead-air case for Webm
  custom emojis that arrived without a thumbnail and weren't in the
  `animateAlways` picker context — the inline view literally had no
  rendering branch for them, so they painted nothing; now the
  placeholder stays for the duration.

## [0.2.0] — 2026-05-06

### Fixed
- **Inline-preview videos showed a 2–3 s black square** before the first
  frame, even on rewind / re-scroll where the file was already buffered.
  Root cause: the underlying `PlayerView` wraps a `SurfaceView`, an opaque
  hardware overlay that paints solid black until the decoder ships its
  first frame. The previous `setShutterBackgroundColor(TRANSPARENT)`
  worked on the foreground shutter but did nothing about the SurfaceView
  itself. Split the renderer: fullscreen viewer (with controls) keeps
  `PlayerView` for its scrubber widgets, but the inline-preview path now
  uses a bare `TextureView(isOpaque = false)`. The texture is transparent
  until the first frame paints over it, so the poster (drawn underneath
  in `MediaWithSpoiler`) reads through cleanly during the prepare window.
- **Web previews with `link_preview_right_image` had no thumbnail**. The
  parser only matched the hero / video-thumb shapes and silently dropped
  the third variant t.me uses for sites whose `og:image` is too short to
  fill the card.
- **Blockquote spacing — extra blank line above and below**. The walker
  injects `\n\n` around block elements and the `RichText` segmenter
  added an 8 dp `Spacer` on top of that, doubling the visible gap. The
  spacer is now conditional: skipped when either side of the boundary
  already carries a `\n` from the source HTML (so `<br><br><blockquote>`
  renders as one paragraph break, not two), kept when neither side has a
  natural break.
- **`parseUsernameFromInput` rejected short Telegram handles**. Lowered
  the regex floor from 5 to 2 characters across all three input shapes
  (`tg://resolve?domain=`, `t.me/<name>`, `@<name>`). Fragment-auctioned
  and Premium-short handles like `@io` / `@no` now pass validation
  before the network round-trip.
- **"Media is too big" video posts now read as videos, not photos**. The
  parser used to emit them as `Kind.Photo` (no play badge, in-app gallery
  on tap that spun on an empty URL). They now emit as `Kind.Video` with
  an empty `url` sentinel; the UI shows the poster with a soft 8 dp blur,
  a centred play badge, an "Open in Telegram" hint chip, and routes the
  tap straight to the Telegram client.

### Changed
- **`AddChannelSheet` auto-pastes from the clipboard**. If the clipboard
  contains a valid Telegram link / `@handle` when the sheet opens, we
  fill the input and trigger lookup automatically — the user lands on
  the preview card with the "Add" button without a manual paste step.
  Privacy-gated through the same regex that validates manual input;
  random clipboard contents fall through silently.

### Added
- **Cross-channel local search (guest mode)**. A search action surfaces in the
  Feed top bar; tapping it opens a full-screen overlay with an auto-focused
  query field. Matches stream straight from `web.db` via the existing indexed
  `LIKE` on `text_plain` — never hits the network, so results are scoped to
  what the user has already seen scroll past in the feed. Query input is
  debounced 220 ms before reaching SQLDelight, then `flatMapLatest` cancels the
  in-flight subscription on every keystroke so the DB never serves a stale
  result set. Minimum query length of 2 chars filters out trivial single-letter
  bursts that would otherwise pull the entire post table on first tap. Result
  rows reuse [`PostCard`] verbatim, so a hit looks identical to the same post
  in the feed (long-press action sheet, share / copy / bookmark, tap → open in
  Telegram). TDLib mode is unchanged — its in-channel search via
  `SearchChatMessages` lives at the `TimelineTopBar` level and stays the only
  search affordance there.
- **Animated TGS custom emojis in guest mode**. The web-mode emoji resolver
  now publishes the real `StickerFormat.{Tgs|Webm|Webp}` instead of forcing
  every asset down the static-WEBP path.
  - `LottieUrlStore` fetches the `.tgs` payload directly from the t.me CDN
    (sniffs the gzip header so it handles both pre-decompressed JSON and raw
    `.tgs` blobs), parses through `LottieCompositionFactory`, and feeds the
    same `LottieAnimation` pipeline TDLib mode uses.
  - WebM custom emojis intentionally stay on the static-WEBP-thumb path
    (same as TDLib mode at inline size). The t.me/i/emoji endpoint serves
    WebMs pre-rendered as `yuv420p` baked against `srgb(0,0,0)` — the
    original alpha lives in a Matroska BlockAdditional sidecar VP9 stream
    that standard Android `MediaCodec` doesn't decode. We tested a luma-key
    fragment shader (`alpha = max(r, g, b)`, the same trick Telegram Web's
    CSS `mix-blend-mode: lighten` runs) but it can't distinguish "background
    black" from "intentionally black glyph parts" (a black pupil, a navy
    outline) and ate them indiscriminately. The only artefact-free animated
    path is `media3-decoder-vp9` (libvpx ext, ~10 MB native libs × 2 archs)
    which is disproportionate for inline-emoji size. The static WEBP thumb
    Coil already caches has proper alpha (VP8X + ALPH chunks), renders
    instantly, and visually matches TDLib mode exactly.
  - `LocalWebHttpClient` shares the guest-mode OkHttp client (with its 10MB
    ETag cache) across the parser, the emoji resolver, and the Lottie URL
    store — connection-pool reuse + warm cache cuts a cold sweep of TGS
    emojis ~80–90% on the second visit.
- **Anonymous → authenticated migration proposal**. After the user signs in,
  a one-time bottom sheet lists every guest-mode subscription with a
  per-channel checkbox (Material 3 `ModalBottomSheet`). Subscribing is
  opt-in — never automatic — because joining N channels in the second after
  sign-in would observably page Telegram's flood-control. Confirmation
  throttles `SearchPublicChat` + `JoinChat` at 1 channel/second with live
  progress, persists "shown" + per-username "migrated" state in
  `MigrationStore` (DataStore), and auto-dismisses on completion. Localised
  EN/UK with proper plurals.

- **Anonymous reading mode** ("гостьовий режим"). Read public Telegram channels
  without signing in: parser ingests `https://t.me/s/<username>` previews, posts
  surface in a Twitter-style feed, custom emoji and reactions render
  client-side. Mode-switching from `AuthScreen` ("Без входу — читати публічні
  канали" CTA) flips a `GuestModeStore` flag in DataStore; the user can sign in
  later from inside web mode and subscriptions persist across the transition.
  All anonymous-mode network access uses a desktop-Chrome User-Agent and
  Telegram never sees a user identifier — IP is the only fingerprint we leak.
- `WebDatabase` (SQLDelight 2.3.2) for the anonymous pipeline. Schema covers
  channels, posts, custom-emoji resolution cache and curated/recent
  suggestions. WAL mode + foreign keys + denormalised `published_at_ms` index
  give the merged-feed query a single sequential disk read on the hot path.
  FTS5 was planned but dropped — Samsung / Pixel SQLite builds ship without
  the module; search falls back to indexed `LIKE` on `text_plain` (~100 ms on
  5K posts) until we bundle SQLite.
- `WebRepository` — single DAO surface with `Flow`-driven observers (SQLDelight
  `asFlow` + `mapToList`) so UI screens never manually invalidate. Atomic
  `ingestPage()` runs the channel upsert + N post upserts in one transaction
  per fetched page; observers fire exactly once per logical change.
- `WebFeedSource` — multi-channel orchestrator. Mirrors `SubscriptionsStore`
  intent into the channel table, fans out parallel fetches under a
  `Semaphore(6)`, and exposes the merged feed as an eager `StateFlow`. Stale
  CDN media URLs (Telegram rotates signed tokens hourly) drive automatic
  `FORCE_NETWORK` on the next sweep when the per-post `fetched_at_ms` exceeds
  a 4-hour TTL.
- `WebFeedScheduler` — tier-2 foreground polling that runs `refresh(force=false)`
  every 5 minutes while the app is visible. Auto-pauses on background.
  Tiers 1 (viewport-driven) and 3 (WorkManager background + Wi-Fi-only opt-in)
  deferred to a later milestone.
- OkHttp `Cache` (10 MB DiskLruCache under `cacheDir/web-http/`) replaces the
  in-memory `cacheState` HashMap. Conditional GET (`If-None-Match` /
  `If-Modified-Since`) now persists across cold starts — a 200-channel
  morning sweep runs ~80–90 % cheaper after the first day.
- `WebTimelineScreen`, `AddChannelSheet`, `WebSettingsSheet`,
  `WebModeScaffold` — production guest-mode UI. Pull-to-refresh,
  curated-channel suggestions per locale, smart-paste validator with
  clipboard hint, lookup-before-subscribe confirmation card.
- English localization. All user-facing strings extracted to `res/values/strings.xml`
  (English, default fallback) and `res/values-uk/strings.xml` (Ukrainian). Android
  picks the locale automatically from system settings; non-Ukrainian devices now see
  English. TDLib's `systemLanguageCode` follows `Locale.getDefault().language` so
  server-localized payloads (country picker, error messages where applicable) match
  the active app locale instead of always being Ukrainian.
- `<plurals>` resources for grammatically correct count formatting — `new_posts`
  (1 / 2-4 / 11-14 forms in Ukrainian, one/other in English), `service_boost` and
  the `duration_minutes` / `duration_hours` flood-wait helpers. Replaces ad-hoc
  `mod10/mod100` switches that previously hard-coded Ukrainian plural rules.
- `StringResolver` interface (data layer): thin abstraction over
  `android.content.res.Resources` so repositories don't import Android types
  directly and JVM-only unit tests can supply a deterministic fake without
  Robolectric or mockito.
- Animated stickers (TGS / WebM / WEBP), animated emoji and custom-emoji
  reactions. Pipeline reuses TDLib's batched `GetCustomEmojiStickers` (up to
  200 ids per call, 50ms debounce) and serves the WEBP/PNG `Sticker.thumbnail`
  as an instant placeholder while the animation downloads. TGS playback is
  Lottie-Compose with a 32-entry LRU cache of parsed compositions and a 5 MB
  gunzip safety cap; WebM playback is ExoPlayer-backed, looped, muted and
  lifecycle-aware (pauses on `ON_PAUSE`, fully releases on dispose). Inline
  custom emojis in formatted text are wired through Compose `InlineTextContent`
  with `needsRepainting`-aware tint via Lottie's COLOR_FILTER dynamic property.
- "Сховище і трафік" section in Settings — surfaces TDLib's network usage and
  storage footprint with a one-tap "Clear cache" action.
- Twitter-style "новi пости" floating pill at the top of the feed: stacked
  channel avatars + count, tap to scroll-to-top and reveal new content. The
  visible feed is frozen on what the user has already seen until they
  explicitly accept (pill tap or pull-to-refresh).
- Process-wide TDLib lifecycle bridge (`TdLifecycleBridge`): toggles
  TDLib's `online` flag with foreground state and tracks the actual
  `NetworkType` via `ConnectivityManager`. Push-safe (does not force
  `NetworkTypeNone` on background).
- ProGuard / R8 keep rules for TDLib's JNI surface, `kotlinx.serialization`
  companions, Compose stability annotations, and coroutines metadata.
- Material 3 predictive back gesture for the comments overlay. The screen now
  translates ~10% in the swipe direction, scales to 0.9 and fades to 0.7 alpha
  under the user's finger instead of snapping closed on release. Driven by
  `PredictiveBackHandler` + an `Animatable` so cancelled gestures rewind
  smoothly and committed ones finish the dismissal animation before unmounting.
  Honours both LEFT and RIGHT back-edge configurations — the transform pivot
  anchors to the swipe edge so the screen "hinges" away from the thumb.

### Changed
- Comments overlay now opens instantly when reopened within 30 seconds.
  First-load anchor resolution is permanently cached for the session.
- Re-opening the app no longer triggers a full 200-channel TDLib refresh
  unless the cached feed is older than 60 seconds.
- Re-entering a channel filter within 60 seconds reuses the previous
  `GetChatHistory(80)` result instead of re-fetching.
- Coil memory cache lowered from 20% to 10% of Java heap — safer on
  low-end devices, leaves headroom for our state holders.
- TDLib log verbosity is now `error` on debug builds and `fatal` on
  release.

### Build
- Release/Beta packaging now fails at task-graph time when
  `keystore.properties` is missing instead of silently producing an
  unsigned APK. Debug builds and `lintRelease` continue to work without
  a keystore.
- `compose-ui-tooling-preview` moved from `implementation` to
  `debugImplementation` (canonical Google split). No `@Preview`
  annotations exist in production source, so the preview API was dead
  weight in release.

### Fixed
- **Guest-mode post text formatting** — every reported regression in one
  pass: leading-space-on-paragraph drift, missing line breaks between
  paragraphs ("ліпиться в одне"), 1-2 char span offsets after styling,
  reply-preview text occasionally rendered in place of the actual post body.
  Replaced the gradually-grown walker (boundary checks + ad-hoc
  `ensureLineBreak` + `text.toString().trim()` at the end) with a strictly
  two-phase HTML→FormattedText pipeline:
    1. `emitVerbatim` walks the Jsoup tree and emits text + spans WITHOUT
       any whitespace normalisation. Block-level wrappers (`<div>`, `<p>`,
       `<blockquote>`, `<pre>`) emit a paragraph break (`\n\n`) on both sides;
       `<br>` emits a single `'\n'`; TextNodes emit verbatim. Walker is
       purely structural — never decides what whitespace is "phantom".
    2. `normaliseWhitespace` does a single linear pass that collapses inline
       whitespace runs, strips whitespace adjacent to `'\n'`, caps consecutive
       newlines at 2 (one blank line max), and trims leading + trailing
       whitespace. Every source position records `srcToDst[i] = out.length`
       in an explicit IntArray; spans re-anchor through that table so
       offsets stay correct under every collapse / drop. Idempotent.
  Side-fix in `TmePageParser`: `selectFirst(".tgme_widget_message_text")`
  was returning the reply-preview block when one was present (it shares
  that class with the modifier `js-message_reply_text`). The new selector
  walks `:not(.js-message_reply_text)`, filters out anything inside a
  `.tgme_widget_message_reply` ancestor, and prefers the innermost
  `.tgme_widget_message_text` (Telegram wraps its post body in a double
  div with the same class).
  Twelve snapshot tests in `WebPostAdapterFormattingTest` lock the
  contract: every span's `text.substring(start, end)` matches the expected
  glyph, paragraphs never begin with phantom whitespace, runs of newlines
  cap at 2, and re-running the parser on its own output is idempotent.
- `MediaCache` no longer logs noisy warnings when a Composable leaves
  composition mid-download (`LeftCompositionCancellationException`).
- Crash on `UpdateMessageInteractionInfo` events with a null payload —
  the new coalescing buffer correctly skips them instead of inserting
  null into a `ConcurrentHashMap`.

### Performance
- `flushPendingInteractionInfo` now does a single O(feed) pass with O(1)
  per-post hash lookups instead of `indexOfFirst` per drained event. On a
  burst that touches 100 posts in a 1000-post feed the worst case drops
  from ~100 000 comparisons to ~1100. Coalescing window and semantics are
  unchanged; updates that arrive during the flush are picked up by the
  next `compareAndSet`.
- Discussion threads share one `WhileSubscribed` SharedFlow per anchor —
  reopening the comments overlay no longer re-runs `GetMessageProperties`
  + `GetMessageThread` + a full history load.
- Feed storage uses a `PersistentList` from `kotlinx.collections.immutable`
  — per-event mutations from `UpdateMessageInteractionInfo` are now
  O(log N) structural updates instead of O(N) array copies.
- `UpdateMessageInteractionInfo` events coalesce in a 200 ms window — one
  batched `_posts.update {}` per window rather than dozens of separate
  emits during news bursts.
- `UpdateFile` progress events throttle to 10 Hz per file — TDLib's 30+ Hz
  sub-pixel-progress emits no longer recompose every visible thumbnail.
- `MediaCache` no longer materialises a `MutableStateFlow` slot for every
  TDLib `UpdateFile` — slots are created only when a Composable observes a
  fileId. Long sessions used to leak slots proportional to TDLib's
  internal file table.

### Architecture
- `web.db` migration pipeline is now end-to-end. A no-op `1.sqm` bumps the
  schema baseline to version 2 and exercises the full
  schema → migration → schema round-trip via `verifyWebDatabaseMigration`,
  with a persisted `databases/<n>.db` snapshot per version checked into the
  source set so PRs see schema deltas next to the migrations that produced
  them. Future schema changes add `<N>.sqm` instead of relying on
  `AndroidSqliteDriver.onCorruption` wiping the DB (which would lose user
  bookmarks). `schemaOutputDirectory` configured in
  `app/build.gradle.kts:sqldelight` is what makes verifyMigrations actually
  run — without a snapshot directory the verify task short-circuits with
  "Verifying a migration requires a database file" and the contract is
  asserted but never exercised.
- `TdLifecycleBridge.bind()` is now idempotent. The
  `ProcessLifecycleOwner` observer + `ConnectivityManager.NetworkCallback`
  registration would silently double up if `bind()` ever ran twice
  (currently `HortayApp.onCreate` is the only caller, but the invariant
  wasn't enforced in code).
- Removed a four-coroutine race in `CommentsRepository.observeThread`
  where four `launchIn` collectors mutated a shared `MutableList` from
  concurrent dispatch threads. A single `td.updates.collect` inside the
  flow body replaces them.
- `PostContent` and the entire data class graph reachable from
  `TimelinePost` (`Reactions`, `ReactionItem`, `FormattedText`, `TdMedia`,
  `WebPreview`, etc.) carry `@Immutable` end-to-end so Compose's
  stability inference can skip recomposition for `PostCard` reliably.
- `ChannelsScreen` now uses `collectAsStateWithLifecycle`, matching the
  rest of the surfaces.
