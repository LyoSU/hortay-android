package dev.lyo.hortay.data.discover

/**
 * One candidate of "Recommended by your channels": a channel [chatId] that [voters] of the
 * user's own subscriptions list as similar. [voters] keep the sources' chat-list order, so
 * `voters.first()` is the most relevant channel vouching for it.
 */
data class RankedCandidate(
    val chatId: Long,
    val voters: List<Long>,
) {
    val votes: Int get() = voters.size
}

/** Voting sources from which a candidate needs [MIN_VOTES_MANY_SOURCES] votes. */
const val MANY_SOURCES_THRESHOLD = 10

/** Votes a candidate needs once the user has [MANY_SOURCES_THRESHOLD]+ voting sources. */
const val MIN_VOTES_MANY_SOURCES = 2

/** Most candidates the section shows. */
const val RECOMMENDED_LIMIT = 30

/**
 * Pure ranking for "Recommended by your channels" — no Android or TDLib types.
 *
 *  - [similarBySource]: source channel → the ids Telegram lists as similar to it. Iteration
 *    order is the sources' chat-list order and becomes each candidate's voter order.
 *  - A source in [hidden] (hidden from the feed) does NOT vote: the user muted it on
 *    purpose, so its taste should not drive suggestions. Archived channels are ordinary
 *    sources and do vote.
 *  - A candidate in [joined] is dropped (the server already excludes most, not all), as is
 *    one in [excluded] (no usable card, or the card says the user is a member) and a
 *    candidate that is itself a source.
 *  - [votingSources] is how many voting (subscribed, not hidden) sources the user has in
 *    all — NOT how many are in [similarBySource]. With [MANY_SOURCES_THRESHOLD] or more a
 *    single vote is noise (one channel's list), so a candidate needs
 *    [MIN_VOTES_MANY_SOURCES]; below that, 1 is enough. A scan fills [similarBySource] one
 *    source at a time; deriving the bar from its size made a 50-channel user see single-vote
 *    rows for the first nine sources, then watch them all vanish at the tenth.
 *  - Order: votes desc, then [subscribers] desc where known (unknown sorts after known),
 *    then chat id asc for a stable order. Capped at [limit].
 */
fun rankRecommendations(
    similarBySource: Map<Long, List<Long>>,
    joined: Set<Long>,
    hidden: Set<Long>,
    votingSources: Int,
    subscribers: Map<Long, Int> = emptyMap(),
    excluded: Set<Long> = emptySet(),
    limit: Int = RECOMMENDED_LIMIT,
): List<RankedCandidate> {
    val voting = similarBySource.filterKeys { it !in hidden }
    val minVotes = minVotesFor(votingSources)
    val voters = LinkedHashMap<Long, MutableList<Long>>()
    for ((source, similar) in voting) {
        for (id in similar.distinct()) {
            if (id == source || id in joined || id in excluded || id in similarBySource) continue
            voters.getOrPut(id) { ArrayList() } += source
        }
    }
    return voters.asSequence()
        .filter { it.value.size >= minVotes }
        .map { RankedCandidate(it.key, it.value) }
        .sortedWith(
            compareByDescending<RankedCandidate> { it.votes }
                .thenByDescending { subscribers[it.chatId] ?: -1 }
                .thenBy { it.chatId },
        )
        .take(limit)
        .toList()
}

/** Votes a candidate needs when the user has [votingSources] voting sources in all. */
private fun minVotesFor(votingSources: Int): Int =
    if (votingSources >= MANY_SOURCES_THRESHOLD) MIN_VOTES_MANY_SOURCES else 1
