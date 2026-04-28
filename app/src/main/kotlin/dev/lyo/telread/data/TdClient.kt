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

    private val _updates = MutableSharedFlow<TdApi.Update>(extraBufferCapacity = 64)
    val updates: SharedFlow<TdApi.Update> = _updates.asSharedFlow()

    private lateinit var client: Client

    fun start() {
        if (this::client.isInitialized) return
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
                    applicationVersion = "0.1.0"
                }
                send(params)
            }
            is TdApi.AuthorizationStateWaitPhoneNumber -> _authStage.value = AuthStage.WaitPhone
            is TdApi.AuthorizationStateWaitCode -> {
                val phone = (_authStage.value as? AuthStage.WaitCode)?.phoneNumber ?: ""
                _authStage.value = AuthStage.WaitCode(phone)
            }
            is TdApi.AuthorizationStateWaitPassword -> _authStage.value = AuthStage.WaitPassword
            is TdApi.AuthorizationStateReady -> _authStage.value = AuthStage.Ready
            is TdApi.AuthorizationStateClosed,
            is TdApi.AuthorizationStateClosing,
            is TdApi.AuthorizationStateLoggingOut -> _authStage.value = AuthStage.Loading
            else -> Unit
        }
    }

    suspend fun submitPhone(phone: String) {
        _authStage.value = AuthStage.WaitCode(phone)
        send(TdApi.SetAuthenticationPhoneNumber(phone, null))
    }

    suspend fun submitCode(code: String) {
        runCatching { send(TdApi.CheckAuthenticationCode(code)) }
            .onFailure { _authStage.value = AuthStage.Error(it.message ?: "code rejected") }
    }

    suspend fun submitPassword(password: String) {
        runCatching { send(TdApi.CheckAuthenticationPassword(password)) }
            .onFailure { _authStage.value = AuthStage.Error(it.message ?: "password rejected") }
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
