package dev.ide.ui.platform

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

actual val isMobilePlatform: Boolean = true

actual val supportsBlur: Boolean = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S

// The system back gesture/button, which the IME also consumes to dismiss itself.
actual val hasSystemBack: Boolean = true

// Android shortcuts (and its system Keyboard Shortcuts list) are written with Ctrl.
actual val usesCommandKey: Boolean = false

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
    val locale = java.util.Locale.getDefault()
    // The skeleton names the fields; the platform orders and punctuates them for the language.
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, if (short) "MMMd" else "EEEEMMMMd")
    return date.format(java.time.format.DateTimeFormatter.ofPattern(pattern, locale))
}
