@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.lyo.hortay.ui.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lyo.hortay.AppGraph
import dev.lyo.hortay.R
import dev.lyo.hortay.data.UserMessageBus
import dev.lyo.hortay.ui.report.ReportFlowSheet
import dev.lyo.hortay.ui.text.ChatInvitePreviewDialog
import dev.lyo.hortay.ui.users.UserProfileSheet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Three modal surfaces that live at MainScaffold scope rather than inside the
 * Scaffold content lambda so they outlive the tab/channel that triggered them
 * and survive rotation:
 *
 *  - [ChatInvitePreviewDialog]: pending invite-link confirmation, stored on
 *    the graph rather than in a local rememberSaveable because TDLib's
 *    `CheckChatInviteLink` is suspending and runs on the app scope — a rotation
 *    between the user tapping the link and the preview arriving would otherwise
 *    drop the dialog on the floor.
 *
 *  - [ReportFlowSheet]: in-app reporting flow (auth mode). Rendered as a
 *    ModalBottomSheet here so it outlives the PostCard that triggered it and
 *    survives tab/channel changes while the user is mid-flow. Keyed on
 *    (chatId, messageId) so reopening the same post restores progress. Clears
 *    on Success (LaunchedEffect inside ReportFlowSheet) or on manual dismiss.
 *
 *  - [UserProfileSheet]: shared surface for every "tap a user" trigger
 *    (comment header, personal-author PostCard, forward-from-user chip, in-text
 *    mention with a userId). Personal-channel row routes back through
 *    pushChannel so drilling into a Premium user's linked channel feels the
 *    same as opening any other subscribed channel.
 */
@Composable
internal fun MainScaffoldDialogs(
    graph: AppGraph,
    scope: CoroutineScope,
    pendingUserId: Long?,
    onUserSheetDismiss: () -> Unit,
    onPushChannel: (chatId: Long, scrollTo: Long?) -> Unit,
) {
    val res = androidx.compose.ui.platform.LocalContext.current.resources
    val pendingInvitePreview by graph.linkDialogs.invitePreview.collectAsStateWithLifecycle()
    val pendingReport by graph.reportDialogs.target.collectAsStateWithLifecycle()

    pendingInvitePreview?.let { preview ->
        ChatInvitePreviewDialog(
            preview = preview,
            onConfirm = {
                graph.linkDialogs.dismissInvitePreview()
                scope.launch {
                    val joinedId = graph.channelActions.joinByInvite(preview.inviteLink)
                    if (joinedId != null) {
                        onPushChannel(joinedId, null)
                    }
                }
            },
            onDismiss = { graph.linkDialogs.dismissInvitePreview() },
        )
    }

    pendingReport?.let { target ->
        ReportFlowSheet(
            chatId = target.chatId,
            messageId = target.messageId,
            openToken = target.token,
            channelUsername = null,
            onDismiss = { success ->
                graph.reportDialogs.close()
                // Surface a confirmation snackbar via the existing UserMessageBus
                // (Severity.Info, not Error — the report succeeded). The bus is
                // already wired to the scaffold's SnackbarHost; manual dismissals
                // skip this path so the user only sees feedback when something
                // actually happened.
                if (success) {
                    graph.userMessages.post(
                        res.getString(R.string.report_success),
                        UserMessageBus.Severity.Info,
                    )
                }
            },
            reportRepository = graph.reportRepository,
            explainerStore = graph.reportExplainerStore,
        )
    }

    pendingUserId?.let { userId ->
        UserProfileSheet(
            userId = userId,
            actions = graph.channelActions,
            onDismiss = onUserSheetDismiss,
            onOpenChannel = { chatId -> onPushChannel(chatId, null) },
        )
    }
}
