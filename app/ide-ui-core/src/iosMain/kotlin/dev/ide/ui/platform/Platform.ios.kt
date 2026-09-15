package dev.ide.ui.platform

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitHour
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

actual val isMobilePlatform: Boolean = true

// `Dispatchers.IO` is internal on Native in coroutines 1.11.0, so the offloading dispatcher is Default.
// That caps parallelism at the core count instead of growing for blocking work, which costs nothing while
// the iOS backend performs no blocking file or network IO. A real backend wants a dedicated pool here.
actual val ioDispatcher: CoroutineDispatcher = Dispatchers.Default

// Foundation rather than `kotlin.system.getTimeMillis()`: the same clock the rest of the iOS host reads,
// and it keeps the wall-clock source identical to the one `localHourOfDay` derives its calendar from.
actual fun nowMillis(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()

actual fun localHourOfDay(): Int =
    NSCalendar.currentCalendar.component(NSCalendarUnitHour, fromDate = NSDate()).toInt()
