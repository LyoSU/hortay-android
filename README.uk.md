# Hortay

Twitter-подібна читалка Telegram-каналів. Підписки відкриваються однією хронологічною стрічкою, а не списком чатів.

Без Firebase, Crashlytics, аналітики, сторонніх push-сервісів. Дозвіл INTERNET використовується тільки для Telegram, анонімних `t.me/s/` прев'ю в гостьовому режимі та для read-only завантаження публічного [списку рекомендованих каналів](suggestions.json) з цього репозиторію через CDN jsDelivr (`cdn.jsdelivr.net`; дані користувача не надсилаються, але CDN бачить IP-адресу запиту).

<p align="center">
  <a href="https://play.google.com/store/apps/details?id=dev.lyo.hortay">
    <img alt="Завантажити в Google Play" src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png" height="60">
  </a>
</p>

<p align="center">
  <img src="playstore/screens/photos/01-hero.jpg" alt="Стрічка Hortay" width="280">
</p>

## Що всередині

- **Два режими** — повний MTProto через TDLib або гостьовий режим читання публічних `t.me/s/<канал>` без облікового запису
- **UX читача** — режим OldestUnreadFirst із межею прочитаного, dwell-based read-tracking, snap-скрол, вкладки папок, FAB до низу з лічильником непрочитаного
- **Усі формати постів** — опитування (голосування, quiz reveal, multi-answer), реакції, кастомні emoji, анімовані стікери (TGS/WebM/WEBP), альбоми, інлайн-відео, кругові відеоповідомлення
- **Коментарі** як overlay з predictive back, ланцюжки відповідей, шит профілю користувача
- **Material 3 Expressive** — dynamic color, motion scheme, врахування reduced motion
- **Англійська і українська** з повними plural-формами

## Збірка

Потрібно: JDK 17, Docker (для TDLib), Android SDK

1. Отримайте `api_id` / `api_hash` на <https://my.telegram.org> → API development tools
2. Скопіюйте `local.properties.example` у `local.properties` і впишіть ключі
3. Зберіть TDLib через Docker (~30 хв перший запуск, ~10 хв далі):

   ```bash
   ./scripts/update-tdlib.sh                  # upstream master (останній збір зафіксовано в scripts/tdlib-version.txt)
   ./scripts/update-tdlib.sh 8fc2344f         # конкретний commit SHA
   ```

4. Встановіть debug-білд:

   ```bash
   ./gradlew :app:installDebug
   ```

Gradle wrapper закомічено — окремий `gradle wrapper` не потрібен

## Стек

AGP 9.5.1 · Gradle 9.8.2 · Kotlin 2.4.21 (K2) · Compose BOM 2026.09.00 · Material 3 1.5.0-rc01 · minSdk 26 / targetSdk 36 · TDLib запінено в `scripts/tdlib-version.txt` · Coroutines 1.11.0 · Coil 3.6.3 · Navigation 3 1.1.4 · SQLDelight 2.4.1 (тільки гостьовий режим) · DataStore 1.3.0

## Архітектура

Розклад модулів, load-bearing рішення і правила роботи з TDLib — у [ARCHITECTURE.md](ARCHITECTURE.md).

Коротко: single-Activity, тільки Compose, чотири модулі — `:app`, `:libtdlib` (vendored TDLib JNI), `:libwebm` (vendored мінімальний ffmpeg-декодер WebM), `:baselineprofile`. Manual DI через `AppGraph`. Без Hilt, без Retrofit, без Firebase.

## Контрибуції

Issues і PR — welcome. Перед нетривіальними змінами читайте [ARCHITECTURE.md](ARCHITECTURE.md): багато рішень є load-bearing і задокументовані поряд із кодом. Conventional Commits зі скоупом-пакетом: `feat(timeline):`, `fix(media):`, `build(beta):` тощо.

## Безпека

Як повідомити про вразливість — [SECURITY.md](SECURITY.md).

## Ліцензія

[GPL-3.0-or-later](LICENSE)

---

Зроблено в Україні 🇺🇦 · [English](README.md)
