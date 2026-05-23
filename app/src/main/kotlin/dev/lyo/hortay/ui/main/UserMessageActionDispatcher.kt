package dev.lyo.hortay.ui.main

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import dev.lyo.hortay.AppGraph
import dev.lyo.hortay.data.UserMessageBus
import kotlinx.coroutines.launch

/**
 * CompositionLocal handle for leaf Composables that need to post a transient
 * snackbar (e.g. tap on a paid-reaction chip → "Paid reactions only from Telegram").
 * Provided by [MainScaffold] / [dev.lyo.hortay.ui.web.WebModeScaffold] at the root —
 * leaves never have to plumb [UserMessageBus] through their parameter lists.
 *
 * Default is `null` (no bus), so a composable that uses it must null-check; this
 * keeps previews and tests light without forcing a fake bus into every preview.
 */
val LocalUserMessageBus = compositionLocalOf<UserMessageBus?> { null }

/**
 * Subscribes to [AppGraph.userMessages] and surfaces each message on [hostState],
 * dispatching the optional [UserMessageBus.Action] when the user taps the action
 * button. Action targets resolve through [openTelegramApp] / [openExternalUrl]
 * (see [TelegramIntents]) and [AppGraph.guestMode] for SignIn.
 *
 * Single relay shared by [MainScaffold] (TDLib mode) and [dev.lyo.hortay.ui.web.WebModeScaffold]
 * (guest mode). Action-bearing messages get [SnackbarDuration.Long] so users have
 * time to decide; plain status pings stay Short.
 */
@Composable
fun UserMessageSnackbarRelay(
    graph: AppGraph,
    hostState: SnackbarHostState,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        graph.userMessages.messages.collect { msg ->
            val result = hostState.showSnackbar(
                message = msg.text,
                actionLabel = msg.action?.label,
                duration = if (msg.action != null) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            val action = msg.action
            if (result == SnackbarResult.ActionPerformed && action != null) {
                when (action) {
                    is UserMessageBus.Action.OpenTelegram -> openTelegramApp(context)
                    is UserMessageBus.Action.OpenUrl -> openExternalUrl(context, action.url)
                    is UserMessageBus.Action.SignIn -> scope.launch {
                        graph.guestMode.setGuest(false)
                    }
                    is UserMessageBus.Action.Run -> action.onClick()
                }
            }
        }
    }
}
