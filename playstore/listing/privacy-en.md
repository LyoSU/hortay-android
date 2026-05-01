# Privacy Policy — Hortay

**Effective:** 1 May 2026

Hortay is an Android app that shows your Telegram channels in a feed format. This document describes how the app handles data. The short version: **we do not collect your data** — we do not even have servers on which we could collect it.

---

## Who we are

Hortay is developed by an individual. For privacy questions, contact **ua.lyo.su@gmail.com**.

Hortay is not an official Telegram product. It is an independent client that uses the official Telegram library (TDLib) to talk to the Telegram API.

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

Hortay uses **TDLib** — the official Telegram library. TDLib stores in an encrypted database on your device:

- session keys for your Telegram account;
- cached chats, messages, and channels you are subscribed to;
- cached media files (photos, videos, stickers);
- your in-app settings.

This data **never leaves your device** in our direction. It exists only locally and disappears when you uninstall the app or clear its data.

Separately, Hortay stores a small set of local preferences (theme, enabled feed filters) through Android DataStore — also strictly local.

---

## Data transmitted to Telegram

When you sign in to Hortay with your phone number, the app connects to Telegram servers via the official MTProto protocol. Telegram receives the same information it would when you use the official Telegram app: your phone number, online status, requested messages, your reactions, session metadata, and so on.

Everything that happens between your device and Telegram servers is governed by **Telegram's Privacy Policy**: [https://telegram.org/privacy](https://telegram.org/privacy)

Hortay does not modify or intercept this traffic.

---

## App permissions

Hortay requests a minimal set of Android permissions:

- **INTERNET** — to talk to Telegram servers (api.telegram.org). Nowhere else.
- **ACCESS_NETWORK_STATE** — to know whether the network is available and its type (Wi-Fi / mobile), so TDLib behaves correctly.
- **POST_NOTIFICATIONS** (Android 13+) — when you enable notifications about new posts (this feature is still in development).

Hortay **does not request** access to your contacts, microphone, camera, location, SMS, call log, or external storage.

---

## Sharing with third parties

We **do not share** your data with anyone, because we do not have it. The only "third party" is Telegram, to which you knowingly connect by signing in with your phone number.

---

## Data deletion

- **Uninstalling the app** removes all of Hortay's local data from your device (including media cache and Telegram session).
- **Logging out** is available in the app's settings — it clears the session without uninstalling.
- **Clearing the media cache** is available under "Profile" → "Storage and traffic" → "Clear media cache".

---

## Children

Hortay is not intended for children under 13. Per Telegram's Terms of Service, Telegram registration requires users to be at least 16 (older in some jurisdictions). We do not collect or process data of users below this age.

---

## Changes to this policy

If this policy changes, an updated version with a new effective date will appear at the same URL. Changes take effect when published.

---

## Contact

For privacy questions, data deletion requests, or anything else — email **ua.lyo.su@gmail.com**.
