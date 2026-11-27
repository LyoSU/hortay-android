package dev.lyo.hortay.ui.web

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.IconToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.lyo.hortay.AppGraph
import dev.lyo.hortay.R
import dev.lyo.hortay.data.UserMessageBus
import dev.lyo.hortay.ui.icons.Symbol
import kotlinx.coroutines.launch

/**
 * The bell in a guest channel's header: new-post notifications for this channel, off by
 * default (see [dev.lyo.hortay.data.web.WebNotifyCoordinator]). Turning it on asks for
 * POST_NOTIFICATIONS on Android 13+ when not yet granted; denied — or notifications
 * blocked for the app on any version — the bell stays off and a snackbar offers the app's
 * notification settings.
 */
@Composable
internal fun WebChannelNotifyButton(
    username: String,
    notifyOn: Boolean,
    graph: AppGraph,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val enabledText = stringResource(R.string.web_notify_enabled)
    val blockedText = stringResource(R.string.web_notify_permission_denied)
    val settingsLabel = stringResource(R.string.web_notify_open_settings)
    val turnOn = rememberUpdatedState {
        scope.launch { graph.webNotifyCoordinator.setNotify(username, on = true) }
        graph.userMessages.post(text = enabledText, severity = UserMessageBus.Severity.Info)
    }
    val explainBlocked = remember(context, blockedText, settingsLabel) {
        {
            graph.userMessages.post(
                text = blockedText,
                severity = UserMessageBus.Severity.Info,
                action = UserMessageBus.Action.Run(settingsLabel) { openAppNotificationSettings(context) },
            )
        }
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted && NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            turnOn.value()
        } else {
            explainBlocked()
        }
    }
    IconToggleButton(
        checked = notifyOn,
        onCheckedChange = { on ->
            when {
                !on -> scope.launch { graph.webNotifyCoordinator.setNotify(username, on = false) }
                needsPermissionRequest(context) -> permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                !NotificationManagerCompat.from(context).areNotificationsEnabled() -> explainBlocked()
                else -> turnOn.value()
            }
        },
    ) {
        Symbol(
            name = "notifications_active",
            filled = notifyOn,
            contentDescription = stringResource(
                if (notifyOn) R.string.web_notify_turn_off else R.string.web_notify_turn_on,
            ),
        )
    }
}

private fun needsPermissionRequest(context: Context): Boolean =
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
        PackageManager.PERMISSION_GRANTED

private fun openAppNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
        .recoverCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
}
