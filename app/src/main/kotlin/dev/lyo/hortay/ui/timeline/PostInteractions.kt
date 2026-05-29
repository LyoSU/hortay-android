package dev.lyo.hortay.ui.timeline

import androidx.compose.runtime.Immutable
import dev.lyo.hortay.data.FormattedText
import dev.lyo.hortay.data.ReactionItem
import dev.lyo.hortay.data.ReactionKind
import dev.lyo.hortay.data.TimelinePost

/**
 * Bundle of post-level callbacks. Held immutable so Compose can skip recomposition when the
 * parent re-renders with the same handlers.
 */
@Immutable
class PostInteractions(
    val onPostClick: (post: TimelinePost) -> Unit = {},
    /** Open the post's full view (comments overlay) anchored so the post lands at [topOffsetPx]
     *  from the feed viewport top — used by the reverseLayout "Показати більше" hero-open. */
    val onShowFull: (post: TimelinePost, topOffsetPx: Int) -> Unit = { _, _ -> },
    val onMediaClick: (post: TimelinePost, index: Int) -> Unit = { _, _ -> },
    val onChannelClick: (post: TimelinePost) -> Unit = {},
    /**
     * User tapped a non-anonymous author header that resolves to ANOTHER chat —
     * TDLib's [TdApi.MessageSenderChat] case where `chatId != hostChannel.id` (admin
     * posting "as one of my other channels"). The default is a no-op so the screens
     * that never see this kind of post keep their current behaviour; production
     * wiring lives in MainScaffold and goes through [safelyOpenChannel] for the
     * same kind-gate the deep-link dispatcher uses (group / user / private →
     * snackbar instead of empty ChannelScreen).
     */
    val onAuthorChatClick: (chatId: Long) -> Unit = {},
    /** User tapped the "Переслано від …" chip — open the source channel if resolvable. */
    val onForwardSourceClick: (post: TimelinePost) -> Unit = {},
    /**
     * User tapped the inline quote card (Twitter-style preview of the post being replied to).
     * Implementations switch the channel filter when the parent is in the loaded feed, or
     * fall back to a `tg://openmessage` deep link when it isn't.
     */
    val onQuotedSourceClick: (post: TimelinePost) -> Unit = {},
    val onBookmarkClick: (post: TimelinePost) -> Unit = {},
    val onShareClick: (post: TimelinePost) -> Unit = {},
    val onCopyClick: (post: TimelinePost) -> Unit = {},
    val onOpenClick: (post: TimelinePost) -> Unit = {},
    val isBookmarked: (post: TimelinePost) -> Boolean = { false },
    val onTranslateClick: (post: TimelinePost) -> Unit = {},
    val onClearTranslationClick: (post: TimelinePost) -> Unit = {},
    val isTranslated: (post: TimelinePost) -> Boolean = { false },
    /** Translated text for this post (or any of its album members), null when not translated. */
    val translationFor: (post: TimelinePost) -> FormattedText? = { null },
    /**
     * Whether the "Translate" affordance should be surfaced at all. False in guest
     * mode (no TDLib session = no `TranslateMessageText` RPC), so the button is
     * suppressed entirely instead of being shown and silently no-op'ing on tap.
     * A free third-party translator (Lingva / LibreTranslate / Google Translate)
     * was rejected here on privacy grounds — guest mode's whole point is "Telegram
     * sees only your IP and a desktop UA"; routing post text through Google would
     * regress that promise without an opt-in.
     */
    val translateEnabled: Boolean = false,
    /** Toggle the user's reaction (emoji or custom-emoji) on the given post. */
    val onReactionToggle: (post: TimelinePost, item: ReactionItem) -> Unit = { _, _ -> },
    /**
     * Fetch the reactions available to cast on [post] — the picker strip in the
     * long-press sheet uses this to offer reactions the user hasn't applied yet.
     * On-demand per-message read (one call per sheet open, FLOOD_WAIT-safe). Default
     * returns empty so guest mode / previews / tests render no picker.
     */
    val availableReactions: suspend (post: TimelinePost) -> List<ReactionKind> = { emptyList() },
    /**
     * Cast a poll vote against [post]. [chosenIndices] is the post-tap selection set keyed
     * off [dev.lyo.hortay.data.PollOption.index]: empty = retract (regular polls only),
     * single = single-choice / quiz commit, multi = multi-answer regular commit. Default
     * no-op so non-TDLib surfaces (guest mode, previews, tests) get a passive results-only
     * render — see [pollVotingEnabled] for the discriminator.
     */
    val onPollVote: (post: TimelinePost, chosenIndices: IntArray) -> Unit = { _, _ -> },
    /**
     * Whether [PollBlock] should wire its option taps to [onPollVote]. False in guest mode
     * (no TDLib session, no `SetPollAnswer` RPC), so option rows render non-interactively
     * and only show settled results. True in auth-mode TDLib feed.
     */
    val pollVotingEnabled: Boolean = false,
    /**
     * User tapped "Report" in the post action sheet. In auth mode, [MainScaffold]
     * wires this to open [dev.lyo.hortay.ui.report.ReportFlowSheet] for the resolved
     * (chatId, messageId). In guest mode, [WebModeScaffold] wires it to
     * [dev.lyo.hortay.ui.report.GuestReportDelegator]. Default is a no-op so callers
     * that never wire reporting (e.g. CommentsScreen in guest mode) don't crash.
     */
    val onReportClick: (post: TimelinePost) -> Unit = {},
    /**
     * Whether the Report affordance should be shown for this post. Returns
     * [TimelinePost.canReportChat] in auth mode (populated from TDLib's
     * [TdApi.MessageProperties]); returns true for all posts in guest mode
     * (delegation chain is always available). Default is false so the sheet
     * action is hidden in contexts that don't wire it.
     */
    val canReport: (post: TimelinePost) -> Boolean = { false },
) {
    companion object {
        val Noop = PostInteractions()
    }
}
