package dev.lyo.hortay.data

/**
 * Escape the three SQL LIKE metacharacters so a user query / URL matches
 * literally. Pair with `ESCAPE '\'` on every LIKE that consumes the result —
 * without it, `%` and `_` in the input act as wildcards (a search for `50%`
 * matches every "50"). Order matters: escape the escape char first so we
 * don't double-escape the backslashes we just introduced.
 */
internal fun escapeSqlLike(s: String): String =
    s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
