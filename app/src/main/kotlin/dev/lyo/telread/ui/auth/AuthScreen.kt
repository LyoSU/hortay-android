package dev.lyo.telread.ui.auth

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Pin
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.lyo.telread.R
import dev.lyo.telread.data.AuthStage
import dev.lyo.telread.data.TdClient
import kotlinx.coroutines.launch

@Composable
fun AuthScreen(client: TdClient, stage: AuthStage) {
    val scope = rememberCoroutineScope()

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
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Spacer(Modifier.height(48.dp))

            HeroBlock(stage)

            Column(modifier = Modifier.padding(bottom = 32.dp)) {
                AnimatedContent(
                    targetState = stage,
                    transitionSpec = {
                        (slideInVertically(tween(280)) { it / 4 } + fadeIn(tween(280)))
                            .togetherWith(slideOutVertically(tween(180)) { -it / 4 } + fadeOut(tween(180)))
                    },
                    label = "auth-stage",
                ) { current ->
                    when (current) {
                        is AuthStage.Loading -> LoadingForm()
                        is AuthStage.WaitPhone -> PhoneForm { scope.launch { client.submitPhone(it) } }
                        is AuthStage.WaitCode -> CodeForm(current.phoneNumber) {
                            scope.launch { client.submitCode(it) }
                        }
                        is AuthStage.WaitPassword -> PasswordForm { scope.launch { client.submitPassword(it) } }
                        is AuthStage.Error -> ErrorBlock(current.message)
                        is AuthStage.Ready -> LoadingForm()
                    }
                }
            }
        }
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
            Icon(
                imageVector = when (stage) {
                    is AuthStage.WaitCode -> Icons.Outlined.Pin
                    is AuthStage.WaitPassword -> Icons.Outlined.Lock
                    else -> Icons.Outlined.Smartphone
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(36.dp),
            )
        }
        Spacer(Modifier.height(24.dp))
        Text(
            text = when (stage) {
                is AuthStage.WaitCode -> "Введіть код"
                is AuthStage.WaitPassword -> "Cloud-пароль"
                is AuthStage.Error -> "Помилка"
                else -> "Telread"
            },
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = when (stage) {
                is AuthStage.WaitCode -> "Ми надіслали код у ваш Telegram на номер ${stage.phoneNumber}"
                is AuthStage.WaitPassword -> "Ваш акаунт захищено двофакторною автентифікацією"
                is AuthStage.Error -> stage.message
                else -> "Стрічка Telegram-каналів у форматі Twitter"
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PhoneForm(onSubmit: (String) -> Unit) {
    var phone by remember { mutableStateOf("") }
    Column {
        OutlinedTextField(
            value = phone,
            onValueChange = { phone = it.filter { c -> c.isDigit() || c == '+' }.take(16) },
            label = { Text(androidx.compose.ui.res.stringResource(R.string.auth_phone_label)) },
            placeholder = { Text("+380…") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            singleLine = true,
            shape = RoundedCornerShape(20.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        PrimaryActionButton(
            text = androidx.compose.ui.res.stringResource(R.string.auth_send_code),
            enabled = phone.length >= 10,
            onClick = { onSubmit(phone) },
        )
    }
}

@Composable
private fun CodeForm(phone: String, onSubmit: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    Column {
        OutlinedTextField(
            value = code,
            onValueChange = { code = it.filter(Char::isDigit).take(6) },
            label = { Text(androidx.compose.ui.res.stringResource(R.string.auth_code_label)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            singleLine = true,
            shape = RoundedCornerShape(20.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        PrimaryActionButton(
            text = androidx.compose.ui.res.stringResource(R.string.auth_continue),
            enabled = code.length >= 5,
            onClick = { onSubmit(code) },
        )
    }
}

@Composable
private fun PasswordForm(onSubmit: (String) -> Unit) {
    var pwd by remember { mutableStateOf("") }
    Column {
        OutlinedTextField(
            value = pwd,
            onValueChange = { pwd = it },
            label = { Text(androidx.compose.ui.res.stringResource(R.string.auth_password_label)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            singleLine = true,
            shape = RoundedCornerShape(20.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        PrimaryActionButton(
            text = androidx.compose.ui.res.stringResource(R.string.auth_continue),
            enabled = pwd.isNotEmpty(),
            onClick = { onSubmit(pwd) },
        )
    }
}

@Composable
private fun PrimaryActionButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        contentPadding = PaddingValues(vertical = 18.dp, horizontal = 24.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun LoadingForm() {
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

@Composable
private fun ErrorBlock(message: String) {
    Text(
        text = message,
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.fillMaxWidth(),
    )
}

