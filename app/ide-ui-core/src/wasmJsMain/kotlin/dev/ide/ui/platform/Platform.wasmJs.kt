// Copyright (C) 2026 tyron12233
// SPDX-License-Identifier: GPL-3.0-or-later WITH Classpath-exception-2.0
// See LICENSE-EXCEPTION: a plugin linking against this file may use any license.
package dev.ide.ui.platform

import dev.ide.platform.ioDispatcher as platformIoDispatcher
import kotlinx.coroutines.CoroutineDispatcher

@JsFun("() => Date.now()")
private external fun jsNow(): Double

@JsFun("() => new Date().getHours()")
private external fun jsHour(): Int

@JsFun("() => new Date().getMonth() + 1")
private external fun jsMonth(): Int

@JsFun("() => new Date().getDate()")
private external fun jsDay(): Int

@JsFun("() => new Date().getFullYear()")
private external fun jsYear(): Int

@JsFun("() => /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent)")
private external fun jsIsApple(): Boolean

/** `2026-11-26` -> "Nov 26" / "Thursday, November 26", in the page's locale. Null when [iso] is not a date. */
@JsFun(
    """(iso, short) => {
    const d = new Date(iso + 'T00:00:00Z');
    if (isNaN(d.getTime())) return null;
    return d.toLocaleDateString(undefined, short
        ? { month: 'short', day: 'numeric', timeZone: 'UTC' }
        : { weekday: 'long', month: 'long', day: 'numeric', timeZone: 'UTC' });
}""",
)
private external fun jsFormatDay(iso: String, short: Boolean): String?

actual val isMobilePlatform: Boolean = false
actual val supportsBlur: Boolean = true
actual val hasSystemBack: Boolean = false
actual val usesCommandKey: Boolean = jsIsApple()
actual val ioDispatcher: CoroutineDispatcher = platformIoDispatcher

actual fun nowMillis(): Long = jsNow().toLong()
actual fun localHourOfDay(): Int = jsHour()
actual fun localMonthDay(): Int = jsMonth() * 100 + jsDay()
actual fun localYear(): Int = jsYear()
actual fun formatIsoDay(isoDate: String, short: Boolean): String = jsFormatDay(isoDate, short) ?: isoDate
