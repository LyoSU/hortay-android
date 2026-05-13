package dev.lyo.hortay.data

/**
 * Pure transformation describing the local effect of `AddMessageReaction` /
 * `RemoveMessageReaction` on a [Reactions] aggregate. Lives at the data layer
 * (no Compose, no TDLib types) so it can be unit-tested without instrumentation
 * and reused from any repository that holds reaction state.
 *
 * The output is what the server is *expected* to confirm — the real
 * `UpdateMessageInteractionInfo` overwrites optimistic state when it arrives,
 * so this policy doesn't need to be perfect under concurrent edits; it just
 * needs to match the obvious arithmetic. Mirrors the same rules
 * Telegram-Android uses for instant chip feedback.
 */
internal object ReactionTogglePolicy {

    /**
     * Apply the user's tap on [kind]. [nowChosen] is the desired state AFTER
     * the tap (`true` = the user is adding this reaction; `false` = removing
     * their previous one). Returns a fresh [Reactions] with `totalCount` and
     * the matching bucket adjusted by exactly one.
     *
     * Bucket bookkeeping:
     *  - Adding to a kind with no existing bucket appends a new
     *    [ReactionItem] with `count = 1, isChosen = true`.
     *  - Adding to an existing bucket increments its count and flips
     *    `isChosen` on (the chip becomes "yours").
     *  - Removing from an existing bucket decrements its count and flips
     *    `isChosen` off. If the count reaches zero the bucket is dropped so
     *    the chip disappears, matching what the server emits.
     *  - Removing from a kind the user wasn't part of is a no-op (defensive;
     *    a well-behaved caller never gets here).
     */
    fun apply(current: Reactions, kind: ReactionKind, nowChosen: Boolean): Reactions {
        val key = kind.stableKey
        val idx = current.items.indexOfFirst { it.kind.stableKey == key }
        return if (nowChosen) {
            val items = current.items.toMutableList()
            if (idx >= 0) {
                val item = items[idx]
                items[idx] = item.copy(count = item.count + 1, isChosen = true)
            } else {
                items.add(ReactionItem(kind, count = 1, isChosen = true))
            }
            Reactions(totalCount = current.totalCount + 1, items = items.toList())
        } else {
            if (idx < 0) return current
            val item = current.items[idx]
            val newCount = (item.count - 1).coerceAtLeast(0)
            val items = current.items.toMutableList()
            if (newCount == 0) items.removeAt(idx)
            else items[idx] = item.copy(count = newCount, isChosen = false)
            Reactions(
                totalCount = (current.totalCount - 1).coerceAtLeast(0),
                items = items.toList(),
            )
        }
    }
}
