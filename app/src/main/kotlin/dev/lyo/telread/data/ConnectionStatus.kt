package dev.lyo.telread.data

/**
 * High-level summary of TDLib's connection lifecycle. Only the UI-relevant subset of
 * [org.drinkless.tdlib.TdApi.ConnectionState] — proxy and direct connecting collapse
 * into [Connecting] because the user can't act on the difference, and the difference
 * doesn't change the banner copy.
 */
enum class ConnectionStatus {
    /** Healthy — fully synchronised. UI hides the banner. */
    Ready,
    /** Establishing or re-establishing the socket. Brief blip on roam / Wi-Fi switch. */
    Connecting,
    /** Connected, catching up on backlog. Common after the app returns from background. */
    Updating,
    /** No network. Surfaced so the user knows the freeze is offline, not the app. */
    WaitingForNetwork,
}
