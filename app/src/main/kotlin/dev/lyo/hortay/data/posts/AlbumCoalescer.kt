package dev.lyo.hortay.data.posts

import dev.lyo.hortay.data.TdSender
import dev.lyo.hortay.data.warnUnlessCancelled
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.drinkless.tdlib.TdApi

/**
 * Recovers the missing members of partially-received albums. Stateless apart from [td]:
 * the feed-side album state (debounce buffers, the Layer-2 repair queue) stays in
 * [PostsRepository], which writes the result into the feed. See ARCHITECTURE.md →
 * "Cold-start album rehydration" for the two layers this serves.
 */
internal class AlbumCoalescer(private val td: TdSender) {

    /**
     * Telegram albums (2–10 messages sharing one `mediaAlbumId`) have no completion
     * signal on the wire — TDLib maintainer levlam confirms in
     * [tdlib/td#1482](https://github.com/tdlib/td/issues/1482): *"There is no way
     * to know this. You need to use some timeout."* Any batch we receive may
     * therefore carry 1..N members of an album whose remaining siblings are
     * still pending. Three paths drop us into the partial case:
     *  - **Window edge.** `GetChatHistory(N)` returns the latest N messages; an
     *    album straddling positions N..N+k is split between this response and
     *    the next page.
     *  - **Debounce flush race.** `PostsRepository.handleNewMessage` buffers album members for
     *    `ALBUM_DEBOUNCE_MS`; on slow mobile networks (3G/Roaming) per-member
     *    arrival can exceed the silence window, flushing 2..9 members while
     *    siblings are still in flight. Same failure mode as openclaw#1811 (their
     *    500 ms wasn't enough; their fix bumped to 1000–1500 ms — we use 1000).
     *  - **UpdateNewChat lead-message ingest.** `PostsRepository.handleNewChat` routes the
     *    chat's `lastMessage` (or a buffered `UpdateChatLastMessage` payload)
     *    through this method on every chat appearing in the session; if
     *    `lastMessage` is an album member, the batch is trivially `size=1`.
     *
     * **Coalesce trigger.** Surround-fetch fires for any album group whose
     * member count is `<` [TELEGRAM_MAX_ALBUM_SIZE]. The previous `size == 1`
     * filter only rescued single fragments — a 2..9 partial fell through and
     * the merged card landed with the partial item count. Triggering up to
     * size-1 is correct because Telegram's protocol caps an album at 10 members
     * (see TDLib `sendMessageAlbum`), so a batch of exactly 10 is provably
     * complete and skips the fetch.
     *
     * **Window sizing.** `offset = -(MAX_ALBUM - 1)`, `limit = 2 * MAX_ALBUM - 1`
     * (i.e. -9, 19). TDLib semantics: returns up to 9 newer than the anchor,
     * the anchor itself, and up to 9 older — covering every possible anchor
     * position inside a 10-member album. The previous `-5, 10` reached only
     * 5 newer + 4 older around the anchor, so a 10-member album whose
     * `lastMessage` is the highest-id member (the canonical cold-start case)
     * returned only 5 of 10.
     *
     * Concurrency: each fragment fires parallel requests — [TdApi.GetMessageLocally]
     * on the cold-start (onlyLocal) path, [TdApi.GetChatHistory] on the networked
     * path — merged synchronously after [awaitAll] so the seen-set has no race.
     * Bounded by distinct album ids in the input — typically 0..2 per batch.
     */
    suspend fun coalesce(
        chatId: Long,
        messages: List<TdApi.Message>,
        // `false` allows a server round-trip (on-demand paths + Layer-2 visibility
        // repair: one chat at a time, within the 30 req / 30 s budget). `true` is the
        // cold-start path: ~200 channels stream their lastMessage at once, so it must
        // stay strictly offline. Rather than GetChatHistory(onlyLocal=true) — which
        // reads the chat's in-memory history list, unbuilt before OpenChat, and so
        // returns just the lastMessage even when siblings are cached — derive the
        // sibling ids from the channel id-stride invariant and pull them through the
        // per-message local index ([GetMessageLocally]). That index works on a cold
        // history cache (same reason snapshot restore uses GetMessage). Members not in
        // the local DB stay degraded until the Layer-2 visibility repair fetches them.
        onlyLocal: Boolean,
    ): List<TdApi.Message> {
        val fragments = messages
            .filter { it.mediaAlbumId != 0L }
            .groupBy { it.mediaAlbumId }
            .values
            .filter { it.size < TELEGRAM_MAX_ALBUM_SIZE }
            .map { it.first() }
        if (fragments.isEmpty()) return messages

        val seen = messages.mapTo(hashSetOf()) { it.id }
        val extras = coroutineScope {
            fragments.map { fragment ->
                async {
                    if (onlyLocal) fetchAlbumSiblingsLocally(chatId, fragment)
                    else fetchAlbumSiblingsNetworked(chatId, fragment)
                }
            }.awaitAll()
        }

        if (extras.all { it.isEmpty() }) return messages
        val merged = messages.toMutableList()
        for (group in extras) {
            for (m in group) if (seen.add(m.id)) merged += m
        }
        return merged
    }

    /**
     * Offline sibling recovery for the cold-start path: address each computed
     * candidate id via [TdApi.GetMessageLocally] (404 → not in the local DB). Capped
     * concurrency so a batch of albums doesn't spike TDLib's worker thread during the
     * drain. Keeps only resolved messages that share the fragment's mediaAlbumId,
     * so deletions/gaps and a back-to-back neighbouring album filter out naturally.
     */
    private suspend fun fetchAlbumSiblingsLocally(
        chatId: Long,
        fragment: TdApi.Message,
    ): List<TdApi.Message> = coroutineScope {
        val semaphore = Semaphore(LOCAL_FETCH_CONCURRENCY)
        albumCandidateIds(fragment.id).map { candidateId ->
            async {
                semaphore.withPermit {
                    runCatching { td.send(TdApi.GetMessageLocally(chatId, candidateId)) }
                        .getOrElse { e -> if (e is kotlin.coroutines.cancellation.CancellationException) throw e else null }
                        ?.takeIf { it.mediaAlbumId == fragment.mediaAlbumId }
                }
            }
        }.awaitAll().filterNotNull()
    }

    /**
     * Networked sibling recovery (on-demand opens + Layer-2 repair): the surround
     * GetChatHistory window. offset=-(MAX-1), limit=2*MAX-1 spans a full 10-member
     * album from any anchor position.
     */
    private suspend fun fetchAlbumSiblingsNetworked(
        chatId: Long,
        fragment: TdApi.Message,
    ): List<TdApi.Message> {
        val resp = runCatching {
            td.send(
                TdApi.GetChatHistory(
                    chatId,
                    /* fromMessageId */ fragment.id,
                    /* offset */ -(TELEGRAM_MAX_ALBUM_SIZE - 1),
                    /* limit */ 2 * TELEGRAM_MAX_ALBUM_SIZE - 1,
                    /* onlyLocal */ false,
                ),
            )
        }.warnUnlessCancelled(TAG, "coalesceAlbum($chatId,${fragment.id})").getOrNull()
        return resp?.messages.orEmpty().filter { it.mediaAlbumId == fragment.mediaAlbumId }
    }

    private companion object {
        const val TAG = "AlbumCoalescer"

        /** Parallel `GetMessageLocally` cap per fragment during the cold-start drain. */
        const val LOCAL_FETCH_CONCURRENCY = 8
    }
}

/**
 * Hard cap on members per Telegram album, per the protocol. Drives
 * [AlbumCoalescer.coalesce]: a batch with exactly this many members of one
 * `mediaAlbumId` is provably complete and skips the surround fetch;
 * anything smaller is potentially partial and triggers a rescue query.
 * Source: TDLib `sendMessageAlbum` spec.
 *
 * Declared at top level (not inside [PostsRepository]'s `private companion object`) so
 * unit tests in the same module can reference it without reflection.
 */
internal const val TELEGRAM_MAX_ALBUM_SIZE = 10

/**
 * TDLib client-side message id for a channel/supergroup message is the server
 * message id shifted left 20 bits (`serverId shl 20`). Album members are posted
 * atomically and carry consecutive server ids, so siblings of an album member
 * sit at exactly `anchor.id ± k * ALBUM_ID_STRIDE`. Load-bearing for the
 * cold-start local rehydration in [AlbumCoalescer.coalesce]: it
 * lets us address siblings by computed id through the per-message local index
 * ([TdApi.GetMessageLocally]) instead of [TdApi.GetChatHistory], which needs the
 * chat's in-memory history list — unbuilt before OpenChat on cold start.
 */
internal const val ALBUM_ID_STRIDE: Long = 1L shl 20

/**
 * Candidate sibling ids for an album member at [anchorId]: up to
 * `2 * (TELEGRAM_MAX_ALBUM_SIZE - 1)` ids straddling the anchor in both
 * directions (the anchor is not guaranteed to be the first or last member),
 * excluding the anchor itself and any non-positive id. Over-generated on
 * purpose — callers fetch each via [TdApi.GetMessageLocally] and keep only the
 * ones that resolve AND share the anchor's `mediaAlbumId`, so deletions, gaps,
 * and a neighbouring album posted back-to-back all filter out naturally.
 */
internal fun albumCandidateIds(anchorId: Long): List<Long> {
    val span = TELEGRAM_MAX_ALBUM_SIZE - 1
    return ((-span)..span)
        .asSequence()
        .filter { it != 0 }
        .map { anchorId + it * ALBUM_ID_STRIDE }
        .filter { it > 0L }
        .toList()
}
