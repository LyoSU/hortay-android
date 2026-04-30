package dev.lyo.hortay.data

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Process-wide channel for user-facing transient messages (Snackbars).
 *
 * Why a bus instead of per-screen state: errors originate in repositories and ViewModels
 * but need to surface in whichever screen the user is currently looking at. Threading
 * a callback through every layer is noisy; a single bus subscribed at the scaffold
 * level decouples producers from consumers.
 *
 * Backpressure: [BufferOverflow.DROP_OLDEST] with a small buffer. Snackbars are
 * inherently transient and a flood of identical errors (e.g. flaky network spamming
 * "не вдалося оновити") would just queue jank if we suspended emitters. Dropping the
 * older message keeps the user informed about the *most recent* state, which is what
 * matters for a user-facing toast.
 */
class UserMessageBus {

    private val _messages = MutableSharedFlow<UserMessage>(
        replay = 0,
        extraBufferCapacity = BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val messages: Flow<UserMessage> = _messages.asSharedFlow()

    fun post(text: String, severity: Severity = Severity.Error) {
        _messages.tryEmit(UserMessage(text, severity))
    }

    enum class Severity { Info, Warning, Error }

    data class UserMessage(val text: String, val severity: Severity)

    private companion object {
        const val BUFFER = 8
    }
}
