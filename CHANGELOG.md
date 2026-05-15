# Changelog

[Keep a Changelog](https://keepachangelog.com) · [SemVer](https://semver.org). One user-visible change per bullet; rationale lives in commits, not here.

## [Unreleased]

### Fixed
- Opening a channel from the subscriptions list (or any other in-app gesture) now actually shows its posts on the very first entry, instead of the misleading "No posts" hero. `ChannelViewModel.init` was issuing `GetChatHistory` without first sending `OpenChat` — and per TDLib upstream (Aliaksei Levin's note on tdlib/td#2937 and the `OpenChat` docstring: *"in supergroups and channels all updates are received only for opened chats"*), a cold-cache `GetChatHistory` for a never-opened channel is served from local storage and returns an empty list even though the server has posts. The pre-refactor `TimelineScreen` did the canonical `openChat → loadChannelHistory → awaitCancellation → closeChat` dance inline; commit `d2e3509` extracted `ChannelScreen` + `ChannelViewModel` and dropped the open/close pair. Restored, with `CloseChat` under `NonCancellable` so a fast back-press still flushes the close. Refcount-wise the new VM-scoped open overlaps cleanly with `TimelineScreen`'s focus-chat tracker (which already releases its own open the moment `coveredByOverlay = stack.isNotEmpty()` flips on channel drill), so Levin's "usually one chat opened" invariant (tdlib/td#2695) is preserved. As a side effect, live updates (`UpdateNewMessage`, `UpdateMessageInteractionInfo`) now stream into the open channel screen too, instead of waiting for the user to pull-to-refresh.
- In-app gestures that previously dumped the user on a frozen-empty `ChannelScreen` for non-channel targets now route to a clear snackbar instead. Tapping a "forwarded from" chip whose source is a supergroup-chat (not a broadcast channel), a cross-channel reply-quote whose target is a group, or any other in-app channel-open whose chat id resolves to a 1:1 / bot / basic group / supergroup-chat — all three used to push a `NavEntry.Channel` for which `loadChannelHistory` short-circuits on `!chat.isChannel()` → empty hero with no recourse. The deep-link dispatcher already gated these via `resolveChatKind` (the `link_unsupported_*` snackbar path it has used since 0.3.0); the in-app gestures didn't. New `MainScaffold.safelyOpenChannel` applies the same gate to `onChannelOpen` callbacks from Feed, Saved, and ChannelScreen, surfacing the kind-keyed snackbar Hortay already ships for the deep-link variant (`Group chats open in Telegram` / `User profiles open in Telegram`). The `Channels` tab and explicit subscription-confirm paths keep the direct `pushChannel` because their inputs are by-construction broadcast channels.
- Custom-emoji TGS rendering no longer crashes the whole app on stickers with malformed gradients. SDK 36 tightened `Shader.convertColors` to throw `IllegalArgumentException: needs >= 2 number of colors` on `RadialGradient` with a 1-stop gradient — and some real-world custom emoji ship exactly that. The exception fired on the `CustomEmojiRenderer` background HandlerThread, killing it and freezing every animated emoji in the process until the next cold launch. The Lottie `draw()` call is now wrapped in a try/catch at the system boundary (TDLib TGS payload is an external API per CLAUDE.md): the offending entry is marked broken and skipped on subsequent renders, the renderer thread survives, and the rest of the emoji on screen keep animating.

### Added
- Tapping any author surface — a comment header, a personal-author channel post, a "forwarded from <user>" chip, or an inline non-`@` user mention — now opens a Material 3 Expressive user profile bottom sheet instead of doing nothing (comments / inline mentions) or routing through the channel sheet (personal-author posts). Sheet surfaces what TDLib exposes for a reader: 88dp avatar, name with verification / Premium / bot / support marks, `@username` (with a "Copy" action), presence ("у мережі" / "був(ла) нещодавно" / "був(ла) X хв тому" / etc.), bio, bot description, birthdate, common-groups count, and the user's Telegram-Premium "personal channel" as a tappable row that drills into the channel overlay in-app. The "Написати" chip hands the OS an `https://t.me/<handle>` URL (or `tg://user?id=<id>` when no handle) so the chat opens in the official Telegram app — Hortay itself is a reader, not a sender. Trigger is centralised through `LocalUserProfileOpener` so every author entry point reuses the same sheet, identical behaviour and look across the app.
- Settings → About → "App language" / "Мова застосунку" lets you pin the UI to Ukrainian or English independently of the system locale. Default is "System default" — the existing behavior. On Android 13+ the choice also surfaces in the system per-app language picker (Settings → Apps → Hortay → Language) and survives reinstall when Android's automatic data-restore kicks in. Persisted via `AppCompatDelegate.setApplicationLocales`, declared via `app/src/main/res/xml/locales_config.xml` (`en` + `uk`). Bilingual users (Ukrainian system locale + occasional EN context, or vice-versa) no longer have to change the entire phone to switch Hortay's language.

### Performance
- Comments overlay, report sheet, and country picker recompose less often. `CommentsRepository.ThreadState.Ready.rows`, `ReportState.OptionSelection.options`, and `Country.allDialCodes` switched from platform `List<X>` to `kotlinx.collections.immutable.ImmutableList<X>`, and the parent data classes are now `@Immutable`. Strong-skipping mode (Kotlin 2.3 / Compose Compiler 2.x default) now skips these slots when the comment thread, report state, or selected country didn't actually change between recompositions — previously the platform-list field made the whole data class "unstable" and every recomposition cascaded.

### Build
- LeakCanary 2.14 wired as `debugImplementation` only — ships in debug builds for memory-leak detection, zero release/beta footprint.
- Compose Compiler stability / skippability reports are now actually generated. `composeCompiler { reportsDestination + metricsDestination }` was missing — CLAUDE.md referenced reports in `app/build/compose_compiler/` but the destination was never wired. Reports land there on every `assembleRelease` / `bundleRelease` and surface skippability regressions as `unstable` verdicts in `app-classes.txt` and as `restartable` without `skippable` in `app-composables.txt`.
- New `compose_stability.conf` at the repo root marks `kotlinx.collections.immutable.{Persistent,Immutable}{List,Map,Set}` and `kotlinx.serialization.json.JsonElement` as stable to Compose. Platform `kotlin.collections.{List,Map,Set}` are deliberately NOT listed — the project's contract is to use `Persistent*` for any collection that reaches a `@Composable` parameter, and treating the platform types as stable would silently mask passing a mutable list at a call site.
- R8 now collapses every obfuscated class into the empty package via `-repackageclasses ''`. Drops per-class package-name strings from the DEX string pool. Stack traces in Play Console stay readable via auto-deobfuscation against the bundled `mapping.txt`.
- Dropped a dead `-keep class org.drinkless.td.libcore.** { *; }` ProGuard rule — that package never existed in the vendored TDLib (only `org.drinkless.tdlib` does).
- Cleaned four K2 smart-cast leftovers in `TdVideoPlayer`, `WebmStickerPlayer`, and `PostBody`: Elvis operators on values K2 now proves non-null, and one explicit null-check K2 now proves redundant. No behaviour change — Kotlin 2.0+ improved smart-cast tracking through stored boolean conditions and short-circuit `&&`.

## [0.4.0] — 2026-05-15

### Added
- Fullscreen photo viewer now supports double-tap-to-zoom (toggles between 1× and 2.5×, pulls the tap point toward viewport centre — Telegram / Instagram idiom). Pinch-zoom and pan get an animatable backing so the zoom transitions ride the same Material Expressive spring as the rest of the app, and pan is clamped to viewport bounds so the image can no longer fly off-screen.
- Fullscreen media viewer gains a Share button (system `ACTION_SEND` chooser) next to Save / Copy. Routes the file directly to any app that accepts the MIME type via a FileProvider URI — no Save-then-pick round-trip required, fast even for big videos because no copy step is needed.
- Web link previews now render every `LinkPreviewType*` TDLib ships — articles, photos, videos (with cover preference over auto-extracted thumb), animations, documents, audio (album cover), embedded players (YouTube / SoundCloud), apps, sticker / sticker-set, story / story-album, web-app, gift, theme, chat / user / boost (avatar thumb), and the catch-all "external" group. Variants without an inline image get a kind-keyed icon tile instead of an empty 72.dp box. Large media flag (`showLargeMedia` / `hasLargeMedia`) is honoured: previews render full-width above or below the metadata (Telegram-X style) rather than always-compact. `displayUrl` and `author` are surfaced when distinct from the site name.
- Telegram Stars paid posts are no longer dropped from the feed when every piece is locked. Unlocked paid media keeps the album layout but stamps a small "⭐ N" chip on the corner; fully locked posts surface a lock card with the star price that taps through to the official Telegram client.
- Paid (⭐) reactions on channel posts now render as a star pill in the reaction row. Read-only — sending paid reactions still requires the official client's star-amount confirmation flow.
- Invoice / Giveaway / Game / Story / Telegram Premium gift / Gift posts no longer silently disappear from the feed. They render as a small "open in Telegram" card with a topical icon (gift / invoice / game / visibility) instead of being filtered as Unsupported.
- Document / Audio / Voice-note / Video-note cards now route a tap to "open in Telegram" — Hortay doesn't host its own download / playback path for those file kinds, but the affordance is no longer dead.
- Feed mode `OldestUnreadFirst` (Settings → Feed): read on top, unread below, lands at boundary.
- Snap-scroll mode (Settings → Feed).
- Per-chat read state with unread strip on card edge.
- Inline retry on failed guest-mode Channel rows.
- Floating "↓ N" unread-remaining counter in `OldestUnreadFirst` — ticks down live as you scroll; tap to jump to the next unread.
- Inline-video autoplay is now gated on (a) a new Settings → Feed toggle "Autoplay videos in feed" (default ON) and (b) the playback file actually being on disk. Short videos pulled by the user's auto-download policy still play silently as you scroll; videos that policy didn't fetch keep the static poster + play badge until tapped (no stealth downloads triggered just because a post entered the viewport).
- Authenticated Settings now offers an explicit "Continue without account" row that signs you out of Telegram and routes the app into guest mode in one confirmed step. Previously the only way into guest mode from a signed-in session was a fresh install. Subscriptions, bookmarks and feed preferences survive the switch.
- Guest mode now responds to a tap on a post body: instead of being silently dead (no comments backend in guest), it surfaces a snackbar explaining that comments require sign-in. Same affordance the user reaches for in TDLib mode, with an honest reason for the lack of an action.

### Changed
- Default feed order is now `OldestUnreadFirst` (Telegram-channel chat-app idiom: oldest read posts on top, unread queue in the middle, newest at the bottom — opens at the read→unread boundary so you continue from where you left off). Existing users who explicitly picked `Newest` in Settings keep their choice; the new default only applies when the preference has never been set. Both options stay available in Settings → Feed, and the new default is now listed first there.
- Spoilers (text + media) reveal as a Telegram-style shimmering particle cloud that disperses Thanos-style on tap, no longer a flat grey block / "tap to view" pill. Adjacent TDLib `Spoiler` entities that get split around a custom-emoji codepoint group back into one logical cover with one shared particle pattern, so the hidden phrase reveals as a single unit instead of in halves. Sensitive (TDLib `isSecret`) covers keep the centred icon-and-label pill so the user knows *why* a consent tap is needed. Reveal state, "show more" expand, and the link long-press sheet now survive reactions, edits-that-don't-change-text, and the reveal animation itself.
- Channels-row status folded into `@handle · <status>` subtitle (UK + EN).
- Channel-drill rendered as overlay above always-mounted Feed.
- Channel lists (TDLib + guest mode), channel-info sheet actions, and the country picker rows now render through Material 3 Expressive `SegmentedListItem` / `ListItem` instead of hand-rolled `Row + clip + clickable` chips — first/last rows get the larger outer corner radius, inner rows pinch tighter, and ripple respects the shape.
- `OldestUnreadFirst` "Непрочитане" rule is now a peripheral session anchor: `labelSmall` typography with a 35%-opacity primary tint at ~28dp height, tuned so the rule reads as orientation rather than as a feed item asking for attention.
- Feed cold-start, channel cold-start, and channel deep-link landing now share a
  single declarative state machine: the `LazyColumn` mounts only when its state
  is `Ready`, with `initialIndex` precomputed against the same `List<FeedItem>`
  the column renders. First paint lands at the correct anchor (top / unread
  boundary / deep-link target) in one frame — no flicker, no animate-through.
  Replaces five parallel `LaunchedEffect`s that previously fought over scroll
  position and produced the "random ancient post on cold launch" and "another
  post for half a second before deep-link target" symptoms.
- "↓ N unread", "↑ N new posts", and the NavBar home-tap pills now do an
  **instant** jump with brief destination highlight when the target is more than
  ~8 rows away; smooth animation only for nearby targets. Previously all three
  animated-through the full intermediate list, locking the user out for seconds
  on far jumps. Matches the canonical Telegram/Slack/Discord pattern.
- Deep-link to a channel post now shows a placeholder skeleton while the
  surrounding history loads, then snaps to the target in one frame. Previously
  the channel's head post flashed for a moment before the scroll landed.
- Guest-mode "Clear cache" now asks for confirmation and keeps bookmarked posts. The previous one-tap row silently deleted every locally cached post, including ones the user had explicitly saved — the dialog body is honest about what survives ("Subscriptions and saved posts are kept; visible content will be refreshed from t.me") and the destructive button is tinted error-red.
- Guest-mode channel rows now show the Retry affordance for `NotFound` and `Private` channels too, not only transient errors. A channel can flip back to public at any time (the original assumption that "no client retry will change Telegram-side state" silently broke the moment an owner toggled visibility); manual retry is one tap per channel and never reaches the auto-sweep, so it can't trigger a FLOOD_WAIT.
- Guest-mode background polling now runs only when the user is actually in guest mode AND the app is foreground. The previous gate only checked "not signed in to TDLib", which kept the t.me/s/ poller alive on the auth screen — burning traffic and battery for a UI that doesn't even render the web feed, with a real CDN-side FLOOD_WAIT risk.

### Fixed
- Guest (web) mode: fullscreen viewer Save / Copy / Share buttons no longer silently vanish on posts whose bytes never reach TDLib. Photos route through Coil's on-disk cache (the same bytes the inline poster + fullscreen variant already painted from) — Save / Copy / Share all use the local-file pipeline, no re-download. Web-mode videos / animations get a Share button that falls back to `text/plain` ACTION_SEND with the CDN URL so recipients still receive a clickable link.
- Fullscreen photo viewer: double-tap to zoom **out** (second tap while zoomed) now actually fires. The pinch handler used to consume every event while `scale > 1×` — including the stationary second tap of a double-tap — which starved the tap detector before it could complete the gesture. Pinch handler now only consumes when there's actual movement (pan or zoom delta), so stationary taps on a zoomed photo pass through to the tap detector and double-tap-back works.
- Guest mode: the floating "↓ N" unread chip is no longer obscured by the "Add channel" FAB on the Feed tab. Both are bottom-end anchored; the pill now stacks above the FAB so it stays tappable while unread posts remain.
- Tapping the centre play button on a video that has played to the end now restarts it from the beginning instead of being a dead tap. ExoPlayer's `play()` is a no-op when position is past `duration`, so the chrome's onClick now special-cases `STATE_ENDED` with a `seekTo(0)` before resuming — matches the canonical YouTube / Telegram "replay" behaviour. Looping videos (≤ 60 s clips) handle this automatically via `REPEAT_MODE_ONE`; this branch covers finite-playback videos in fullscreen.
- Short videos (≤ 60 s) now loop in the fullscreen viewer too, matching the inline-feed autoplay threshold. Previously a clip that looped silently in the feed stopped at the end on fullscreen-tap and required a manual replay tap. Longer videos keep finite playback so the scrubber rests at the end and exiting the viewer doesn't restart playback in the background.
- "↑/↓ N нових постів" pill no longer lands the user on the post immediately BEFORE the just-accepted batch. Two stale-state hops compounded on this path: (1) the `feedItems` captured by `onClick` was the PRE-ack snapshot, and (2) even after switching to the live `feedItemsState`, the LazyColumn's actually-laid-out item count lags one Compose frame behind because `TimelineUiState.Ready.items` is latched through a `LaunchedEffect` — `scrollToItem(N)` then clamped N down to the OLD `lastIndex`, i.e. the row right above the new arrivals. The handler now waits (capped at 800 ms) for `listState.layoutInfo.totalItemsCount` to grow past the pre-tap value — that's downstream of BOTH staleness hops and the same number `scrollToItem` clamps against — then scrolls to the FIRST of the just-accepted posts so the user reads the new batch top → down (canonical Telegram / Slack / Discord "New messages" jump). `UnreadCounterPill` shares the live-read path for staleness safety even though it does not trigger an ack.
- Tapping an inline reply / quote card on a post no longer scrolls and highlights the post underneath in the feed. Feed: the freshly pushed `ChannelScreen` lands at the replied-to message and pulses the highlight there; the feed itself stays still. In-channel cross-channel replies now also pass the target messageId through to the new channel screen — drilling into a different channel from a quote tap lands at the replied-to post instead of opening cold at the newest message. Polls expose the question; checklists expose the title plus `[x] / [ ]` task lines; audio exposes title + performer; documents fall back to the filename when no caption was authored.
- Post-card "Report" action shows a moderation glyph instead of the silent help question-mark fallback. Bundle a dedicated `sym_flag.xml` when convenient — current mapping is `shield`.
- Reaction chips on the post-detail anchor and on comments now actually toggle; the anchor PostCard tracks the live feed entry so optimistic updates and server `UpdateMessageInteractionInfo` flow into the visible chip.
- Fresh posts reach `OldestUnreadFirst` feed without restart.
- Cold-start scroll-pin no longer fires on mid-session arrivals.
- Photo albums no longer ship with missing members on slow networks.
- Cold start waits for the fresh feed and only falls back to the cached snapshot when refresh fails — no more visible top-of-feed content swap mid-load.
- `OldestUnreadFirst` no longer auto-scrolls to the bottom on cold start when read cursors haven't loaded yet.
- `OldestUnreadFirst` no longer flashes a random ancient post as the first visible card on cold start; falls back to newest-first until read cursors land, then re-sorts.
- Editing a caption on an album in the channel no longer collapses the card to a single photo — `UpdateMessageContent` for any album member (anchor or sibling) re-ingests the whole group instead of replacing the merged content in place.
- `OldestUnreadFirst` cold start: restore the asc-by-date reverse-feed layout (oldest read at top → unread queue → newest at the bottom, chat-app idiom) and eliminate the cold-start re-sort flash by gating the LazyColumn render on cursors-landed. The column shows nothing until TDLib's first `UpdateChatReadInbox` burst arrives, then paints in one stable transition with the scroll already positioned at the read→unread boundary (or at the bottom when caught up). No more "ghost old post for a beat, then jump".
- 5-photo albums whose anchor is `Chat.lastMessage` now reliably reach the feed on relaunch: the previous healthy session's saved member ids drive a targeted `GetMessage` upgrade in `restoreFromSnapshot`, which sidesteps TDLib's chat-history hydration race that the old delay-and-retry pass tried (and sometimes failed) to wait out.
- Cold-start snapshot no longer self-poisons when the user backgrounds before the album-upgrade pass lands: `saveSnapshotNow` now preserves previously-saved album siblings of any currently-degraded album, so the next cold start still has a source-of-truth to rebuild the full card from.
- Album cards stay marked unread until the chat cursor has crossed the HIGHEST album member id — external acks (`UpdateChatReadInbox` from the official Telegram client) that land the cursor mid-album no longer prematurely flip the card to "read".
- Album dwell-read now advances TDLib's `lastReadInboxMessageId` past every member id, matching the explicit-tap path. Previously dwell sent only the anchor (lowest) id, so the chat cursor could end up mid-album.
- Share / Open-in-Telegram for real TDLib channels whose internal channel id falls in `[1, 2^32)` no longer produces a `t.me/<handle>/<huge-mangled-id>` fallback URL — the mode discriminator was a chatId-range check that overlapped TDLib supergroup ids. Switched to the caller's nullable repository argument (null = guest mode).
- Web mode media URL rotation now actually re-fetches: the ingest fingerprint guard treated `fetched_at_ms == 0` (the marker `markMediaStale` writes when Coil reports a 401/403/410) as unchanged, so rotated CDN tokens never reached the DB and the next image load failed identically. Stale flag now forces the upsert through.
- Feed ordering is now deterministic across refreshes when multiple posts share the same whole-second timestamp (cross-poster bots, schedule bursts). Newest-first sort tie-breaks by id descending; `OldestUnreadFirst` and the SQLite web-mode feed tie-break by id/seq. Previously the HashMap iteration order in `PostFilterStrategy.mergeAlbums` made same-second posts swap places between refreshes, reading as "feed jitters" or "post moved" in the UI.
- Lint gate is green again — `LocalContextGetResourceValueCall` errors in `FullScreenMediaViewer` and `WebModeScaffold` (resource lookups via captured `LocalContext.current` inside coroutine bodies) now go through `context.resources.getString` so lint's heuristic stops flagging them.
- `OldestUnreadFirst`: the "Непрочитане" boundary divider no longer migrates under the user's scroll when a card is dwell-acked. The rule now reads from a frozen cursor snapshot latched on cold-start landing and on pull-to-refresh completion; the per-card unread strip and the floating "↓ N" counter stay live as before. Matches the chat-app idiom (Telegram-Android, Slack, Discord all latch their New-messages rule on chat open and refuse to move it mid-session).
- `rememberPendingScrollToMessage` no longer silently hangs when
  `loadHistoryAround` succeeds but the target gets pruned by `PostFilterStrategy`
  or album grouping. After a 1500 ms grace, the `onMissed` callback fires and
  the UI surfaces "link not found" instead of staying in a Resolving state
  indefinitely.
- `OldestUnreadFirst` cold start no longer briefly renders an ancient post
  before snapping to the read→unread boundary. The reverse-feed `LazyColumn`
  now holds un-mounted until cursors land — boundary lands in the first
  visible frame.
- Guest-mode single-channel screen no longer silently truncates the channel's history when the user has many subscriptions. Previously it filtered the merged 1000-post global feed by `chatId`, so a quiet channel sitting under several high-volume ones would show only a tail of its actual local history (or nothing at all). It now reads through the per-channel SQL DAO and shows every locally cached post for that channel.
- Guest-mode single-channel screen no longer overlaps the status bar at the top. The shared `ChannelHeaderBar` ships with zero window-insets so it can be wrapped by hosts that own the system-bar inset themselves; TDLib mode wrapped it correctly, the guest variant didn't — now both use the same status-bar Spacer prefix.
- Tapping a channel name on a post in the guest-mode feed now opens that channel in-app (same `WebChannelScreen` overlay the Channels tab uses) instead of doing nothing. Forwarded-from chips pointing at strangers' channels fall back to t.me as before.

### Performance
- Reaction taps flip optimistically across feed / channel / post detail / comments; server reconciles via `UpdateMessageInteractionInfo`, RPC failure rolls back.
- Feed scroll jank rework. Removes per-frame work that accumulated as the
  app grew, surfacing as micro-stutter on media-heavy stretches and on
  accounts with active dwell-ack traffic. Six load-bearing fixes ship in
  one pass:
  (1) Read cursors propagate through a `SnapshotStateMap`-backed
  `CursorHolder` (not `staticCompositionLocalOf<ReadCursors>` over a
  PersistentMap). Per-key snapshot tracking — a cursor advance for chat X
  invalidates only PostCards in chat X, not the whole MainScaffold
  subtree. Was: every dwell-ack / external `UpdateChatReadInbox`
  recomposed everything under the scaffold provider, including the
  LazyColumn body.
  (2) Viewport-centre key (used for VisibleCenter download priority
  promotion) propagates as `State<Any?>` rather than a value read at the
  `items()` lambda level. Per-item `derivedStateOf` lets a centre flip
  invalidate only the two affected items instead of every visible card.
  (3) Feed `LazyColumn` now distinguishes `FeedItem.Single` vs
  `FeedItem.Thread` via `contentType`, so the reuse pool serves each
  shape from its own slot.
  (4) Per-post inline-autoplay cache probe (`isCachedReady`) runs only
  after the cheap gates (global autoplay toggle, duration, revealed,
  active, !unplayable) all pass. Was: every video card mounted a
  `StateFlow` collector + `LaunchedEffect` resync even when autoplay
  was anyway impossible.
  (5) `MediaCache.resync` from `rememberMediaBinding` is now gated on
  `LocalScrollGate.value` so flings stop firing per-mount JNI calls for
  cards that sweep through the viewport without settling.
  (6) `TdMediaImage` minithumb (with its `RenderEffect` blur GPU pass)
  drops out 280 ms after `MediaState.Ready` lands — long enough for the
  Coil 220 ms crossfade to hide the "блимок" symptom the eager-drop
  had, then ends the steady-state per-frame GPU cost on stable media.
- `TdVideoPlayer` texture attach moved from `AndroidView.update` into
  `factory`; only the aspect-ratio update stays in `update`. The
  `exoPlayer` reference is `remember`'d per player instance, so binding
  the texture is a one-time setup — repeating it on every recomposition
  was a wasted `setVideoTextureView` call per centred-flip / parent emit.

### Architecture
- `ReactionTogglePolicy` + `PostsRepository.applyOptimisticReaction` + `CommentsRepository` per-thread override map merged into the single-collector update fan-in.
- New `TimelineUiState` and `ChannelUiState` sealed unions (Loading/Empty/Ready
  and Resolving/Ready/Missing respectively). Pure `build...UiState` functions
  derive state from already-filtered/-ordered/-grouped `List<FeedItem>` —
  scroll anchor lives in row-space, same as what the user sees. One-shot
  latching via `reduce...UiState` + `rememberLatched...UiState` keeps the
  initial scroll index stable across post arrivals and live cursor advances;
  PTR completion re-latches.
- `LazyListState` constructed once per route via `rememberSaveable(routeKey,
  saver = LazyListState.Saver)` at the resolved `initialIndex`. Cold-start pin
  loop (~80 lines of `snapshotFlow + takeWhile + scrollToItem`), channel
  cold-entry effect (~30 lines of `snapshotFlow + distinctUntilChanged`),
  scope-switch effect, feedOrder-flip effect, and inline `rememberPendingScrollToMessage`
  for deep-links all deleted in favor of the type-driven gate.

### Build
- Removed three Gradle dependencies that were declared in `libs.versions.toml` + `app/build.gradle.kts` but never imported by any source file: `androidx-navigation-compose` (project uses the in-house `NavStack` for both `MainScaffold` and `WebModeScaffold`), `compose-material-icons-extended` (project uses the in-house `Symbol` system over `painterResource(R.drawable.*)`), and `sqldelight-primitive-adapters` (`WebDatabase` is constructed without any `ColumnAdapter` — all `.sq` columns are raw `INTEGER`/`TEXT`). Lint + unit tests stay green; downstream effect is a slightly smaller R8 input and one fewer transitive `androidx.navigation.*` graph for AGP to resolve.

## [0.3.0] — 2026-05-12

### Added
- CSAE-compliant in-app reporting (`ReportChat`, guest-mode delegation, audit log).
- Safety section in Settings (Report, Child safety, Privacy).

### Changed
- `ChannelScreen` extracted; `TimelineScreen` is feed-only.
- Inline links no longer underlined.
- Reply pill hidden on zero-reply posts.

### Fixed
- Cold launch always lands on Home top-of-feed.
- `#hashtag` taps scope to current channel; `tg://search` URLs parsed.
- Deep-links to inaccessible posts no longer hang on skeleton.
- Link resolver hardening (scheme allowlist, logout invalidation).
- Auto-download skipped during boot; metered networks → photos only.

### Performance
- Cold-start RPC budget cut ~30×.
- Custom-emoji TGS: janky frames 28% → 14%.
- Comments prefetch debounce 700 → 1200 ms.

### Build
- `material3` 1.5.0-alpha19; `compileSdk` 37.
- Unstripped `libtdjni.so` debug symbols.

## [0.2.0] — 2026-05-06

### Added
- Anonymous (guest) mode: read `t.me/s/<channel>` without sign-in; subscriptions persisted across modes.
- Cross-channel local search in guest mode.
- Animated stickers (TGS / WebM / WEBP), emojis, custom-emoji reactions.
- Twitter-style "new posts" pill — feed frozen until accept.
- Predictive back for comments overlay.
- English localisation + plurals.
- Settings → Storage & Traffic.

### Changed
- AddChannelSheet auto-pastes valid clipboard links.
- Comments overlay re-opens instantly within 30 s.
- Cold launch reuses cached feed if < 60 s old.

### Fixed
- Inline-preview video black-square bug.
- Guest-mode text formatting rewritten.
- "Media too big" posts now offer "Open in Telegram".
- Crash on `UpdateMessageInteractionInfo` with null payload.

### Build
- Release/Beta fail at task-graph time without `keystore.properties`.
