package dev.lyo.hortay.data

import kotlinx.collections.immutable.toPersistentList

/**
 * Pure optimistic-vote transforms for [PostContent.Poll], applied inside
 * `PostsRepository._posts.update {}` (so they must stay pure functions of the snapshot).
 *
 * [PollOption.isBeingChosen] marks every option whose `isChosen` the optimistic step
 * FLIPPED — not just the newly selected ones. That makes the revert self-contained:
 * flipping each pending option back restores the exact pre-tap selection, including a
 * retract (selection `[]`, previously chosen rows become pending-unchosen) and a vote
 * change (A → B: A pending-unchosen, B pending-chosen). An earlier version marked only
 * the selected rows, so a failed retract had nothing to revert and a failed vote change
 * left the old choice cleared.
 */
internal object PollVotePolicy {

    fun applyOptimistic(poll: PostContent.Poll, selection: Set<Int>): PostContent.Poll {
        val newOptions = poll.options.map { opt ->
            val nowChosen = opt.index in selection
            opt.copy(
                isChosen = nowChosen,
                isBeingChosen = nowChosen != opt.isChosen,
            )
        }
        return poll.copy(
            options = newOptions.toPersistentList(),
            hasVoted = newOptions.any { it.isChosen },
        )
    }

    /** Returns `null` when nothing is pending (the caller keeps the post as-is). */
    fun clearPending(poll: PostContent.Poll, revert: Boolean): PostContent.Poll? {
        if (poll.options.none { it.isBeingChosen }) return null
        val newOptions = poll.options.map { opt ->
            if (!opt.isBeingChosen) {
                opt
            } else {
                opt.copy(
                    isBeingChosen = false,
                    isChosen = if (revert) !opt.isChosen else opt.isChosen,
                )
            }
        }
        return poll.copy(
            options = newOptions.toPersistentList(),
            hasVoted = newOptions.any { it.isChosen },
        )
    }
}
