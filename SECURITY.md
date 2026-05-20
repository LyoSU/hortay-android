# Security Policy

## Reporting a vulnerability

Please report security issues through one of these private channels:

1. **GitHub private vulnerability advisory** — Security → Advisories → Report a vulnerability (preferred).
2. **Email** — `ua.lyo.su@gmail.com` with subject prefix `[hortay-security]`.

Do not file public issues for security reports.

## Scope

In scope:

- The app itself (`:app` and the `:libtdlib` integration).
- The `t.me/s/` HTML parser in guest mode (untrusted input).
- Local data persistence (DataStore, the `web.db` SQLDelight database).

Out of scope:

- Vulnerabilities in TDLib itself — report upstream at https://github.com/tdlib/td.
- Telegram MTProto server-side issues — report to Telegram via https://core.telegram.org.
- Issues requiring a rooted device, a custom OS image, or physical access past the lock screen.

## Response

We aim to acknowledge reports within 7 days and ship a fix within 30 days for high-severity issues. Coordinated disclosure preferred. Reporters who request credit will be credited in the release notes.
