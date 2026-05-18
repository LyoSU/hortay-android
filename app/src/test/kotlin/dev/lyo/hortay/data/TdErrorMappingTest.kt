package dev.lyo.hortay.data

import dev.lyo.hortay.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Locks the TDLib code-406 contract per core.telegram.org/tdlib/docs:
 *
 *   "If the error code is 406, the error message must not be processed in any way
 *    and must not be displayed to the user."
 *
 * Regression for the "(406) error every time" symptom on every poll vote: 406 must
 * classify as [TdErrorKind.Silent] and [surfaceTo] must drop it before the bus emits
 * anything to the snackbar layer. [isTdSilent] is the matching helper that lets
 * action-repository call sites treat 406 as success so optimistic UI does not revert.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TdErrorMappingTest {

    @Test
    fun `code 406 classifies as Silent`() {
        val (kind, _) = TdClient.TdException(406, "ANYTHING")
            .toUserFacing(FakeStrings, R.string.op_vote_in_poll)
        assertEquals(TdErrorKind.Silent, kind)
    }

    @Test
    fun `surfaceTo does not emit for code 406`() = runTest {
        val bus = UserMessageBus()
        // Subscribe BEFORE surfaceTo runs — UserMessageBus is replay=0, so a subscriber
        // attached after-the-fact would see nothing regardless and the test would falsely
        // pass even if 406 were not filtered. The shareIn replay-1 cache makes the
        // subscriber permanent and observable from the test scope.
        val collected = bus.messages.shareIn(this, SharingStarted.Eagerly, replay = 1)
        advanceUntilIdle()
        TdClient.TdException(406, "no-op").surfaceTo(
            bus = bus,
            res = FakeStrings,
            operationRes = R.string.op_vote_in_poll,
            connection = ConnectionStatus.Ready,
        )
        advanceUntilIdle()
        assertNull(collected.replayCache.firstOrNull(), "surfaceTo must swallow code 406")
        coroutineContext[Job]?.cancelChildren()
    }

    @Test
    fun `surfaceTo emits a snackbar for non-Silent codes`() = runTest {
        val bus = UserMessageBus()
        val collected = bus.messages.shareIn(this, SharingStarted.Eagerly, replay = 1)
        advanceUntilIdle()
        TdClient.TdException(500, "boom").surfaceTo(
            bus = bus,
            res = FakeStrings,
            operationRes = R.string.op_vote_in_poll,
            connection = ConnectionStatus.Ready,
        )
        advanceUntilIdle()
        val emitted = collected.replayCache.firstOrNull()
        assertEquals(UserMessageBus.Severity.Warning, emitted?.severity)
        coroutineContext[Job]?.cancelChildren()
    }

    @Test
    fun `code 500 does not silence — classified as ServerError`() {
        val (kind, msg) = TdClient.TdException(500, "Internal server error")
            .toUserFacing(FakeStrings, R.string.op_vote_in_poll)
        assertEquals(TdErrorKind.ServerError, kind)
        assertTrue(msg.isNotEmpty(), "non-Silent kinds must surface a user-facing message")
    }

    @Test
    fun `isTdSilent is true only for code 406`() {
        assertTrue(TdClient.TdException(406, "x").isTdSilent())
        assertEquals(false, TdClient.TdException(400, "x").isTdSilent())
        assertEquals(false, (null as Throwable?).isTdSilent())
        assertEquals(false, RuntimeException("plain").isTdSilent())
    }
}
