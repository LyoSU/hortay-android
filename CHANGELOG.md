# Changelog

[Keep a Changelog](https://keepachangelog.com) · [SemVer](https://semver.org). One user-visible change per bullet, one sentence. Rationale lives in commits and KDoc — not here.

## [Unreleased]

### Added
- Round video messages play inline with progress ring, time chip, tap-to-pause and independent mute toggle.
- Hide channels from the home feed without unsubscribing (Channel info → "Hide from feed"; Settings → Hidden channels manages the list).
- Settings → Privacy → "Invisible reading" hides online status in Telegram while reading Hortay.

### Changed
- Posts now mark as read after 500 ms of viewport-stable dwell, down from 1 s.
- Opening a comments thread or user profile is instant — the destination enters its transition animation in the same frame as the tap, prefetch runs in parallel, and fast loads paint zero skeleton; only opens still loading past 120 ms surface a skeleton.
- Opening a channel from a feed post waits briefly (up to 400 ms) for the deeper history to land before mounting the screen, so the channel always opens with the full slice in place — no more "one post then 79 older posts visibly merge in above" on cold first opens.
- Skeleton anti-flicker grace tracks the system animator-duration-scale, so users who disabled animations see feedback immediately and users on x2 animation speed get a proportionally longer grace.
- Tapping the forwarded-from author on a forward chip drills into the source channel AT the original post, not just at its newest entry.
- Auto-download settings (Wi-Fi / Cellular / Roaming) now sync across Telegram clients via your account.
- Tapping an `@username` mention in a post opens the in-app user profile sheet instead of bouncing out to Telegram.
- The "↓ N unread" affordance in `OldestUnreadFirst` mode is now a circular scroll-to-bottom FAB with a count badge that softly bursts on press; tap returns you to the boundary where you left off reading, and the pill stops looking like a twin of the "X new posts" alert when both surface at once.

### Fixed
- Opening a channel from a feed post no longer shows a single post that suddenly grows into the full history mid-scroll; the screen waits for the deeper load and mounts the list in one frame at the right anchor.
- Returning from a channel to the feed no longer jumps to a different post when an album-tailed channel hydrates in the background.
- Channel scroll-up no longer hits an invisible "loading limit"; pagination now triggers near the older edge instead of running away on cold entry in OldestUnreadFirst mode.
- Scroll position is preserved on return from a channel and while scrolling inside a channel; reply chains no longer collapse into a Thread row, so LazyColumn's keyed anchor stays put through every ingest.
- Poll voting works — TDLib code 406 is treated as a silent no-op instead of surfacing as an error and reverting the vote.
- Channel cards paint on the first frame of entry; no white flash before the post appears.
- Channel entry no longer flashes a skeleton on fast resolves (now gated behind a 600 ms grace).
- Returning from Comments → ChannelScreen lands on the feed row you left, not at the top.
- Channel header subscriber count appears on the first frame instead of with a delay.
- Inline videos and GIFs render at correct aspect ratio on autoplay mount.
- Opening a cached channel from the feed no longer flashes a skeleton.
- Channel title no longer jumps upward when the subscriber count loads.
- Snap-scroll mode now lets you read tall posts and feels predictable on gentle flings (one fling = one logical step).
- Feed scroll position preserved across tab swaps even when read cursors advanced under the user.
- `OldestUnreadFirst` no longer lands on ancient or admin-owned posts on cold start.
- Albums render correctly in comments: no phantom comments from album mirrors, and albums posted as comments group as one card.
- "↓ N" unread pill no longer skips every second post; dwell-ack requires the row to be fully visible and the scroll idle.
- Deep-link to an old post no longer surfaces "link not found" on a busy feed; the merged-feed size cap is removed so the just-fetched anchor isn't evicted before the resolver sees it.
- Comments load in one chronological pass instead of revealing the newest first and then squeezing older comments in above.
- Long posts and media captions now collapse with "Показати більше" even when they contain a quote block.
- Tapping the channel chip from a post opened from the feed and swiping back now returns to the post instead of the feed; tapping a channel that's already one swipe-back away pops to it instead of stacking a duplicate.
- Cold-start feed now includes every subscribed channel's latest post, not only channels with an unread one — read context is back in Newest mode and `OldestUnreadFirst` no longer collapses to a tiny unread-only list.
- `OldestUnreadFirst` no longer lands on a weeks-old dormant unread when fresh unread exists; the cold-start anchor picks within a 7-day recency window and falls through to the newest post when nothing recent is unread.
- Never-opened / freshly-joined channels show the unread strip on their posts instead of silently appearing read until the user opens the chat.
- Cold-start anchor no longer lands on a self-authored post in an admin / outgoing-only channel — the `0 / 0` cursor shape (TDLib invariant for channels with no incoming reads) is no longer interpreted as "everything unread".
- Switching folder tabs no longer auto-scrolls onto a weeks-old dormant unread; the same 7-day recency floor that protects the cold-start landing now applies to every scope jump (folder switch, NavBar home re-tap, ↓N pill fallback).
- Returning to the feed from a deep drill (channel → comments → back-back) no longer lands on a post that loaded into the background while the overlay was up; the cold-start anchor is now pinned to the post identity instead of its row index, so ingested history above it can't shift the anchor onto a different row.

## [0.5.0] — 2026-05-17

### Added
- Interactive polls: tap to vote, quiz reveal, multi-answer staging, live countdown, photo banners, explanation sheet.
- Tapping any author surface opens a user profile bottom sheet (avatar, name, presence, bio, personal channel).
- Settings → About → "App language" pins UI language independently of system locale (uk/en).

### Fixed
- Tapping a reply quote / channel chip / foreign-author header inside comments uses an atomic `replaceTop` push — no more flash-and-snap-back.
- Channel header avatar in `ChannelScreen` shows the channel photo, not the latest personal-author admin's avatar.
- Tapping a foreign-channel author header drills into that channel instead of being a dead surface.
- Tapping a reply quote on a comments anchor drills into the original and lands at the replied-to post.
- Icons across the app match their semantic role — Wi-Fi, Cellular, Roaming, Photos, GIFs, Report, etc.
- "Data Saver is on" banner uses the active-state glyph.
- Opening a channel issues `OpenChat` first, so cold-cache history loads instead of showing "No posts".
- In-app channel-opens for non-channel targets show a snackbar instead of an empty channel screen.
- Custom-emoji TGS no longer crashes on stickers with malformed gradients.
- Live comments overlay no longer splices in comments from sibling threads in the same discussion group.
- Feed no longer scrolls up onto the previous post after returning from a drill or comments overlay.

### Performance
- Comments, report sheet, and country picker switched to `ImmutableList` so Compose skips recompositions when state is unchanged.

### Build
- LeakCanary in debug builds.
- Compose Compiler stability reports now generated under `app/build/compose_compiler/`.
- `compose_stability.conf` marks `kotlinx.collections.immutable` types as stable.
- R8 `-repackageclasses ''` shrinks the DEX string pool.
- Cleaned a dead ProGuard rule and several K2 smart-cast leftovers.

## [0.4.0] — 2026-05-15

### Added
- Fullscreen photo viewer: double-tap zoom, pinch-pan with bounds clamp.
- Share button in fullscreen media viewer.
- Web link previews render every `LinkPreviewType*` TDLib ships.
- Telegram Stars paid posts show locked / unlocked state instead of being dropped.
- Paid (⭐) reactions render as star pills (read-only).
- Invoice / Giveaway / Game / Story / Gift posts render as "open in Telegram" cards instead of being filtered out.
- Document / Audio / Voice-note / Video-note cards route taps to Telegram.
- Feed mode `OldestUnreadFirst` with read/unread boundary anchor.
- Snap-scroll mode.
- Per-chat read state with unread strip on the card edge.
- Inline retry on failed guest-mode channel rows.
- Floating "↓ N" unread-remaining counter.
- Settings → Feed → "Autoplay videos in feed" toggle.
- Authenticated Settings → "Continue without account" routes to guest mode without reinstall.
- Guest-mode tap on a post body surfaces a snackbar explaining comments need sign-in.

### Changed
- Default feed order is now `OldestUnreadFirst` (chat-app idiom: read on top, unread queue below, lands at boundary).
- Spoilers reveal as a Telegram-style shimmering particle cloud with Thanos-disperse animation.
- Channels-row status folded into `@handle · status` subtitle.
- Channel-drill rendered as overlay above the always-mounted feed.
- Channel lists, channel-info sheet, and the country picker use Material 3 Expressive `SegmentedListItem` / `ListItem`.
- `OldestUnreadFirst` boundary rule rendered as a peripheral session anchor.
- Feed / channel / deep-link state unified into one declarative state machine; first paint lands at the correct anchor in one frame.
- "↓ N unread" / "↑ N new" / NavBar home-tap pills do an instant jump with brief highlight for far targets, smooth scroll for near ones.
- Deep-link landings show a skeleton, then snap to the target in one frame.
- Guest-mode "Clear cache" asks for confirmation and keeps bookmarks.
- Guest-mode retry available for `NotFound` / `Private` channels.
- Guest-mode polling runs only when in guest mode AND foreground.

### Fixed
- Guest fullscreen viewer Save / Copy / Share work via Coil's on-disk cache; web-mode videos share the CDN URL.
- Fullscreen photo: zoom-out via second double-tap now actually fires.
- Floating "↓ N" pill no longer obscured by the guest-mode FAB.
- Centre play button on an ended video restarts from the beginning.
- Short videos (≤ 60 s) loop in fullscreen too.
- "↑/↓ N new posts" pill lands at the first new post, not the one before.
- Inline reply quote tap scrolls and highlights in the destination, not under the feed.
- Cross-channel reply tap passes the target message id through to the new channel screen.
- Polls / checklists / audio / documents expose text in quote cards.
- Report action uses a moderation glyph instead of the `?` fallback.
- Reactions on the post-detail anchor and on comments actually toggle.
- Fresh posts reach `OldestUnreadFirst` feed without restart.
- Cold-start scroll-pin no longer fires on mid-session arrivals.
- Photo albums no longer ship with missing members on slow networks.
- Cold start waits for the fresh feed; falls back to the cached snapshot only on failure.
- `OldestUnreadFirst` doesn't auto-scroll to the bottom when cursors absent.
- `OldestUnreadFirst` doesn't flash a random ancient post as the first visible card on cold start.
- Editing an album caption no longer collapses the card to a single photo.
- 5-photo album restore on relaunch via targeted `GetMessage` upgrade.
- Snapshot preserves saved album siblings of currently-degraded albums.
- Album cards stay unread until the chat cursor crosses the highest member id.
- Album dwell-read advances the cursor past every member id.
- Share / Open-in-Telegram fallback URL fixed for real-channel ids in `[1, 2^32)`.
- Web-mode media URL rotation re-fetches when a CDN token expires.
- Feed ordering deterministic across refreshes on same-second timestamps.
- `OldestUnreadFirst` boundary divider latched on landing and PTR — no more migration under scroll.
- Deep-link to a pruned target surfaces "link not found" after a 1500 ms grace instead of hanging.
- Guest single-channel screen reads through the per-channel SQL DAO — no more truncated history.
- Guest single-channel screen no longer overlaps the status bar.
- Guest channel chip on a post opens that channel in-app.

### Performance
- Reactions flip optimistically across feed / channel / post detail / comments; server reconciles via `UpdateMessageInteractionInfo`.
- Feed scroll jank rework: snapshot-state cursors, per-item viewport-centre state, `contentType` per FeedItem, conditional autoplay probe, scroll-gated MediaCache resync, late-drop minithumb.
- `TdVideoPlayer` texture attach moved to `factory`.

### Architecture
- `TimelineUiState` / `ChannelUiState` sealed unions; pure `buildUiState` + latched `reduce` + `rememberLatched` replace ~110 lines of `LaunchedEffect` + `snapshotFlow` pinning.
- `LazyListState` constructed once per route via `rememberSaveable(saver)`.

### Build
- Removed three unused Gradle deps (`androidx-navigation-compose`, `compose-material-icons-extended`, `sqldelight-primitive-adapters`).

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
