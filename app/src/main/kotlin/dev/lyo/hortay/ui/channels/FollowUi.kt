package dev.lyo.hortay.ui.channels

import android.content.res.Resources
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import dev.lyo.hortay.AppGraph
import dev.lyo.hortay.R
import dev.lyo.hortay.data.ChannelActionsRepository
import dev.lyo.hortay.data.ChannelMembership
import dev.lyo.hortay.data.OnboardingStore
import dev.lyo.hortay.data.UserMessageBus
import dev.lyo.hortay.data.subscriptions.FOLLOWED_CHANNELS_MAX
import dev.lyo.hortay.data.subscriptions.FollowedChannels
import dev.lyo.hortay.data.subscriptions.FollowedChannels.FollowResult
import dev.lyo.hortay.data.subscriptions.FollowedChannelsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * What the "read without joining" UI needs from [AppGraph], bundled so a screen takes one
 * parameter. Signed-in mode only: guest screens never receive one, so none of this reaches
 * them. See ARCHITECTURE.md → "Read without joining" for the data side.
 */
@Stable
class FollowedChannelsUi(
    val followed: FollowedChannels,
    val store: FollowedChannelsStore,
    val onboarding: OnboardingStore,
    val actions: ChannelActionsRepository,
    val userMessages: UserMessageBus,
    /** The account session scope, read at launch time: an Undo outlives the screen, not a logout. */
    val sessionScope: () -> CoroutineScope,
)

fun AppGraph.followedChannelsUi(): FollowedChannelsUi = FollowedChannelsUi(
    followed = followedChannels,
    store = followedChannelsStore,
    onboarding = onboardingStore,
    actions = channelActions,
    userMessages = userMessages,
    sessionScope = { session.scope },
)

/** Where the channel screen stands with this account. Only [NotJoined] shows the join bar. */
enum class ChannelJoinState { Unknown, Member, NotJoined, Followed, Ineligible }

/**
 * [membership] is the offline answer (null while unknown); [followed] whether the channel
 * is in the followed store. A member that is still in the store is mid hand-off (the join
 * just landed) and already reads as [ChannelJoinState.Member].
 */
internal fun channelJoinState(membership: ChannelMembership?, followed: Boolean): ChannelJoinState = when {
    membership == null -> if (followed) ChannelJoinState.Followed else ChannelJoinState.Unknown
    membership.isMember -> ChannelJoinState.Member
    followed -> ChannelJoinState.Followed
    membership.isChannel && membership.isPublic -> ChannelJoinState.NotJoined
    else -> ChannelJoinState.Ineligible
}

/** What the user is told after a follow attempt. */
internal sealed interface FollowNotice {
    data object Added : FollowNotice
    /** Added with only [left] free places (1..[NEAR_LIMIT_SLOTS]). */
    data class AddedNearLimit(val left: Int) : FollowNotice
    /** Added, and that was the last free place. */
    data object AddedAtLimit : FollowNotice
    data object LimitReached : FollowNotice
    data object Failed : FollowNotice
    /** Nothing to say: already followed, or already a member (the screen just refreshes). */
    data object None : FollowNotice
}

/** Free places at or below which a successful follow mentions how many are left. */
internal const val NEAR_LIMIT_SLOTS = 10

/** [followedCount] is the store size right after the attempt. */
internal fun followNotice(result: FollowResult, followedCount: Int, max: Int = FOLLOWED_CHANNELS_MAX): FollowNotice =
    when (result) {
        FollowResult.Followed -> {
            val left = (max - followedCount).coerceAtLeast(0)
            when {
                left == 0 -> FollowNotice.AddedAtLimit
                left <= NEAR_LIMIT_SLOTS -> FollowNotice.AddedNearLimit(left)
                else -> FollowNotice.Added
            }
        }
        FollowResult.LimitReached -> FollowNotice.LimitReached
        FollowResult.NotPublicChannel, FollowResult.Unavailable -> FollowNotice.Failed
        FollowResult.AlreadyFollowed, FollowResult.AlreadyMember -> FollowNotice.None
    }

/**
 * Follow / stop-reading actions shared by the channel screen, its info sheet, the Channels
 * tab and the feed's hide-channel sheet. The calls run in the account session scope, so a
 * snackbar's Undo still works after the screen that posted it is gone; messages go through
 * [UserMessageBus] like every other transient notice.
 */
@Stable
class FollowController internal constructor(
    private val ui: FollowedChannelsUi,
    private val res: Resources,
) {
    /** The one-time "posts may arrive a few minutes late" explanation is due. */
    var tipVisible by mutableStateOf(false)
        private set

    private val inFlight = mutableStateMapOf<Long, Unit>()

    fun isBusy(chatId: Long): Boolean = chatId in inFlight

    /** Follow [chatId]; [onDone] gets the raw result (e.g. to re-check membership). */
    fun follow(chatId: Long, onDone: (FollowResult) -> Unit = {}) {
        if (chatId in inFlight) return
        inFlight[chatId] = Unit
        ui.sessionScope().launch(Dispatchers.Main.immediate) {
            try {
                val result = ui.followed.follow(chatId)
                announce(followNotice(result, ui.store.ids.value.size))
                if (result == FollowResult.Followed && !ui.onboarding.followDelayTipSeen.first()) tipVisible = true
                onDone(result)
            } finally {
                inFlight.remove(chatId)
            }
        }
    }

    /** Stop reading [chatId] ([title] names it in the snackbar), with Undo. */
    fun stopReading(chatId: Long, title: String) {
        val scope = ui.sessionScope()
        scope.launch(Dispatchers.Main.immediate) {
            val removed = ui.followed.unfollow(chatId) ?: return@launch
            ui.userMessages.post(
                text = res.getString(R.string.follow_stopped, title),
                severity = UserMessageBus.Severity.Info,
                action = UserMessageBus.Action.Run(res.getString(R.string.action_undo)) {
                    scope.launch(Dispatchers.Main.immediate) {
                        val result = ui.followed.follow(chatId, restoring = removed)
                        val notice = followNotice(result, ui.store.ids.value.size)
                        // An undo that worked needs no second "Added" message.
                        if (result != FollowResult.Followed) announce(notice)
                    }
                },
            )
        }
    }

    fun dismissTip() {
        tipVisible = false
        ui.sessionScope().launch { ui.onboarding.setFollowDelayTipSeen() }
    }

    private fun announce(notice: FollowNotice) {
        val text = when (notice) {
            FollowNotice.Added -> res.getString(R.string.follow_added)
            is FollowNotice.AddedNearLimit ->
                res.getQuantityString(R.plurals.follow_added_slots_left, notice.left, notice.left)
            FollowNotice.AddedAtLimit -> res.getString(R.string.follow_added_at_limit, FOLLOWED_CHANNELS_MAX)
            FollowNotice.LimitReached -> res.getString(R.string.follow_limit_reached, FOLLOWED_CHANNELS_MAX)
            FollowNotice.Failed -> res.getString(R.string.follow_failed)
            FollowNotice.None -> return
        }
        val severity = when (notice) {
            FollowNotice.LimitReached, FollowNotice.Failed -> UserMessageBus.Severity.Error
            else -> UserMessageBus.Severity.Info
        }
        ui.userMessages.post(text, severity)
    }
}

/** A [FollowController] for this composition, or null when [ui] is (guest mode). */
@Composable
fun rememberFollowController(ui: FollowedChannelsUi?): FollowController? {
    val res = LocalContext.current.resources
    return remember(ui, res) { ui?.let { FollowController(it, res) } }
}

/** The one-time explanation after the first follow; [onDismiss] records it as seen. */
@Composable
fun FollowDelayTipDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.follow_tip_title)) },
        text = { Text(stringResource(R.string.follow_tip_body)) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.onboarding_got_it)) }
        },
    )
}
