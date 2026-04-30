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
 *   - The `online` flag tracks [ProcessLifecycleOwner] ON_START / ON_STOP, gated on
 *     [AuthStage.Ready]. We combine those two signals into a single "active" boolean
 *     so cold-start (auth not ready when ON_START fires) and runtime transitions are
 *     handled by the same edge-trigger.
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
) {

    private val appContext = context.applicationContext
    private val cm = appContext.getSystemService(ConnectivityManager::class.java)!!

    private val _foreground = MutableStateFlow(false)
    /**
     * App-level foreground signal mirrored from [ProcessLifecycleOwner]. Exposed so other
     * subsystems (the [MediaCache] stall watchdog) can park their work while the user
     * isn't looking — zero CPU/battery cost via a suspending Flow collector.
     */
    val foreground: StateFlow<Boolean> = _foreground.asStateFlow()

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
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                _foreground.value = true
            }

            override fun onStop(owner: LifecycleOwner) {
                _foreground.value = false
            }
        })

        cm.registerDefaultNetworkCallback(networkCallback)

        combine(td.authStage, foreground) { auth, fg -> auth is AuthStage.Ready && fg }
            .distinctUntilChanged()
            .onEach { active ->
                if (active) goOnline() else goOffline()
            }
            .launchIn(scope)
    }

    private suspend fun goOnline() {
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
        if (!_foreground.value) return
        scope.launch {
            runCatching { td.send(TdApi.SetNetworkType(currentNetworkType())) }
                .warnUnlessCancelled(TAG, "networkType-update")
        }
    }

    private fun currentNetworkType(): TdApi.NetworkType {
        val net = cm.activeNetwork ?: return TdApi.NetworkTypeNone()
        val caps = cm.getNetworkCapabilities(net) ?: return TdApi.NetworkTypeNone()
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> TdApi.NetworkTypeWiFi()
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> TdApi.NetworkTypeWiFi()
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> TdApi.NetworkTypeMobile()
            else -> TdApi.NetworkTypeOther()
        }
    }

    private companion object {
        const val TAG = "TdLifecycleBridge"
    }
}
