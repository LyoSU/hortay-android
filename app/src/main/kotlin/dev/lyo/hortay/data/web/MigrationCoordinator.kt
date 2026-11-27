package dev.lyo.hortay.data.web

import android.util.Log
import dev.lyo.hortay.data.AuthStage
import dev.lyo.hortay.data.subscriptions.ThrottledChannelJoiner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Surfaces a one-time PROPOSAL to migrate the user's anonymous-mode subscriptions
 * into TDLib chats after they finally sign in. Migration is opt-in per channel —
 * never automatic — because:
 *   • The user's guest-mode set might include channels they were curious about
 *     but don't actually want auto-joined to their authenticated account.
 *   • Joining is observable to the channel admin (member count, audit log) and
 *     to Telegram's flood-control system; auto-joining 50 channels in the second
 *     after sign-in is a reliable way to earn a global FLOOD_WAIT.
 *
 * Lifecycle:
 *   1. [bind] subscribes to [authStage] + [migrationStore.proposalShown].
 *   2. When auth flips to Ready AND the proposal hasn't been shown AND the
 *      anonymous set is non-empty, [pendingProposal] emits the candidate list.
 *   3. UI renders [dev.lyo.hortay.ui.web.MigrationProposalSheet]; user picks
 *      a subset (or none).
 *   4. UI calls [confirm] with the chosen usernames; we throttle-iterate
 *      `SearchPublicChat` + `JoinChat` (1 per second to stay under TDLib's
 *      per-method gate), update [progress], and finally mark the proposal
 *      shown so it never reappears.
 *   5. Skipping (`dismiss`) also marks shown — the user's "no thanks" sticks.
 *
 * Why bind to authStage rather than emitting from MainActivity: the migration
 * needs to survive a user navigating to Settings → log out → log in again
 * within the same process. Hooking authStage Ready transitions in this
 * process-singleton coordinator is the only place that's guaranteed to see
 * every transition.
 */
class MigrationCoordinator(
    private val migrationStore: MigrationStore,
    private val subscriptions: SubscriptionsStore,
    private val joiner: ThrottledChannelJoiner,
    private val authStage: StateFlow<AuthStage>,
    private val scope: CoroutineScope,
) {
    private val _pendingProposal = MutableStateFlow<List<String>?>(null)
    /**
     * Non-null when a proposal is awaiting user response. List ordering matches
     * [SubscriptionsStore]'s insertion order (most-recently-added first).
     */
    val pendingProposal: StateFlow<List<String>?> = _pendingProposal.asStateFlow()

    private val _progress = MutableStateFlow<Progress?>(null)
    val progress: StateFlow<Progress?> = _progress.asStateFlow()

    private val mutex = Mutex()
    @Volatile private var bound = false

    fun bind(): Job? {
        if (bound) return null
        bound = true
        // Re-evaluate every time auth.Ready flips on. We don't dedupe on flip
        // because the user might have logged out / back in, generating two
        // consecutive Ready transitions (rare but possible).
        // StateFlow is conflated/distinct-by-construction; no distinctUntilChanged needed.
        return authStage
            .filter { it == AuthStage.Ready }
            .onEach { evaluate() }
            .launchIn(scope)
    }

    private suspend fun evaluate() {
        if (migrationStore.isProposalShown()) return
        val current = subscriptions.subscriptions.first()
        val alreadyMigrated = migrationStore.migratedUsernames.first()
        val candidates = current
            .filter { it.isNotBlank() && it !in alreadyMigrated }
            .map { it.lowercase() }
            .distinct()
        // Empty candidates → just don't show. We deliberately do NOT call
        // markProposalShown() here: the user may sign in (auth → Ready) before
        // they've added any guest-mode subscriptions and then add some later.
        // If we'd marked shown now, that future "subscribe-then-relog" path
        // would never surface the proposal. The DataStore lookup is cheap, so
        // re-evaluating on each Ready transition while the set is still empty
        // is the safer default.
        //
        // Clear any stale proposal BEFORE the empty-candidates return: a logout →
        // sign-in-to-a-different-account sequence re-runs evaluate() against the
        // new account's (empty) candidate set, and without this reset the sheet
        // would keep showing account A's handles. See [clearProposal] for the
        // logout hook that also covers the "never signed back in" case.
        if (candidates.isEmpty()) {
            _pendingProposal.value = null
            return
        }
        _pendingProposal.value = candidates
    }

    /**
     * User accepted a subset of channels for migration. We throttle the TDLib
     * calls to one chat per second (each chat = SearchPublicChat + JoinChat,
     * effectively two RPCs; see [ThrottledChannelJoiner]) so a 30-channel
     * migration spreads over half a minute rather than triggering a global
     * FLOOD_WAIT in the first second post-login.
     *
     * Returns when all channels have been processed. The caller is expected to
     * keep the sheet visible during the run (showing [progress]) and dismiss
     * it when this method returns.
     */
    suspend fun confirm(usernames: List<String>): Unit = mutex.withLock {
        if (usernames.isEmpty()) {
            migrationStore.markProposalShown()
            _pendingProposal.value = null
            return@withLock
        }
        _progress.value = Progress(total = usernames.size, processed = 0, lastUsername = null)
        // Failures are tracked separately. Successful joins go through `addMigrated` so
        // they're never re-offered; a failure (FLOOD_WAIT, transient network, a
        // SearchPublicChat that could not be answered — PublicChatLookup.Failed) is
        // intentionally NOT recorded — markProposalShown() at the bottom would otherwise
        // strand them forever, with no UI path to retry. By gating markProposalShown on
        // "every requested handle resolved AND joined", a partial-failure run leaves the
        // proposal pending; the next auth.Ready (foreground re-entry, sign out + sign in)
        // re-evaluates and re-offers just the failed subset (alreadyMigrated filter
        // excludes successes). A handle TDLib says is not taken (NotFound) is permanent
        // and not re-offered.
        //
        // The joiner checks cancellation between channels: confirm() runs inside
        // `mutex.withLock`, and `dismiss()` takes the same lock, so a parent-scope cancel
        // (sheet swipe-down, sign-out mid-run) must bubble through without waiting for the
        // remaining channels to drain — critical for the ~30 s worst case (30 channels ×
        // THROTTLE_MS). stopOnFloodWait = false: the migration rides out a FLOOD_WAIT on
        // TdClient's gate and carries on, as it always has.
        val result = joiner.joinAll(
            usernames = usernames,
            stopOnFloodWait = false,
            onProgress = { processed, current ->
                _progress.value = Progress(total = usernames.size, processed = processed, lastUsername = current)
            },
            // Book-keep each success the moment it lands, not after the loop: a run
            // cancelled mid-way (sheet swipe-down, sign-out) used to drop the whole
            // `migrated` list — already-joined channels were re-offered and stayed
            // duplicated as guest-mode subscriptions.
            onJoined = { recordMigrated(it) },
        )
        val migrated = result.joined
        val anyFailure = result.failed.isNotEmpty()
        // Successful joins were already recorded per channel by [recordMigrated].
        //
        // No manual refresh after JoinChat: TDLib emits UpdateNewChat with
        // chat.lastMessage populated, followed by UpdateChatAddedToList
        // (ChatListMain), and [PostsRepository]'s live listeners ingest both
        // — the freshly-joined channels surface in `_posts` without any
        // extra RPC. The previous explicit refresh() existed for the legacy
        // cold-start path (`GetChat × N` / `GetChatHistory × N`); the
        // current `drainChatList(ChatListMain)` + `Chat.lastMessage`
        // harvest in `refreshLocked` makes that redundant, and re-running
        // it right after a 50-channel batch JoinChat would amplify
        // FLOOD_WAIT risk during the post-auth storm window for no gain.
        // Only suppress future proposals when EVERY requested handle either
        // succeeded (in `migrated`) or was conclusively a no-op
        // (PublicChatLookup.NotFound — wrong handle / deleted channel). Any transient failure leaves
        // the proposal pending so the user gets another bite at the apple.
        if (!anyFailure) {
            migrationStore.markProposalShown()
        } else {
            Log.i(TAG, "migration: ${migrated.size}/${usernames.size} succeeded, retryable failures left — proposal stays pending")
        }
        _progress.value = Progress(total = usernames.size, processed = usernames.size, lastUsername = null)
        _pendingProposal.value = null
        // After an explicit completion screen, we hold [progress] for one more
        // moment so the UI can flash a "done" state before dismissing — caller
        // typically reads it once-after-confirm and discards.
        scope.launch {
            delay(POST_COMPLETE_HOLD_MS)
            _progress.value = null
        }
    }

    /**
     * Persist one successful join: never re-offer it ([MigrationStore.addMigrated]) and
     * drop it from the web-mode subscription list. Removing makes the migration a true
     * move (anon → authenticated) rather than a copy — otherwise a later sign-out would
     * show the channel as a guest-mode sub although the account now follows it.
     * Channels the user skipped in the sheet stay in webSubscriptions (intentional
     * guest-mode reads).
     *
     * [NonCancellable]: the join already happened server-side, so the bookkeeping must
     * complete even when the run is being cancelled right now.
     */
    private suspend fun recordMigrated(username: String) = withContext(NonCancellable) {
        runCatching { migrationStore.addMigrated(listOf(username)) }
            .onFailure { Log.w(TAG, "migration: addMigrated($username) failed: ${it.message}") }
        runCatching { subscriptions.remove(username) }
            .onFailure { Log.w(TAG, "migration: subscriptions.remove($username) failed: ${it.message}") }
    }

    /**
     * User skipped the proposal. Persist that decision so it doesn't reappear.
     * Cooperative with [confirm]: if a confirm-run is in flight (sheet still
     * showing the throttle progress), this call waits for it to finish. The
     * sheet's `inFlight` flag should expose a Cancel affordance that calls
     * [scope].coroutineContext.cancelChildren() on the confirm job rather
     * than this method — dismissing post-completion is the only canonical use
     * of `dismiss()` in the working state machine.
     */
    suspend fun dismiss(): Unit = mutex.withLock {
        migrationStore.markProposalShown()
        _pendingProposal.value = null
        _progress.value = null
    }

    /**
     * Logout hook: drop any account-scoped proposal / progress so the migration
     * sheet can't render account A's guest-mode handles after a sign-out (or on a
     * sign-in to a different account). The persisted proposal-shown flag is reset
     * separately via [MigrationStore.reset] in [dev.lyo.hortay.AppGraph]'s logout
     * fan-out; this only touches the in-memory surfaces MainActivity observes.
     *
     * Non-suspending and lock-free: it races against an in-flight [confirm], but
     * both just write [MutableStateFlow]s — the confirm-run is separately cancelled
     * by the parent-scope teardown on logout, and a stale null here is corrected on
     * the next auth.Ready [evaluate].
     */
    fun clearProposal() {
        _pendingProposal.value = null
        _progress.value = null
    }

    data class Progress(
        val total: Int,
        val processed: Int,
        val lastUsername: String?,
    )

    private companion object {
        const val TAG = "MigrationCoordinator"
        const val POST_COMPLETE_HOLD_MS = 1_500L
    }
}
