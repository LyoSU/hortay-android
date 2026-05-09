# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com),
and this project adheres to [Semantic Versioning](https://semver.org).

## [Unreleased]

### Added

- **Read-state sync with the official Telegram client** (TDLib mode). 1 s viewport
  dwell acks via `viewMessages(forceRead=true)`, advancing
  `lastReadInboxMessageId`. Comments overlay does the same against the discussion
  thread. Explicit taps ack immediately.
- **Realtime reactions / views / comment counts on the post under the user's
  gaze**. Dwell-driven focus tracker keeps exactly one merged-feed chat
  OpenChat'd at a time — TDLib only streams `UpdateMessageInteractionInfo` for
  opened chats (per tdlib/td#2312).
- **Auto-download media settings (TDLib mode), TG-style**. Two-level Wi-Fi /
  Mobile / Roaming screen with Photos / Videos / GIFs toggles + video size
  slider. OS Data Saver honoured; defaults match Telegram-Android.
- **Background media prefetch** through `MediaAutoDownloader`, keyed on real-time
  arrivals only. Low-priority class never blocks visible-media downloads.
- **2FA password recovery in-app** — "Не пам'ятаю пароль" link triggers
  Telegram's recovery-code flow when a recovery email is on file; otherwise
  shows a "use another device" hint instead of routing into a TDLib error.
- `HortayNetworkType` (Wifi / Mobile / Roaming / None) exposed as `StateFlow`
  from `TdLifecycleBridge`.

### Changed

- **End-to-end M3 Expressive redesign**. `MaterialExpressiveTheme` +
  `MotionScheme.expressive()`. Container scale bumped to 8/12/18/24/36 dp. Hero
  polygon morphs on selection: reactions (`Cookie9Sided`), folders & nav tabs
  (`Cookie7Sided`), connection banner (`Bun`), empty-state badges (`Flower` /
  `Heart`).
- **Top bar slides off-screen on scroll** (Twitter / Instagram pattern),
  returning at the top. Tool stages (channel filter, search) stay pinned.
  Top-level destinations use `MediumFlexibleTopAppBar` with subtitles.
- **Fullscreen video viewer chrome rebuilt in Compose**: 72 dp `Square ↔ Circle`
  morph play/pause, M3 Slider scrubber, `Pill`-backdropped mute toggle,
  double-tap-to-seek ∓10 s, auto-hide. Inline preview now uses bare
  `TextureView(isOpaque=false)` so the poster reads through the prepare window
  instead of getting masked by `SurfaceView` black.
- **Rounded corners read from `MaterialTheme.shapes` tokens** across 12 files /
  ~40 sites; sub-token (1–8 dp) and animated radii stay raw.
- **`CircularProgressIndicator` → M3 1.5 `LoadingIndicator`** across the app;
  auth blocking loader uses `ContainedLoadingIndicator`; migration sheet uses
  `LinearWavyProgressIndicator`.
- **Material Symbols re-pulled at Rounded · weight 500 · 24 dp**, with filled
  companions for selected/active state. Channels tab icon `forum` →
  `dynamic_feed`.
- **Copy pass across all user-facing strings (uk + en)**. Removed AI-slop tells:
  trailing periods on labels/chips, em-dash splices, system-narration framings.
  Plurals and format specifiers preserved.
- **TDLib log stream via `SetLogStream`**: 5 MB rotating file in debug,
  `LogStreamEmpty()` in release.
- Read-only-client TDLib options applied on every `goOnline`:
  `disable_top_chats=true`, `notification_group_count_max=0`.
- `WebRepository.observeFeed`: debounce(50ms) → sample(150ms) so a 200-channel
  burst sweep emits incrementally instead of appearing frozen.

### Fixed

- **Comments overlay sometimes hung in Loading until the user backed out**
  (TDLib mode). Cascading bug: `prefetchThread` re-fired
  `GetMessageThread` on every viewport-stable burst (debounced 700 ms) for
  posts with no linked discussion group, and racing `prefetchThread` +
  `observeThread` callers both saw the same cache miss and both fired the
  RPC concurrently. With ~200 channels and dwell-driven viewport stables,
  per-method requests piled up until Telegram returned `[429] retry after
  31` — and `TdClient.send` only armed its flood-gate on code 420, so the
  pipe kept hammering 429s for the duration of the ban window. Three fixes
  converge: `isFloodWaitCode(code)` recognises both 420 (legacy MTProto)
  and 429 (translated layer), `CommentsRepository.ensureAnchor` negative-
  caches `[400] Message has no thread` so dead anchors stop re-firing, and
  in-flight `CompletableDeferred` dedup makes concurrent callers share one
  RPC instead of racing two. Transient failures (network blip, 429, 5xx)
  do **not** poison the negative cache — only deterministic `code == 400`
  responses do.
- **Auto-download dumped multi-GB into the cache the moment the feed opened**
  (TDLib mode). `MediaAutoDownloader` was subscribed to the merged-feed
  `StateFlow`, re-walking the entire 1000-post head on every emit. New
  `PostsRepository.newArrivals: SharedFlow<TimelinePost>` emits only from
  `ingest()`; cold-start / restore / pagination download zero bytes through
  auto-download. Foreground-gated. `DEFAULT_VIDEO_MAX_WIFI` 100 MB → 50 MB to
  match Telegram-Android.
- **`OptimizeStorage` was wiping the entire media cache every 24 h**. Passed
  `count=0` literally; TDLib treats `0` as the limit, not the default. Now
  passes `-1` so only the explicit 500 MB cap drives eviction.
- **`OptimizeStorage` couldn't catch a long single session that didn't cycle
  foreground**. Threshold-first sweep: ≥80% of cap → immediate, otherwise 24 h
  housekeeping. Also runs on background→foreground via `goOnline`.
- **`maybeOptimizeStorage` was double-triggered on cold start** (both
  `auth.Ready` and `goOnline` fired). Removed the `auth.Ready` trigger.
- **Coil disk cache could grow into gigabytes on big phones**. Clamped to
  `[32 MB, 256 MB]`.
- **"Новi пости" pill counted older pagination arrivals as new**. Switched
  per-channel id-set to a per-channel **date** high-water mark; pagination
  loads with `date < hw[chatId]` are now invisible to the pill.
- **Per-account state survived logout** (privacy bug). New `TdClient.loggedOut`
  SharedFlow fans out `clear()` to every per-account repo + snapshot file +
  `MigrationStore.reset()` before the new TDLib session spawns.
- **Cold-start feed appeared in one big batch ~3-5 s after launch** (TDLib
  mode). Streams per-channel results into `_posts` as they land instead of
  `awaitAll().flatten()`. Seen-set seeding waits for both `livePosts.isNotEmpty()`
  AND `refreshing == false`.
- **Album anchor flipped between sessions, breaking reactions / comments / feed
  visibility**. `sortedBy { it.date }` was unstable — Telegram emits album
  members with the same whole-second date. Now sorts on `it.id`, the canonical
  "first message" TDLib uses for reactions, replies, view receipts (per
  tdlib/td#2312). Permutation-invariance test in `PostFilterStrategyTest`.
- **Photo albums collapsed to a single photo after restart**. Three pile-on
  bugs in merge logic collapsed into one canonical `foldRawIntoCurrent`.
  `restoreFromSnapshotInternal` re-runs `coalesceAlbumFragments` so corrupted
  snapshots self-heal.
- **Reactions / views / comment counts stopped updating on photo-album posts**.
  `flushPendingInteractionInfo` matched on `post.id` only; index now covers
  anchors AND every `albumMessageIds` entry.
- **Album view counts could briefly tick backward**. Per-member `viewCount` lag
  could downgrade a card. Now `maxOf(post.views, info.viewCount)`.
- **220 ms grey blink between minithumb and full photo**. Minithumb stays
  composed under the file image so Coil's `crossfade(220)` covers it naturally.
- **Mid-playback rebuffer indicator flashed during healthy playback**. Filtered
  through `rememberDeferredLoading(400ms)` — sub-second `STATE_BUFFERING` blips
  stay invisible.
- **Video / animation posters didn't auto-download on metered networks**.
  Posters now ride `AutoDownloadPolicy.photos`; playback continues to ride
  `videos`.
- **Inline custom-emoji rendered as transparent dead air for several seconds
  in TDLib mode**. `CustomEmojiInlineView` keeps the placeholder mounted as an
  overlay until the file lands `Ready`.
- **Migration partial-failure left the user permanently stranded**.
  Distinguishes permanent skip (no public chat) from transient failure
  (FLOOD_WAIT, network); proposal stays pending unless every requested handle
  either succeeded or was permanent-skip. Triggers `refresh()` after migration.
- **Migration left migrated channels in `webSubscriptions`** — visible
  duplicates between modes after sign-out. Successful join now removes the
  username from `subscriptions`.
- **Migration proposal sheet — confirm button text wrapped to two lines**.
  Switched to stacked `fillMaxWidth` layout.
- **`WebFeedScheduler` polled t.me/s/ even while signed in to TDLib mode**.
  Tier-2 sweep pauses when `authStage == AuthStage.Ready`.
- **Adding a guest-mode channel kicked a full N-channel sweep**.
  `subscribeAndRefresh` pre-populates the diff snapshot under the same mutex
  `handleSubscriptionsChanged` takes.
- **Private guest-mode channels surfaced as "network error"**. 301/302 to bare
  `t.me/<u>` now classifies as `PrivateChannel`.
- **Tier-2 web-mode polling died silently on a single failed sweep**. Defensive
  try/catch per iteration.
- **Empty channel title flipped a guest-mode channel to ParseFailure for hours**.
  Falls back to `@<username>` from the URL handle.
- **Single malformed `published_at_ms` pinned a guest-mode post at epoch 1970**.
  Returns null on parse failure; callers fall back to `fetchedAtMs`.
- **`WebPostAdapter.parseShortNumber("1,5K") = 1500`** (was 15000). Comma-decimal
  locales now parse correctly.
- **MediaCache user-action writes raced the single-coroutine reducer**.
  `cancelExplicit` / `retry` / `invalidate` now route through `FileEvent.Reset`
  inside the reducer loop.
- **`MediaCache.resync` — defensive mount-time `GetFile`** routed through the
  reducer. Closes the *"показує що не загружено, але якщо проскролити вниз і
  знову вверх — все вже там"* symptom.
- **All five TDLib media renderers now share `rememberMediaBinding`**
  (`ui/media/MediaBinding.kt`) — single observe / ensure / cancel / web-mode
  contract.
- **Several smaller TDLib-layer races and divergences from maintainer
  guidance**: `MediaCache.evictTerminalSlots` TOCTOU,
  `PostsRepository.refreshLocked` clobbering archive updates,
  `WebTelegramClient.awaitGate` rate-limit TOCTOU, `ChannelFetchStatus.Loading`
  stuck after process kill, adaptive-backoff conflating rate-limited with
  no-op sweeps, `WebTelegramClient.lookupChannel` UI freeze on rate-limit gate,
  comments overlay vanishing after process kill,
  `TimelineScreen.interactions` capturing stale references after logout/login,
  `ChannelsScreen.ChannelSummary` missing `@Immutable`, `MediaCache.invalidate`
  TOCTOU, `loadChannelHistory` cooldown on empty success, `searchInChannel`
  swallowing errors, `TdLifecycleBridge.bind()` seed/register order,
  `GetChats(limit=500)` clipping >500-channel users,
  `CommentsRepository.ensureAnchor` redundant probe on standalone posts.
- **"At-top" pill behaviour broke after switching channel filter** — `atTop`
  derivedStateOf had no `remember` key.
- **Auto-download category summary lost the space after each comma** — `aapt2`
  strips trailing whitespace from string resources. `joinToString(", ")` now
  carries it directly.
- `MediaCache` no longer logs noisy warnings on
  `LeftCompositionCancellationException`.

### Performance

- **Tamed TDLib pool contention** that surfaced as multi-second stalls on
  user-facing photos and the "comments sometimes load slowly" complaint. Live
  logcat showed user-staring files sitting at `bytes=0` for 5-79 s while
  newer-issued speculative downloads jumped ahead of them. Per Levin
  ([tdlib/td#786](https://github.com/tdlib/td/issues/786)), TDLib serves
  same-priority `DownloadFile` requests in **reverse order of issue** (LIFO);
  combined with the per-DC active-slot pool, every additional ensure() at the
  same priority pushed earlier (visible) files toward the back. Three fixes
  converge on this:
  1. `PREFETCH_AHEAD: 4 → 2` — half the speculative storm, matching
     Telegram-Android's empirical neighbour-cell window.
  2. Prefetched neighbours moved off [VisibleMedia] into [Prefetch] (priority
     8 instead of 16). Visible posts self-ensure at 16 via
     `rememberMediaBinding`, so the priority-aware scheduler now serves visible
     first regardless of LIFO inside each lane.
  3. `MediaCache.checkStalled` skips the watchdog reissue for non-user-facing
     files (`Avatar` / `Prefetch`). Reissue at the same priority on an
     already-active job is a TDLib no-op (per `ResourceManager.cpp`); 200
     channel avatars × 15-s cadence × 3 retries was 600 wasted RPCs/h
     contributing to the very pool contention the watchdog was meant to fix.
- **`prefetchThread` fan-out capped at top-3 visible posts**. Without the cap,
  5-10 simultaneous `GetMessageProperties + GetMessageThread +
  GetMessageThreadHistory` bursts blocked the TDLib RPC queue exactly when the
  user tapped a different post's comments — surfacing the "comments slow"
  symptom. Three slots covers the dwell-likely set without owning the pipe.
- **Photo size selection now matches Telegram-Android's display-tier picker**
  (TDLib mode). Feed cards used the largest available variant (`w`, ~2560 px)
  for 1080-px screens — paying ~3-4× the bytes / decode CPU per photo.
  `Photo.toMedia(targetMaxSidePx)` now picks the smallest variant whose longer
  side ≥ target. Three tiers: Preview (320 px) for reply previews + link-preview
  thumbs; Inline (1280 px) for feed cards / video posters; Fullscreen (largest)
  for the pinch-zoom viewer. The viewer stacks the inline variant under the
  fullscreen one for progressive enhancement — feed-cached `y` paints
  immediately on tap and `w` crossfades over once it lands.
- **Skip JSON re-encoding for unchanged web posts**. New `selectFingerprint`
  query reads `(text_html, views)` and skips four serialises + UPDATE on a
  match. ~28 s of CPU saved per hour of foreground sweeping on 200 channels.
- **Dropped dead `post_text_plain_idx`** (LIKE with leading wildcard ignored
  it). Saves ~40 MB disk on 5K posts and ~20% INSERT overhead. Migration
  `2.sqm`.
- **`OptimizeStorage` skipped when cache is well under the cap**. Probes via
  `GetStorageStatisticsFast` (~10 ms) before the file-table walk (50–300 ms).
- `OptimizeStorage` `fileTypes` made explicit (heavyweight media types we
  serve) for self-documenting call sites and stable behaviour across TDLib
  bumps. `clearCache` keeps `fileTypes = null` to preserve sticker /
  profile-photo protection.

### Architecture

- **Floating-bar `NestedScrollConnection` extracted to
  `rememberFloatingTopBarBehavior()`**. `TimelineScreen` and `CommentsScreen`
  share one helper with an `enabled = { … }` lambda for the filter /
  search-pinning case.
- **A11y batch**: 9 hard-coded English `contentDescription` literals →
  `stringResource(R.string.action_*)`; 9 button-shaped Rows / Boxes get
  `Modifier.clickable(role = Role.Button)`.
- `ChatPresence` is now the single facade for all `OpenChat` / `CloseChat` /
  `ViewMessages` traffic.
- **17 new unit tests** pinning pure-function targets surfaced by the audit
  fix batch: `WebPostAdapterParseShortNumberTest` (11 cases),
  `WebFeedSourceBackoffTest` (6 cases). Backoff aggregator extracted to
  `nextNoOpStreak(outcomes, current)` so it's testable without HTTP / SQLite.

### Build

- **Native debug symbols for `libtdjni.so` flow into release AABs
  automatically**. `update-tdlib.sh` defaults `KEEP_DEBUG=1`, extracts
  unstripped libs into the gitignored overlay
  `libtdlib/build/tdlib-unstripped/<abi>/`, AGP picks them up via `srcDirs`
  last-wins merge. Symbolicated native crash / ANR stacks end-to-end in Play
  Console.
- **material3 pinned to 1.5.0-alpha19** ahead of the next BOM (April 2026 BOM
  still ships 1.4.0). `androidx.graphics:graphics-shapes:1.1.0` declared
  explicitly. compileSdk 36 → 37 (forced by Compose UI 1.12.0-alpha02);
  targetSdk stays 36.
- **Removed unused build configuration**: `POST_NOTIFICATIONS` permission,
  `JitPack` repository, `sqldelight-sqlite-driver` and `androidx-test-runner`
  library aliases.
- **R8 keep rules for SQLDelight generated code** so `<DatabaseName>.Schema`
  survives R8 in release.
- **Four new Material Symbols vector drawables** (`play_arrow`, `pause`,
  `volume_up`, `volume_off`).
- **`HortayExpressive` shape registry adds `PlayPausePaused` /
  `PlayPausePlaying` / `PlayPauseMorph`** — pre-built `Morph` avoids per-frame
  float-array allocation.

## [0.2.0] — 2026-05-06

### Added

- **Anonymous reading mode** ("гостьовий режим"). Read public Telegram channels
  without signing in: parser ingests `https://t.me/s/<username>`, posts surface
  in a Twitter-style feed, custom emoji and reactions render client-side.
  Subscriptions persist across sign-in.
- **Cross-channel local search (guest mode)**. Search overlay with debounced
  (220 ms) `LIKE` query against `web.db`; ≥2 chars; `flatMapLatest` cancels
  stale subscriptions. Reuses `PostCard`.
- **Animated TGS custom emojis in guest mode**. `LottieUrlStore` fetches `.tgs`
  from the t.me CDN (gzip-sniffed), feeds the same `LottieAnimation` pipeline
  TDLib mode uses. WebMs intentionally stay on the static-WEBP-thumb path
  (alpha sidecar requires libvpx ext, ~10 MB native libs × 2 archs —
  disproportionate for inline emoji).
- **Anonymous → authenticated migration proposal**. One-time bottom sheet lists
  guest-mode subscriptions with per-channel checkboxes; opt-in only; throttles
  `SearchPublicChat` + `JoinChat` at 1 channel/second with live progress.
- **Animated stickers (TGS / WebM / WEBP), animated emoji and custom-emoji
  reactions**. Pipeline reuses TDLib's batched `GetCustomEmojiStickers` (200
  ids/call, 50 ms debounce). TGS via Lottie-Compose with 32-entry LRU + 5 MB
  gunzip cap; WebM via ExoPlayer (looped, muted, lifecycle-aware). Inline
  emojis via Compose `InlineTextContent`.
- **Twitter-style "новi пости" floating pill**. Visible feed is frozen on what
  the user has already seen until they explicitly accept (pill tap or PTR).
- **Material 3 predictive back gesture for the comments overlay**: ~10%
  translate, scale 0.9, alpha 0.7 under the finger; rewinds smoothly on cancel.
- **English localization**. Strings extracted to `res/values/strings.xml` (en,
  default fallback) and `res/values-uk/strings.xml`. TDLib's
  `systemLanguageCode` follows `Locale.getDefault().language`. `<plurals>` for
  grammatically correct counts (uk: 1 / 2-4 / 11-14; en: one/other).
  `StringResolver` interface so repositories don't import Android types.
- `WebDatabase` (SQLDelight 2.3.2): channels, posts, custom-emoji cache,
  suggestions. WAL + foreign keys + denormalised `published_at_ms` index. FTS5
  dropped (Samsung/Pixel SQLite ships without the module).
- `WebRepository` — single DAO surface with `Flow`-driven observers; atomic
  `ingestPage()` runs channel + N post upserts in one transaction.
- `WebFeedSource` — multi-channel orchestrator under `Semaphore(6)`. Stale CDN
  media URLs trigger `FORCE_NETWORK` after 4 h.
- `WebFeedScheduler` — tier-2 foreground polling every 5 min while visible.
- OkHttp `Cache` (10 MB DiskLruCache) with conditional GET — ~80–90% cheaper
  morning sweeps after the first day.
- `WebTimelineScreen`, `AddChannelSheet`, `WebSettingsSheet`, `WebModeScaffold`.
  PTR, curated suggestions per locale, smart-paste validator,
  lookup-before-subscribe.
- "Сховище і трафік" Settings section — TDLib network usage and storage with
  one-tap "Clear cache".
- Process-wide `TdLifecycleBridge`: TDLib `online` flag + `NetworkType` via
  `ConnectivityManager`. Push-safe.
- ProGuard / R8 keep rules for TDLib JNI surface, `kotlinx.serialization`
  companions, Compose stability annotations, coroutines metadata.

### Changed

- `AddChannelSheet` auto-pastes valid Telegram links / `@handle` from the
  clipboard on open. Privacy-gated through the same regex as manual input.
- Comments overlay opens instantly when reopened within 30 seconds (anchor
  resolution cached for the session).
- Re-opening the app no longer triggers a full 200-channel TDLib refresh
  unless cached feed is older than 60 seconds.
- Re-entering a channel filter within 60 seconds reuses the previous
  `GetChatHistory(80)` result.
- Coil memory cache lowered from 20% → 10% of Java heap.
- TDLib log verbosity: `error` on debug, `fatal` on release.

### Fixed

- **Inline-preview videos showed a 2–3 s black square** before the first frame.
  `PlayerView`'s `SurfaceView` paints solid black until the decoder ships its
  first frame; replaced with a bare `TextureView(isOpaque=false)` for inline
  preview. Fullscreen viewer keeps `PlayerView` for its scrubber widgets.
- **Web previews with `link_preview_right_image` had no thumbnail**. Parser
  now matches the third t.me variant.
- **Blockquote spacing** — extra blank line above and below. Spacer is now
  conditional on absent natural `\n` boundaries.
- **`parseUsernameFromInput` rejected short Telegram handles** (Fragment-
  auctioned, Premium-short like `@io`). Lowered regex floor 5 → 2 chars.
- **"Media is too big" video posts read as videos, not photos**. Now emit as
  `Kind.Video` with empty-URL sentinel; UI shows poster + play badge + "Open
  in Telegram" hint chip routing the tap.
- **Guest-mode post text formatting** — every reported regression in one pass
  (leading-space drift, missing line breaks, span-offset drift, reply-preview-
  as-body). Replaced ad-hoc walker with strict two-phase pipeline:
  `emitVerbatim` (structural) + `normaliseWhitespace` (single linear pass;
  spans re-anchored through `srcToDst` IntArray; idempotent). Side-fix in
  `TmePageParser`: selector now walks `:not(.js-message_reply_text)` and
  prefers the innermost `.tgme_widget_message_text`. 12 snapshot tests in
  `WebPostAdapterFormattingTest`.
- `MediaCache` no longer logs noisy warnings on
  `LeftCompositionCancellationException`.
- Crash on `UpdateMessageInteractionInfo` with null payload (coalescing buffer
  skips them).

### Performance

- `flushPendingInteractionInfo` does a single O(feed) pass with O(1) hash
  lookups (was `indexOfFirst` per drained event).
- Discussion threads share one `WhileSubscribed` SharedFlow per anchor — reopen
  no longer re-runs `GetMessageProperties` + `GetMessageThread` + history load.
- Feed storage uses `PersistentList` from `kotlinx.collections.immutable` —
  O(log N) per-event mutations.
- `UpdateMessageInteractionInfo` events coalesce in a 200 ms window — one
  batched `_posts.update {}` per window.
- `UpdateFile` progress events throttle to 10 Hz per file.
- `MediaCache` materialises `MutableStateFlow` slots only when a Composable
  observes a fileId.

### Architecture

- `web.db` migration pipeline end-to-end. No-op `1.sqm` baseline + persisted
  `databases/<n>.db` snapshots so PRs see schema deltas alongside their
  migrations. `schemaOutputDirectory` configured so `verifyMigrations` actually
  runs.
- `TdLifecycleBridge.bind()` is now idempotent.
- Removed a four-coroutine race in `CommentsRepository.observeThread` — single
  `td.updates.collect` inside the flow body.
- `PostContent` and the entire data class graph reachable from `TimelinePost`
  carry `@Immutable` end-to-end.
- `ChannelsScreen` uses `collectAsStateWithLifecycle`.

### Build

- Release/Beta packaging fails at task-graph time when `keystore.properties` is
  missing instead of producing an unsigned APK silently.
- `compose-ui-tooling-preview` moved from `implementation` to
  `debugImplementation`.
