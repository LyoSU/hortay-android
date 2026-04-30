package dev.lyo.hortay.ui.auth

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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

            AnimatedContent(
                targetState = stage,
                transitionSpec = {
                    (slideInVertically(tween(280)) { it / 6 } + fadeIn(tween(280)))
                        .togetherWith(slideOutVertically(tween(180)) { -it / 6 } + fadeOut(tween(180)))
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
                        hint = current.hint,
                        errorMessage = authError,
                    )
                    is AuthStage.Error -> RecoverableErrorBlock(
                        message = current.message,
                        onRetry = { graph.appScope.launch { client.cancelAuth() } },
                    )
                    is AuthStage.Ready -> LoadingForm()
                }
            }

            Spacer(Modifier.height(40.dp))
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
                .clip(RoundedCornerShape(24.dp))
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
                is AuthStage.WaitCode -> "Введіть код"
                is AuthStage.WaitPassword -> "Cloud-пароль"
                is AuthStage.Error -> "Потрібна додаткова дія"
                else -> "Hortay"
            },
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = when (stage) {
                is AuthStage.WaitCode -> "Ми надіслали код ${stage.channelLabel}."
                is AuthStage.WaitPassword -> if (stage.hint.isNotEmpty()) {
                    "Ваш акаунт захищено двофакторною автентифікацією."
                } else {
                    "Ваш акаунт захищено двофакторною автентифікацією. Введіть Cloud-пароль."
                }
                is AuthStage.Error -> stringResource(R.string.auth_open_telegram_hint)
                else -> "Стрічка Telegram-каналів у форматі Twitter."
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
            .clip(RoundedCornerShape(20.dp))
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
                text = "Країна",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = country?.name ?: "Завантаження…",
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
            shape = RoundedCornerShape(20.dp),
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
            placeholder = { Text("Номер телефону") },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Phone,
                imeAction = ImeAction.Done,
            ),
            singleLine = true,
            isError = isError,
            shape = RoundedCornerShape(20.dp),
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

    var code by remember { mutableStateOf("") }
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
        // Numeric channels (TelegramMessage, Sms, Call, MissedCall, Fragment, Firebase)
        // get the segmented OTP grid sized to TDLib's reported length. The exotic SmsWord
        // / SmsPhrase channels need a free-form text field instead — those expect a word
        // or phrase, not digits.
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
                shape = RoundedCornerShape(20.dp),
                placeholder = { Text("Слово або фраза з SMS") },
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
                Text(
                    text = resendLabel(
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
 * "Надіслати ще раз" / "Надсилаємо…" / "0:42" / "Надіслати через SMS" depending on cool-
 * down state. Pulling this out keeps CodeForm's layout block readable.
 */
private fun resendLabel(
    secondsLeft: Int,
    resending: Boolean,
    nextChannelLabel: String?,
): String = when {
    resending -> "Надсилаємо…"
    secondsLeft > 0 -> "Ще раз за %d:%02d".format(secondsLeft / 60, secondsLeft % 60)
    nextChannelLabel != null -> "Надіслати $nextChannelLabel"
    else -> "Надіслати ще раз"
}

// ---------- Password ----------

@Composable
private fun PasswordForm(graph: AppGraph, hint: String, errorMessage: String?) {
    val client = graph.tdClient
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var submitting by remember { mutableStateOf(false) }

    LaunchedEffect(password) {
        if (errorMessage != null) client.clearAuthError()
    }

    Column(modifier = Modifier.fillMaxWidth()) {
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
            shape = RoundedCornerShape(20.dp),
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
        if (hint.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Symbol(
                    name = "info",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    size = 18.dp,
                )
                Text(
                    text = stringResource(R.string.auth_password_hint, hint),
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
        Spacer(Modifier.height(20.dp))
        PrimaryActionButton(
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

// ---------- Shared ----------

@Composable
private fun PrimaryActionButton(
    text: String,
    enabled: Boolean,
    loading: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
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
            CircularProgressIndicator(
                color = LocalContentColor.current,
                strokeWidth = 2.5.dp,
                modifier = Modifier.size(20.dp),
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
        CircularProgressIndicator()
    }
}

@Composable
private fun AnimatedFieldError(text: String?) {
    AnimatedVisibility(
        visible = text != null,
        enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { -it / 2 },
        exit = fadeOut(tween(120)),
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
        PrimaryActionButton(
            text = stringResource(R.string.auth_retry),
            enabled = true,
            loading = false,
            onClick = onRetry,
        )
    }
}
