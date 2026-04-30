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

    data class WaitPassword(val hint: String = "") : AuthStage
    data object Ready : AuthStage
    /** Unrecoverable-here states (email confirm, register, other-device). UI offers reset. */
    data class Error(val message: String) : AuthStage
}
