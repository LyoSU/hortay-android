package dev.lyo.hortay.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * Lifetime of one signed-in TDLib account, between sign-in and `loggedOut`.
 *
 * Two things hang off it:
 *
 *  - **[scope]** — a child of the app scope that is cancelled and replaced when the
 *    session ends. Work that belongs to the account (first-sign-in backfill, history
 *    prefetches, anything that would write account A's data after logout) launches
 *    here, so a logout cancels it structurally instead of each call site re-checking a
 *    session epoch after every suspend point.
 *  - **[onEnd] hooks** — the per-account caches that must be wiped on logout, run in
 *    registration order. This replaces the hand-maintained list in `AppGraph` plus the
 *    `bindLogoutClear` collectors scattered across holders: every session-scoped state
 *    holder registers here once, next to where it is constructed, so "added a cache,
 *    forgot the logout clear" is visible in review at the construction site.
 *
 * A failing hook is logged and the rest still run — a partial wipe beats an aborted one.
 * Cancellation is propagated (the collecting scope is going away).
 *
 * Deliberately NOT a per-session object graph: repositories stay process singletons
 * reached through `AppGraph`, so the UI keeps its direct references. Moving holders'
 * state onto a per-session object is the follow-up this enables.
 */
class AccountSession(private val parent: CoroutineScope) {
    private val hooks = CopyOnWriteArrayList<Pair<String, suspend () -> Unit>>()
    private val scopeRef = AtomicReference(newScope())

    /** Scope of the current session; replaced (old one cancelled) by [end]. */
    val scope: CoroutineScope get() = scopeRef.get()

    /** Register [hook] to run when the session ends. Hooks run in registration order. */
    fun onEnd(name: String, hook: suspend () -> Unit) {
        hooks += name to hook
    }

    /** End the session on every [loggedOut] emission. Call once at graph build. */
    fun bind(loggedOut: SharedFlow<Unit>) {
        parent.launch { loggedOut.collect { end() } }
    }

    /** Cancel in-flight session work, then run every [onEnd] hook. */
    suspend fun end() {
        scopeRef.getAndSet(newScope()).cancel()
        for ((name, hook) in hooks) {
            runCatching { hook() }.warnUnlessCancelled(TAG, "session end: $name")
        }
    }

    private fun newScope(): CoroutineScope =
        CoroutineScope(parent.coroutineContext + SupervisorJob(parent.coroutineContext[Job]))

    private companion object {
        const val TAG = "AccountSession"
    }
}
