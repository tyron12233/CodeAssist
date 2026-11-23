package dev.ide.ui.platform

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual val isMobilePlatform: Boolean = false

actual val supportsBlur: Boolean = true

// Window chrome, not a back gesture. Nothing here raises a soft keyboard either.
actual val hasSystemBack: Boolean = false

actual val usesCommandKey: Boolean = System.getProperty("os.name").orEmpty().startsWith("Mac")

actual val ioDispatcher: CoroutineDispatcher = Dispatchers.IO

actual fun nowMillis(): Long = System.currentTimeMillis()

actual fun localHourOfDay(): Int = java.time.LocalTime.now().hour

actual fun localMonthDay(): Int = java.time.LocalDate.now().let { it.monthValue * 100 + it.dayOfMonth }

actual fun localYear(): Int = java.time.LocalDate.now().year

actual fun formatIsoDay(isoDate: String, short: Boolean): String {
    val date = try {
        java.time.LocalDate.parse(isoDate)
    } catch (e: java.time.format.DateTimeParseException) {
        return isoDate
    }
    val pattern = if (short) "MMM d" else "EEEE, MMMM d"
    return date.format(java.time.format.DateTimeFormatter.ofPattern(pattern, java.util.Locale.getDefault()))
}
