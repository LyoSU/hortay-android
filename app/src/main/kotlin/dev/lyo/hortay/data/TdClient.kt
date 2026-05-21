package dev.lyo.hortay.data

import android.content.Context
import android.util.Log
import dev.lyo.hortay.BuildConfig
import dev.lyo.hortay.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
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
    private val settings: SettingsStore,
) : TdSender {

    private val strings: StringResolver = context.resources.toStringResolver()


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

    /**
     * Fires when the user explicitly logs out (TDLib emits
     * [TdApi.AuthorizationStateLoggingOut]). Subscribers — wired in
     * [HortayApp]'s graph — wipe per-account caches (PostsRepository feed,
     * MessageMapper resolvers, MediaCache slots, on-disk timeline snapshot,
     * MigrationStore proposal flag, etc.) before TDLib's spawnClient
     * rebuilds a fresh native client. This keeps account A's cached data
     * from leaking into account B if the user signs into a different
     * Telegram account afterward.
     *
     * SharedFlow with extraBufferCapacity=1 + DROP_OLDEST: if a logout
     * happens before any subscriber attaches (hypothetical, since AppGraph
     * subscribes at construction), the most recent event is replayed once
     * a subscriber connects. We emit via tryEmit from the auth-state
     * handler so we don't suspend the JNI-driven dispatcher.
     */
    private val _loggedOut = MutableSharedFlow<Unit>(
        extraBufferCapacity = 1,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    val loggedOut: SharedFlow<Unit> = _loggedOut.asSharedFlow()

    private val _connection = MutableStateFlow(ConnectionStatus.Connecting)
    /**
     * Mirrors TDLib's [TdApi.UpdateConnectionState]. The UI uses this to render a top
     * banner ("З'єднання…", "Оновлення…", "Очікує мережі"), so transient hiccups during
     * a long roam or a Wi-Fi handoff have a visible explanation instead of an apparently
     * frozen feed.
     */
    val connection: StateFlow<ConnectionStatus> = _connection.asStateFlow()

    private val _me = MutableStateFlow<TdApi.User?>(null)
    /**
     * Authenticated user, populated lazily on [TdApi.AuthorizationStateReady] via
     * [TdApi.GetMe] and kept in sync afterwards through [TdApi.UpdateUser] events
     * for the self user-id (so name / phone / username edits made in the official
     * client surface here without a manual refresh). Cleared back to `null` on
     * logout so a new account doesn't briefly inherit the previous one's hero.
     *
     * Profile UI ([SettingsScreen]) renders a TG-style header from this flow.
     * Null is a render-nothing signal — pre-Ready cold starts and guest mode
     * both see `null` and skip the hero block.
     */
    val me: StateFlow<TdApi.User?> = _me.asStateFlow()

    // Last phone number the user tried — kept so AuthorizationStateWaitCode can display
    // the right number even when we no longer optimistically pre-set WaitCode.
    @Volatile
    private var lastAttemptedPhone: String = ""

    // Two-stage update pipeline. The JNI callback runs on a TDLib worker thread
    // outside any coroutine context, so it can't call MutableSharedFlow.emit (which
    // suspends). Two earlier designs both had real problems:
    //   • scope.launch { emit } from the callback — each event spawns its own
    //     coroutine, so identical-fileId UpdateFile bursts could deliver out of
    //     order and combined with a SUSPEND buffer caused races where MediaCache
    //     saw completion-before-progress.
    //   • tryEmit + a 256-slot SharedFlow — preserved order but silently dropped
    //     events on the cold-start burst (login emits thousands of UpdateNewChat /
    //     UpdateChatLastMessage / UpdateUser, far more than any reasonable buffer).
    //
    // The fix: an UNLIMITED Channel in front of the SharedFlow. The JNI callback
    // does a non-suspending trySend on the channel (which never fails on an
    // unlimited buffer); a single consumer coroutine drains it and emits to the
    // SharedFlow with proper backpressure-aware suspend semantics. FIFO is
    // guaranteed by the channel, the SharedFlow keeps its modest 64-slot buffer
    // because it now only buffers the *gap* between consumer reads, not the
    // entire login burst.
    private val incoming = Channel<TdApi.Update>(Channel.UNLIMITED)
    private val _updates = MutableSharedFlow<TdApi.Update>(extraBufferCapacity = 64)
    override val updates: SharedFlow<TdApi.Update> = _updates.asSharedFlow()

    init {
        scope.launch {
            for (update in incoming) {
                _updates.emit(update)
            }
        }
    }

    private lateinit var client: Client

    /**
     * Epoch-ms until which all outbound TDLib calls must back off. Set by [send]
     * whenever the server returns a `420 FLOOD_WAIT_<n>` (or "Too Many Requests:
     * retry after N") error — Telegram's per-method rate limit. Without honoring
     * this, the next refresh storm would compound the breach and earn a longer
     * sanction (Telegram escalates: 10s → 60s → minutes → an api_id lock).
     *
     * One global gate (vs per-method tracking) is intentional for a read-only
     * client: we generate light traffic anyway, and a single suspended sleep is
     * far simpler than threading per-method state through every callsite.
     *
     * Exposed as a [StateFlow] so [ui.main.ConnectionBanner] can surface the
     * throttle to the user when it stretches long enough to feel like the app
     * is frozen — silent multi-second sleeps were the worst part of the
     * previous experience. [update] with a `max` semantics ensures concurrent
     * flood responses can only push the deadline further, never pull it back.
     */
    private val _floodWaitUntilMs = MutableStateFlow(0L)
    val floodWaitUntilMs: StateFlow<Long> = _floodWaitUntilMs.asStateFlow()

    fun start() {
        if (this::client.isInitialized) return
        // Configure TDLib's native logging BEFORE spawning the client.
        //
        // TDLib's default sink is stdout, which on Android disappears into
        // /dev/null — making non-zero LOG_VERBOSITY useless and leaving any
        // TDLib-internal complaint invisible during development. The TDLib
        // README explicitly recommends calling SetLogStream during startup
        // to redirect output somewhere you can read.
        //
        //   • Debug: rotating file under filesDir/td-logs/. 5 MB cap, picked up
        //     when crash-investigating. redirectStderr=true also catches the
        //     handful of low-level prints that TDLib still does outside its
        //     normal log pipeline (during early startup, mostly).
        //   • Release: LogStreamEmpty() — TDLib doesn't open a file handle at
        //     all. Combined with LOG_VERBOSITY=0 (fatal-only) this means zero
        //     log I/O on the hot path in production.
        runCatching {
            val stream: TdApi.LogStream = if (BuildConfig.DEBUG) {
                val logsDir = context.filesDir.resolve("td-logs").apply { mkdirs() }
                TdApi.LogStreamFile(
                    logsDir.resolve("td.log").absolutePath,
                    /* maxFileSize */ 5L * 1024 * 1024,
                    /* redirectStderr */ true,
                )
            } else {
                TdApi.LogStreamEmpty()
            }
            Client.execute(TdApi.SetLogStream(stream))
        }.onFailure { Log.w(TAG, "SetLogStream failed", it) }
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
                // trySend on an UNLIMITED Channel never fails — order is preserved by
                // the channel, the consumer coroutine handles SharedFlow backpressure.
                incoming.trySend(obj)
            }
        }, null, null)
    }

    private fun handleUpdate(update: TdApi.Update) {
        when (update) {
            is TdApi.UpdateAuthorizationState -> scope.launch { onAuthState(update.authorizationState) }
            is TdApi.UpdateConnectionState -> _connection.value = update.state.toStatus()
            is TdApi.UpdateUser -> {
                // Keep [me] live when the official client edits the self user
                // (display name, username, phone-number formatting, isPremium
                // toggle from a fresh subscription, etc.). Cheap identity check
                // first — UpdateUser fires for every contact / chat author so
                // the hot path is "not me, skip".
                val current = _me.value
                if (current != null && update.user.id == current.id) {
                    _me.value = update.user
                }
            }
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
                    // Follow the app's resolved locale so server-localized payloads
                    // (country names, MTProto error messages where applicable) match
                    // what the rest of the UI shows. Falls back to "en" because the
                    // default fallback resource bundle is English.
                    systemLanguageCode = java.util.Locale.getDefault().language.ifBlank { "en" }
                    deviceModel = android.os.Build.MODEL
                    systemVersion = "Android ${android.os.Build.VERSION.RELEASE}"
                    applicationVersion = BuildConfig.VERSION_NAME
                }
                // Failure here is unrecoverable from inside the auth loop — bad apiId/apiHash,
                // unwritable database directory, etc. Surface a stage the UI can render
                // ("Збій конфігурації…") instead of leaving the user on Loading forever,
                // which is what the bare `send(params)` used to do (TdException would bubble
                // into scope.launch's SupervisorJob and get silently swallowed).
                runCatching { send(params) }
                    .onFailure { err ->
                        if (err is CancellationException) throw err
                        Log.e(TAG, "SetTdlibParameters failed", err)
                        _authStage.value = AuthStage.Error(
                            strings.getString(R.string.auth_err_init_failure),
                        )
                    }
            }
            is TdApi.AuthorizationStateWaitPhoneNumber -> _authStage.value = AuthStage.WaitPhone
            is TdApi.AuthorizationStateWaitCode -> {
                val info = state.codeInfo
                // Prefer TDLib's normalized phone (with proper formatting); fall back to the
                // raw input if TDLib didn't echo one.
                val phone = info.phoneNumber.orEmpty().ifBlank { lastAttemptedPhone }
                _authStage.value = AuthStage.WaitCode(
                    phoneNumber = phone,
                    codeLength = info.type.numericLength() ?: DEFAULT_CODE_LENGTH,
                    channelLabel = info.type.toLabel(strings, phone),
                    nextChannelLabel = info.nextType?.toLabel(strings, phone),
                    resendAvailableInSec = info.timeout,
                    isNumeric = info.type.isNumeric(),
                )
            }
            is TdApi.AuthorizationStateWaitPassword -> _authStage.value =
                AuthStage.WaitPassword(
                    hint = state.passwordHint.orEmpty(),
                    hasRecoveryEmail = state.hasRecoveryEmailAddress,
                    recoveryEmailPattern = state.recoveryEmailAddressPattern.orEmpty(),
                )
            is TdApi.AuthorizationStateReady -> {
                _authStage.value = AuthStage.Ready
                // [maybeOptimizeStorage] runs from [TdLifecycleBridge.goOnline]
                // instead — that fires whenever (auth.Ready && foreground=true)
                // becomes true, which is the same edge as cold-start completion
                // *plus* every later background → foreground transition. We
                // used to call it here too, but the two triggers fired
                // back-to-back on cold start and racing-launched two parallel
                // OptimizeStorage walks that TDLib then serialised — wasted
                // 50-300 ms on every launch.

                // Resolve the authenticated user for the Profile hero block.
                // One-shot GetMe; the subsequent [TdApi.UpdateUser] events for
                // the self user-id keep [_me] live without a re-fetch.
                scope.launch {
                    runCatching { send(TdApi.GetMe()) }
                        .onSuccess { result -> if (result is TdApi.User) _me.value = result }
                }
            }
            is TdApi.AuthorizationStateLoggingOut -> {
                _authStage.value = AuthStage.Loading
                // Drop the hero user before any UI recompose so a logout that
                // races with a Settings recomposition can't briefly render the
                // outgoing account's name / avatar on top of the loading state.
                _me.value = null
                // User-initiated logout: signal AppGraph to wipe per-account
                // in-memory caches + the on-disk timeline snapshot BEFORE
                // TDLib's eventual Closed → spawnClient races create a fresh
                // session that could otherwise read stale state. tryEmit on a
                // BUFFERED+DROP_OLDEST flow is non-suspending and never fails.
                _loggedOut.tryEmit(Unit)
            }
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
                AuthStage.Error(strings.getString(R.string.auth_err_email_required))
            is TdApi.AuthorizationStateWaitRegistration -> _authStage.value =
                AuthStage.Error(strings.getString(R.string.auth_err_phone_unregistered))
            is TdApi.AuthorizationStateWaitOtherDeviceConfirmation -> _authStage.value =
                AuthStage.Error(strings.getString(R.string.auth_err_other_device))
            // TDLib emits this when sign-in requires a Telegram Premium purchase to
            // continue (a server-side rule for some fresh accounts / regions). The
            // purchase has to go through the official client's in-app billing — we
            // can't complete it ourselves. Surface that explicitly so the user isn't
            // left on a blank Loading.
            is TdApi.AuthorizationStateWaitPremiumPurchase -> _authStage.value =
                AuthStage.Error(strings.getString(R.string.auth_err_premium_required))
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
     * 2FA password-recovery flow, step 1 of 2: ask Telegram to email a recovery
     * code to the recovery address registered on the account. Only valid while
     * the auth state is [TdApi.AuthorizationStateWaitPassword] AND the account
     * has a confirmed recovery email — UI gates the call on
     * [AuthStage.WaitPassword.hasRecoveryEmail]. TDLib responds with an error
     * for accounts without recovery email and we surface that via [authError].
     */
    suspend fun requestPasswordRecovery() {
        _authError.value = null
        runCatching { send(TdApi.RequestAuthenticationPasswordRecovery()) }
            .reportAuthFailure()
    }

    /**
     * 2FA password-recovery flow, step 2 of 2: submit the code Telegram emailed.
     * On success TDLib advances auth past [WaitPassword]; on failure (wrong code,
     * expired) the failure surfaces through [authError] and the UI keeps the
     * password screen up so the user can re-enter the password normally.
     */
    suspend fun recoverPassword(recoveryCode: String) {
        _authError.value = null
        runCatching {
            send(
                TdApi.RecoverAuthenticationPassword(
                    recoveryCode,
                    /* newPassword */ null,
                    /* newHint */ null,
                ),
            )
        }.reportAuthFailure()
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
            _authError.value = friendlyAuthErrorMessage(strings, err)
        }
    }

    suspend fun logOut() {
        runCatching { send(TdApi.LogOut()) }
    }

    /**
     * Suspend-style wrapper around [Client.send]. Wraps three cross-cutting concerns
     * around the bare JNI call:
     *
     *   • **FLOOD_WAIT throttle.** Before sending, await any outstanding rate-limit
     *     deadline set by a prior 420. After the call, if TDLib hands us back a
     *     420, parse the retry-after seconds out of the message and push the
     *     deadline forward. We do NOT auto-retry — the failed call still throws
     *     and the caller decides; all we guarantee is that the *next* send waits.
     *   • Result/error translation into [TdException], same as before.
     *   • **Defense-in-depth timeout.** [withTimeout] guards against a hung JNI
     *     callback (daemon livelock / native crash mid-call) leaking a permanently
     *     suspended coroutine. 60s is generous enough that legitimately slow calls
     *     (e.g. `GetMessageThreadHistory` on a cold cache, large `SearchMessages`)
     *     have headroom; well past that, the daemon is the problem, not the call.
     *     [suspendCancellableCoroutine] is cancellation-aware, so timeout
     *     propagates correctly — the callback from TDLib will still fire eventually
     *     but `cont.resume` becomes a no-op once the cont completed via cancel.
     */
    override suspend fun <T : TdApi.Object> send(query: TdApi.Function<T>): T {
        awaitFloodGate()
        return try {
            withTimeout(SEND_TIMEOUT_MS) {
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
            }
        } catch (e: TdException) {
            // TDLib hands us back rate-limit errors under two distinct codes
            // depending on which protocol layer answered:
            //   • 420 FLOOD_WAIT_<n>      — legacy MTProto, message text is
            //                                "FLOOD_WAIT_42".
            //   • 429 Too Many Requests   — newer translated layer, message text
            //                                is "Too Many Requests: retry after 42".
            // The original implementation gated only on 420 — a real bug surfaced
            // by live logcat: a `prefetchHistory` call hit a 429 with retry-after
            // 31, the gate was never armed, and every subsequent send (including
            // the user's interactive comments tap) blew through to TDLib in the
            // FLOOD window, getting served the same 429 over and over until TG
            // released the rate-limit. Treating both codes as flood-wait keeps
            // the gate honest regardless of which layer answered.
            if (isFloodWaitCode(e.code)) registerFloodWait(e.message.orEmpty())
            throw e
        }
    }

    private suspend fun awaitFloodGate() {
        val until = _floodWaitUntilMs.value
        val now = System.currentTimeMillis()
        if (until > now) {
            Log.w(TAG, "FLOOD_WAIT throttle: sleeping ${(until - now) / 1000}s before next send")
            delay(until - now)
        }
    }

    private fun registerFloodWait(message: String) {
        // TDLib formats the error a couple of ways depending on origin: the legacy
        // MTProto layer hands back "FLOOD_WAIT_42"; the newer translated layer uses
        // "Too Many Requests: retry after 42". Pinning to those two prefixes is
        // safer than the previous bare `\d+` scan, which would happily parse a
        // stray number out of any unrelated message text.
        val seconds = FLOOD_WAIT_SECONDS_RX.find(message)
            ?.groupValues?.get(1)?.toLongOrNull() ?: return
        if (seconds <= 0) return
        // Cap at FLOOD_WAIT_CAP_SECONDS as a safety belt — Telegram occasionally
        // returns absurd values (hours, days) that would freeze the app entirely.
        // Capping at 5 minutes preserves the throttle's intent (slow down) while
        // letting a stuck client recover via app restart in the worst case.
        val capped = seconds.coerceAtMost(FLOOD_WAIT_CAP_SECONDS)
        val deadline = System.currentTimeMillis() + capped * 1000L
        _floodWaitUntilMs.update { existing -> maxOf(existing, deadline) }
        Log.w(TAG, "FLOOD_WAIT registered: $message → throttle for ${capped}s")
    }

    /**
     * Run [TdApi.OptimizeStorage] when the on-disk media cache approaches the cap, or
     * (lower priority) once per [OPTIMIZE_INTERVAL_MS] for housekeeping. The 24h timer
     * alone proved insufficient — a heavy listening session can pile gigabytes between
     * sweeps if the user doesn't cycle the app — so we now ALSO trigger on a fast probe
     * showing the cache approaching its cap, regardless of when the last sweep ran.
     *
     * Decision matrix (probe → sweep):
     *   - usage ≥ [OPTIMIZE_TRIGGER_BYTES]  → sweep immediately, ignoring timer
     *   - usage < trigger AND 24h elapsed   → sweep (housekeeping)
     *   - otherwise                         → skip
     *
     * Called from:
     *   1. [TdApi.AuthorizationStateReady] — cold start after login.
     *   2. [TdLifecycleBridge.goOnline] — every background → foreground transition.
     *      The fast-probe path makes this cheap (~10 ms metadata read); only when usage
     *      is over the threshold do we pay the file-table walk (50-300 ms on a populated
     *      cache).
     *
     * Runs in the background; the caller does not await. We stamp the timestamp *before*
     * the call returns so a long sweep can't be retriggered by a concurrent invocation.
     */
    fun maybeOptimizeStorage() {
        scope.launch {
            runCatching {
                // [TdApi.GetStorageStatisticsFast] is a metadata-only read (sub-10 ms);
                // [TdApi.OptimizeStorage] walks the file table on a populated ~500 MB
                // cache (50-300 ms). Levin's recommended pattern in
                // tdlib/td#667#issuecomment-521611484: *"you can from time to time use
                // getStorageStatisticsFast to quickly get size of TDLib's cache and run
                // the method optimizeStorage if needed."* The probe is cheap enough to
                // run on every foreground transition.
                val fast = send(TdApi.GetStorageStatisticsFast())
                val usageBytes = fast.filesSize
                val now = System.currentTimeMillis()
                val last = settings.lastStorageOptimizeAt()
                val overThreshold = usageBytes >= OPTIMIZE_TRIGGER_BYTES
                val timerDue = (now - last) >= OPTIMIZE_INTERVAL_MS
                if (!overThreshold && !timerDue) {
                    Log.i(
                        TAG,
                        "OptimizeStorage skipped: cache=${usageBytes / (1024L * 1024L)}MB " +
                            "(under ${OPTIMIZE_TRIGGER_BYTES / (1024L * 1024L)}MB trigger and within 24h)",
                    )
                    return@runCatching
                }
                settings.setLastStorageOptimizeAt(now)
                Log.i(
                    TAG,
                    "OptimizeStorage running: cache=${usageBytes / (1024L * 1024L)}MB " +
                        "(overThreshold=$overThreshold, timerDue=$timerDue)",
                )
                // For OptimizeStorage's eviction-driving limits (size, ttl, count,
                // immunityDelay) TDLib treats 0 as a literal "limit 0" and -1 as
                // "use the default limit". An earlier version passed `count=0`
                // and `immunityDelay=60`, which under "strictest-limit-wins"
                // semantics overrode our intended `size=500MB` cap and made the
                // daily sweep evict everything not currently in flight. Users
                // saw it as the avatar pyramid + cached photos re-downloading
                // the next day. Fix:
                //   • size          = 500 MB hard cap (kept)
                //   • ttl           = -1 (TDLib default)
                //   • count         = -1 (no count cap; size drives eviction)
                //   • immunityDelay = -1 (TDLib default 1 h — keeps a freshly
                //                     downloaded file safe from eviction during
                //                     the immediate post-download window when
                //                     the user is most likely still looking at
                //                     it; size cap drives long-term eviction)
                // chatLimit stays 0 — per the field's javadoc it "Affects only
                // returned statistics" (number of chats with their own breakdown
                // in the response), not eviction. We don't read those stats, so
                // 0 is the correct "no per-chat breakdown" value.
                //
                // [fileTypes] explicitly scopes eviction to the heavyweight media
                // types. TDLib's null-default ALREADY excludes thumbnails, profile
                // photos, stickers and wallpapers (per TdApi.OptimizeStorage javadoc:
                // *"By default, all types except thumbnails, profile photos, stickers
                // and wallpapers are deleted"*) — so listing this set explicitly is a
                // documentation win, not a behaviour change. Two reasons to keep the
                // explicit form anyway:
                //   • Reading this call site, you can see at a glance what's
                //     touched without having to remember TDLib's default policy.
                //   • Stable across TDLib version bumps. If a future TDLib release
                //     adds a new "secondary" type to the default-exclude set
                //     (e.g. ringtones, notification sounds), our explicit list
                //     stays the same — the new type rides out alongside stickers,
                //     which is the conservative default we'd choose anyway.
                // Levin's tdlib/td#2376 reminder *"don't forget to specify all
                // existing file_types"* applies if you SCOPE differently from us
                // — e.g. evicting only one type. Our list already enumerates
                // every media type we serve.
                send(
                    TdApi.OptimizeStorage(
                        /* size */ 500L * 1024 * 1024,
                        /* ttl  */ -1,
                        /* count */ -1,
                        /* immunityDelay */ -1,
                        /* fileTypes */ MEDIA_FILE_TYPES,
                        /* chatIds */ null,
                        /* excludeChatIds */ null,
                        /* returnDeletedFileStatistics */ false,
                        /* chatLimit */ 0,
                    ),
                )
            }.onFailure { err ->
                if (err is CancellationException) throw err
                Log.w(TAG, "OptimizeStorage failed", err)
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
        private const val TAG = "TdClient"

        // Defense-in-depth ceiling for a single [send] call. Generous enough that
        // legitimately slow TDLib operations (cold-cache GetMessageThreadHistory,
        // SearchMessages against large corpora) complete with headroom. Past 60s
        // the daemon is the problem, not the call — fail the coroutine instead of
        // leaking it. See [send]'s KDoc for the full rationale.
        private const val SEND_TIMEOUT_MS = 60_000L

        private const val OPTIMIZE_INTERVAL_MS = 24L * 60 * 60 * 1000 // once per day

        // Skip OptimizeStorage's file-table walk when usage is below this threshold.
        // Picked at 80% of the cap (500 MB × 0.8 = 400 MB) — far enough below the
        // hard limit that an "in-progress" sweep can still complete naturally before
        // the next day-boundary sweep, but high enough that a typical light user
        // never triggers a walk at all.
        private const val OPTIMIZE_TRIGGER_BYTES = 400L * 1024 * 1024

        // Media file types that participate in our daily storage sweep. Matches
        // what Telegram-Android scopes its automatic eviction to — small types
        // (stickers, profile photos, wallpapers, thumbnails) ride out across
        // sessions because they re-download annoyingly for very little space win.
        // See [TdApi.FileType] subclasses for the full taxonomy.
        private val MEDIA_FILE_TYPES: Array<TdApi.FileType> = arrayOf(
            TdApi.FileTypePhoto(),
            TdApi.FileTypeVideo(),
            TdApi.FileTypeAnimation(),
            TdApi.FileTypeVoiceNote(),
            TdApi.FileTypeAudio(),
            TdApi.FileTypeDocument(),
            TdApi.FileTypeVideoNote(),
        )
        private const val DEFAULT_CODE_LENGTH = 5
        // Telegram's per-method rate-limit error codes. TDLib reports two distinct
        // codes depending on which protocol layer answered: 420 (legacy MTProto,
        // "FLOOD_WAIT_42") and 429 (newer translation, "Too Many Requests:
        // retry after 42"). Both observed in the field — treat as equivalent.
        fun isFloodWaitCode(code: Int): Boolean = code == 420 || code == 429

        // Shared with [registerFloodWait] and [parseFloodWaitSeconds]; matches
        // both TDLib formats — "FLOOD_WAIT_<n>" (420 / MTProto) and "retry after
        // <n>" (429 / translated layer; the verbose form is "Too Many Requests:
        // retry after <n>").
        private val FLOOD_WAIT_SECONDS_RX =
            Regex("(?:FLOOD_WAIT_|retry after )(\\d+)")

        /**
         * Public helper for callers (e.g. ReportRepository.mapError) that need
         * to surface a "Telegram попросив почекати N секунд" hint from a TDLib
         * error. Returns null when the error is not a flood-wait one, or when
         * the message doesn't carry a parseable second count.
         */
        fun parseFloodWaitSeconds(error: TdApi.Error): Int? {
            if (!isFloodWaitCode(error.code)) return null
            val msg = error.message ?: return null
            return FLOOD_WAIT_SECONDS_RX.find(msg)
                ?.groupValues?.get(1)?.toIntOrNull()
        }

        /** Same as [parseFloodWaitSeconds] but for a [TdException] surfaced from [send]. */
        fun parseFloodWaitSeconds(exception: TdException): Int? {
            if (!isFloodWaitCode(exception.code)) return null
            val msg = exception.message ?: return null
            return FLOOD_WAIT_SECONDS_RX.find(msg)
                ?.groupValues?.get(1)?.toIntOrNull()
        }
        // Safety cap on flood-wait throttling. Telegram very occasionally returns
        // multi-hour or multi-day deadlines (typically after egregious abuse). For an
        // interactive client, sleeping that long is worse than failing visibly, so we
        // cap and let a failure path surface to the user.
        private const val FLOOD_WAIT_CAP_SECONDS = 300L

        fun create(context: Context, settings: SettingsStore): TdClient {
            check(BuildConfig.TELEGRAM_API_ID != 0 && BuildConfig.TELEGRAM_API_HASH.isNotEmpty()) {
                "Telegram api credentials missing. Add telegram.apiId / telegram.apiHash to local.properties."
            }
            return TdClient(
                context.applicationContext,
                BuildConfig.TELEGRAM_API_ID,
                BuildConfig.TELEGRAM_API_HASH,
                settings,
            )
        }
    }
}

/**
 * Digit count expected by [this] code channel, or null when the channel does not deliver
 * a numeric code (SmsWord, SmsPhrase, FlashCall — those expect a word/phrase or pattern).
 *
 * Why this matters: hardcoding `length = 5` in the OTP UI breaks for 6-digit Firebase /
 * Fragment / TelegramMessage codes — TDLib has been rolling those out steadily over the
 * past two years. Reading [length] from the actual [TdApi.AuthenticationCodeType] is the
 * only way to stay correct as Telegram changes channel lengths server-side.
 */
private fun TdApi.AuthenticationCodeType.numericLength(): Int? = when (this) {
    is TdApi.AuthenticationCodeTypeTelegramMessage -> length
    is TdApi.AuthenticationCodeTypeSms -> length
    is TdApi.AuthenticationCodeTypeCall -> length
    is TdApi.AuthenticationCodeTypeMissedCall -> length
    is TdApi.AuthenticationCodeTypeFragment -> length
    is TdApi.AuthenticationCodeTypeFirebaseAndroid -> length
    is TdApi.AuthenticationCodeTypeFirebaseIos -> length
    else -> null
}

private fun TdApi.AuthenticationCodeType.isNumeric(): Boolean = numericLength() != null

/**
 * Pre-render a Ukrainian description of the code-delivery channel so the UI doesn't have
 * to switch on TDLib's [TdApi.AuthenticationCodeType] sum type. [phone] is the active
 * phone number used to enrich SMS / missed-call labels with the destination number.
 *
 * Note on third-party limits: TDLib's docs state that [TdApi.AuthenticationCodeTypeSms],
 * [TdApi.AuthenticationCodeTypeSmsWord] and [TdApi.AuthenticationCodeTypeSmsPhrase] are
 * *not* sent to non-official applications. Hortay therefore mostly sees TelegramMessage,
 * Call, MissedCall, Fragment, FirebaseAndroid; the SMS branches are kept in the mapping
 * for completeness in case Telegram lifts the restriction.
 */
private fun TdApi.AuthenticationCodeType.toLabel(res: StringResolver, phone: String): String = when (this) {
    is TdApi.AuthenticationCodeTypeTelegramMessage -> res.getString(R.string.auth_channel_telegram_message)
    is TdApi.AuthenticationCodeTypeSms ->
        if (phone.isNotEmpty()) res.getString(R.string.auth_channel_sms_with_phone, phone)
        else res.getString(R.string.auth_channel_sms)
    is TdApi.AuthenticationCodeTypeSmsWord -> res.getString(R.string.auth_channel_sms_word)
    is TdApi.AuthenticationCodeTypeSmsPhrase -> res.getString(R.string.auth_channel_sms_phrase)
    is TdApi.AuthenticationCodeTypeCall ->
        if (phone.isNotEmpty()) res.getString(R.string.auth_channel_call_with_phone, phone)
        else res.getString(R.string.auth_channel_call)
    is TdApi.AuthenticationCodeTypeMissedCall -> res.getString(R.string.auth_channel_missed_call)
    is TdApi.AuthenticationCodeTypeFlashCall -> res.getString(R.string.auth_channel_flash_call)
    is TdApi.AuthenticationCodeTypeFragment -> res.getString(R.string.auth_channel_fragment)
    is TdApi.AuthenticationCodeTypeFirebaseAndroid,
    is TdApi.AuthenticationCodeTypeFirebaseIos -> res.getString(R.string.auth_channel_firebase)
    else -> res.getString(R.string.auth_channel_other)
}
