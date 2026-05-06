package dev.lyo.hortay.data

sealed interface AuthStage {
    data object Loading : AuthStage
    data object WaitPhone : AuthStage

    /**
     * TDLib accepted the phone and is waiting on the user to type the verification code.
     *
     * Carries everything the UI needs to render the right input + helper copy:
     *
     *   - [codeLength] — digit count expected by the active channel (TDLib has been
     *     rolling out 6-digit Firebase / Fragment codes, hardcoding this in the UI is a
     *     real functional bug for ~half the channels).
     *   - [channelLabel] — pre-rendered Ukrainian description of where the code lands
     *     ("повідомленням у Telegram", "пропущеним дзвінком на +380…"). UI never has to
     *     switch on TDLib's [org.drinkless.tdlib.TdApi.AuthenticationCodeType] sum type.
     *   - [nextChannelLabel] — what the next attempt will use; null when TDLib has no
     *     fallback. Drives the "Надіслати ще раз через SMS" button copy.
     *   - [resendAvailableInSec] — server-mandated resend cooldown. UI ticks this down
     *     to 0 before enabling the resend button so users can't spam into FLOOD_WAIT.
     *   - [isNumeric] — false for the rare SmsWord / SmsPhrase channels, where the user
     *     must type a word / phrase rather than digits. UI falls back to a plain text
     *     field in that case; common channels (Sms, TelegramMessage, Call, MissedCall,
     *     Fragment, Firebase*) all set this to true.
     */
    data class WaitCode(
        val phoneNumber: String,
        val codeLength: Int,
        val channelLabel: String,
        val nextChannelLabel: String?,
        val resendAvailableInSec: Int,
        val isNumeric: Boolean,
    ) : AuthStage

    /**
     * 2FA password challenge. Carries enough state for the UI to offer recovery:
     *
     *   - [hint] — user-supplied passphrase hint shown above the password field.
     *   - [hasRecoveryEmail] — true iff TDLib reports the account has a confirmed
     *     recovery email on file. When false, the user has NO in-app recovery path
     *     and must reset the password from another device — the UI surfaces a
     *     "I forgot my password" link only when this is true. Without this field
     *     a user who set up 2FA without recovery email would still see the link
     *     and tap into a TDLib error; a user WITH recovery email never saw a link
     *     and was permanently locked out from inside the app.
     *   - [recoveryEmailPattern] — masked preview of the recovery email
     *     (`a***@example.com`). Surfaced as a hint so the user knows which inbox
     *     to check. Empty string when TDLib hasn't supplied it.
     */
    data class WaitPassword(
        val hint: String = "",
        val hasRecoveryEmail: Boolean = false,
        val recoveryEmailPattern: String = "",
    ) : AuthStage
    data object Ready : AuthStage
    /** Unrecoverable-here states (email confirm, register, other-device). UI offers reset. */
    data class Error(val message: String) : AuthStage
}
