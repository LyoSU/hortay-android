# Privacy Policy — Hortay

**Effective:** 6 May 2026

Hortay is an Android app that shows Telegram channels in a feed format. This document describes how the app handles data. The short version: **we do not collect your data** — we do not even have servers on which we could collect it.

Hortay can run in two modes:

- **Authenticated mode** — you sign in with your Telegram account; the app uses TDLib (Telegram's official client library) to fetch your subscriptions, posts, and reactions over the MTProto protocol.
- **Guest mode (anonymous reading)** — you read public Telegram channels without signing in. The app fetches the public web previews of channels (`https://t.me/s/<username>`) directly from Telegram's public web frontend. **No Telegram account, phone number, or user identifier is involved.**

Both modes are fully on-device. Neither one routes data through any server we control.

---

## Who we are

Hortay is developed by an individual. For privacy questions, contact **ua.lyo.su@gmail.com**.

Hortay is not an official Telegram product. It is an independent client that uses the official Telegram library (TDLib) for the authenticated mode and Telegram's public web preview pages for the guest mode.

---

## Data we do **not** collect

We do **not** collect, receive, or store on any servers:

- your name, phone number, email or any other contact details;
- your messages, chats, channels, media, files, or history;
- your contacts, friends, or groups;
- your location;
- your IP address, device identifier, advertising ID, or IMEI;
- app usage events (opens, clicks, session duration);
- crash reports, error logs, or telemetry.

The app contains **no**:

- Google Firebase, Crashlytics, or Google Analytics;
- Sentry, Bugsnag, AppsFlyer, Amplitude, or any other analytics SDK;
- ad networks or trackers;
- pixel tags, third-party cookies, or fingerprinting libraries;
- automatic uploads to developer servers (there are none).

---

## Data processed locally on your device

### Authenticated mode (TDLib)

Hortay uses **TDLib** — the official Telegram library. TDLib stores in an encrypted database on your device:

- session keys for your Telegram account;
- cached chats, messages, and channels you are subscribed to;
- cached media files (photos, videos, stickers);
- your in-app settings.

### Guest mode (anonymous reading)

When reading without signing in, Hortay stores in a separate local SQLite database (`web.db`) on your device:

- the list of public channels you have added to your guest-mode feed;
- the cached HTML / text of posts that the app has fetched from public `t.me/s/` pages;
- a small cache of custom-emoji metadata, HTTP response headers (ETag / Last-Modified) used for conditional revalidation, and curated channel suggestions.

Cached media (images, video thumbnails) is also stored in a local Coil / OkHttp disk cache.

### Both modes

Hortay also stores a small set of local preferences (theme, enabled feed filters, the mode flag, the list of guest-mode subscriptions) through Android DataStore — strictly local.

All of the above **never leaves your device** in our direction. It exists only locally and disappears when you uninstall the app or clear its data.

---

## Data transmitted over the internet

### In authenticated mode (Telegram MTProto)

When you sign in to Hortay with your phone number, the app connects to Telegram servers via the official MTProto protocol. Telegram receives the same information it would when you use the official Telegram app: your phone number, online status, requested messages, your reactions, session metadata, and so on.

### In guest mode (public t.me web previews)

When reading without an account, the app issues plain **HTTPS requests to `https://t.me/s/<channel-username>`** — the same anonymous public web preview of a Telegram channel that anyone can open in a browser. We send **no user identifier, no account, no phone number**. The only information Telegram's web frontend sees is whatever any HTTPS request reveals:

- your **IP address**;
- a generic desktop User-Agent string;
- the channel URL being fetched and the timing of those requests.

This is the same fingerprint as opening the same URL in a regular browser. Media (channel avatars, photos, video thumbnails) is fetched directly from Telegram's CDN over HTTPS in the same way.

Both modes' traffic with Telegram (MTProto in authenticated mode, HTTPS in guest mode) is governed by **Telegram's Privacy Policy**: [https://telegram.org/privacy](https://telegram.org/privacy)

Hortay does not modify or intercept this traffic.

---

## App permissions

Hortay requests a minimal set of Android permissions:

- **INTERNET** — to talk to Telegram: `api.telegram.org` (authenticated mode), `t.me` (guest-mode public previews), and Telegram's media CDN. Nowhere else.
- **ACCESS_NETWORK_STATE** — to know whether the network is available and its type (Wi-Fi / mobile), so the app behaves correctly.
- **POST_NOTIFICATIONS** (Android 13+) — only if you enable notifications about new posts (this feature is still in development).

Hortay **does not request** access to your contacts, microphone, camera, location, SMS, call log, or external storage.

---

## Sharing with third parties

We **do not share** your data with anyone, because we do not have it. The only external service the app connects to is Telegram: you either sign in with your phone number or open a public channel preview (guest mode, the same as visiting `t.me/<channel>` in a browser).

---

## Data deletion

- **Uninstalling the app** removes all of Hortay's local data from your device — TDLib database, guest-mode `web.db`, media caches, and all settings.
- **Logging out** of your Telegram account (authenticated mode) is available in the app's settings — it clears the Telegram session without uninstalling.
- **Clearing the cache** is available in Settings:
  - authenticated mode: "Storage and traffic" → "Clear media cache" — wipes TDLib's media cache;
  - guest mode: Settings → "Clear cache" — wipes the cached posts and media in `web.db` while keeping your subscription list.
- **Switching between modes** does not delete your guest-mode subscription list — it survives sign-in and sign-out so you can continue reading the same channels in either direction.

---

## Children

Hortay is not intended for children under 13. Per Telegram's Terms of Service, Telegram registration requires users to be at least 16 (older in some jurisdictions). We do not collect or process data of users below this age.

The guest mode reads public channels only and does not require any registration; no minimum-age check is technically possible there. Public channels you read in guest mode are governed by Telegram's own community standards.

---

## Changes to this policy

If this policy changes, an updated version with a new effective date will appear at the same URL. Changes take effect when published.

---

## Contact

For privacy questions, data deletion requests, or anything else — email **ua.lyo.su@gmail.com**.
