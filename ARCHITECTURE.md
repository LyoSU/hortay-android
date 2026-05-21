<!--
Maintainer notes:
- INDEX, not tutorial. Setup / "what this is" → README.md. Rationale → header KDoc next to code.
- Every rule must be verifiable: "Use X" / "Don't Y", not "be mindful of X".
- 3+ sentences of rationale = move it into a code comment.
-->

# Architecture

Modules, load-bearing decisions, hard rules, and conventions. Pair with [README.md](README.md) for setup.

## Language policy

- **Code, comments, identifiers, commit messages — English only.**
- **User-facing strings — never hardcoded.** `strings.xml` (default English) + `values-uk/strings.xml` mirror, in the same commit. Use `<plurals>` (UK: one/few/many/other; EN: one/other).
- **Talk to the user in their language** (UA/PL/EN/…). Code stays English.

## Two modes

1. **Authenticated (TDLib)** — full MTProto. Persistence is TDLib's own (`useFileDatabase` / `useChatInfoDatabase` / `useMessageDatabase = true`).
2. **Guest / anonymous** — read `t.me/s/<u>` without credentials. Persistence in `web.db` (SQLDelight). Activated via `GuestModeStore` flag.

Single-process, single-Activity. `MainActivity` routes: `auth.Ready → MainScaffold` → `isGuest → WebModeScaffold` → else `AuthScreen`. Subscriptions (DataStore `SubscriptionsStore`) survive both transitions.

## Architecture (3 modules)

- **`:app`** — Compose UI, `AppGraph` (manual DI), repositories, ViewModels. JVM 17.
- **`:libtdlib`** — Vendored TDLib JNI (`org.drinkless.tdlib.{Client,TdApi}.java`) + `jniLibs`. Don't hand-edit the `.java` files — `scripts/update-tdlib.sh` will clobber them.
- **`:baselineprofile`** — Macrobenchmark, AOT cold-start profile.

DI built in `HortayApp.onCreate` as `graph: AppGraph`, accessed via `(application as HortayApp).graph`. Heavy singletons (`MediaCache`, `CustomEmoji`, `ExoPlayerPool`, `ReadCursors`) injected via CompositionLocal in `MainActivity`.

**Modularization trigger.** Stay single-`:app` until any of: > 300 Kotlin files in `:app/src/main`, cold build > 60 s on dev hardware, or > 1 active contributor. Cut lines are already encoded by packages — `data/web/*` → `:data-web`, `ui/timeline/*` + `ui/main/*` → `:feature-timeline`, `ui/theme/*` + `ui/components/*` → `:core-ui`. Until then, enforce boundaries with `internal` visibility, not separate modules.

## Load-bearing — don't change without reading the rationale in place

| What | Rationale lives in | TL;DR |
|---|---|---|
| TDLib two-stage update pipeline | `data/TdClient.kt:71-89` | UNLIMITED Channel → SharedFlow(64). |
| MediaCache single-coroutine reducer | `data/MediaCache.kt:125-138` | `fileEvents` Channel = one writer. |
| MediaCache stall watchdog | `data/MediaCache.kt:149-178` | 3 regimes; skip under `WaitingForNetwork`. |
| PostsRepository concurrency | `data/posts/PostsRepository.kt` (refreshMutex KDoc) | `refreshMutex` serialises batch refresh; live ingest runs OUTSIDE mutex via `MutableStateFlow.update` CAS-loop. Lambdas to `_posts.update {}` MUST be pure functions of the snapshot. |
| PostsRepository cold-start contract | `data/posts/PostsRepository.kt:triggerInitialSync` + `handleNewChat` | Event-driven: `LoadChats(Main)` + `LoadChats(Archive)` trigger the update stream; `UpdateNewChat` ingests `chat.lastMessage` directly. No harvest, no GetChats, no 2 s wait. **Don't reintroduce `GetChat × N` / `GetChatHistory × N`** — FLOOD_WAIT class (tdlib/td#3019). |
| UpdateChatLastMessage race buffer | `data/posts/PostsRepository.kt` (`pendingLastMessages`) | An `UpdateChatLastMessage` arriving before its matching `UpdateNewChat` is stashed, then flushed by `handleNewChat`. Closes the cold-start window that previously dropped these and waited for the next PTR. |
| Compose stability chain | `data/PostContent.kt`, `TimelinePost.kt` | `@Immutable` end-to-end. |
| Cold-start snapshot | `data/TimelineSnapshotStore.kt` + `TimelineViewModel.init` | Snapshot restore fires immediately; the live update stream from `AppGraph`-driven `triggerInitialSync` overwrites it via `foldRawIntoCurrent`. |
| FLOOD_WAIT global gate | `data/TdClient.kt:100-113` | Single `AtomicLong` deadline. Recognise **both 420 and 429**. |
| TDLib quirks (album sync, stall) | `data/MediaCache.kt:55-71` + `data/posts/PostsRepository.kt:67-74` | `tdlib/td#2523`, `tdlib/td#2585`. |
| Web-mode SQL portability | `app/src/main/sqldelight/.../web/db/*.sq` | All upserts via `INSERT OR IGNORE` + `UPDATE` — **not** `ON CONFLICT DO UPDATE`. Android 8/9 SQLite < 3.24. FTS5 skipped. |
| Web-mode media TTL | `data/web/Post.sq` + `WebFeedSource.DEFAULT_MEDIA_TTL_MS` | t.me/s/ CDN URLs live 1–4 h. |
| Guest-mode routing | `MainActivity.kt` | `auth.Ready → MainScaffold` → `isGuest → WebModeScaffold` → `AuthScreen`. |
| StartupCoordinator | `data/StartupCoordinator.kt` | `Booting → Active` gates speculative work. |
| Channel-drill as overlay | `ui/main/MainScaffold.kt` | `channelStack` is `remember`; AnimatedVisibility over always-mounted feed. |
| ReadCursors / OldestUnreadFirst | `data/ReadCursors.kt`, `ui/timeline/LocalReadCursors.kt` | `PersistentMap` + CompositionLocal; snapshot frozen at refresh boundaries. |
| Tap-navigation contract | `data/TapNavigation.kt`, `ui/main/MainScaffold.kt` (`pushChannel`, `CHANNEL_PUSH_PREFETCH_TIMEOUT_MS`) | **Channels:** push AWAITS `loadChannelHistory` up to 400 ms, then mounts `NavEntry.Channel` (warm/cache opens stay instant via cooldown short-circuit). **Comments:** still fire-and-forget. Destination-side `SCREEN_MOUNT_GRACE_MS` (120 ms × `ValueAnimator.getDurationScale()`) suppresses skeleton flicker as a backstop. The channel exception exists because the cold-start `UpdateNewChat → handleNewChat` ingest seeds the slice with exactly one post per channel (the `chat.lastMessage` snapshot); in OldestUnreadFirst (asc-by-date) those 79 older posts merging in above the visible row read as a "stretching" jump that the destination-side gate alone couldn't hide. |
| Interaction-stream contract | `data/posts/PostsRepository.kt:viewMessages` + `data/ChatPresence.kt:isOpen` + `ui/timeline/TimelineScreen.kt` (focus tracker) | TDLib streams `updateMessageInteractionInfo` only for OPEN chats (tdlib/td#2312). Merged feed obeys "usually one chat opened" (tdlib/td#2695) by OpenChat-ing the **dominant-visible** post's chat — NOT `firstVisibleItem`, which on full-width cards is often the partially-clipped neighbour. `viewMessages` picks `force_read` from `ChatPresence.isOpen(chat)`: `false` when open (canonical "user is actively reading"), `true` when closed (closed-chat read-state workaround). The focus tracker must **re-ack the focused post's ids via viewMessages AFTER OpenChat**, in the same `NonCancellable` block — read-ack dwell (500 ms) fires before focus dwell (600 ms), so without the re-ack the focused post's only viewMessages call would have happened with the chat still closed and `force_read=true`. ChannelScreen doesn't need the re-ack because `ChannelViewModel.init` opens the chat before any viewMessages. |
| Channel-post reactions: known limitations | (TDLib upstream, no app-side workaround) | (a) `updateMessageInteractionInfo` does NOT push the **initial** reaction (0 → 1) on a channel post — per tdlib/td#2312 the stream only wakes on the second reaction. Real-world impact: counts may show `N=2` instead of `N=1` for a few seconds on freshly-reacted posts. (b) Reactions on **non-focused** posts in the merged feed are eventual-consistent — only the focused chat's stream is live. Both are accepted as TDLib design constraints; do not add periodic `viewMessages` re-pokes or `GetMessage` polls "to fix" them — `GetMessage` reads local DB only (returns the same stale value), and Levin has not endorsed periodic re-pokes as a workaround. If user reports make this UX gap significant, the only known-working escape is a point-targeted `GetChatHistory(focusedId+1, offset=-1, limit=1)` which forces a server refetch — but that fights the cold-start FLOOD_WAIT inviolant and should be gated carefully. |
| Interaction-buffer merge-on-put | `data/posts/PostsRepository.kt` (`pendingInteractionInfo`, `handleInteractionInfo`) | TDLib often emits two back-to-back `updateMessageInteractionInfo` updates for the same message — first one with fresh views/replies but `reactions=null` ("see your local copy"), then one with the new `reactions`. Buffer inserts go through `ConcurrentHashMap.merge` with null-preserve per field; a naive `put` lets a later null-reactions heartbeat overwrite an earlier non-null one inside a 200 ms coalesce window. |

## Critical identifiers

| Identifier | Why it's load-bearing |
|---|---|
| `dev.lyo.hortay` (+ `.beta`) | applicationId, namespace, signing identity. |
| `org.drinkless.tdlib` | TDLib upstream FQCN. Renaming breaks JNI symbol lookup in libtdjni.so. |
| Release keystore (`storeFile` + `keyAlias` from `keystore.properties`) | Release signing identity. Losing it = losing the upgrade path for installed users. |
| `HortayApp.graph` | Process-singleton DI root. |
| `LocalMediaCache` / `LocalCustomEmoji` / `LocalExoPlayerPool` / `LocalReadCursors` | CompositionLocal heavy-singleton injection. |

## Hard rules

### Architecture & DI

Each `❌` carries a **Revisit:** clause — the concrete condition that would justify reopening the decision. Without that condition, the answer is no.

- ❌ Hilt / Dagger / Koin. DI is manual (`AppGraph`). **Revisit:** > 1 active contributor, or `AppGraph` > 60 properties, or multi-module split lands.
- ❌ Firebase / Crashlytics / Sentry / analytics / phone-home. INTERNET is for TDLib + anonymous `t.me/s/` only. **Revisit:** never — privacy-as-feature.
- ❌ Room. SQLDelight 2.3 owns `web.db`; TDLib owns its own. **Revisit:** never (no shared scope).
- ❌ OkHttp / Retrofit / Ktor as a general HTTP client. Coil pulls `coil-network-okhttp` for images, OkHttp is declared directly only for the web-mode `t.me/s/` pipeline + custom-emoji JSON. **Revisit:** if a typed REST backend joins the stack (none planned).
- ❌ FCM / push. TDLib `RegisterDevice` + `UpdateNotification`. **Revisit:** if TDLib push proves unreliable in field reports.
- ❌ ViewBinding / Fragments. Compose-only, single-Activity. **Revisit:** never.
- ❌ Compose Navigation typed routes. Current overlay-heavy nav (`NavStack` + polymorphic `NavEntry` sealed) covers ~6 destinations. **Revisit:** back-stack past ~8 destinations, or recurring need for type-safe args, or `androidx.navigation` ships overlay-destination primitives that subsume `NavStack`.
- ✅ SQLDelight 2.3 for `web.db` only. TDLib mode runs without a DB.

### TDLib usage

- ❌ Direct `client.send` from UI / Composable. Go through a repository (FLOOD_WAIT gate + UserMessageBus error routing live there).
- ❌ `GetChat × N` / `GetChatHistory × N` per-channel fan-out on cold-start. 200 channels × `GetChatHistory(80)` = instant FLOOD_WAIT. On-demand paths (`loadChannelHistory`, `loadOlder`, `loadHistoryAround`) are fine.
- ❌ Parsing FLOOD_WAIT from error message strings. `TdClient` handles it centrally (420 + 429) — extend the helper if needed.
- ❌ Hand-editing `libtdlib/.../{Client,TdApi}.java` — vendored upstream.
- ❌ `LOG_VERBOSITY > 1`.
- ✅ `OpenChat` / `CloseChat` / `ViewMessages` go through `ChatPresence`. Wrap critical pairs in `NonCancellable` (`tdlib/td#2312`).
- ✅ Every session-scoped state holder subscribes to `TdClient.loggedOut.collect { clear() }`. Includes process-wide sets and Composable state.

### Compose

- ❌ `rememberSaveable` for top-level navigation (`selectedTab`, `channelStack`). Cold launch must land on Home top-of-feed.
- ❌ Literal `tween(...)` for transitions. Use `MotionScheme.{default,fast}{Spatial,Effects}Spec()`.
- ❌ Stripping `@Immutable` from `TimelinePost` / `PostContent` graph. Silent skippability regression.
- ❌ Substituting `PersistentList` / `PersistentMap` with `List` / `Map` "for simplicity".
- ❌ Passing heavy singletons as Composable params (caused constructor explosion on 600-row PostCard). Use CompositionLocal.
- ❌ Blocking comments / user-profile / other non-channel pushes on a prefetch. The destination-side anti-flicker grace (`SCREEN_MOUNT_GRACE_MS`, 120 ms) is the right tool for those — the original sender slot is invisible past the slide-in so the prefetch has free runway. **Exception: channel pushes** await `loadChannelHistory` up to `CHANNEL_PUSH_PREFETCH_TIMEOUT_MS` (400 ms) because OldestUnreadFirst + cold-start `Chat.lastMessage` harvest leaves the slice with one post — without the await, 79 older posts merge in above the visible row mid-frame and the user reads it as a "stretching" jump. See `ui/main/MainScaffold.kt` `pushChannel`.
- ❌ Hardcoding a skeleton-grace number per screen. Screen-mount loading states use `rememberDeferredLoading(graceMs = SCREEN_MOUNT_GRACE_MS)` (120 ms, anti-flicker); media file-IO uses the default `LOADING_OVERLAY_GRACE_MS` (600 ms, longer latency budget). Two calibrated constants, not knobs to retune ad-hoc.
- ❌ Push-side flags on `NavEntry` that override screen-side loading (e.g. `preloadTimedOut`, `instantSkeleton`). They leak push logic into the model and turn one decision into two — the screen already has all the data it needs (its own state) to decide.
- ✅ Material 3 Expressive: `MaterialExpressiveTheme` + `MotionScheme.expressive()`.
- ✅ Predictive back: `PredictiveBackHandler` + `Animatable` + `graphicsLayer`. Only one handler `enabled = true` at any time.
- ✅ Lambdas in `LazyColumn`/`LazyRow` items wrapped in `remember(...)` with stable keys. Inline `{ … }` capturing non-stable scope breaks skipping under scroll.
- ✅ Read state: `ReadCursors` is the single source of truth, consumed via `LocalReadCursors`. Cursors are monotonic — clamp on every seed/update (`if (new > existing) put`) to survive logout/login races.
- ✅ `rememberDeferredLoading` scales its grace by `ValueAnimator.getDurationScale()` via `effectiveSkeletonGrace` — reduced motion (system / developer options) collapses the grace to 0 and paints the skeleton on the first Loading frame.

### i18n & a11y

- ❌ Hardcoded user-facing strings. Always `values/strings.xml` + `values-uk/strings.xml` in the same commit.
- ❌ Replying to the user in English when they write in another language.
- ✅ `<plurals>` for counts. `contentDescription` via `stringResource(...)`.
- ✅ Every clickable Row/Box that isn't `IconButton`/`Button` gets `Modifier.clickable(role = Role.Button)` + meaningful `contentDescription`.

### Build & release

- ❌ `enableV1Signing = true` — AGP 9 + R8 zip layout breaks JarInputStream v1.
- ❌ `x86_64` in release `abiFilters` — +24 MB libtdjni.so for zero users.
- ❌ Bumping `versionCode` by hand. It's auto-derived from `git rev-list --count HEAD` (`app/build.gradle.kts:158-185`).
- ❌ `bundleRelease` without a fresh commit — same versionCode → Play returns 409. Workflow: commit → bundle.

### Workspace

- ❌ New `.md` files without an explicit user request. README + CHANGELOG + ARCHITECTURE + SECURITY is the full set.
- ❌ TODO comments, commented-out dead code, debug `println`s.
- ❌ Compressing header KDocs that say "tried X, broke Y" — they're load-bearing for onboarding.
- ✅ Conventional commits, scope = package: `feat(timeline):`, `perf(media):`, `build(beta):`.
- ✅ `@Immutable` / `@Stable` on every data class that reaches Compose.

### Changelog

CHANGELOG.md is release notes for a user, not a PR description. Rationale lives elsewhere (commit body, load-bearing KDoc, this file). Aggressive editing of entries is OK — write the entry as it should be read on release day, not as a stream of consciousness during the fix.

- ❌ Multi-sentence paragraphs per bullet. One sentence, one change. Two sentences max only if the second is "no behaviour change" / "see CommitX for rationale".
- ❌ File paths, line numbers, function names in bullets (`PostsRepository.refreshLocked`, `TdClient.kt:71-89`). Internal — belongs in commits / KDoc.
- ❌ TDLib / Android issue links, "per Aliaksei Levin on tdlib/td#N" citations, RFC numbers. Internal.
- ❌ "Two compounding root causes…", "the previous fix…", post-mortem narrative. Internal.
- ❌ Reformulating from the developer's POV ("fixed bug in foo()"). Use the user's POV ("X works again" / "Y no longer Z").
- ✅ Categories in Keep-a-Changelog order: **Added** → **Changed** → **Fixed** → **Performance** → **Architecture** → **Build**. Skip empty ones.
- ✅ English; UI strings keep their original glyphs (`⭐`, `→`, `↓ N`, `@handle`).
- ✅ New entries go under `## [Unreleased]`. On release: rename `[Unreleased]` to `## [X.Y.Z] — YYYY-MM-DD` (em-dash, ISO date) and start a fresh `## [Unreleased]` block on top.
- ✅ When a single fix sentence loses load-bearing context, point at the durable home (`ARCHITECTURE.md → "Load-bearing"`, the file's KDoc, the commit) — never re-explain inline.
- ✅ Before adding a bullet under `[Unreleased]`, check it isn't already there in a different phrasing. Merge variants of the same user-visible change.

## Commands

```bash
./gradlew :app:installDebug
./gradlew :app:assembleRelease           # release APK (needs keystore.properties)
./gradlew :app:assembleBeta              # beta, applicationId.beta, versionCode = git commit count
./gradlew test                           # JUnit 5 unit tests
./gradlew :app:lintRelease               # R8 + lint vital — pre-commit gate
./gradlew :app:generateBaselineProfile   # AOT profile (~3–5 min on device)
./scripts/update-tdlib.sh [SHA]          # Bump TDLib (Docker, ~10–15 min)
adb logcat -s TdClient MediaCache PostsRepository ChatPresence
```

Toolchain: JDK 17, Gradle 9.4.1, AGP 9.2.0, Kotlin 2.3.10 (K2). Compose Compiler via `org.jetbrains.kotlin.plugin.compose`.

### Verifying rules

- **Compose skippability** — Compose Compiler stability reports are wired via the Kotlin Compose plugin; check `app/build/compose_compiler/` after a build. New `@Stable`/`@Immutable` regressions show up as "unstable" classes in the graph.
- **Translations parity** — `./gradlew :app:lintRelease` flags `MissingTranslation`. CI gate.
- **Cold-start budget** — `:baselineprofile` macrobenchmark + `adb logcat -s PostsRepository` (look for `GetChat`/`GetChatHistory` storms).
- **Static analysis (Compose stability + Kotlin smells)** — `./gradlew :app:detekt` (config: `config/detekt/detekt.yml`, baseline: `config/detekt/baseline.xml`). Not bundled into `lintRelease` — heavy on dev hardware; CI gate adds it explicitly. Compose rules from `nlopez/compose-rules` surface `Modifier` ordering, `UnstableCollections` (platform `List`/`Map` reaching Composables), `CompositionLocalAllowlist` and the rest of the Compose-specific smells. Run `./gradlew :app:detektBaseline` once after enabling to seed the baseline; commit the regenerated file.

## Setup delta on top of README

- `keystore.properties` at the repo root (gitignored) supplies `storeFile`, `storePassword`, `keyAlias`, `keyPassword`. AGP enables release signing only when this file exists (`app/build.gradle.kts:59-70`).
- Beta uses the same keystore + auto-versionCode from git.
- `gradle.properties` carries `HORTAY_CHILD_SAFETY_POLICY_URL` / `HORTAY_PRIVACY_POLICY_URL` for CSAE compliance.

## Versioning

- `versionCode` for release and beta is auto-derived from `git rev-list --count HEAD` (`app/build.gradle.kts:158-185`).
- `versionCode = 1` in `defaultConfig` is a sentinel for debug builds.
- `versionName` is manual. Bump on semver-worthy releases. Beta auto-appends `-beta-<sha>`.
- TDLib pin: `scripts/tdlib-version.txt` (auto-generated). Dedicated commit `chore(tdlib): bump to <sha>` per bump.
- Native debug symbols: `scripts/update-tdlib.sh` (default `KEEP_DEBUG=1`) extracts unstripped libs into `libtdlib/build/tdlib-unstripped/<abi>/libtdjni.so`. AGP `debugSymbolLevel = "FULL"` packages them into the AAB.
