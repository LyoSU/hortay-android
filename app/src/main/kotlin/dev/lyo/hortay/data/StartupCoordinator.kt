package dev.lyo.hortay.data

import kotlinx.collections.immutable.PersistentList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Process-wide "is the TDLib client past its first-render storm yet?" signal.
 *
 * Why this exists. On the FIRST [AuthorizationStateReady] of a session the
 * TDLib local DB is empty: every `GetChat`, `GetChatHistory`, `GetMessageThread`
 * we issue is a real server round-trip rather than a cache hit, and TDLib's own
 * internal sync (UpdateNewChat × every chat, UpdateUser × every contact,
 * UpdateSupergroup × every channel) is competing for the same per-DC active-
 * slot pool we are. Stacking speculative work on top of that — auto-download
 * for every newly-arrived post, comments-thread prefetch for every dwell-
 * stable viewport — was the empirical cause of "FLOOD_WAIT one minute after I
 * just logged in." Per tdlib/td#786 the active-slot pool is small (~4 on
 * mobile, ~10 on Wi-Fi) and per tdlib/td#3019 the RPC queue is per-client and
 * serialised, so an unbounded burst doesn't go faster — it just queues up
 * behind the user's eventual interactive tap.
 *
 * The coordinator exposes [phase] which speculative subsystems consult before
 * dispatching:
 *
 *   • [Phase.Booting] — the post-auth storm window. Auto-download and
 *     comments-thread prefetch SKIP entirely. Interactive paths (user tap on
 *     comments, media on screen, refresh, OpenChat on focused post) continue
 *     unchanged — those are what the user actually waited for.
 *   • [Phase.Active] — full functionality. Speculative work resumes.
 *
 * Transition `Booting → Active` happens when *both* conditions hold:
 *
 *   1. Either the feed has at least [ACTIVATE_POSTS_THRESHOLD] posts (the
 *      perceptual definition of "feed loaded") OR [BOOT_TIMEOUT_MS] elapsed
 *      since auth.Ready (escape hatch — a brand-new account with zero
 *      subscriptions must still eventually activate).
 *   2. A further [SETTLE_MS] elapsed, giving TDLib's own
 *      sync chatter and our `refreshLocked` per-channel `GetChatHistory` fan-
 *      out room to drain before speculative consumers re-enter the pipe.
 *
 * Transition `Ready → !Ready` (logout, connection-close-and-recover, or a
 * fresh login flow) resets to [Phase.Booting] so the next sign-in starts a
 * fresh storm window. The bound to `authStage` rather than `connection`
 * because [AuthStage.Ready] is the authoritative signal that we're allowed
 * to issue traffic at all; [ConnectionStatus] flapping between Updating /
 * Ready during normal operation must NOT bounce us back to Booting.
 *
 * Consumers (current):
 *   • [MediaAutoDownloader.dispatchAsync] — skip while Booting.
 *   • [ui.timeline.TimelineScreen] comments-thread prefetch — skip while Booting.
 *
 * Consumers (deliberately NOT gated):
 *   • [PostsRepository.refreshLocked] — this is what the user is waiting to see;
 *     blocking it on a gate that itself depends on a non-empty feed deadlocks.
 *   • [CommentsRepository.observeThread] — fires only on explicit user tap.
 *   • [MediaCache.ensure] for visible cards — user is looking at it.
 *   • [CustomEmojiRepository] — already batched at 50ms; further gating would
 *     leave reactions as plain chips during cold start.
 */
class StartupCoordinator(
    private val authStage: StateFlow<AuthStage>,
    private val posts: StateFlow<PersistentList<TimelinePost>>,
    scope: CoroutineScope,
) {
    enum class Phase { Booting, Active }

    private val _phase = MutableStateFlow(Phase.Booting)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    init {
        scope.launch {
            // collectLatest so a Ready → !Ready transition cancels any in-flight
            // settle delay; the next Ready spawns a fresh boot window. Without
            // this, a fast logout-then-login could fall through to Active using
            // the previous account's settle timer.
            authStage.collectLatest { stage ->
                if (stage !is AuthStage.Ready) {
                    _phase.value = Phase.Booting
                    return@collectLatest
                }
                // Already Active for this Ready window? Don't re-arm.
                if (_phase.value == Phase.Active) return@collectLatest
                // Gate 1: feed reaches threshold OR boot timeout.
                // withTimeoutOrNull returns null on timeout — we treat that the
                // same as "threshold met" so empty accounts (no subs yet, fresh
                // signup) still activate after [BOOT_TIMEOUT_MS] instead of
                // sitting in Booting forever.
                withTimeoutOrNull(BOOT_TIMEOUT_MS) {
                    posts.first { it.size >= ACTIVATE_POSTS_THRESHOLD }
                }
                // Gate 2: settle. The threshold-met instant is when our
                // streaming refresh has emitted enough rows to fill the
                // viewport, but TDLib's internal initial-sync chatter and our
                // per-channel GetChatHistory drain are still going. A small
                // fixed delay gives both sides space to drain so speculative
                // consumers don't immediately re-pile-on the pipe.
                delay(SETTLE_MS)
                // Re-check we're still on the same Ready window — collectLatest
                // would have cancelled us if not, but make the invariant
                // self-documenting at the write site.
                if (authStage.value is AuthStage.Ready) {
                    _phase.value = Phase.Active
                }
            }
        }
    }

    private companion object {
        /**
         * Post count at which we consider the cold-start refresh "perceptually
         * done." 8 posts cover ~1.5 screens at typical card height — comfortably
         * above one screenful (3-4 cards) yet still reachable on a small-
         * subscription account where each channel contributes exactly one post
         * via the lastMessage-harvest cold-start path. The previous value (20)
         * assumed up to 30 posts per channel via GetChatHistory fan-out; that
         * path was removed in the TDLib cold-start rework (2026-05-11).
         */
        const val ACTIVATE_POSTS_THRESHOLD = 8

        /**
         * Escape hatch for empty accounts / extreme network conditions where
         * the threshold can't be reached. 8s is generous on a normal cold
         * start (refresh completes well inside that on Wi-Fi) but short enough
         * that a brand-new account with zero channels isn't left without
         * auto-download forever.
         */
        const val BOOT_TIMEOUT_MS = 8_000L

        /**
         * Buffer between "feed populated" and "speculative work allowed." Sized
         * to cover the tail of `refreshLocked`'s per-channel fan-out at
         * concurrency=4: 200 channels × ~50 ms RTT ÷ 4 ≈ 2.5 s total, of which
         * the first ~1 s is spent reaching the 20-post threshold. The
         * remaining ~1.5 s is the window we want to keep clear of speculative
         * load.
         */
        const val SETTLE_MS = 1_500L
    }
}
