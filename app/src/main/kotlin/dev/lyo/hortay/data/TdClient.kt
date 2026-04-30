package dev.lyo.hortay.data

import android.content.Context
import dev.lyo.hortay.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import kotlin.coroutines.resume

/**
 * Thin Kotlin wrapper around TDLib's [Client].
 *
 * The native client is event-driven — every call to [send] returns asynchronously through a
 * [Client.ResultHandler]. We bridge that to coroutines via [suspendCancellableCoroutine] so the
 * UI layer can simply write `client.send(query)` and `await` the result.
 */
class TdClient private constructor(
    private val context: Context,
    private val apiId: Int,
    private val apiHash: String,
) : TdSender {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _authStage = MutableStateFlow<AuthStage>(AuthStage.Loading)
    val authStage: StateFlow<AuthStage> = _authStage.asStateFlow()

    /**
     * Transient errors from in-flight auth submits (PHONE_NUMBER_INVALID, FLOOD_WAIT_X,
     * PHONE_CODE_INVALID, PASSWORD_HASH_INVALID…). Kept *separate* from [authStage] so a
     * rejected code doesn't blow the user back to a blank screen — they stay on the same
     * form with their input intact and an inline supporting-text error. UI clears it via
     * [clearAuthError] when the user starts retyping.
     */
    private val _authError = MutableStateFlow<String?>(null)
    val authError: StateFlow<String?> = _authError.asStateFlow()

    private val _connection = MutableStateFlow(ConnectionStatus.Connecting)
    /**
     * Mirrors TDLib's [TdApi.UpdateConnectionState]. The UI uses this to render a top
     * banner ("З'єднання…", "Оновлення…", "Очікує мережі"), so transient hiccups during
     * a long roam or a Wi-Fi handoff have a visible explanation instead of an apparently
     * frozen feed.
     */
    val connection: StateFlow<ConnectionStatus> = _connection.asStateFlow()

    // Last phone number the user tried — kept so AuthorizationStateWaitCode can display
    // the right number even when we no longer optimistically pre-set WaitCode.
    @Volatile
    private var lastAttemptedPhone: String = ""

    private val _updates = MutableSharedFlow<TdApi.Update>(extraBufferCapacity = 64)
    override val updates: SharedFlow<TdApi.Update> = _updates.asSharedFlow()

    private lateinit var client: Client

    fun start() {
        if (this::client.isInitialized) return
        // Silence TDLib's default verbose stdout chatter; we still log warnings via Log.w.
        Client.execute(TdApi.SetLogVerbosityLevel(LOG_VERBOSITY))
        spawnClient()
    }

    /**
     * Create (or recreate) the underlying native [Client]. After a successful logout TDLib
     * walks the authorization state machine to [TdApi.AuthorizationStateClosed] and tears
     * down the native handle — any further `send()` against it would block forever. The
     * canonical recovery per TDLib docs is to spin up a fresh native client which then
     * re-emits [TdApi.AuthorizationStateWaitTdlibParameters] from scratch and the auth
     * loop runs again. We call this once on first [start] and once more on every Closed.
     */
    private fun spawnClient() {
        client = Client.create({ obj ->
            if (obj is TdApi.Update) {
                handleUpdate(obj)
                scope.launch { _updates.emit(obj) }
            }
        }, null, null)
    }

    private fun handleUpdate(update: TdApi.Update) {
        when (update) {
            is TdApi.UpdateAuthorizationState -> scope.launch { onAuthState(update.authorizationState) }
            is TdApi.UpdateConnectionState -> _connection.value = update.state.toStatus()
            else -> Unit
        }
    }

    private fun TdApi.ConnectionState.toStatus(): ConnectionStatus = when (this) {
        is TdApi.ConnectionStateReady -> ConnectionStatus.Ready
        is TdApi.ConnectionStateConnecting -> ConnectionStatus.Connecting
        is TdApi.ConnectionStateConnectingToProxy -> ConnectionStatus.Connecting
        is TdApi.ConnectionStateUpdating -> ConnectionStatus.Updating
        is TdApi.ConnectionStateWaitingForNetwork -> ConnectionStatus.WaitingForNetwork
        else -> ConnectionStatus.Connecting
    }

    private suspend fun onAuthState(state: TdApi.AuthorizationState) {
        when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                val params = TdApi.SetTdlibParameters().apply {
                    useTestDc = false
                    databaseDirectory = context.filesDir.resolve("tdlib").absolutePath
                    filesDirectory = context.filesDir.resolve("tdlib-files").absolutePath
                    useFileDatabase = true
                    useChatInfoDatabase = true
                    useMessageDatabase = true
                    useSecretChats = false
                    apiId = this@TdClient.apiId
                    apiHash = this@TdClient.apiHash
                    systemLanguageCode = "uk"
                    deviceModel = android.os.Build.MODEL
                    systemVersion = "Android ${android.os.Build.VERSION.RELEASE}"
                    applicationVersion = BuildConfig.VERSION_NAME
                }
                send(params)
            }
            is TdApi.AuthorizationStateWaitPhoneNumber -> _authStage.value = AuthStage.WaitPhone
            is TdApi.AuthorizationStateWaitCode -> {
                _authStage.value = AuthStage.WaitCode(lastAttemptedPhone)
            }
            is TdApi.AuthorizationStateWaitPassword -> _authStage.value =
                AuthStage.WaitPassword(state.passwordHint.orEmpty())
            is TdApi.AuthorizationStateReady -> {
                _authStage.value = AuthStage.Ready
                // TDLib stores everything it downloads under `tdlib-files/` and never bounds
                // it on its own — a year of timeline scrolling can balloon to gigabytes.
                // Run a non-blocking cleanup pass at startup with sane defaults: cap at
                // 500 MB and drop anything not accessed in the last 30 days. This is the
                // canonical TDLib hook for storage hygiene; the daemon does the work in
                // the background and emits StorageStatistics back, which we ignore.
                runCatching {
                    send(
                        TdApi.OptimizeStorage(
                            /* size */ 500L * 1024 * 1024,
                            /* ttl  */ 30 * 24 * 60 * 60,
                            /* count */ 0,
                            /* immunityDelay */ 60,
                            /* fileTypes */ null,
                            /* chatIds */ null,
                            /* excludeChatIds */ null,
                            /* returnDeletedFileStatistics */ false,
                            /* chatLimit */ 0,
                        ),
                    )
                }
            }
            is TdApi.AuthorizationStateLoggingOut,
            is TdApi.AuthorizationStateClosing -> _authStage.value = AuthStage.Loading
            is TdApi.AuthorizationStateClosed -> {
                // Native client is dead at this point; respawn so TDLib re-emits
                // WaitTdlibParameters and the user lands on a fresh phone form instead
                // of staring at an indefinite spinner. Stays in Loading until the new
                // client emits its first auth state.
                _authStage.value = AuthStage.Loading
                lastAttemptedPhone = ""
                spawnClient()
            }
            // States we don't have dedicated UI for yet — surface a clear message instead
            // of silently leaving the user on a Loading spinner forever. Telegram now
            // commonly requires email-2FA on first sign-in, so WaitEmail* hits real users.
            is TdApi.AuthorizationStateWaitEmailAddress,
            is TdApi.AuthorizationStateWaitEmailCode -> _authStage.value =
                AuthStage.Error("Підтвердьте email у офіційному Telegram, потім поверніться сюди.")
            is TdApi.AuthorizationStateWaitRegistration -> _authStage.value =
                AuthStage.Error("Цей номер ще не зареєстрований у Telegram. Створіть акаунт у офіційному застосунку.")
            is TdApi.AuthorizationStateWaitOtherDeviceConfirmation -> _authStage.value =
                AuthStage.Error("Підтвердіть вхід у Telegram на іншому пристрої.")
            else -> Unit
        }
    }

    fun clearAuthError() {
        _authError.value = null
    }

    suspend fun submitPhone(phone: String) {
        // Don't pre-set WaitCode optimistically — on rejection (PHONE_NUMBER_INVALID,
        // FLOOD_WAIT, BANNED…) the user used to land on an empty code screen forever
        // because the exception was silently swallowed by the caller's scope.launch and
        // we never bounced back. TDLib will emit AuthorizationStateWaitCode itself on
        // success, which onAuthState turns into AuthStage.WaitCode(lastAttemptedPhone).
        lastAttemptedPhone = phone
        _authError.value = null
        runCatching { send(TdApi.SetAuthenticationPhoneNumber(phone, null)) }
            .reportAuthFailure()
    }

    suspend fun submitCode(code: String) {
        _authError.value = null
        runCatching { send(TdApi.CheckAuthenticationCode(code)) }
            .reportAuthFailure()
    }

    suspend fun submitPassword(password: String) {
        _authError.value = null
        runCatching { send(TdApi.CheckAuthenticationPassword(password)) }
            .reportAuthFailure()
    }

    /**
     * Ask Telegram to deliver the login code through the next channel in its sequence
     * (e.g. SMS after the in-app code). Only valid while we're in WaitCode — TDLib will
     * reject otherwise and the error is surfaced through [authError] like any other
     * transient failure.
     */
    suspend fun resendCode() {
        _authError.value = null
        runCatching { send(TdApi.ResendAuthenticationCode(null)) }
            .reportAuthFailure()
    }

    /**
     * Drop the current half-finished auth attempt and bounce back to the phone screen.
     * Implemented as a logout because TDLib has no "cancel and stay anonymous" call —
     * the tdlib daemon then re-emits AuthorizationStateWaitPhoneNumber and the UI rests
     * on the phone form again with cleared state.
     */
    suspend fun cancelAuth() {
        _authError.value = null
        runCatching { send(TdApi.LogOut()) }
    }

    /**
     * Auth submit calls run on the AuthScreen's `rememberCoroutineScope` — when TDLib
     * succeeds, the screen recomposes (because AuthStage flips), the scope leaves
     * composition and any in-flight coroutine cancels with a `LeftCompositionCancellation`
     * subtype of [kotlinx.coroutines.CancellationException]. That's a normal lifecycle
     * event, not an error to surface; surfacing it produced a misleading red banner with
     * "rememberCoroutineScope left the composition" right after a successful login.
     *
     * Real failures get translated from raw TDLib codes (PHONE_CODE_INVALID, FLOOD_WAIT_42…)
     * into Ukrainian phrasing the user can act on, then routed to [_authError] so the
     * current form stays mounted and shows the message inline.
     */
    private fun Result<*>.reportAuthFailure() {
        onFailure { err ->
            if (err is kotlinx.coroutines.CancellationException) return@onFailure
            _authError.value = friendlyAuthErrorMessage(err)
        }
    }

    suspend fun logOut() {
        runCatching { send(TdApi.LogOut()) }
    }

    /** Suspend-style wrapper around [Client.send]. */
    override suspend fun <T : TdApi.Object> send(query: TdApi.Function<T>): T =
        suspendCancellableCoroutine { cont ->
            client.send(query) { result ->
                if (result is TdApi.Error) {
                    cont.resumeWith(Result.failure(TdException(result.code, result.message)))
                } else {
                    @Suppress("UNCHECKED_CAST")
                    cont.resume(result as T)
                }
            }
        }


    class TdException(val code: Int, message: String) : RuntimeException("[$code] $message")

    companion object {
        // TDLib log levels: 0 = fatal, 1 = error, 2 = warning, 5 = verbose. Debug builds
        // surface "error" level (1) for traceability while developing; release stays at
        // fatal-only because TDLib's WebPagesManager spams blockquote / link-preview
        // parses at "error" level which would clutter production crashlog tooling.
        private val LOG_VERBOSITY = if (BuildConfig.DEBUG) 1 else 0

        fun create(context: Context): TdClient {
            check(BuildConfig.TELEGRAM_API_ID != 0 && BuildConfig.TELEGRAM_API_HASH.isNotEmpty()) {
                "Telegram api credentials missing. Add telegram.apiId / telegram.apiHash to local.properties."
            }
            return TdClient(
                context.applicationContext,
                BuildConfig.TELEGRAM_API_ID,
                BuildConfig.TELEGRAM_API_HASH,
            )
        }
    }
}
