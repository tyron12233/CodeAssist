package dev.ide.ios

import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.dateWithTimeIntervalSince1970

/** Local wall-clock labels for log lines. */
internal object IosClock {
    private val formatter = NSDateFormatter().apply { dateFormat = "HH:mm:ss.SSS" }

    /** `14:03:21.482` for [epochMs], in the device's time zone. */
    fun timeOfDay(epochMs: Long): String = formatter.stringFromDate(NSDate.dateWithTimeIntervalSince1970(epochMs / 1000.0))
}
