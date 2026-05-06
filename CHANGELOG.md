# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com),
and this project adheres to [Semantic Versioning](https://semver.org).

## [Unreleased]

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

### Fixed
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
