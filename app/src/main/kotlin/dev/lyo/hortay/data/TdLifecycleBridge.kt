package dev.lyo.hortay.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi

/**
 * Tells TDLib about the app's foreground/background state and the actual network type
 * so the daemon can pause presence chatter and adjust download throttling. This mirrors
 * the pattern Telegram-Android (Telegram X) uses around its TDLib daemon.
 *
 * Design notes:
 *   - The `online` option tracks [ProcessLifecycleOwner] ON_START / ON_STOP, gated on
 *     [AuthStage.Ready] AND on the user's [SettingsStore.hideOnlineStatus] preference
 *     (invisible-reading mode). We combine those three signals into a single "active"
 *     boolean so cold-start (auth not ready when ON_START fires), runtime transitions
 *     and a mid-session invisible-mode toggle are all handled by the same edge-trigger.
 *     Per Aliaksei Levin in tdlib/td#3144: *"`online` option is about the user, not the
 *     network"* — TDLib maps it 1:1 to `account.updateStatus`, the single signal that
 *     drives Telegram's last-seen / green-dot. Suppressing it (invisible mode ON)
 *     reliably hides presence WITHOUT breaking content updates, because chat updates
 *     ride [TdApi.SetNetworkType] + per-chat [TdApi.OpenChat] which we keep wired up
 *     unconditionally. Invisible mode does NOT touch the server-side `privacy.lastSeen`
 *     setting — that's a global property of the Telegram account and stays under the
 *     official client's control.
 *   - [TdApi.SetNetworkType] always reflects the actual current network. We deliberately
 *     never push [TdApi.NetworkTypeNone] from the lifecycle observer — Telegram X does
 *     not, and forcing None on background would break FCM if push is added later (TDLib
 *     can't sync content on push wake-up with no network). Doze handles real network
 *     pause at the OS level.
 *   - The [ConnectivityManager.NetworkCallback] is registered for the lifetime of the
 *     process; there is no symmetric unregister because [ProcessLifecycleOwner] has no
 *     destroyed state and the callback dies with the process anyway.
 */
class TdLifecycleBridge(
    private val td: TdClient,
    context: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsStore,
) {

    private val appContext = context.applicationContext
    private val cm = appContext.getSystemService(ConnectivityManager::class.java)!!

    // bind() registers a process-lifetime ProcessLifecycleOwner observer + a
    // ConnectivityManager.NetworkCallback — both leak silently if bind() ever
    // runs twice (double goOnline/goOffline emits, double network pushes).
    // Currently HortayApp.onCreate is the only caller, but the invariant isn't
    // enforced by the type system, so guard it here.
    private var bound = false

    private val _foreground = MutableStateFlow(false)
    /**
     * App-level foreground signal mirrored from [ProcessLifecycleOwner]. Exposed so other
     * subsystems (the [MediaCache] stall watchdog) can park their work while the user
     * isn't looking — zero CPU/battery cost via a suspending Flow collector.
     */
    val foreground: StateFlow<Boolean> = _foreground.asStateFlow()

    private val _networkType = MutableStateFlow(HortayNetworkType.None)
    /**
     * Coarse classification of the device's currently active default network — picked
     * from [NetworkCapabilities] inside [networkCallback]. Exists so subsystems that
     * apply per-network policy (e.g. [MediaAutoDownloader] resolving an
     * [AutoDownloadPolicy]) don't each duplicate the ConnectivityManager dance. Updates
     * are deduped by [MutableStateFlow]'s structural equality so consumers don't
     * recompose for re-emits of the same value (a common case during quick
     * connect-disconnect cycles on flaky networks).
     */
    val networkType: StateFlow<HortayNetworkType> = _networkType.asStateFlow()

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            pushNetworkType()
        }

        override fun onLost(network: Network) {
            pushNetworkType()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            pushNetworkType()
        }
    }

    fun bind() {
        if (bound) return
        bound = true
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                _foreground.value = true
            }

            override fun onStop(owner: LifecycleOwner) {
                _foreground.value = false
            }
        })

        // Seed BEFORE registering the callback. With the previous order, a
        // network change firing between register() and the seed write could
        // see the callback push a freshly-classified value, then have the seed
        // overwrite it back to a stale snapshot we read inside this method.
        // Now the seed lands first; any subsequent callback always wins, and a
        // race between "callback fires immediately on register()" and "seed
        // here" resolves with the callback's value (which is the desired
        // outcome — it's the more recent observation). Consumers that read
        // [networkType] synchronously at construction (e.g.
        // [MediaAutoDownloader.activePolicy]) see a real value, not the
        // [HortayNetworkType.None] sentinel.
        _networkType.value = classifyNetwork()
        cm.registerDefaultNetworkCallback(networkCallback)

        // Three-way derivation of "should the user be presented as online to Telegram":
        //   authStage = Ready  AND  foreground = true  AND  invisible-mode = false.
        //
        // The boolean is the SAME source of truth that drove the previous two-input combine,
        // so existing edges (cold-start finishes auth → goOnline; ON_STOP → goOffline) keep
        // their semantics. The new third input layers in cleanly: a mid-session
        // invisible-mode flip while the app is foreground emits exactly one transition
        // (true → false → goOffline, or false → true → goOnline) thanks to
        // distinctUntilChanged. A flip while backgrounded is a no-op because the combine
        // result stays false either way — the toggle takes effect next time the user
        // brings Hortay back to the foreground, which is the correct presence semantics
        // (we're already offline to Telegram while backgrounded).
        combine(td.authStage, foreground, settings.hideOnlineStatus) { auth, fg, hide ->
            auth is AuthStage.Ready && fg && !hide
        }
            .distinctUntilChanged()
            .onEach { active ->
                if (active) goOnline() else goOffline()
            }
            .launchIn(scope)
    }

    private suspend fun goOnline() {
        // Per Levin in tdlib/td#3144: `online` is a per-user signal that drives
        // `account.updateStatus`. Pushed on every foreground transition AFTER
        // auth is Ready. Read-only client options (`disable_top_chats`,
        // `notification_group_count_max`) are now set ONCE in
        // TdClient.onAuthState(WaitTdlibParameters) — they stick across the
        // session and don't need re-application here. `maybeOptimizeStorage`
        // moved to [StorageOptimizer], which gates on
        // [StartupCoordinator.Phase.Active] so the daily sweep can't race with
        // cold-start LoadChats traffic.
        runCatching { td.send(TdApi.SetOption("online", TdApi.OptionValueBoolean(true))) }
            .warnUnlessCancelled(TAG, "online=true")
        runCatching { td.send(TdApi.SetNetworkType(currentNetworkType())) }
            .warnUnlessCancelled(TAG, "networkType")
    }

    private suspend fun goOffline() {
        runCatching { td.send(TdApi.SetOption("online", TdApi.OptionValueBoolean(false))) }
            .warnUnlessCancelled(TAG, "online=false")
        // Intentionally do NOT change NetworkType. See class doc.
    }

    private fun pushNetworkType() {
        // Re-classify on every callback regardless of foreground — consumers like
        // [MediaAutoDownloader] need an up-to-date value while the app is in the
        // background (a roaming cell handover that lands while the screen is off
        // must not still read as "Wi-Fi" when the user opens the app).
        _networkType.value = classifyNetwork()
        if (!_foreground.value) return
        scope.launch {
            runCatching { td.send(TdApi.SetNetworkType(currentNetworkType())) }
                .warnUnlessCancelled(TAG, "networkType-update")
        }
    }

    private fun currentNetworkType(): TdApi.NetworkType = when (classifyNetwork()) {
        HortayNetworkType.Wifi -> TdApi.NetworkTypeWiFi()
        HortayNetworkType.Mobile,
        HortayNetworkType.Roaming -> TdApi.NetworkTypeMobile()
        HortayNetworkType.None -> TdApi.NetworkTypeNone()
    }

    /**
     * Maps the current default network to our coarse [HortayNetworkType] enum.
     *
     * Roaming detection uses [NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING]
     * (absent → roaming).
     *
     * **Metered Wi-Fi → Mobile.** A Wi-Fi link can be metered (phone hotspot
     * tethering, capped corporate networks, some home plans in EU markets);
     * `NET_CAPABILITY_NOT_METERED` is the OS-side flag for "free bytes." When
     * absent we treat the connection as Mobile so the user's tighter mobile
     * policy (typically 10 MB video cap) applies instead of the liberal Wi-Fi
     * one (100 MB). This matches Telegram-Android's behaviour and prevents
     * the most common "I tethered through my phone and it ate 500 MB"
     * surprise. Ethernet stays on the Wi-Fi profile because Ethernet is not a
     * metering vector users worry about.
     *
     * "Other" (Bluetooth / VPN-only) falls back to Mobile — conservative for
     * unknown transports.
     */
    private fun classifyNetwork(): HortayNetworkType {
        val net = cm.activeNetwork ?: return HortayNetworkType.None
        val caps = cm.getNetworkCapabilities(net) ?: return HortayNetworkType.None
        val notMetered = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> HortayNetworkType.Wifi
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                if (notMetered) HortayNetworkType.Wifi else HortayNetworkType.Mobile
            }
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> {
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)) {
                    HortayNetworkType.Mobile
                } else {
                    HortayNetworkType.Roaming
                }
            }
            else -> HortayNetworkType.Mobile
        }
    }

    private companion object {
        const val TAG = "TdLifecycleBridge"
    }
}

/**
 * Coarse classification of the device's currently active default network. Distinct
 * from [TdApi.NetworkType] because the daemon enum doesn't model roaming as a
 * separate state — Telegram-Android tracks roaming on the client side too and so
 * do we, since per-network auto-download policy needs to distinguish "home cellular"
 * from "roaming cellular".
 */
enum class HortayNetworkType { Wifi, Mobile, Roaming, None }

