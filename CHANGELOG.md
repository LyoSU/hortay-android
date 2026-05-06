# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com),
and this project adheres to [Semantic Versioning](https://semver.org).

## [Unreleased]

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
