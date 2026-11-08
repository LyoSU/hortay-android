package dev.lyo.hortay.data

import dev.lyo.hortay.R
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AuthErrorMessagesTest {

    @Test
    fun `a resend with no next channel maps to its own message, not the raw TDLib prose`() {
        // Literal text from tdlib/td SendCodeHelper.cpp, typo included — never the
        // production constant, or the test could not catch a mismatch.
        val err = TdClient.TdException(400, "Authentication code can't be resend")
        assertEquals(
            FakeStrings.getString(R.string.auth_err_code_cant_resend),
            friendlyAuthErrorMessage(FakeStrings, err),
        )
    }

    @Test
    fun `the mapping survives an upstream fix of the typo`() {
        val err = TdClient.TdException(400, "Authentication code can't be resent")
        assertEquals(
            FakeStrings.getString(R.string.auth_err_code_cant_resend),
            friendlyAuthErrorMessage(FakeStrings, err),
        )
    }

    @Test
    fun `an unknown code still falls back to the step-failed message with the code`() {
        val err = TdClient.TdException(400, "PHONE_MIGRATE_2")
        assertEquals(
            FakeStrings.getString(R.string.auth_err_step_failed, "PHONE_MIGRATE_2"),
            friendlyAuthErrorMessage(FakeStrings, err),
        )
    }
}
