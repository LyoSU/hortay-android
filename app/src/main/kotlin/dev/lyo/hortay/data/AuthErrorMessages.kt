package dev.lyo.hortay.data

/**
 * TDLib speaks SCREAMING_SNAKE error codes (PHONE_NUMBER_INVALID, FLOOD_WAIT_42,
 * PASSWORD_HASH_INVALID…). It deliberately leaves localisation to the client because the
 * codes are stable contract; the messages aren't. We translate the ones a real user can
 * actually act on into Ukrainian, and fall back to a neutral phrase for the rest so we
 * never blast `[400] PHONE_NUMBER_BANNED` at someone's screen.
 *
 * Keep the mapping ordered by likelihood (cheap wins early). Anything that contains a
 * numeric tail (FLOOD_WAIT_X) needs prefix matching; everything else is exact.
 */
internal fun friendlyAuthErrorMessage(throwable: Throwable): String {
    val raw = (throwable as? TdClient.TdException)?.let { extractCode(it.message ?: "") }
        ?: throwable.message.orEmpty()
    return when {
        raw.startsWith("FLOOD_WAIT") -> {
            val seconds = raw.substringAfter("FLOOD_WAIT_", "").toIntOrNull()
            if (seconds != null) {
                "Забагато спроб. Спробуйте знову за ${humaniseSeconds(seconds)}."
            } else {
                "Забагато спроб. Спробуйте трохи пізніше."
            }
        }
        raw == "PHONE_NUMBER_INVALID" ->
            "Невірний номер. Перевірте код країни та цифри."
        raw == "PHONE_NUMBER_BANNED" ->
            "Цей номер заблоковано в Telegram. Зверніться до підтримки Telegram."
        raw == "PHONE_NUMBER_FLOOD" ->
            "Telegram тимчасово обмежив авторизацію з цього номера. Спробуйте пізніше."
        raw == "PHONE_NUMBER_OCCUPIED" ->
            "Цей номер уже зайнятий. Зайдіть під ним замість того, щоб реєструвати знову."
        raw == "PHONE_CODE_INVALID" || raw == "PHONE_CODE_EMPTY" ->
            "Невірний код. Перевірте та введіть ще раз."
        raw == "PHONE_CODE_EXPIRED" ->
            "Код прострочений. Запросіть новий."
        raw == "PASSWORD_HASH_INVALID" ->
            "Невірний пароль. Спробуйте ще раз."
        raw == "PASSWORD_TOO_FRESH" ->
            "Пароль було щойно змінено — Telegram попросить почекати, перш ніж його використати."
        raw == "PASSWORD_RECOVERY_NA" ->
            "Відновлення пароля недоступне. Скиньте 2FA в офіційному Telegram."
        raw == "SESSION_PASSWORD_NEEDED" ->
            "Потрібен Cloud-пароль 2FA."
        raw == "API_ID_INVALID" || raw == "API_ID_PUBLISHED_FLOOD" ->
            "Збій конфігурації застосунку. Зверніться до розробника."
        raw == "ACCESS_TOKEN_INVALID" ->
            "Невірні облікові дані. Перезапустіть авторизацію."
        raw.isBlank() ->
            "Щось пішло не так. Спробуйте ще раз."
        else ->
            // Unknown code — show a neutral message but keep the raw code in parens for
            // bug reports. The user can't act on PHONE_MIGRATE_2 directly but they can
            // copy it into a support message if it ever happens.
            "Не вдалося завершити крок (${raw}). Спробуйте ще раз."
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

private fun humaniseSeconds(total: Int): String = when {
    total < 60 -> "$total с"
    total < 3600 -> {
        val m = total / 60
        "$m ${pluralMinutes(m)}"
    }
    else -> {
        val h = total / 3600
        "$h ${pluralHours(h)}"
    }
}

private fun pluralMinutes(n: Int): String = when {
    n % 10 == 1 && n % 100 != 11 -> "хвилину"
    n % 10 in 2..4 && n % 100 !in 12..14 -> "хвилини"
    else -> "хвилин"
}

private fun pluralHours(n: Int): String = when {
    n % 10 == 1 && n % 100 != 11 -> "годину"
    n % 10 in 2..4 && n % 100 !in 12..14 -> "години"
    else -> "годин"
}
