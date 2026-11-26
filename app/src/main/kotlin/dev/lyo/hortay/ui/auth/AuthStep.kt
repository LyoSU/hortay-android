package dev.lyo.hortay.ui.auth

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import dev.lyo.hortay.data.AuthStage

/**
 * What the auth screen shows. TDLib's [AuthStage] says where authorization stands;
 * [Welcome] vs [Phone] is the user's own choice on top of it — TDLib sits in WaitPhone
 * for both — so it lives in UI state ("sign-in chosen"), not in the data layer.
 */
internal enum class AuthStep { Welcome, Phone, Code, Password, Error, Finishing }

/** What back does on a step. */
internal enum class AuthBack {
    /** Root of the flow: back leaves the app as usual. */
    None,

    /** Phone → Welcome; purely local, the typed number is kept. */
    ToWelcome,

    /** Code / password → abandon the attempt; TDLib returns to WaitPhone. */
    CancelAttempt,
}

internal fun authStep(stage: AuthStage, signInChosen: Boolean): AuthStep = when (stage) {
    AuthStage.Loading, AuthStage.WaitPhone -> if (signInChosen) AuthStep.Phone else AuthStep.Welcome
    is AuthStage.WaitCode -> AuthStep.Code
    is AuthStage.WaitPassword -> AuthStep.Password
    is AuthStage.Error -> AuthStep.Error
    AuthStage.Ready -> AuthStep.Finishing
}

/**
 * Once TDLib is past the phone number (also when a session is restored mid-sign-in),
 * the user has chosen to sign in: abandoning the code or "Change number" then lands on
 * the phone step, not back on Welcome.
 */
internal fun signInChosenAfter(stage: AuthStage, chosen: Boolean): Boolean =
    chosen || stage is AuthStage.WaitCode || stage is AuthStage.WaitPassword

/** The Welcome sign-in button acts only once TDLib can take a phone number. */
internal fun canStartSignIn(stage: AuthStage): Boolean = stage is AuthStage.WaitPhone

internal fun authBack(step: AuthStep): AuthBack = when (step) {
    AuthStep.Phone -> AuthBack.ToWelcome
    AuthStep.Code, AuthStep.Password -> AuthBack.CancelAttempt
    AuthStep.Welcome, AuthStep.Error, AuthStep.Finishing -> AuthBack.None
}

/**
 * The phone number being typed, hoisted above the steps so going back to Welcome and
 * forward again keeps it, and saved so rotation, a locale switch and process death
 * keep it too. The picked country is saved by ISO and re-resolved against the catalogue.
 */
@Stable
internal class PhoneDraft(
    selectedIso: String? = null,
    national: String = "",
    customDial: String = "+",
) {
    var selectedIso by mutableStateOf(selectedIso)
    var national by mutableStateOf(national)

    /**
     * Used only with the synthetic "Other country" row: the user types any dial code
     * (e.g. +1268, +672). For real countries the prefix is the country's own code.
     */
    var customDial by mutableStateOf(customDial)

    companion object {
        val Saver: Saver<PhoneDraft, Any> = listSaver(
            save = { listOf(it.selectedIso.orEmpty(), it.national, it.customDial) },
            restore = { PhoneDraft(it[0].ifEmpty { null }, it[1], it[2]) },
        )
    }
}
