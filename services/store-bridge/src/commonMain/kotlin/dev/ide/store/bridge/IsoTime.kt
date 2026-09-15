package dev.ide.store.bridge

/**
 * ISO-8601 to epoch milliseconds, for the timestamps PostgREST renders.
 *
 * `java.time` did this before the store had a Kotlin/Native host, and `kotlinx-datetime` would be a new
 * dependency for the two fields that need it — a review's date and a submission's. So it is written out,
 * and narrowly: what the backend actually sends, which is `timestamptz` rendered by PostgREST
 * (`2026-09-14T04:11:07.481123+00:00`, six fractional digits) or by `to_jsonb` (`...Z`), plus the
 * offset-less `2026-09-14 04:11:07` that older rows carry.
 *
 * A value it cannot read is 0 rather than an exception. A timestamp is decoration on a card, and a queue
 * that will not load because one row had an unexpected date is a worse failure than a card reading
 * "unknown".
 */
internal fun parseIsoMillis(text: String?): Long {
    if (text.isNullOrBlank()) return 0L
    val s = text.trim().replace(' ', 'T')
    val datePart = s.substringBefore('T')
    val rest = s.substringAfter('T', "")
    if (rest.isEmpty()) return 0L

    val date = datePart.split('-')
    if (date.size != 3) return 0L
    val year = date[0].toIntOrNull() ?: return 0L
    val month = date[1].toIntOrNull() ?: return 0L
    val day = date[2].toIntOrNull() ?: return 0L

    // The offset ends the string: `Z`, `+HH:MM` or `-HH:MM`. A `-` is only a sign after the time, so the
    // search starts past the hour rather than at 0, where the date's own dashes are.
    var offsetMinutes = 0
    var time = rest
    when {
        rest.endsWith("Z") || rest.endsWith("z") -> time = rest.dropLast(1)
        else -> {
            val sign = rest.indexOfLast { it == '+' || it == '-' }
            if (sign > 0) {
                val offset = rest.substring(sign + 1)
                val parts = offset.split(':')
                val hours = parts.getOrNull(0)?.toIntOrNull() ?: return 0L
                val minutes = parts.getOrNull(1)?.toIntOrNull() ?: 0
                offsetMinutes = (hours * 60 + minutes) * if (rest[sign] == '-') -1 else 1
                time = rest.substring(0, sign)
            }
        }
    }

    val clock = time.split(':')
    val hour = clock.getOrNull(0)?.toIntOrNull() ?: return 0L
    val minute = clock.getOrNull(1)?.toIntOrNull() ?: 0
    val secondText = clock.getOrNull(2) ?: "0"
    val second = secondText.substringBefore('.').toIntOrNull() ?: return 0L
    // Any number of fractional digits, truncated to milliseconds — Postgres sends six.
    val fraction = secondText.substringAfter('.', "").take(3).padEnd(3, '0')
    val millis = if (secondText.contains('.')) fraction.toIntOrNull() ?: 0 else 0

    val days = daysFromCivil(year, month, day)
    val seconds = days * 86_400L + hour * 3600L + minute * 60L + second - offsetMinutes * 60L
    return seconds * 1000L + millis
}

/**
 * Days since 1970-01-01 for a proleptic-Gregorian date (Howard Hinnant's `days_from_civil`).
 *
 * The shift to a March-based year is what removes the leap-day special case: February lands at the end,
 * so a leap day never falls in the middle of the arithmetic.
 */
private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
    val y = (if (month <= 2) year - 1 else year).toLong()
    val era = (if (y >= 0) y else y - 399) / 400
    val yearOfEra = y - era * 400
    val monthShifted = if (month > 2) month - 3 else month + 9
    val dayOfYear = (153L * monthShifted + 2) / 5 + day - 1
    val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
    return era * 146_097 + dayOfEra - 719_468
}
