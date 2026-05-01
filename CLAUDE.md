<!--
Maintainer notes (stripped from Claude's context — see code.claude.com/docs/en/memory):
- Цей файл — ІНДЕКС, не туторіал. Setup і "що це" — у @README.md (підтягнуто нижче).
- Rationale ("чому так") живе ПОРУЧ З КОДОМ (header KDoc у TdClient.kt, MediaCache.kt тощо).
  Тут — лише "куди дивитись + чого не ламати".
- Якщо знайдете себе додаючи 3+ речення rationale в розділ — це сигнал переїхати до code-comment'а.
- Кожне правило має бути verifiable: "Use X" а не "be mindful of X".
-->

# CLAUDE.md

Project context for Claude Code agents. README + CHANGELOG підтягуються імпортом — не дублюємо.

@README.md
@CHANGELOG.md

## Кодова доповнення до README

README пояснює що це і як запустити. Нижче — те, чого README не каже і код сам по собі не показує.

## Архітектура (3 модулі)

- **`:app`** — Compose UI, `AppGraph` (manual DI), repositories, ViewModels. JVM 17.
- **`:libtdlib`** — Vendored TDLib JNI (`org.drinkless.tdlib.{Client,TdApi}.java`) + `jniLibs/{arm64-v8a,x86_64}`. **Не редагувати `.java` руками** — `scripts/update-tdlib.sh` затре.
- **`:baselineprofile`** — Macrobenchmark проти `benchmark` build type, генерує AOT cold-start профіль.

DI-граф створюється в `HortayApp.onCreate` як `graph: AppGraph`, доступається через `(application as HortayApp).graph`. Heavy synglet'и (`MediaCache`, `CustomEmoji`, `ExoPlayerPool`) інжектяться у Compose-дерево через CompositionLocal — `LocalMediaCache` тощо у `MainActivity:34-37`.

## Load-bearing — НЕ ламати без читання rationale на місці

Кожен пункт має детальний rationale у header-коментарі вказаного файлу. Тут — лише "куди дивитись".

| Що | Де rationale | TL;DR не-ламання |
|---|---|---|
| TDLib two-stage update pipeline | `data/TdClient.kt:71-89` | UNLIMITED Channel → SharedFlow(64). Дві ранніші ітерації обидві ламали ordering або login burst. |
| MediaCache single-coroutine reducer | `data/MediaCache.kt:125-138` | `fileEvents` Channel = один writer для slot state. Інший writer = silent dropped Ready emit. |
| MediaCache stall watchdog | `data/MediaCache.kt:149-178` | 3 регіми (background suspend / idle 5s / active 2s) + skip під `WaitingForNetwork`. |
| PostsRepository concurrency | `data/PostsRepository.kt:32-49` | `refreshMutex` + `PersistentList` + album coalescing per `(chatId, mediaAlbumId)`. |
| Compose stability chain | `data/PostContent.kt`, `TimelinePost.kt` | `@Immutable` end-to-end від `TimelinePost`. Будь-який `var`/`MutableList`/`Any?` у графі = тихий regress skippability. |
| Cold start снапшот | `data/TimelineSnapshotStore.kt` + `TimelineViewModel:59-66` | Restore → паралельний refreshIfStale. Race-safe через `_posts.update` reducer. |
| FLOOD_WAIT global gate | `data/TdClient.kt:100-113` | Один глобальний `AtomicLong` deadline. Per-method tracking — overkill для read-only. |
| TDLib quirks (album sync, stall, post-completion resync) | `data/MediaCache.kt:55-71` + `PostsRepository.kt:67-74` | Issue refs: `tdlib/td#2523` (albums), `tdlib/td#2585` (stall mid-chunk). |

## Critical identifiers

| Ідентифікатор | Чому load-bearing |
|---|---|
| `dev.lyo.hortay` (+ `.beta`) | applicationId, namespace, signing identity. Зміна = всі users перевстановлюють. |
| `org.drinkless.tdlib` | TDLib upstream FQCN. Зміна = JNI symbol mismatch у libtdjni.so. |
| `~/.hortay/release.jks`, `keyAlias=hortay` | Release signing. Loose'нете keystore = втрата upgrade path для всіх users. |
| `HortayApp.graph` | Process-singleton DI root. |
| `LocalMediaCache`/`LocalCustomEmoji`/`LocalExoPlayerPool` | CompositionLocal heavy-singleton injection. Альтернатива (param-через-Composable) була пробувалась — конструктор-explosion на 600-row PostCard. |

## Заборонено

- ❌ Hilt / Dagger / Koin — DI ручний (`AppGraph`), навмисно. Один process, ~15 синглтонів.
- ❌ Firebase / Crashlytics / Sentry / analytics / phone-home — INTERNET виключно для TDLib.
- ❌ Room / SQLDelight / будь-яка локальна БД — TDLib персистить сам (`useFileDatabase`/`useChatInfoDatabase`/`useMessageDatabase = true`).
- ❌ OkHttp / Retrofit / Ktor — Coil тягне `coil-network-okhttp` для зображень, цього достатньо.
- ❌ FCM / push через Firebase — TDLib `RegisterDevice` + `UpdateNotification` коли треба буде.
- ❌ ViewBinding / Fragment-based screens — Compose-only, single-Activity.
- ❌ Compose Navigation typed routes — string-based + `MainScaffold` switch достатньо.

## Команди

```bash
./gradlew :app:installDebug              # Debug на пристрій
./gradlew :app:assembleRelease           # Release APK (потребує keystore.properties)
./gradlew :app:assembleBeta              # Beta-канал, applicationId.beta, версія "0.1.0-beta-<sha>", versionCode = git commit count
./gradlew test                           # JUnit 5 unit (PostFilterStrategy, CommentsRepository через FakeTdSender)
./gradlew :app:lintRelease               # R8 + lint vital
./gradlew :app:generateBaselineProfile   # Re-bake AOT профіль (~3-5 хв на пристрої)
./scripts/update-tdlib.sh [SHA]          # Bump TDLib з upstream (Docker, ~10-15 хв)
adb logcat -s TdClient MediaCache PostsRepository
```

JDK 17, Gradle 9.4.1, AGP 9.2.0, Kotlin 2.3.10 (K2). Compose Compiler через `org.jetbrains.kotlin.plugin.compose`, не legacy `kotlinCompilerExtensionVersion`.

## Setup-delta поверх README

README пояснює `local.properties` (telegram.apiId/apiHash) і TDLib build. Додатково для signing/розробки:

- `keystore.properties` у корені (gitignored), `storeFile=~/.hortay/release.jks`, `keyAlias=hortay`. AGP вмикає release signing коли файл існує (`app/build.gradle.kts:59-70`).
- Beta-білди не потребують додаткового setup'у — використовують той самий release keystore + auto-versionCode з git.

## Code style

- Kotlin official, 4-space indent.
- **Коментарі = архів інженерних рішень**, не самоопис. У repos типу cally / hortay header-KDoc'и містять "пробували X, зламалося Y" — це load-bearing для онбордингу. Не редагуйте в "стислу версію".
- Conventional commits зі скоупом-як-пакет: `feat(timeline):`, `perf(media):`, `build(beta):`.
- `@Immutable` / `@Stable` на data class усе, що дотягується до Compose.
- Без emojis у коді / коммітах.

## НЕ робіть (типові помилки агента)

- НЕ додавайте Hilt, не пропонуйте. Обговорено, відмовлено.
- НЕ замінюйте `PersistentList` → `List` "для простоти".
- НЕ вилучайте `@Immutable` з графа `TimelinePost`. Тихий regress skippability.
- НЕ робіть `client.send` з UI / Composable. Завжди через repository (FLOOD_WAIT gate + UserMessageBus error routing там).
- НЕ вмикайте `enableV1Signing = true`. AGP 9 + R8 zip layout ламає JarInputStream v1. Деталі — git коміт `444d0fb`.
- НЕ додавайте `x86_64` у release `abiFilters`. +24MB libtdjni.so для нуля користувачів.
- НЕ pre-fetch'те весь chat history. 200 каналів × `GetChatHistory(80)` = миттєвий FLOOD_WAIT.
- НЕ редагуйте `libtdlib/src/main/java/.../{Client,TdApi}.java` — vendored upstream.
- НЕ підіймайте `LOG_VERBOSITY` понад 1. Гард у `TdClient.kt:426`.
- НЕ створюйте додаткових `.md` файлів (ARCHITECTURE.md, CONTRIBUTING.md, ROADMAP.md) без явного запиту. README + CHANGELOG + цей CLAUDE.md — достатньо.

## Versioning

- `versionCode = 1`, `versionName = "0.1.0"` у `defaultConfig`. Бампати РУКАМИ перед production release.
- Beta: `versionCode` = `git rev-list --count HEAD`, `versionName` = `<base>-beta-<sha>`. Push коміт → beta APK з вищим versionCode → in-place update.
- TDLib pin: `scripts/tdlib-version.txt` (auto-generated). Окремий коміт `chore(tdlib): bump to <sha>` при upstream bump'і.
- Кожна user-visible зміна → `## [Unreleased]` у `CHANGELOG.md` (Keep a Changelog: Added / Changed / Fixed / Performance / Architecture).
