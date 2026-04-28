package dev.lyo.telread.data

sealed interface AuthStage {
    data object Loading : AuthStage
    data object WaitPhone : AuthStage
    data class WaitCode(val phoneNumber: String) : AuthStage
    data object WaitPassword : AuthStage
    data object Ready : AuthStage
    data class Error(val message: String) : AuthStage
}
