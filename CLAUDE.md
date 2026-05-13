<!--
Maintainer notes:
- This file is an INDEX, not a tutorial. Setup and "what this is" — see @README.md (imported below).
- Rationale ("why this way") lives NEXT TO THE CODE (header KDoc in TdClient.kt, MediaCache.kt, etc.).
  Here — only "where to look + what not to break".
- If you find yourself adding 3+ sentences of rationale to a section, that's a signal to move it into a code comment.
- Every rule must be verifiable: "Use X" rather than "be mindful of X".
-->

# CLAUDE.md

Project context for agents. README + CHANGELOG are pulled in by import — don't duplicate them.

@README.md
@CHANGELOG.md

## Language

- **All code, comments, file content, commit messages, and identifiers — English.** No exceptions.
- **User-facing strings — never hardcoded.** Route everything through `strings.xml` (default English) with a `values-uk/strings.xml` mirror. Use `<plurals>` for counted nouns (UK: one/few/many/other; EN: one/other).
- **Talk to the user in the language they use.** If they write in Ukrainian, reply in Ukrainian; if in Polish, reply in Polish; etc. Code, file content, and commit messages stay English regardless.
- **Adding any new user-visible string** = add to both `values/strings.xml` and `values-uk/strings.xml` in the same change. No "I'll translate later" — half-localised features ship half-broken.

## Two modes

1. **Authenticated (TDLib)** — full MTProto client. Persistence is TDLib's own (`useFileDatabase` / `useChatInfoDatabase` / `useMessageDatabase = true`).
2. **Guest / anonymous** — read `t.me/s/<u>` without credentials. Persistence in `web.db` (SQLDelight). Activated via `GuestModeStore` flag.

Single-process, single-Activity. `MainActivity` routes: `auth.Ready → MainScaffold` → `isGuest → WebModeScaffold` → else `AuthScreen`. Subscriptions (DataStore `SubscriptionsStore`) survive both transitions.

## Architecture (3 modules)

- **`:app`** — Compose UI, `AppGraph` (manual DI), repositories, ViewModels. JVM 17.
- **`:libtdlib`** — Vendored TDLib JNI (`org.drinkless.tdlib.{Client,TdApi}.java`) + `jniLibs`. **Don't edit the `.java` files by hand** — `scripts/update-tdlib.sh` will clobber them.
- **`:baselineprofile`** — Macrobenchmark, AOT cold-start profile.

DI is built in `HortayApp.onCreate` as `graph: AppGraph`, accessed via `(application as HortayApp).graph`. Heavy singletons (`MediaCache`, `CustomEmoji`, `ExoPlayerPool`) are injected via CompositionLocal in `MainActivity`.

## Load-bearing — don't change without reading the rationale in place

| What | Rationale lives in | TL;DR |
|---|---|---|
| TDLib two-stage update pipeline | `data/TdClient.kt:71-89` | UNLIMITED Channel → SharedFlow(64). |
| MediaCache single-coroutine reducer | `data/MediaCache.kt:125-138` | `fileEvents` Channel = one writer. |
| MediaCache stall watchdog | `data/MediaCache.kt:149-178` | 3 regimes; skip under `WaitingForNetwork`. |
| PostsRepository concurrency | `data/PostsRepository.kt:32-49` | `refreshMutex` + `PersistentList` + album coalescing. |
| PostsRepository cold-start contract | `data/PostsRepository.kt:refreshLocked` | Harvest `Chat.lastMessage` from `chatCache`. **Don't reintroduce `GetChat × N` / `GetChatHistory × N`** — FLOOD_WAIT class. |
| Compose stability chain | `data/PostContent.kt`, `TimelinePost.kt` | `@Immutable` end-to-end. |
| Cold-start snapshot | `data/TimelineSnapshotStore.kt` + `TimelineViewModel:59-66` | Restore → parallel `refreshIfStale`. |
| FLOOD_WAIT global gate | `data/TdClient.kt:100-113` | Single `AtomicLong` deadline. Recognise **both 420 and 429**. |
| TDLib quirks (album sync, stall) | `data/MediaCache.kt:55-71` + `PostsRepository.kt:67-74` | `tdlib/td#2523`, `tdlib/td#2585`. |
| Web-mode SQL portability | `app/src/main/sqldelight/.../web/db/*.sq` | All upserts via `INSERT OR IGNORE` + `UPDATE` — **not** `ON CONFLICT DO UPDATE`. Android 8/9 SQLite < 3.24. FTS5 skipped. |
| Web-mode media TTL | `data/web/Post.sq` + `WebFeedSource.DEFAULT_MEDIA_TTL_MS` | t.me/s/ CDN URLs live 1–4 h. |
| Guest-mode routing | `MainActivity.kt` | `auth.Ready → MainScaffold` → `isGuest → WebModeScaffold` → `AuthScreen`. |
| StartupCoordinator | `data/StartupCoordinator.kt` | `Booting → Active` gates speculative work. |
| Channel-drill as overlay | `ui/main/MainScaffold.kt` | `channelStack` is `remember`; AnimatedVisibility over always-mounted feed. |
| ReadCursors / OldestUnreadFirst | `data/ReadCursors.kt`, `ui/timeline/LocalReadCursors.kt` | `PersistentMap` + CompositionLocal; snapshot frozen at refresh boundaries. |

## Critical identifiers

| Identifier | Why it's load-bearing |
|---|---|
| `dev.lyo.hortay` (+ `.beta`) | applicationId, namespace, signing identity. |
| `org.drinkless.tdlib` | TDLib upstream FQCN. Renaming breaks JNI symbol lookup in libtdjni.so. |
| `~/.hortay/release.jks`, `keyAlias=hortay` | Release signing. Losing it = losing the upgrade path. |
| `HortayApp.graph` | Process-singleton DI root. |
| `LocalMediaCache` / `LocalCustomEmoji` / `LocalExoPlayerPool` / `LocalReadCursors` | CompositionLocal heavy-singleton injection. |

## Forbidden

- Hilt / Dagger / Koin — DI is manual (`AppGraph`).
- Firebase / Crashlytics / Sentry / analytics / phone-home — INTERNET is for TDLib + anonymous `t.me/s/` only.
- Room — kapt overhead; SQLDelight replaced it.
- OkHttp / Retrofit / Ktor as a general HTTP client — Coil pulls `coil-network-okhttp` for images, that's enough.
- FCM / push — TDLib `RegisterDevice` + `UpdateNotification`.
- ViewBinding / Fragment-based screens — Compose-only, single-Activity.
- Compose Navigation typed routes — string-based + `MainScaffold` switch is enough.
- `GetChat × N` / `GetChatHistory × N` per-channel fan-out on cold-start. On-demand paths (`loadChannelHistory`, `loadOlder`, `loadHistoryAround`) are fine.
- `rememberSaveable` for top-level navigation (`selectedTab`, `channelStack`) — cold launch must land on Home top-of-feed.
- Direct `client.send` from UI / Composable — always go through a repository (FLOOD_WAIT gate + UserMessageBus error routing live there).
- `enableV1Signing = true` — AGP 9 + R8 zip layout breaks JarInputStream v1.
- `x86_64` in release `abiFilters` — +24 MB libtdjni.so for zero users.
- `LOG_VERBOSITY` above 1.
- Hand-editing `libtdlib/.../{Client,TdApi}.java` — vendored upstream.
- New `.md` files without an explicit user request. README + CHANGELOG + this CLAUDE.md is enough.
- Literal `tween(...)` for transitions — use `MotionScheme.{default,fast}{Spatial,Effects}Spec()` everywhere.
- Hardcoded user-facing strings. Always `strings.xml` + `values-uk/strings.xml`.
- Non-English code, comments, or commits.
- TODO comments, commented-out dead code, debug `println`s.

## Allowed / correct practices

- **SQLDelight 2.3** for `web.db` only. TDLib mode runs without a DB (TDLib owns its persistence).
- **Material 3 Expressive**: `MaterialExpressiveTheme` + `MotionScheme.expressive()`.
- **Predictive back**: `PredictiveBackHandler` + `Animatable` + `graphicsLayer`. Only one handler `enabled = true` at any time.
- **Heavy singletons** via CompositionLocal. Passing them as Composable params caused a constructor explosion on the 600-row PostCard.
- **`@Immutable` end-to-end** for anything reaching Compose. Any `var` / `MutableList` / `Any?` in the graph is a silent skippability regression.
- **`PersistentList` / `PersistentMap`** from `kotlinx.collections.immutable`. Don't substitute `List` "for simplicity".
- **TDLib lifecycle**: `OpenChat` / `CloseChat` / `ViewMessages` go through `ChatPresence`. Wrap critical pairs in `NonCancellable` (`tdlib/td#2312`).
- **Per-account state cleanup**: every session-scoped state holder subscribes to `TdClient.loggedOut.collect { clear() }`. Includes process-wide sets and Composable state.
- **Lambdas in LazyColumn `items`** must be wrapped in `remember(...)` with stable keys. Inline `{ ... }` capturing non-stable scope breaks skipping under scroll.
- **Read state**: `ReadCursors` (TDLib + DataStore) is the single source of truth. UI consumes it via `LocalReadCursors`.
- **Monotonic clamp** on read cursors. Every seed (`UpdateNewChat`) and update (`UpdateChatReadInbox`) does `if (new > existing) put` — otherwise logout/login races corrupt cursors.
- **i18n by default**: every new user-facing string lands in both `values/strings.xml` and `values-uk/strings.xml` in the same commit. Use `<plurals>` for counts. `contentDescription` via `stringResource` with a placeholder for the entity name.
- **A11y**: every clickable Row/Box that isn't an `IconButton`/`Button` gets `Modifier.clickable(role = Role.Button)` + a meaningful `contentDescription`.

## Commands

```bash
./gradlew :app:installDebug
./gradlew :app:assembleRelease           # release APK (needs keystore.properties)
./gradlew :app:assembleBeta              # beta, applicationId.beta, versionCode = git commit count
./gradlew test                           # JUnit 5 unit tests
./gradlew :app:lintRelease               # R8 + lint vital
./gradlew :app:generateBaselineProfile   # AOT profile (~3–5 min on device)
./scripts/update-tdlib.sh [SHA]          # Bump TDLib (Docker, ~10–15 min)
adb logcat -s TdClient MediaCache PostsRepository
```

JDK 17, Gradle 9.4.1, AGP 9.2.0, Kotlin 2.3.10 (K2). Compose Compiler via `org.jetbrains.kotlin.plugin.compose`.

## Setup delta on top of README

- `keystore.properties` at the repo root (gitignored), `storeFile=~/.hortay/release.jks`, `keyAlias=hortay`. AGP enables release signing when this file exists (`app/build.gradle.kts:59-70`).
- Beta uses the same keystore + auto-versionCode from git.
- `gradle.properties` carries `HORTAY_CHILD_SAFETY_POLICY_URL` / `HORTAY_PRIVACY_POLICY_URL` for CSAE compliance.

## Code style

- Kotlin official, 4-space indent.
- **Comments are an engineering archive**, not self-description. Header KDocs that say "tried X, broke Y" are load-bearing for onboarding — don't compress them.
- Conventional commits, scope = package: `feat(timeline):`, `perf(media):`, `build(beta):`.
- `@Immutable` / `@Stable` on every data class that reaches Compose.
- No emoji in code, comments, commits, or user-facing strings.

## CHANGELOG

- Every user-visible change → a bullet under `## [Unreleased]` in `CHANGELOG.md`, Keep-a-Changelog format.
- Categories: **Added** / **Changed** / **Fixed** / **Performance** / **Architecture** / **Build**.
- One bullet = 1–3 lines. No emoji, tables, or code blocks. No long-form rationale.
- Engineering rationale ("why this way, what we tried, what broke") goes into the file's header KDoc or the commit body — **not** the CHANGELOG.
- At release time, rename `[Unreleased]` to `[X.Y.Z] — YYYY-MM-DD` and create a fresh `[Unreleased]`.

## Versioning

- `versionCode` for release and beta is auto-derived from `git rev-list --count HEAD` (wired in `androidComponents.onVariants` in `app/build.gradle.kts:158-185`). **Don't bump it by hand.**
- `bundleRelease` without a new commit produces the same versionCode Play already saw → 409. Always: commit → then bundle.
- `versionCode = 1` in `defaultConfig` is a sentinel for debug builds.
- `versionName` is manual. Bump on semver-worthy releases. Beta auto-appends `-beta-<sha>`.
- TDLib pin: `scripts/tdlib-version.txt` (auto-generated). Dedicated commit `chore(tdlib): bump to <sha>` per bump.
- Native debug symbols: `scripts/update-tdlib.sh` (default `KEEP_DEBUG=1`) extracts unstripped libs into `libtdlib/build/tdlib-unstripped/<abi>/libtdjni.so`. AGP `debugSymbolLevel = "FULL"` packages symbol info into the AAB metadata.

## Common agent mistakes

- Don't add Hilt; don't propose it. Settled, rejected.
- Don't substitute `PersistentList` → `List` "for simplicity".
- Don't strip `@Immutable` from the `TimelinePost` graph. Silent skippability regression.
- Don't call `client.send` from UI / Composable.
- Don't pre-fetch the whole chat history. 200 channels × `GetChatHistory(80)` = instant FLOOD_WAIT.
- Don't edit `libtdlib/.../{Client,TdApi}.java`.
- Don't raise `LOG_VERBOSITY` above 1.
- Don't create extra `.md` files.
- Don't use literal `tween(...)` for transitions — `MotionScheme` everywhere.
- Don't forget logout-cleanup for per-account state (subscribe to `TdClient.loggedOut`).
- Don't parse FLOOD_WAIT from message strings — `TdClient` does it centrally; expose a helper if you need one.
- Don't hardcode user-facing strings. Don't ship a string in English without its `values-uk` mirror in the same commit.
- Don't reply to the user in English when they're writing in another language.
