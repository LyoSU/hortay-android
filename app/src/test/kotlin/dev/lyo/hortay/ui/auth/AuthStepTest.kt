package dev.lyo.hortay.ui.auth

import dev.lyo.hortay.data.AuthStage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AuthStepTest {

    private val code = AuthStage.WaitCode(
        phoneNumber = "+380000000000",
        codeLength = 5,
        channelLabel = "Telegram",
        nextChannelLabel = null,
        resendAvailableInSec = 0,
        isNumeric = true,
        deliveredInApp = true,
    )

    @Test
    fun `before choosing to sign in, loading and phone stages show Welcome`() {
        assertEquals(AuthStep.Welcome, authStep(AuthStage.Loading, signInChosen = false))
        assertEquals(AuthStep.Welcome, authStep(AuthStage.WaitPhone, signInChosen = false))
    }

    @Test
    fun `after choosing to sign in, loading and phone stages show the phone step`() {
        assertEquals(AuthStep.Phone, authStep(AuthStage.WaitPhone, signInChosen = true))
        // TDLib passes through Loading on its way back to WaitPhone after "Change number":
        // the form stays up instead of flashing Welcome.
        assertEquals(AuthStep.Phone, authStep(AuthStage.Loading, signInChosen = true))
    }

    @Test
    fun `later TDLib stages show their own step whatever the choice`() {
        for (chosen in listOf(false, true)) {
            assertEquals(AuthStep.Code, authStep(code, chosen))
            assertEquals(AuthStep.Password, authStep(AuthStage.WaitPassword(), chosen))
            assertEquals(AuthStep.Error, authStep(AuthStage.Error("x"), chosen))
            assertEquals(AuthStep.Finishing, authStep(AuthStage.Ready, chosen))
        }
    }

    @Test
    fun `reaching code or password counts as having chosen to sign in`() {
        assertTrue(signInChosenAfter(code, chosen = false))
        assertTrue(signInChosenAfter(AuthStage.WaitPassword(), chosen = false))
        assertFalse(signInChosenAfter(AuthStage.WaitPhone, chosen = false))
        assertFalse(signInChosenAfter(AuthStage.Error("x"), chosen = false))
        // Never un-chooses on its own; only back to Welcome does.
        assertTrue(signInChosenAfter(AuthStage.WaitPhone, chosen = true))
    }

    @Test
    fun `sign-in starts only once TDLib waits for a phone number`() {
        assertTrue(canStartSignIn(AuthStage.WaitPhone))
        assertFalse(canStartSignIn(AuthStage.Loading))
        assertFalse(canStartSignIn(AuthStage.Error("x")))
    }

    @Test
    fun `back goes phone to Welcome, abandons code and password, and is the system's elsewhere`() {
        assertEquals(AuthBack.ToWelcome, authBack(AuthStep.Phone))
        assertEquals(AuthBack.CancelAttempt, authBack(AuthStep.Code))
        assertEquals(AuthBack.CancelAttempt, authBack(AuthStep.Password))
        assertEquals(AuthBack.None, authBack(AuthStep.Welcome))
        assertEquals(AuthBack.None, authBack(AuthStep.Error))
        assertEquals(AuthBack.None, authBack(AuthStep.Finishing))
    }

    @Test
    fun `steps are ordered as the flow runs, which sets the slide direction`() {
        val order = listOf(AuthStep.Welcome, AuthStep.Phone, AuthStep.Code, AuthStep.Password)
        assertEquals(order, order.sortedBy { it.ordinal })
    }
}
