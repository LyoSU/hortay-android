# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com),
and this project adheres to [Semantic Versioning](https://semver.org).

## [Unreleased]

### Added
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
