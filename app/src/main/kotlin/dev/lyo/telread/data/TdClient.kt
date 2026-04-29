package dev.lyo.telread.data

import android.content.Context
import dev.lyo.telread.BuildConfig
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
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _authStage = MutableStateFlow<AuthStage>(AuthStage.Loading)
    val authStage: StateFlow<AuthStage> = _authStage.asStateFlow()

    // Last phone number the user tried — kept so AuthorizationStateWaitCode can display
    // the right number even when we no longer optimistically pre-set WaitCode.
    @Volatile
    private var lastAttemptedPhone: String = ""

    private val _updates = MutableSharedFlow<TdApi.Update>(extraBufferCapacity = 64)
    val updates: SharedFlow<TdApi.Update> = _updates.asSharedFlow()

    private lateinit var client: Client

    fun start() {
        if (this::client.isInitialized) return
        // Silence TDLib's default verbose stdout chatter; we still log warnings via Log.w.
        Client.execute(TdApi.SetLogVerbosityLevel(LOG_VERBOSITY))
        client = Client.create({ obj ->
            if (obj is TdApi.Update) {
                handleUpdate(obj)
                scope.launch { _updates.emit(obj) }
            }
        }, null, null)
    }

    private fun handleUpdate(update: TdApi.Update) {
        if (update is TdApi.UpdateAuthorizationState) {
            scope.launch { onAuthState(update.authorizationState) }
        }
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
            is TdApi.AuthorizationStateWaitPassword -> _authStage.value = AuthStage.WaitPassword
            is TdApi.AuthorizationStateReady -> _authStage.value = AuthStage.Ready
            is TdApi.AuthorizationStateClosed,
            is TdApi.AuthorizationStateClosing,
            is TdApi.AuthorizationStateLoggingOut -> _authStage.value = AuthStage.Loading
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

    suspend fun submitPhone(phone: String) {
        // Don't pre-set WaitCode optimistically — on rejection (PHONE_NUMBER_INVALID,
        // FLOOD_WAIT, BANNED…) the user used to land on an empty code screen forever
        // because the exception was silently swallowed by the caller's scope.launch and
        // we never bounced back. TDLib will emit AuthorizationStateWaitCode itself on
        // success, which onAuthState turns into AuthStage.WaitCode(lastAttemptedPhone).
        lastAttemptedPhone = phone
        runCatching { send(TdApi.SetAuthenticationPhoneNumber(phone, null)) }
            .onFailure { _authStage.value = AuthStage.Error(it.message ?: "phone rejected") }
    }

    suspend fun submitCode(code: String) {
        runCatching { send(TdApi.CheckAuthenticationCode(code)) }
            .onFailure { _authStage.value = AuthStage.Error(it.message ?: "code rejected") }
    }

    suspend fun submitPassword(password: String) {
        runCatching { send(TdApi.CheckAuthenticationPassword(password)) }
            .onFailure { _authStage.value = AuthStage.Error(it.message ?: "password rejected") }
    }

    suspend fun logOut() {
        runCatching { send(TdApi.LogOut()) }
    }

    /** Suspend-style wrapper around [Client.send]. */
    suspend fun <T : TdApi.Object> send(query: TdApi.Function<T>): T =
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
        // TDLib's "error" level (1) is noisy — its WebPagesManager spams blockquote /
        // link-preview parses through the same channel, which clutters logcat. Keep
        // fatal-only (0) for builds; bump up locally during deep debugging.
        // 0 = fatal, 1 = error, 2 = warning, 5 = verbose.
        private const val LOG_VERBOSITY = 0

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
