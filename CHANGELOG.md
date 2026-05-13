# Changelog

Format — [Keep a Changelog](https://keepachangelog.com), versions — [SemVer](https://semver.org).

**Authoring rules:**

- Each entry is a single user-visible change, 1–3 lines. No long-form rationale.
- Engineering rationale ("why this approach, what we tried, what broke") lives in the file's header KDoc or the commit body — not here.
- Categories: **Added** / **Changed** / **Fixed** / **Performance** / **Architecture** / **Build**.
- No emoji, no markdown tables, no code blocks. Plain bullet list.
- `[Unreleased]` accumulates changes until release; at release time, rename to `[X.Y.Z] — YYYY-MM-DD` and start a fresh `[Unreleased]`.

## [Unreleased]

### Added

- Reverse feed mode `OldestUnreadFirst` (Settings → Feed): read posts on top, unread below, auto-lands at the read/unread boundary.
- Snap-scroll mode (Settings → Feed): each post snaps into place on fling.
- Per-chat read state: `ReadCursors` (TDLib `UpdateChatReadInbox` + DataStore map), `UnreadStrip` on the post card's left edge.
- Bottom-pill mirror for `OldestUnreadFirst` — fresh arrivals at the tail.
- Unread-boundary divider between read and unread blocks in `OldestUnreadFirst`.
- Inline retry on failed rows in guest-mode Channels (statuses `Error` / `RateLimited` / `ParseFailure`).

### Changed

- Channels-row status folded into the `@handle · <status>` subtitle; error-tinted status wins over cached subscriber count.
- Per-status copy rewritten (UK + EN): "не вдалося оновити" / "couldn't refresh", "Telegram попросив почекати" / "Telegram asked us to wait", "сторінка змінилася" / "page changed".
- Channel-drill rendered as an overlay above the always-mounted Feed; scroll position is no longer serialised across drills.
- `PostsRepository.ingest()` filters by `Chat.positions` (Main/Archive), not just `isChannel()`.

### Fixed

- Fresh posts now reach the visible feed in `OldestUnreadFirst` without an app restart — auto-accept gate is mode-aware (`atTop` / `atBottom`).
- Cold-start scroll-pin no longer fires on mid-session arrivals (gated on `refreshing == true`).

### Architecture

- `LocalReadCursors` CompositionLocal decouples the reactive cursor map from the `TimelinePost` graph — `@Immutable` skippability preserved.

## [0.3.0] — 2026-05-12

### Added

- CSAE-compliant in-app reporting (Google Play Child Safety): TDLib `ReportChat` flow in auth mode; delegation to Telegram client / CustomTabs / mailto in guest mode; JSONL audit log.
- Safety section in Settings: Report content, Child safety standards, Privacy policy.
- `canReportChat` on `TimelinePost`; Report row in post action sheet.

### Changed

- Single-channel view extracted to `ChannelScreen` + `ChannelViewModel` (keyed by chatId). `TimelineScreen` is now feed-only.
- Inline links in post bodies no longer underlined (only explicit `<u>` entity).
- `chat_bubble` pill hidden on posts with zero replies.

### Fixed

- Cold launch always lands on Home top-of-feed.
- `#hashtag` taps inside a channel scope to that channel; `#tag@channel` suffix recognised; `tg://search?query=…` URLs extract the tag.
- Deep-links to inaccessible old posts no longer hang on the skeleton.
- Hardening pass on the link resolver: per-link try/catch, scheme allowlist, cache invalidation on logout, masked-link dialog hoisted to process-level state.
- Auto-download is skipped during the `Booting` phase; metered networks limited to photos.

### Performance

- Cold-start RPC budget cut ~30× by harvesting `Chat.lastMessage` instead of `GetChatHistory × N`.
- Inline custom-emoji TGS: shared `LottieDrawable` + double-buffered background render; parse-failure negative cache; janky frames 28% → 14%.
- Comments prefetch capped at top-3 viewport-stable; debounce 700→1200 ms.
- Photo size selection now uses a display-tier picker (Preview / Inline / Fullscreen).
- Metered-mode prefetch clamp: photos only; viewport-centre priority decay.

### Architecture

- `StartupCoordinator` (`Booting → Active`) gates speculative work until the post-auth RPC storm settles.

### Build

- `material3` 1.5.0-alpha19; `compileSdk` bumped to 37.
- Native debug symbols for `libtdjni.so` via an unstripped overlay.

## [0.2.0] — 2026-05-06

### Added

- Anonymous (guest) mode: read `t.me/s/<channel>` without sign-in; own `web.db` (SQLDelight); subscriptions persisted across modes.
- Cross-channel local search in guest mode (debounce 220 ms).
- Animated TGS custom emojis in guest mode via `LottieUrlStore`.
- Anonymous → authenticated migration: bottom sheet, throttle 1 channel/sec.
- Animated stickers (TGS / WebM / WEBP), animated emojis, custom-emoji reactions.
- Twitter-style "new posts" floating pill — visible feed frozen until accept.
- Material 3 predictive back for the comments overlay.
- English localisation + `<plurals>`.
- `WebDatabase`, `WebRepository`, `WebFeedSource` (Semaphore(6)), `WebFeedScheduler` (5-min foreground polling), OkHttp 10 MB cache.
- Settings → Storage & Traffic.
- `TdLifecycleBridge`: TDLib online flag + NetworkType.

### Changed

- AddChannelSheet auto-pastes valid links from the clipboard.
- Comments overlay re-opens instantly within a 30-second window.
- Cold launch reuses cached feed if younger than 60 s.
- Coil memory cache lowered 20% → 10% of Java heap.

### Fixed

- Inline-preview videos: black square replaced with `TextureView(isOpaque = false)`.
- Guest-mode post text formatting rewritten on a two-phase pipeline.
- "Media too big" posts emit as `Kind.Video` with empty URL + "Open in Telegram" hint.
- `parseUsernameFromInput` accepts 2-char handles.
- Crash on `UpdateMessageInteractionInfo` with null payload.

### Performance

- `PersistentList` for feed storage — O(log N) per-event mutations.
- `UpdateMessageInteractionInfo` coalescing 200 ms; `UpdateFile` throttle 10 Hz.

### Architecture

- `web.db` migration pipeline with `databases/N.db` snapshots.
- `PostContent` graph `@Immutable` end-to-end.

### Build

- Release/Beta fail at task-graph time when `keystore.properties` is missing.
