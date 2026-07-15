# Hortay

*Pronounced **hor-TYE** /ɦorˈtaj/ — Ukrainian for "scroll!", the imperative of гортати (to leaf through)*

A Twitter-style reader for Telegram channels. Read your subscribed channels as one chronological feed instead of a chat list.

No Firebase, no Crashlytics, no analytics, no third-party push. INTERNET permission is used only for Telegram itself, anonymous `t.me/s/` previews in guest mode, and a read-only fetch of the public [suggested-channels list](suggestions.json) from this repo (no user data ever leaves the device).

<p align="center">
  <a href="https://play.google.com/store/apps/details?id=dev.lyo.hortay">
    <img alt="Get it on Google Play" src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png" height="60">
  </a>
</p>

<p align="center">
  <img src="playstore/screens/photos/01-hero.jpg" alt="Hortay feed" width="280">
</p>

## Highlights

- **Two modes** — full MTProto via TDLib, or guest mode reading public `t.me/s/<channel>` previews without an account
- **Reader-first UX** — OldestUnreadFirst boundary, dwell-based read tracking, snap scroll, folder tabs, scroll-to-bottom with unread badge
- **Full post features** — polls (vote, quiz reveal, multi-answer), reactions, custom emoji, animated stickers (TGS/WebM/WEBP), albums, inline videos, round video messages
- **Comments overlay** with predictive back, reply chains, and a user-profile sheet
- **Material 3 Expressive** — dynamic color, motion scheme, reduced-motion aware
- **13 languages** — English, Ukrainian, Russian, Spanish, German, French, Italian, Portuguese (Brazil), Polish, Turkish, Indonesian, Persian, Arabic — with proper CLDR plural forms throughout

## Building

Requirements: JDK 17, Docker (for the TDLib build), Android SDK

1. Get `api_id` / `api_hash` at <https://my.telegram.org> → API development tools
2. Copy `local.properties.example` to `local.properties` and fill in the credentials
3. Build TDLib (Docker; ~30 min first run, ~10 min after):

   ```bash
   ./scripts/update-tdlib.sh                  # upstream master (last build is recorded in scripts/tdlib-version.txt)
   ./scripts/update-tdlib.sh 8fc2344f         # specific commit SHA
   ```

4. Install a debug build:

   ```bash
   ./gradlew :app:installDebug
   ```

The Gradle wrapper is checked in — no separate `gradle wrapper` step

## Releases & CI

Shipping artifacts are built and published by GitHub Actions — you never build a release locally.

- **Cut a release** — push a `vX.Y.Z` tag (or run the **Release** workflow manually with a version). CI builds a signed AAB + universal APK and publishes a GitHub Release, using the matching `CHANGELOG.md` section as notes. The tag drives `versionName`; `versionCode` stays the `git rev-list --count` auto-derivation.
- **Beta channel** — after CI passes on `main`, a signed `dev.lyo.hortay.beta` APK is built and the single rolling **`beta`** pre-release is updated, so testers get in-place updates (installs alongside the Play build).
- **Bump TDLib** — run the **TDLib bump** workflow. It builds TDLib on a native x86_64 runner (~30 min, no Rosetta), smoke-compiles `:app`, warms the CI cache, and opens a PR changing only `scripts/tdlib-version.txt` (the native libs are gitignored and rebuilt from the pin).
- **Gate** — `CI` runs detekt + unit tests + `lintRelease` (the translation-parity check) on every push/PR.

Signing + credentials live in repo secrets (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`, `TELEGRAM_API_ID`, `TELEGRAM_API_HASH`). To enable **Google Play** auto-publish (internal track), create a Play Console service account (Play Console → Setup → API access), grant it release permissions, and add its JSON as the `PLAY_SERVICE_ACCOUNT_JSON` secret — the release workflow picks it up automatically; without it, releases go to GitHub only.

## Stack

AGP 9.3.0 · Gradle 9.5.0 · Kotlin 2.4.0 (K2) · Compose BOM 2026.06.01 · Material 3 1.5.0-alpha23 · minSdk 26 / targetSdk 36 · TDLib pinned in `scripts/tdlib-version.txt` · Coroutines 1.11.0 · Coil 3.4.0 · Navigation 3 1.1.4 · SQLDelight 2.3.2 (guest mode only) · DataStore 1.2.1

## Architecture

See [ARCHITECTURE.md](ARCHITECTURE.md) for module layout, load-bearing decisions, and TDLib usage rules.

Short version: single-Activity, Compose-only, three modules — `:app`, `:libtdlib` (vendored TDLib JNI), `:baselineprofile`. Manual DI through `AppGraph`. No Hilt, no Retrofit, no Firebase.

## Contributing

Issues and PRs welcome. Read [ARCHITECTURE.md](ARCHITECTURE.md) before non-trivial changes — many decisions are load-bearing and documented inline. Conventional Commits with package scopes: `feat(timeline):`, `fix(media):`, `build(beta):`, etc.

## Security

See [SECURITY.md](SECURITY.md) for how to report vulnerabilities.

## License

[GPL-3.0-or-later](LICENSE)

---

Made in Ukraine 🇺🇦 · [Українською](README.uk.md)
