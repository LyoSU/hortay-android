package dev.lyo.hortay.data.web

import android.util.Log
import dev.lyo.hortay.data.AuthStage
import dev.lyo.hortay.data.ChannelActionsRepository
import dev.lyo.hortay.data.PostsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
    private val postsRepository: PostsRepository,
    private val channelActions: ChannelActionsRepository,
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
        if (candidates.isEmpty()) return
        _pendingProposal.value = candidates
    }

    /**
     * User accepted a subset of channels for migration. We throttle the TDLib
     * calls to one chat per second (each chat = SearchPublicChat + JoinChat,
     * effectively two RPCs) so a 30-channel migration spreads over half a
     * minute rather than triggering a global FLOOD_WAIT in the first second
     * post-login.
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
        val migrated = mutableListOf<String>()
        // Track failures separately. Successful joins go through `addMigrated` so
        // they're never re-offered; a failure (FLOOD_WAIT, transient network,
        // SearchPublicChat returning null on a temporarily-unreachable handle) is
        // intentionally NOT recorded — markProposalShown() at the bottom would
        // otherwise strand them forever, with no UI path to retry. By gating
        // markProposalShown on "every requested handle resolved AND joined",
        // a partial-failure run leaves the proposal pending; the next auth.Ready
        // (foreground re-entry, sign out + sign in) re-evaluates and re-offers
        // just the failed subset (alreadyMigrated filter excludes successes).
        val anyFailure = java.util.concurrent.atomic.AtomicBoolean(false)
        for ((i, username) in usernames.withIndex()) {
            // Cooperative cancellation: confirm() runs inside `mutex.withLock`,
            // and `dismiss()` takes the same lock. Without an explicit
            // ensureActive between iterations, a long throttle delay or a stuck
            // RPC could keep the user pinned to the sheet. Now a parent-scope
            // cancel (sheet swipe-down, sign-out mid-run) bubbles through here
            // without waiting for the remaining channels to drain — critical
            // for the ~30 s worst-case (30 channels × THROTTLE_MS).
            currentCoroutineContext().ensureActive()
            val cleaned = username.removePrefix("@").trim().lowercase()
            if (cleaned.isEmpty()) continue
            _progress.value = Progress(total = usernames.size, processed = i, lastUsername = cleaned)
            val chatId = try {
                postsRepository.resolvePublicChat(cleaned)
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                Log.w(TAG, "migration: SearchPublicChat($cleaned) failed: ${t.message}")
                anyFailure.set(true)
                null
            }
            if (chatId == null) {
                if (!anyFailure.get()) {
                    // null without exception = TDLib reports no such public chat.
                    // That's a permanent classification (handle was wrong, channel
                    // was deleted) — don't re-offer.
                    Log.w(TAG, "migration: SearchPublicChat($cleaned) returned null (skip, won't retry)")
                }
            } else {
                try {
                    channelActions.joinChat(chatId)
                    migrated += cleaned
                } catch (cancellation: kotlinx.coroutines.CancellationException) {
                    throw cancellation
                } catch (t: Throwable) {
                    Log.w(TAG, "migration: JoinChat($cleaned) failed: ${t.message}")
                    anyFailure.set(true)
                }
            }
            if (i < usernames.lastIndex) delay(THROTTLE_MS)
        }
        if (migrated.isNotEmpty()) {
            migrationStore.addMigrated(migrated)
            // JoinChat tells TDLib we're a member; UpdateNewChat eventually surfaces the
            // chat in the main list, but GetChatHistory for the freshly-joined channels
            // never runs unless we ask for it. Without this refresh, [PostsRepository]'s
            // _posts shows whatever was loaded *before* the migration — typically a
            // sparse / empty feed — until the user cold-starts the app and the
            // ViewModel's init-time refreshIfStale picks the new chats up. Triggering
            // a full refresh here closes the gap so the feed updates in the same
            // session the user authenticated in.
            try {
                postsRepository.refresh()
            } catch (cancellation: kotlinx.coroutines.CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                Log.w(TAG, "migration: post-join refresh failed: ${t.message}")
            }
        }
        // Only suppress future proposals when EVERY requested handle either
        // succeeded (in `migrated`) or was conclusively a no-op (null without
        // throw — wrong handle / deleted channel). Any transient failure leaves
        // the proposal pending so the user gets another bite at the apple.
        if (!anyFailure.get()) {
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

    data class Progress(
        val total: Int,
        val processed: Int,
        val lastUsername: String?,
    )

    private companion object {
        const val TAG = "MigrationCoordinator"
        // 1 chat / sec — two TDLib RPCs each (SearchPublicChat + JoinChat).
        // TDLib's per-method gate tolerates ~30 SearchPublicChat per minute on
        // Telegram's side; staying at 60/min for the slower-throttled JoinChat
        // is safer.
        const val THROTTLE_MS = 1_000L
        const val POST_COMPLETE_HOLD_MS = 1_500L
    }
}
