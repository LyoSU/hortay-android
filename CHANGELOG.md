# Changelog

[Keep a Changelog](https://keepachangelog.com) · [SemVer](https://semver.org). One user-visible change per bullet; rationale lives in commits, not here.

## [Unreleased]

### Added
- Feed mode `OldestUnreadFirst` (Settings → Feed): read on top, unread below, lands at boundary.
- Snap-scroll mode (Settings → Feed).
- Per-chat read state with unread strip on card edge.
- Inline retry on failed guest-mode Channel rows.
- Floating "↓ N" unread-remaining counter in `OldestUnreadFirst` — ticks down live as you scroll; tap to jump to the next unread.

### Changed
- Channels-row status folded into `@handle · <status>` subtitle (UK + EN).
- Channel-drill rendered as overlay above always-mounted Feed.

### Fixed
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

### Performance
- Reaction taps flip optimistically across feed / channel / post detail / comments; server reconciles via `UpdateMessageInteractionInfo`, RPC failure rolls back.

### Architecture
- `ReactionTogglePolicy` + `PostsRepository.applyOptimisticReaction` + `CommentsRepository` per-thread override map merged into the single-collector update fan-in.

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
