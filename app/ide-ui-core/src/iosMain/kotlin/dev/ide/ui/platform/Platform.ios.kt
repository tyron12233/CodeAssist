package dev.ide.ui.platform

import dev.ide.ui.concurrent.iosIoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitHour
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

actual val isMobilePlatform: Boolean = true

// `Dispatchers.IO` is internal on Native in coroutines 1.11.0, and Default is the CPU pool — the wrong
// place for work that blocks, now that this host has a backend that reads files and talks to the store.
// See [iosIoDispatcher] for what replaced it and why.
actual val ioDispatcher: CoroutineDispatcher = iosIoDispatcher

// Foundation rather than `kotlin.system.getTimeMillis()`: the same clock the rest of the iOS host reads,
// and it keeps the wall-clock source identical to the one `localHourOfDay` derives its calendar from.
actual fun nowMillis(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()

actual fun localHourOfDay(): Int =
    NSCalendar.currentCalendar.component(NSCalendarUnitHour, fromDate = NSDate()).toInt()
