package dev.lyo.hortay.data

import dev.lyo.hortay.R

/**
 * TDLib speaks SCREAMING_SNAKE error codes (PHONE_NUMBER_INVALID, FLOOD_WAIT_42,
 * PASSWORD_HASH_INVALID…). It deliberately leaves localisation to the client because the
 * codes are stable contract; the messages aren't. We translate the ones a real user can
 * actually act on, and fall back to a neutral phrase for the rest so we never blast
 * `[400] PHONE_NUMBER_BANNED` at someone's screen.
 *
 * Strings live in `res/values{,-uk}/strings.xml`; we resolve through [Resources] rather
 * than hold a localized lookup table here so locale changes propagate via the standard
 * resource-config path.
 *
 * Keep the mapping ordered by likelihood (cheap wins early). Anything that contains a
 * numeric tail (FLOOD_WAIT_X) needs prefix matching; everything else is exact.
 */
internal fun friendlyAuthErrorMessage(res: StringResolver, throwable: Throwable): String {
    val raw = (throwable as? TdClient.TdException)?.let { extractCode(it.message ?: "") }
        ?: throwable.message.orEmpty()
    return when {
        raw.startsWith("FLOOD_WAIT") -> {
            val seconds = raw.substringAfter("FLOOD_WAIT_", "").toIntOrNull()
            if (seconds != null) {
                res.getString(R.string.auth_err_too_many_with_time, humaniseSeconds(res, seconds))
            } else {
                res.getString(R.string.auth_err_too_many_generic)
            }
        }
        raw == "PHONE_NUMBER_INVALID" -> res.getString(R.string.auth_err_phone_invalid)
        raw == "PHONE_NUMBER_BANNED" -> res.getString(R.string.auth_err_phone_banned)
        raw == "PHONE_NUMBER_FLOOD" -> res.getString(R.string.auth_err_phone_flood)
        raw == "PHONE_NUMBER_OCCUPIED" -> res.getString(R.string.auth_err_phone_occupied)
        raw == "PHONE_CODE_INVALID" || raw == "PHONE_CODE_EMPTY" ->
            res.getString(R.string.auth_err_code_invalid)
        raw == "PHONE_CODE_EXPIRED" -> res.getString(R.string.auth_err_code_expired)
        raw == "PASSWORD_HASH_INVALID" -> res.getString(R.string.auth_err_password_invalid)
        raw == "PASSWORD_TOO_FRESH" -> res.getString(R.string.auth_err_password_too_fresh)
        raw == "PASSWORD_RECOVERY_NA" -> res.getString(R.string.auth_err_password_recovery_na)
        raw == "SESSION_PASSWORD_NEEDED" -> res.getString(R.string.auth_err_session_password_needed)
        raw == "API_ID_INVALID" || raw == "API_ID_PUBLISHED_FLOOD" ->
            res.getString(R.string.auth_err_app_misconfigured)
        raw == "ACCESS_TOKEN_INVALID" -> res.getString(R.string.auth_err_token_invalid)
        raw.isBlank() -> res.getString(R.string.auth_err_generic)
        // Unknown code — show a neutral message but keep the raw code in parens for bug
        // reports. The user can't act on PHONE_MIGRATE_2 directly but they can copy it
        // into a support message if it ever happens.
        else -> res.getString(R.string.auth_err_step_failed, raw)
    }
}

/** TdException stores `[code] message` — strip the bracketed prefix to get the raw token. */
private fun extractCode(full: String): String {
    val closeBracket = full.indexOf(']')
    return if (closeBracket >= 0 && full.startsWith('[')) {
        full.substring(closeBracket + 1).trim()
    } else {
        full.trim()
    }
}

private fun humaniseSeconds(res: StringResolver, total: Int): String = when {
    total < 60 -> res.getString(R.string.duration_seconds_short, total)
    total < 3600 -> {
        val m = total / 60
        res.getQuantityString(R.plurals.duration_minutes, m, m)
    }
    else -> {
        val h = total / 3600
        res.getQuantityString(R.plurals.duration_hours, h, h)
    }
}
