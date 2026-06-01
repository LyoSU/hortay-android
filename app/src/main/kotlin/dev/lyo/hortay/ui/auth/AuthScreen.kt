@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.lyo.hortay.ui.auth

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import dev.lyo.hortay.ui.icons.Symbol
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lyo.hortay.AppGraph
import dev.lyo.hortay.R
import dev.lyo.hortay.data.AuthStage
import dev.lyo.hortay.data.Country
import dev.lyo.hortay.data.TdClient
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.settings.ProxyScreen
import kotlinx.coroutines.launch

/**
 * Auth flow entry point. Reads three signals from [TdClient]:
 *   - [TdClient.authStage] — where we are in TDLib's authorization state machine.
 *   - [TdClient.authError]  — transient errors from the last submit (kept *separate* so
 *     a rejected code doesn't blow the user back to a blank "Помилка" screen — they stay
 *     on the same form with their input intact and an inline message under the field).
 *   - [TdClient.connection] (optional) — could be used to gate the submit button when
 *     offline; currently we let TDLib reject and the friendly mapper handles it.
 *
 * The [graph] dependency is what gives us [AppGraph.countries]; we let the country picker
 * lazy-load on first composition of the phone form (`countries.load()` inside `LaunchedEffect`).
 */
@Composable
fun AuthScreen(graph: AppGraph, stage: AuthStage) {
    val client = graph.tdClient
    val authError by client.authError.collectAsStateWithLifecycle()
    // Proxy can be configured before sign-in — addProxy works pre-authorization (tdlib/td#300),
    // so a user in a blocked network can reach Telegram to authenticate at all.
    var showProxy by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            Spacer(Modifier.height(40.dp))

            // Back affordance only on stages where bouncing back makes sense. WaitPhone
            // is the root of the flow — there's nowhere to back to. Loading/Ready/Error
            // either auto-resolve or have their own retry button.
            if (stage is AuthStage.WaitCode || stage is AuthStage.WaitPassword) {
                BackAffordance(onBack = { graph.appScope.launch { client.cancelAuth() } })
                Spacer(Modifier.height(12.dp))
            }

            HeroBlock(stage = stage)

            Spacer(Modifier.height(36.dp))

            // Auth-stage slide: vertical (sibling forms, not depth navigation).
            // MotionScheme spec captured in composable scope; transitionSpec is
            // non-composable so we can't read MaterialTheme inside it.
            val authSpatial = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
            val authEffects = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
            AnimatedContent(
                targetState = stage,
                transitionSpec = {
                    (slideInVertically(authSpatial) { it / 6 } + fadeIn(authEffects))
                        .togetherWith(slideOutVertically(authSpatial) { -it / 6 } + fadeOut(authEffects))
                },
                label = "auth-stage",
            ) { current ->
                when (current) {
                    is AuthStage.Loading -> LoadingForm()
                    is AuthStage.WaitPhone -> PhoneForm(graph = graph, errorMessage = authError)
                    is AuthStage.WaitCode -> CodeForm(
                        graph = graph,
                        stage = current,
                        errorMessage = authError,
                    )
                    is AuthStage.WaitPassword -> PasswordForm(
                        graph = graph,
                        stage = current,
                        errorMessage = authError,
                    )
                    is AuthStage.Error -> RecoverableErrorBlock(
                        message = current.message,
                        onRetry = { graph.appScope.launch { client.cancelAuth() } },
                    )
                    is AuthStage.Ready -> LoadingForm()
                }
            }

            Spacer(Modifier.height(24.dp))

            TextButton(
                onClick = { showProxy = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Symbol(name = "vpn_key", size = 18.dp)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.proxy_title))
            }

            Spacer(Modifier.height(24.dp))
        }

        if (showProxy) {
            // AuthScreen is the root with no back stack, so the system back gesture would finish
            // the Activity. This handler — composed only while the overlay is up — intercepts back
            // to dismiss the proxy screen instead, and unregisters when it closes.
            BackHandler { showProxy = false }
            Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                ProxyScreen(
                    repo = graph.proxyRepository,
                    contentPadding = PaddingValues(),
                    onBack = { showProxy = false },
                )
            }
        }
    }
}

@Composable
private fun BackAffordance(onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Symbol(
                name = "arrow_back",
                tint = MaterialTheme.colorScheme.onSurface,
                size = 24.dp,
                contentDescription = stringResource(R.string.auth_back),
            )
        }
        Text(
            text = stringResource(R.string.auth_back),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clickable(onClick = onBack),
        )
    }
}

@Composable
private fun HeroBlock(stage: AuthStage) {
    Column {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Symbol(
                name = when (stage) {
                    is AuthStage.WaitCode -> "pin"
                    is AuthStage.WaitPassword -> "lock"
                    is AuthStage.Error -> "info"
                    else -> "smartphone"
                },
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                size = 36.dp,
            )
        }
        Spacer(Modifier.height(20.dp))
        Text(
            text = when (stage) {
                is AuthStage.WaitCode -> stringResource(R.string.auth_title_wait_code)
                is AuthStage.WaitPassword -> stringResource(R.string.auth_title_wait_password)
                is AuthStage.Error -> stringResource(R.string.auth_title_error)
                else -> stringResource(R.string.app_name)
            },
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = when (stage) {
                is AuthStage.WaitCode -> stringResource(R.string.auth_subtitle_wait_code, stage.channelLabel)
                is AuthStage.WaitPassword -> stringResource(
                    if (stage.hint.isNotEmpty()) R.string.auth_subtitle_password_no_hint
                    else R.string.auth_subtitle_password_with_hint,
                )
                is AuthStage.Error -> stringResource(R.string.auth_open_telegram_hint)
                else -> stringResource(R.string.auth_subtitle_default)
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------- Phone ----------

@Composable
private fun PhoneForm(graph: AppGraph, errorMessage: String?) {
    val client = graph.tdClient
    val scope = rememberCoroutineScope()
    val countries by graph.countries.countries.collectAsStateWithLifecycle()
    val detectedIso by graph.countries.detectedIso.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { graph.countries.load() }

    var selected by remember { mutableStateOf<Country?>(null) }
    var phoneNational by remember { mutableStateOf("") }
    var sheetOpen by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    // Used only when the user picks the synthetic "Інша країна" row — they can type any
    // dial code into the prefix field (e.g. +1268, +672). For real countries the prefix
    // stays read-only and equals the picked country's dialCode.
    var customDial by remember { mutableStateOf("+") }

    // Pick the default country once the catalogue + detection arrive. Prefer the carrier
    // ISO; fall back to UA — this app's audience is Ukraine-first and a sensible default
    // beats a blank dial-code field on first launch.
    LaunchedEffect(countries, detectedIso) {
        if (selected == null && countries.isNotEmpty()) {
            val iso = detectedIso?.uppercase() ?: "UA"
            selected = countries.firstOrNull { it.iso.equals(iso, ignoreCase = true) }
                ?: countries.firstOrNull { it.iso.equals("UA", ignoreCase = true) }
                ?: countries.first()
        }
    }

    // Errors from a previous submit clear once the user edits anything — feels right and
    // avoids a stale red message hanging around after they've already corrected it.
    LaunchedEffect(phoneNational, selected, customDial) {
        if (errorMessage != null) client.clearAuthError()
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        CountrySelectorRow(
            country = selected,
            onClick = { sheetOpen = true },
        )
        Spacer(Modifier.height(12.dp))
        PhoneNumberRow(
            country = selected,
            customDial = customDial,
            onCustomDialChange = { raw ->
                // Allow only `+` plus digits; keep `+` always at the head so the user
                // can't accidentally erase it. Cap at 5 (E.164 country code max is 3,
                // but Fragment-style services can quote longer prefixes; 5 is safe).
                val digits = raw.filter(Char::isDigit).take(4)
                customDial = "+$digits"
            },
            value = phoneNational,
            onValueChange = { phoneNational = it.filter(Char::isDigit).take(15) },
            isError = errorMessage != null,
        )
        AnimatedFieldError(text = errorMessage)
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.auth_phone_helper),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))
        PrimaryActionButton(
            text = stringResource(R.string.auth_send_code),
            enabled = !submitting &&
                (selected != null) &&
                phoneNational.length >= 5 &&
                (!(selected?.isCustom ?: false) || customDial.length >= 2),
            loading = submitting,
            onClick = {
                val country = selected ?: return@PrimaryActionButton
                val prefix = if (country.isCustom) customDial else country.dialCode
                val full = prefix + phoneNational
                submitting = true
                scope.launch {
                    try { client.submitPhone(full) } finally { submitting = false }
                }
            },
        )
        Spacer(Modifier.height(20.dp))
        // Guest-mode escape hatch. Visible only on the WaitPhone stage so it
        // doesn't compete with code/password forms mid-flow. Flipping the flag
        // recomposes MainActivity, which routes through to WebModeScaffold.
        // No TDLib call, no phone number, no MTProto — purely a local DataStore
        // write. Subscriptions persist across mode switches both directions so
        // the user can experiment without losing their channel list.
        //
        // Visual weight: bumped from a near-invisible TextButton to an
        // OutlinedButton with a leading "visibility" glyph. Still secondary to
        // the filled "Send code" PrimaryActionButton above (no background fill,
        // outlined stroke is materially less prominent than the primary
        // container) — guest mode is a real opt-out, not the happy path.
        OutlinedButton(
            onClick = {
                scope.launch { graph.guestMode.setGuest(true) }
            },
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Symbol(name = "visibility", contentDescription = null, size = 18.dp)
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.auth_continue_without_login),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }

    if (sheetOpen) {
        CountryPickerSheet(
            countries = countries,
            onPick = { picked ->
                selected = picked
                sheetOpen = false
            },
            onDismiss = { sheetOpen = false },
        )
    }
}

@Composable
private fun CountrySelectorRow(country: Country?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(modifier = Modifier.width(28.dp), contentAlignment = Alignment.Center) {
            Text(
                text = country?.flag ?: "🌐",
                style = MaterialTheme.typography.titleLarge,
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.auth_country_label),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = country?.name ?: stringResource(R.string.auth_country_loading),
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        Symbol(
            name = "arrow_forward",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            size = 20.dp,
        )
    }
}

@Composable
private fun PhoneNumberRow(
    country: Country?,
    customDial: String,
    onCustomDialChange: (String) -> Unit,
    value: String,
    onValueChange: (String) -> Unit,
    isError: Boolean,
) {
    val isCustom = country?.isCustom == true
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Dial-code field. Read-only when paired with a real country (changing it means
        // opening the picker, exactly like in official Telegram), but editable when the
        // user picks the synthetic "Інша країна" row so they can type any prefix.
        OutlinedTextField(
            value = if (isCustom) customDial else (country?.dialCode ?: "+"),
            onValueChange = onCustomDialChange,
            readOnly = !isCustom,
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            isError = isError,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            textStyle = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                disabledTextColor = MaterialTheme.colorScheme.onSurface,
            ),
            modifier = Modifier.width(110.dp),
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(stringResource(R.string.auth_phone_placeholder)) },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Phone,
                imeAction = ImeAction.Done,
            ),
            singleLine = true,
            isError = isError,
            shape = MaterialTheme.shapes.medium,
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
            ),
            modifier = Modifier.weight(1f),
        )
    }
}

// ---------- Code ----------

@Composable
private fun CodeForm(graph: AppGraph, stage: AuthStage.WaitCode, errorMessage: String?) {
    val client = graph.tdClient
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    // Reset the typed code whenever TDLib swaps the active code channel — resend can
    // change codeLength (e.g. a 5-digit TelegramMessage code falling back to a 6-digit
    // Fragment one) and a submit with a stale longer/shorter value would 400 with
    // PHONE_CODE_INVALID even if the user re-typed correctly afterwards. Keying on
    // (codeLength, channelLabel) means the field clears on a real channel switch but stays
    // put through harmless recompositions of the same WaitCode payload.
    var code by remember(stage.codeLength, stage.channelLabel) { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var resending by remember { mutableStateOf(false) }

    // Server-mandated cooldown ticks down from stage.resendAvailableInSec to 0. While it
    // is non-zero the resend button is disabled with a "0:42" label, which is what the
    // official Telegram client does — and saves users from spamming themselves into a
    // FLOOD_WAIT. Resets whenever the stage emits a fresh cooldown (e.g. after a resend
    // succeeds and TDLib hands back a new AuthorizationStateWaitCode with a new timeout).
    var secondsLeft by remember(stage.resendAvailableInSec) {
        mutableIntStateOf(stage.resendAvailableInSec)
    }
    LaunchedEffect(stage.resendAvailableInSec) {
        while (secondsLeft > 0) {
            kotlinx.coroutines.delay(1000)
            secondsLeft -= 1
        }
    }

    LaunchedEffect(code) {
        if (errorMessage != null) client.clearAuthError()
    }

    val onSubmit: (String) -> Unit = { input ->
        if (!submitting) {
            submitting = true
            scope.launch {
                try { client.submitCode(input) } finally { submitting = false }
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Numeric channels get the segmented OTP grid sized to TDLib's reported length.
        // For a third-party app that's TelegramMessage and (rarely) Fragment — the only
        // types we ever receive (tdlib/td#2310). The exotic SmsWord / SmsPhrase channels
        // would need a free-form text field instead, so the defensive `else` branch keeps
        // one; in practice it doesn't fire for us.
        if (stage.isNumeric) {
            OtpInput(
                value = code,
                onValueChange = { code = it },
                length = stage.codeLength,
                isError = errorMessage != null,
                enabled = !submitting,
                onComplete = onSubmit,
            )
        } else {
            OutlinedTextField(
                value = code,
                onValueChange = { code = it },
                singleLine = true,
                isError = errorMessage != null,
                enabled = !submitting,
                shape = MaterialTheme.shapes.medium,
                placeholder = { Text(stringResource(R.string.auth_code_placeholder)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        AnimatedFieldError(text = errorMessage)
        // For third-party apps the code only ever arrives inside Telegram itself
        // (tdlib/td#2310) — SMS is reserved for official mobile clients. Spell that out
        // so a user without a second signed-in device doesn't sit waiting for an SMS that
        // will never come and conclude the app is broken.
        if (stage.deliveredInApp) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.auth_code_hint_telegram),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(20.dp))
        PrimaryActionButton(
            text = stringResource(R.string.auth_continue),
            enabled = !submitting && code.length >= minSubmitLength(stage),
            loading = submitting,
            onClick = {
                focusManager.clearFocus()
                onSubmit(code)
            },
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                enabled = !resending && !submitting && secondsLeft == 0,
                onClick = {
                    resending = true
                    scope.launch {
                        try { client.resendCode() } finally { resending = false }
                    }
                },
            ) {
                val ctxRes = LocalContext.current.resources
                Text(
                    text = resendLabel(
                        res = ctxRes,
                        secondsLeft = secondsLeft,
                        resending = resending,
                        nextChannelLabel = stage.nextChannelLabel,
                    ),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            TextButton(
                enabled = !submitting,
                onClick = { scope.launch { client.cancelAuth() } },
            ) {
                Text(
                    text = stringResource(R.string.auth_change_number),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

/**
 * Minimum length to enable the primary submit button. For numeric channels we wait for
 * exactly the digit count TDLib expects; for SmsWord / SmsPhrase any non-empty input is
 * acceptable because we don't know the actual length up front.
 */
private fun minSubmitLength(stage: AuthStage.WaitCode): Int =
    if (stage.isNumeric) stage.codeLength else 1

/**
 * Resend button label by cooldown state: "Sending…" while in flight, a "0:42" countdown
 * while the server cooldown is non-zero, then either "Send code again (<next channel>)"
 * when TDLib advertises a next_type (for us only ever Fragment — tdlib/td#2310) or a plain
 * "Resend" when there's none. We never name SMS here because a third-party app's next_type
 * is never SMS. Pulling this out keeps CodeForm's layout block readable.
 */
private fun resendLabel(
    res: android.content.res.Resources,
    secondsLeft: Int,
    resending: Boolean,
    nextChannelLabel: String?,
): String = when {
    resending -> res.getString(R.string.auth_resend_sending)
    secondsLeft > 0 -> res.getString(R.string.auth_resend_countdown, secondsLeft / 60, secondsLeft % 60)
    nextChannelLabel != null -> res.getString(R.string.auth_resend_via, nextChannelLabel)
    else -> res.getString(R.string.auth_resend_again)
}

// ---------- Password ----------

@Composable
private fun PasswordForm(graph: AppGraph, stage: AuthStage.WaitPassword, errorMessage: String?) {
    val client = graph.tdClient
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    var password by remember { mutableStateOf("") }
    var recoveryCode by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }
    // Recovery flow has three states:
    //   • Idle (default) — show password field + "Forgot password" link.
    //   • Sent — link tapped, requestPasswordRecovery fired, code field shown.
    //     Toggling back into password mode (auth_password_recovery_back) resets
    //     to Idle without retracting Telegram's already-sent email; that's
    //     acceptable, the code stays valid for a few minutes either way.
    //   • Unavailable — link tapped but stage.hasRecoveryEmail is false. Static
    //     info card explaining the user must reset on another device. No RPC.
    var recoveryMode by remember { mutableStateOf(RecoveryMode.Idle) }

    LaunchedEffect(password, recoveryCode) {
        if (errorMessage != null) client.clearAuthError()
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        if (recoveryMode != RecoveryMode.Sent) {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(stringResource(R.string.auth_password_label)) },
                singleLine = true,
                isError = errorMessage != null,
                visualTransformation = if (visible) VisualTransformation.None
                else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                shape = MaterialTheme.shapes.medium,
                trailingIcon = {
                    IconButton(onClick = { visible = !visible }) {
                        Symbol(
                            name = if (visible) "visibility_off" else "visibility",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            size = 22.dp,
                            contentDescription = stringResource(
                                if (visible) R.string.auth_password_hide else R.string.auth_password_show,
                            ),
                        )
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            AnimatedFieldError(text = errorMessage)
            if (stage.hint.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(MaterialTheme.shapes.medium)
                        .background(MaterialTheme.colorScheme.surfaceContainerLow)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Symbol(
                        name = "info",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        size = 18.dp,
                    )
                    Text(
                        text = stringResource(R.string.auth_password_hint, stage.hint),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.auth_password_helper),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (recoveryMode == RecoveryMode.Unavailable) {
            Spacer(Modifier.height(12.dp))
            RecoveryInfoCard(
                text = stringResource(R.string.auth_password_recovery_unavailable),
            )
        }
        if (recoveryMode == RecoveryMode.Sent) {
            RecoveryInfoCard(
                text = stringResource(
                    R.string.auth_password_recovery_sent,
                    stage.recoveryEmailPattern.ifEmpty { "***" },
                ),
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = recoveryCode,
                onValueChange = { recoveryCode = it.filter { c -> c.isDigit() }.take(8) },
                label = { Text(stringResource(R.string.auth_password_recovery_code_label)) },
                singleLine = true,
                isError = errorMessage != null,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.NumberPassword,
                    imeAction = ImeAction.Done,
                ),
                shape = MaterialTheme.shapes.medium,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            AnimatedFieldError(text = errorMessage)
        }
        // Forgot-password / back-to-password toggle. Hidden when recovery is
        // Unavailable — the info card already explains the user has no path
        // forward in-app, a "back to password" link there would be misleading.
        if (recoveryMode != RecoveryMode.Unavailable) {
            Spacer(Modifier.height(8.dp))
            val toggleText = stringResource(
                if (recoveryMode == RecoveryMode.Sent) R.string.auth_password_recovery_back
                else R.string.auth_password_forgot,
            )
            Text(
                text = toggleText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clickable {
                        if (recoveryMode == RecoveryMode.Sent) {
                            recoveryMode = RecoveryMode.Idle
                            recoveryCode = ""
                        } else if (!stage.hasRecoveryEmail) {
                            recoveryMode = RecoveryMode.Unavailable
                        } else {
                            recoveryMode = RecoveryMode.Sent
                            scope.launch { client.requestPasswordRecovery() }
                        }
                    }
                    .padding(vertical = 4.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        when (recoveryMode) {
            RecoveryMode.Sent -> PrimaryActionButton(
                text = stringResource(R.string.auth_continue),
                enabled = !submitting && recoveryCode.isNotEmpty(),
                loading = submitting,
                onClick = {
                    focusManager.clearFocus()
                    submitting = true
                    scope.launch {
                        try { client.recoverPassword(recoveryCode) } finally { submitting = false }
                    }
                },
            )
            RecoveryMode.Unavailable -> Unit
            RecoveryMode.Idle -> PrimaryActionButton(
                text = stringResource(R.string.auth_continue),
                enabled = !submitting && password.isNotEmpty(),
                loading = submitting,
                onClick = {
                    focusManager.clearFocus()
                    submitting = true
                    scope.launch {
                        try { client.submitPassword(password) } finally { submitting = false }
                    }
                },
            )
        }
    }
}

private enum class RecoveryMode { Idle, Sent, Unavailable }

@Composable
private fun RecoveryInfoCard(text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Symbol(
            name = "info",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            size = 18.dp,
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------- Shared ----------

@Composable
private fun PrimaryActionButton(
    text: String,
    enabled: Boolean,
    loading: Boolean,
    onClick: () -> Unit,
) {
    // M3 Expressive press-shape morph: pill at rest, squarer (`shapes.medium`,
    // 18 dp) under thumb. Squish reads as tactile feedback and is the canonical
    // ButtonGroup vocabulary documented in M3E's interaction-states spec; without
    // it the primary "Sign in" / "Continue" buttons looked identical pressed vs
    // resting, hiding the only press-state cue.
    Button(
        onClick = onClick,
        shapes = ButtonDefaults.shapes(
            shape = CircleShape,
            pressedShape = MaterialTheme.shapes.medium,
        ),
        enabled = enabled,
        contentPadding = PaddingValuesPrimary,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (loading) {
            // Inline expressive spinner that morphs through the canonical M3 polygon
            // cycle (Circle → SoftBurst → Cookie9 → Pill → Sunny). Reads as "alive"
            // even at 20 dp where a circular ring would be a static halo.
            LoadingIndicator(
                modifier = Modifier.size(24.dp),
                color = LocalContentColor.current,
            )
        } else {
            Text(text, style = MaterialTheme.typography.titleMedium)
        }
    }
}

private val PaddingValuesPrimary
    @Composable get() = androidx.compose.foundation.layout.PaddingValues(
        vertical = 18.dp,
        horizontal = 24.dp,
    )

@Composable
private fun LoadingForm() {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        // ContainedLoadingIndicator wraps the morphing polygon cycle in a tonal
        // surface — heavier visual weight that reads as "system is working" on a
        // full-screen blocking loader, vs the inline LoadingIndicator on buttons.
        ContainedLoadingIndicator()
    }
}

@Composable
private fun AnimatedFieldError(text: String?) {
    val spatial = MaterialTheme.motionScheme.fastSpatialSpec<IntOffset>()
    val effects = MaterialTheme.motionScheme.fastEffectsSpec<Float>()
    AnimatedVisibility(
        visible = text != null,
        enter = fadeIn(effects) + slideInVertically(spatial) { -it / 2 },
        exit = fadeOut(effects),
    ) {
        if (text != null) {
            Row(
                modifier = Modifier.padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Symbol(
                    name = "info",
                    tint = MaterialTheme.colorScheme.error,
                    size = 16.dp,
                )
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun RecoverableErrorBlock(message: String, onRetry: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(20.dp))
        // AuthStage.Error always carries an "open Telegram, do X there, come back"
        // recovery path (the subtitle on the hero block makes this explicit). Surface
        // the open-Telegram action right next to retry so the user has a one-tap
        // jump to the recovery surface — not just guidance copy.
        val context = LocalContext.current
        OutlinedButton(
            onClick = { dev.lyo.hortay.ui.main.openTelegramApp(context) },
            shapes = ButtonDefaults.shapes(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Symbol(name = "open_in_new", contentDescription = null, size = 18.dp)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.action_open_telegram))
        }
        Spacer(Modifier.height(12.dp))
        PrimaryActionButton(
            text = stringResource(R.string.auth_retry),
            enabled = true,
            loading = false,
            onClick = onRetry,
        )
    }
}
