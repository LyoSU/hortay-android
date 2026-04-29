# Telread — Android

Стрічка Telegram-каналів у форматі Twitter. Прототип на TDLib + Kotlin + Jetpack Compose + Material 3.

## Стек (квітень 2026)

| Шар | Що | Версія |
|---|---|---|
| Build | AGP | 9.2.0 |
| Build | Gradle | 8.14 |
| Lang | Kotlin | 2.3.10 |
| UI | Compose BOM | 2026.04.01 |
| UI | Material 3 | 1.4 (через BOM) |
| Compat | minSdk / targetSdk / compileSdk | 26 / 36 / 36 |
| Telegram | TDLib (self-built, `:libtdlib`) | див. `scripts/tdlib-version.txt` |
| Async | Coroutines | 1.10.1 |
| Images | Coil 3 | 3.3.0 |
| Storage | DataStore | 1.2.0 |

## Перед першим запуском

1. **Отримайте `api_id` / `api_hash`** на https://my.telegram.org → API development tools.
2. Відкрийте `app/src/main/kotlin/dev/lyo/telread/data/TdClient.kt` → `TdClient.Companion.create(...)` і вставте свої значення. (Production-варіант — читати з `BuildConfig`, який заповнюється з `local.properties`.)
3. **Збудуйте TDLib** (потрібен будь-який Docker — Docker Desktop, OrbStack, Colima тощо):
   ```bash
   ./scripts/update-tdlib.sh                  # default: master (TDLib не оновлює теги)
   ./scripts/update-tdlib.sh 8fc2344f         # конкретний commit SHA
   ```
   Скрипт компілює нативну TDLib з upstream і кладе результат у `libtdlib/src/main/{java,jniLibs}`, фіксує версію у `scripts/tdlib-version.txt`. Перший запуск — ~30 хв (SDK + OpenSSL), наступні bump-и — ~10–15 хв (кешовані шари). Деталі та параметри — у `scripts/tdlib-builder/Dockerfile` і шапці скрипта.
4. Згенеруйте gradle wrapper, якщо його ще нема локально:
   ```bash
   cd telread-android
   gradle wrapper --gradle-version 8.14
   ```
5. Відкрийте папку в Android Studio Iguana 2025+ або зберіть з CLI:
   ```bash
   ./gradlew :app:installDebug
   ```

## Архітектура

```
ui/
  theme/        Material 3 Expressive: dynamic color + brand fallback
  auth/         Stateful auth flow: phone → code → 2FA password
  timeline/     LazyColumn у Twitter-стилі: PostCard з аватаром, текстом, медіа, переглядами
data/
  TdClient      Coroutines wrapper над org.drinkless.tdlib.Client
  AuthStage     sealed interface для FSM авторизації
  PostsRepository  завантажує канали → останні N повідомлень → мерджить
  PostFilterStrategy  ← LEARNING CHECKPOINT #1 (фільтрація / групування альбомів)
  TimelinePost  UI-модель, відв'язана від TdApi.*
```

## Що залишилось «дописати руками»

Промарковано в коді як `🎯 LEARNING CHECKPOINT`:

1. **`data/PostFilterStrategy.kt`** — як саме формувати стрічку: групування альбомів, фільтр шуму, сортування. Це продуктове рішення з кількома живими трейдофами.
2. **`ui/timeline/PostCard.kt → MediaSlot`** — як завантажувати картинки з TDLib (file.id → DownloadFile → UpdateFile → local path). Описано три варіанти; обирайте за смаком до архітектури.

## Чого ще немає (TODO для повноцінної читалки)

- [ ] Перегляд повного посту (deep link на канал у Telegram або власний detail-екран).
- [ ] Inline media gallery (HorizontalPager для альбомів).
- [ ] Push-нотифікації про нові пости (TDLib has its own; need WorkManager glue).
- [ ] Збереження прочитаного / непрочитаного.
- [ ] BuildConfig + local.properties для api_id / api_hash.
- [ ] ProGuard rules перевірити на release-білд.
