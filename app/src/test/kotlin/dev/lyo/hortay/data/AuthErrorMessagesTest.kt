package dev.lyo.hortay.data

import dev.lyo.hortay.R
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AuthErrorMessagesTest {

    @Test
    fun `a resend with no next channel maps to its own message, not the raw TDLib prose`() {
        val err = TdClient.TdException(400, RESEND_IMPOSSIBLE_MESSAGE)
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
