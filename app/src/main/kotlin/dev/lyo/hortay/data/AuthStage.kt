package dev.lyo.hortay.data

sealed interface AuthStage {
    data object Loading : AuthStage
    data object WaitPhone : AuthStage
    data class WaitCode(val phoneNumber: String) : AuthStage
    data class WaitPassword(val hint: String = "") : AuthStage
    data object Ready : AuthStage
    /** Unrecoverable-here states (email confirm, register, other-device). UI offers reset. */
    data class Error(val message: String) : AuthStage
}
