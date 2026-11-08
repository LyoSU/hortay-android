package dev.lyo.hortay.ui.components

/**
 * Binary (1024) byte-size formatting shared by every size label in the app. [units] is
 * the localised `R.array.size_units` (B, KB, MB, GB) — never hardcode the unit names.
 *
 * Whole numbers print without a decimal ("12 MB"), as does anything ≥ 100 or in bytes;
 * otherwise one decimal ("5.2 MB"). Non-positive input reads as zero bytes.
 */
internal fun humanizeBytes(bytes: Long, units: Array<String>): Pair<String, String> {
    if (bytes <= 0L) return "0" to units[0]
    var size = bytes.toDouble()
    var idx = 0
    while (size >= BYTES_PER_UNIT && idx < units.lastIndex) {
        size /= BYTES_PER_UNIT
        idx++
    }
    val value = if (size >= WHOLE_NUMBER_FROM || idx == 0 || size == size.toLong().toDouble()) {
        size.toLong().toString()
    } else {
        "%.1f".format(size)
    }
    return value to units[idx]
}

/** "[value] [unit]" form of [humanizeBytes]. */
internal fun formatByteSize(bytes: Long, units: Array<String>): String {
    val (value, unit) = humanizeBytes(bytes, units)
    return "$value $unit"
}

private const val BYTES_PER_UNIT = 1024
private const val WHOLE_NUMBER_FROM = 100
